package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Columnar-basalt outcrop: a tightly packed patch of flat-topped basalt columns whose tops step
 * up and down like Giant's Causeway terraces, instead of the earlier scattered 1-block-radius
 * tuff/deepslate pillars (which read as random sticks, alternated materials per pillar, and left
 * floating rings on slopes).
 *
 * <p>Geometry is derived per <em>block column</em> from world-coordinate noise plus the site's
 * raster elevation, so every chunk the patch overlaps computes the same terrace surface and
 * contributes exactly its own 16x16 share -- no half-columns at chunk borders. Each block column
 * anchors to its own local ground (roots run down until they hit something solid), so nothing
 * floats on slopes; columns whose shaft would have to drop more than {@link #MAX_SHAFT} blocks to
 * reach ground (cliff edges) are skipped instead of towering.</p>
 */
public final class BasaltColumnFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x42415341L;
    private static final int CELL_SIZE = 44;
    private static final int MAX_PATCH_RADIUS = 8;
    /** Longest allowed column shaft from terrace top down to the ground it stands on. */
    private static final int MAX_SHAFT = 12;
    /** How far below its base a column may extend roots to find solid ground. */
    private static final int ROOT_DEPTH = 6;

    private static final float PLACEMENT_NOISE_WAVELENGTH = 200.0f;
    private static final float PLACEMENT_THRESHOLD = 0.3f;
    /** Wavelength (blocks) of the terrace-height field: neighbouring columns share a step. */
    private static final float TERRACE_WAVELENGTH = 7.0f;

    private static final BlockState BASALT = Blocks.BASALT.defaultBlockState();
    private static final BlockState SMOOTH_BASALT = Blocks.SMOOTH_BASALT.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    @Override
    public String id() {
        return "basalt_columns";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return MAX_PATCH_RADIUS + 2;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        float siteElevation = TerrainSampling.elevationAt(data, row, col);
        if (siteElevation <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("badlands") || biomeKey.contains("mesa")
                || biomeKey.contains("mountain") || biomeKey.contains("peaks"))) return;

        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;
        float strength = SurfaceNoise.clamp01((placement - PLACEMENT_THRESHOLD) / (1.0f - PLACEMENT_THRESHOLD));

        // Patch shape: an ellipse of noise-jittered radius, rotated per site so outcrops don't
        // all share an axis-aligned footprint.
        float radiusA = 4.5f + strength * 3.0f + SurfaceNoise.unitHash(site.seed(), 21, 0) * 0.5f;
        float radiusB = radiusA * (0.6f + SurfaceNoise.unitHash(site.seed(), 22, 0) * 0.4f);
        float rotation = SurfaceNoise.unitHash(site.seed(), 23, 0) * (float) Math.PI;
        float cos = (float) Math.cos(rotation);
        float sin = (float) Math.sin(rotation);
        int maxSteps = 3 + Math.round(strength * 4f); // terrace top varies 0..maxSteps above base

        // Terrace base plane comes from the raster at the site, not any one chunk's heightmap,
        // so all overlapping chunks agree on it.
        int baseY = HeightConverter.convertToMinecraftHeight((short) siteElevation) - 1;

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        int reach = (int) Math.ceil(radiusA);
        int minWX = Math.max(site.worldX() - reach, chunk.getPos().getMinBlockX());
        int maxWX = Math.min(site.worldX() + reach, chunk.getPos().getMinBlockX() + 15);
        int minWZ = Math.max(site.worldZ() - reach, chunk.getPos().getMinBlockZ());
        int maxWZ = Math.min(site.worldZ() + reach, chunk.getPos().getMinBlockZ() + 15);

        for (int wz = minWZ; wz <= maxWZ; wz++) {
            for (int wx = minWX; wx <= maxWX; wx++) {
                float dx = wx - site.worldX();
                float dz = wz - site.worldZ();
                float u = (dx * cos + dz * sin) / radiusA;
                float v = (-dx * sin + dz * cos) / radiusB;
                float d2 = u * u + v * v;
                if (d2 > 1f) continue;

                // Stepped terrace height: coherent noise quantized to whole steps, fading toward
                // the patch edge so the outcrop rises out of the ground instead of ending in a wall.
                float n = SurfaceNoise.valueNoise(site.seed(),
                        wx / TERRACE_WAVELENGTH, wz / TERRACE_WAVELENGTH) * 0.5f + 0.5f;
                float edgeFade = 1f - d2;
                int steps = Math.round(n * maxSteps * edgeFade + 1.2f * edgeFade);
                if (steps <= 0) continue;
                int topY = baseY + steps;

                placeColumn(chunk, worldSurface, motionBlocking, site.seed(), wx, wz, topY);
            }
        }
    }

    /**
     * One flat-topped column: smooth-basalt cap on a basalt shaft, dropped from {@code topY} down
     * to whatever solid ground this block column has, giving up if the shaft would exceed
     * {@link #MAX_SHAFT} blocks without touching down.
     */
    private static void placeColumn(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                     long siteSeed, int wx, int wz, int topY) {
        // Find the ground this column stands on: first non-air below the terrace top.
        int groundY = topY - 1;
        int floor = Math.max(chunk.getMinBuildHeight() + 1, topY - MAX_SHAFT - ROOT_DEPTH);
        while (groundY > floor && SurfaceStamp.stateAt(chunk, wx, groundY, wz).isAir()) {
            groundY--;
        }
        if (topY - groundY > MAX_SHAFT) return;                       // cliff edge: don't tower
        if (!SurfaceStamp.isSolidGround(SurfaceStamp.stateAt(chunk, wx, groundY, wz))) return; // water/void

        // Rare whole-column deepslate seam for tonal variation -- per column, never per block.
        boolean seam = SurfaceNoise.unitHash(siteSeed ^ 0x5EA3L, wx, wz) < 0.10f;
        BlockState shaft = seam ? DEEPSLATE : BASALT;
        BlockState cap = seam ? DEEPSLATE : SMOOTH_BASALT;

        for (int y = groundY + 1; y <= topY; y++) {
            SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, wx, y, wz,
                    y == topY ? cap : shaft);
        }
    }
}
