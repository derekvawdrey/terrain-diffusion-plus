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
 * TEMPLATE B -- multi-anchor cluster that follows the local ridge. Copy this shape for anything
 * that's really "several related columns scattered around one site, each independently anchored
 * to the terrain": hoodoo/rock-spire fields, crystal clusters, stalagmite groups, small ruin
 * clusters, etc.
 *
 * <p>Differs from {@link BoulderFeaturePlacer} in two ways worth noting for a new placer:</p>
 * <ul>
 *     <li>Placement is gated by a slow {@link SurfaceNoise#valueNoise coherent noise field}
 *     instead of a flat per-site rarity roll, the way the removed {@code ScarpCarver} gated scarp
 *     amplification -- this makes hoodoos cluster into organic-looking regions of badlands rather
 *     than appearing as isolated columns scattered uniformly everywhere.</li>
 *     <li>Each column in the cluster is a <em>separate</em> anchor: its own jittered offset from
 *     the site, its own ground-height lookup, its own height/radius roll. Sub-anchors are derived
 *     from {@code site.seed()} reseeded with the column index, so re-decorating the same chunk
 *     (or a neighboring one, for columns near the chunk edge) always produces the same cluster.</li>
 * </ul>
 *
 * <p>Three lessons learned here that any copy should keep:</p>
 * <ul>
 *     <li><b>Anchor to the raster, not this chunk's heightmap.</b> A column near a chunk border is
 *     written by every chunk its footprint touches; only a ground height all of them can compute
 *     (the diffusion raster) keeps the halves at the same Y. The old chunk-heightmap anchor also
 *     skipped the column entirely when its center fell in the neighbouring chunk, leaving
 *     flat-sided half-columns along chunk borders.</li>
 *     <li><b>Roots.</b> The raster is approximate and slopes are real: every block column of the
 *     base extends downward until it meets solid ground, so no ring of blocks floats.</li>
 *     <li><b>Bands key on absolute Y with one per-cluster offset.</b> Hashing the band per
 *     (x, z) block column speckles the strata; real badlands bands are horizontal and continuous
 *     across every hoodoo of the cluster.</li>
 * </ul>
 */
public final class HoodooClusterFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x400D00A1L;
    private static final int CELL_SIZE = 40;
    private static final int CLUSTER_RADIUS = 8;
    private static final int MAX_COLUMNS = 4;
    private static final int MIN_COLUMN_HEIGHT = 5;
    private static final int MAX_COLUMN_HEIGHT = 13;
    private static final int BASE_RADIUS = 2;
    /** How far below its base a column may extend roots to find solid ground. */
    private static final int ROOT_DEPTH = 6;

    /** Same slow-noise gating ScarpCarver used to decide where mountain amplification applied. */
    private static final float PLACEMENT_NOISE_WAVELENGTH = 180.0f;
    private static final float PLACEMENT_THRESHOLD = 0.35f;

    private static final BlockState[] BADLANDS_BANDS = {
            Blocks.RED_SAND.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
    };

    @Override
    public String id() {
        return "hoodoo_cluster";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return CLUSTER_RADIUS + BASE_RADIUS + 2;
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
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("badlands") || biomeKey.contains("mesa"))) return;

        // Coherent placement noise: gate density so hoodoos cluster into patches of the badlands,
        // not every eligible site. Threshold rejects most of the field; strength (see below) fades
        // cluster size in near the threshold so patch edges look organic instead of a hard cutoff.
        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;
        float strength = SurfaceNoise.clamp01((placement - PLACEMENT_THRESHOLD) / (1.0f - PLACEMENT_THRESHOLD));

        int columnCount = 2 + (int) (strength * (MAX_COLUMNS - 2));
        // One strata offset for the whole cluster, so bands line up across every hoodoo in it.
        int bandOffset = (int) (SurfaceNoise.unitHash(site.seed(), 5, 9) * BADLANDS_BANDS.length);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        for (int i = 0; i < columnCount; i++) {
            long columnSeed = SurfaceNoise.hash(site.seed(), i, 1000 + i);
            float angle = SurfaceNoise.unitHash(columnSeed, 1, 0) * (float) (Math.PI * 2);
            float distance = SurfaceNoise.unitHash(columnSeed, 2, 0) * CLUSTER_RADIUS;
            int columnWorldX = site.worldX() + Math.round((float) Math.cos(angle) * distance);
            int columnWorldZ = site.worldZ() + Math.round((float) Math.sin(angle) * distance);

            // Skip columns whose footprint can't touch this chunk -- everything else is placed
            // with per-block clipping, so border columns get their full shape from both sides.
            if (!footprintTouchesChunk(chunk, columnWorldX, columnWorldZ, BASE_RADIUS + 1)) continue;

            int cRow = columnWorldZ - dataOriginZ;
            int cCol = columnWorldX - dataOriginX;
            if (!TerrainSampling.inBounds(data, cRow, cCol)) continue;
            float columnElevation = TerrainSampling.elevationAt(data, cRow, cCol);
            if (columnElevation <= 0f) continue;
            int groundY = HeightConverter.convertToMinecraftHeight((short) columnElevation) - 1;
            if (groundY <= chunk.getMinBuildHeight()) continue;

            int height = MIN_COLUMN_HEIGHT
                    + (int) (SurfaceNoise.unitHash(columnSeed, 3, 0) * (MAX_COLUMN_HEIGHT - MIN_COLUMN_HEIGHT));
            placeColumn(chunk, worldSurface, motionBlocking, columnWorldX, columnWorldZ,
                    groundY, height, bandOffset);
        }
    }

    static boolean footprintTouchesChunk(ChunkAccess chunk, int centerX, int centerZ, int reach) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        return centerX + reach >= minX && centerX - reach <= minX + 15
                && centerZ + reach >= minZ && centerZ - reach <= minZ + 15;
    }

    private void placeColumn(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                              int worldX, int worldZ, int groundY, int height, int bandOffset) {
        int capLayer = height - 1;
        for (int dy = 0; dy <= height; dy++) {
            int worldY = groundY + 1 + dy;
            // Radius shrinks with height, with a slightly flared cap layer for the classic hoodoo
            // silhouette (narrow neck, wider capstone).
            float taper = 1.0f - (dy / (float) height) * 0.7f;
            float radius = BASE_RADIUS * taper;
            if (dy == capLayer) radius += 0.6f;

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

    /** Extends a base block column downward until it meets solid ground, so slopes and raster
     *  error never leave the skirt floating. */
    private static void placeRoots(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                    int wx, int groundY, int wz, int bandOffset) {
        if (!SurfaceStamp.inChunk(chunk, wx, wz)) return;
        for (int y = groundY; y > groundY - ROOT_DEPTH; y--) {
            if (!SurfaceStamp.stateAt(chunk, wx, y, wz).isAir()) break;
            SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, wx, y, wz,
                    bandFor(bandOffset, y));
        }
    }

    /** Horizontal 1-block strata keyed to absolute Y -- continuous across the whole cluster. */
    private static BlockState bandFor(int bandOffset, int y) {
        return BADLANDS_BANDS[Math.floorMod(y + bandOffset, BADLANDS_BANDS.length)];
    }
}
