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
 * Tall rock spires in mountain biomes above treeline. Follows the same cluster/anchoring rules
 * as {@link HoodooClusterFeaturePlacer} (raster-anchored geometry with per-block clipping so
 * chunk borders get full columns, roots so nothing floats, strata keyed to absolute Y), but with
 * a broader base that tapers to a genuine 1-block tip -- the old 1-block-radius shafts with
 * per-block-random stone/deepslate/tuff read as speckled sticks, not alpine needles.
 */
public final class NeedlePeakFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4E454544L;
    private static final int CELL_SIZE = 52;
    private static final int CLUSTER_RADIUS = 10;
    private static final int MAX_COLUMNS = 3;
    private static final int MIN_COLUMN_HEIGHT = 8;
    private static final int MAX_COLUMN_HEIGHT = 20;
    private static final float BASE_RADIUS = 2.4f;
    private static final int ROOT_DEPTH = 6;
    /** Spires belong on high ground; measured in blocks above sea level, not raw model metres. */
    private static final float MIN_ELEVATION_BLOCKS = 90f;
    private static final float PLACEMENT_NOISE_WAVELENGTH = 250.0f;
    private static final float PLACEMENT_THRESHOLD = 0.5f;
    /** Thickness in blocks of each horizontal stratum. */
    private static final int BAND_THICKNESS = 3;

    private static final BlockState[] MOUNTAIN_BANDS = {
            Blocks.STONE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
    };

    @Override
    public String id() {
        return "needle_peak";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return CLUSTER_RADIUS + (int) Math.ceil(BASE_RADIUS) + 2;
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
        if (TerrainSampling.elevationAt(data, row, col)
                <= SurfaceStamp.blocksToElevation(MIN_ELEVATION_BLOCKS)) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("mountain") || biomeKey.contains("peaks")
                || biomeKey.contains("jagged") || biomeKey.contains("stony")
                || biomeKey.contains("grove"))) {
            return;
        }

        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;
        float strength = SurfaceNoise.clamp01((placement - PLACEMENT_THRESHOLD) / (1.0f - PLACEMENT_THRESHOLD));

        int columnCount = 1 + (int) (strength * (MAX_COLUMNS - 1));
        int bandOffset = (int) (SurfaceNoise.unitHash(site.seed(), 5, 9)
                * MOUNTAIN_BANDS.length * BAND_THICKNESS);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        for (int i = 0; i < columnCount; i++) {
            long columnSeed = SurfaceNoise.hash(site.seed(), i, 1000 + i);
            float angle = SurfaceNoise.unitHash(columnSeed, 1, 0) * (float) (Math.PI * 2);
            float distance = SurfaceNoise.unitHash(columnSeed, 2, 0) * CLUSTER_RADIUS;
            int columnWorldX = site.worldX() + Math.round((float) Math.cos(angle) * distance);
            int columnWorldZ = site.worldZ() + Math.round((float) Math.sin(angle) * distance);

            int reach = (int) Math.ceil(BASE_RADIUS) + 1;
            if (!HoodooClusterFeaturePlacer.footprintTouchesChunk(chunk, columnWorldX, columnWorldZ, reach)) continue;

            int cRow = columnWorldZ - dataOriginZ;
            int cCol = columnWorldX - dataOriginX;
            if (!TerrainSampling.inBounds(data, cRow, cCol)) continue;
            float columnElevation = TerrainSampling.elevationAt(data, cRow, cCol);
            if (columnElevation <= 0f) continue;
            int groundY = HeightConverter.convertToMinecraftHeight((short) columnElevation) - 1;
            if (groundY <= chunk.getMinBuildHeight()) continue;

            int height = MIN_COLUMN_HEIGHT
                    + (int) (SurfaceNoise.unitHash(columnSeed, 3, 0) * (MAX_COLUMN_HEIGHT - MIN_COLUMN_HEIGHT));
            placeColumn(chunk, worldSurface, motionBlocking, columnSeed, columnWorldX, columnWorldZ,
                    groundY, height, bandOffset);
        }
    }

    private void placeColumn(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                              long columnSeed, int worldX, int worldZ, int groundY, int height, int bandOffset) {
        for (int dy = 0; dy <= height; dy++) {
            int worldY = groundY + 1 + dy;
            float t = dy / (float) height;
            // Concave taper: broad footing, long thin upper shaft, true 1-block tip. The slight
            // per-layer jitter keeps tall spires from being perfect cones without speckling them.
            float radius = BASE_RADIUS * (float) Math.pow(1.0f - t, 1.4)
                    + (SurfaceNoise.unitHash(columnSeed, 4, dy) - 0.5f) * 0.4f;
            if (t >= 0.85f) radius = 0f;

            int r = Math.max(0, Math.round(radius));
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.sqrt(dx * dx + dz * dz) > radius + 0.3f) continue;
                    int wx = worldX + dx;
                    int wz = worldZ + dz;
                    SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, wx, worldY, wz,
                            bandFor(bandOffset, worldY));
                    if (dy == 0) {
                        placeRoots(chunk, worldSurface, motionBlocking, wx, groundY, wz, bandOffset);
                    }
                }
            }
        }
    }

    private static void placeRoots(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                    int wx, int groundY, int wz, int bandOffset) {
        if (!SurfaceStamp.inChunk(chunk, wx, wz)) return;
        for (int y = groundY; y > groundY - ROOT_DEPTH; y--) {
            if (!SurfaceStamp.stateAt(chunk, wx, y, wz).isAir()) break;
            SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, wx, y, wz,
                    bandFor(bandOffset, y));
        }
    }

    /** Horizontal strata, {@link #BAND_THICKNESS} blocks thick, keyed to absolute Y so they run
     *  continuously through every needle of the cluster. */
    private static BlockState bandFor(int bandOffset, int y) {
        int band = Math.floorDiv(y + bandOffset, BAND_THICKNESS);
        return MOUNTAIN_BANDS[Math.floorMod(band, MOUNTAIN_BANDS.length)];
    }
}
