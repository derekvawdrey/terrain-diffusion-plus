package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Tide pool placer -- a cluster of small water pools at the base of coastal cliffs.
 * Based on {@link BoulderFeaturePlacer} (single-anchor pattern) but creates multiple
 * small depressions filled with water and decorated with prismarine blocks.
 *
 * <p>Pattern: gate on coastal biome + flat terrain + rarity roll, anchor to heightmap,
 * then create 2-4 circular depressions scattered within range, each filled with water
 * and edged with prismarine/dark prismarine/sea lantern blocks.</p>
 */
public final class TidePoolFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x54494450L;
    private static final float SPAWN_CHANCE = 0.25f;
    private static final int CELL_SIZE = 40;
    private static final float MAX_SLOPE_BLOCKS = 0.15f;
    private static final float MIN_ELEVATION_BLOCKS = -1f;
    private static final float MAX_ELEVATION_BLOCKS = 3f;
    private static final int SCATTER_RADIUS = 6;
    private static final int MIN_POOLS = 2;
    private static final int MAX_POOLS = 4;
    private static final int MAX_POOL_RADIUS = 2;

    private static final BlockState[] EDGE_BLOCKS = {
            Blocks.PRISMARINE.defaultBlockState(),
            Blocks.DARK_PRISMARINE.defaultBlockState(),
            Blocks.SEA_LANTERN.defaultBlockState(),
    };

    @Override
    public String id() {
        return "tide_pool";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return SCATTER_RADIUS + MAX_POOL_RADIUS + 2;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        // No site-in-chunk gate: every column below is anchored and clipped individually, so each
        // chunk this site reaches draws its own slice instead of the footprint being cut off at the
        // chunk border.
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation < SurfaceStamp.blocksToElevation(MIN_ELEVATION_BLOCKS) || elevation > SurfaceStamp.blocksToElevation(MAX_ELEVATION_BLOCKS)) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !isCoastalBiome(biomeKey)) return;

        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        int poolCount = SurfaceStamp.randRange(site.seed(), 0, 0, MIN_POOLS, MAX_POOLS);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int i = 0; i < poolCount; i++) {
            long poolSeed = SurfaceNoise.hash(site.seed(), i, 2000 + i);
            float angle = SurfaceNoise.unitHash(poolSeed, 1, 0) * (float) (Math.PI * 2);
            float distance = SurfaceNoise.unitHash(poolSeed, 2, 0) * SCATTER_RADIUS;
            int poolWorldX = site.worldX() + Math.round((float) Math.cos(angle) * distance);
            int poolWorldZ = site.worldZ() + Math.round((float) Math.sin(angle) * distance);

            int poolLocalX = poolWorldX - minX;
            int poolLocalZ = poolWorldZ - minZ;
            if (poolLocalX < 0 || poolLocalX > 15 || poolLocalZ < 0 || poolLocalZ > 15) continue;

            int poolGroundY = SurfaceStamp.surfaceY(chunk, poolLocalX, poolLocalZ);
            if (poolGroundY <= chunk.getMinY()) continue;

            int radius = SurfaceStamp.randRange(poolSeed, 3, 0, 1, MAX_POOL_RADIUS);
            placePool(chunk, worldSurface, motionBlocking, poolSeed, poolWorldX, poolWorldZ,
                    poolGroundY, radius);
        }
    }

    private boolean isCoastalBiome(String biomeKey) {
        return biomeKey.contains("beach") || biomeKey.contains("ocean") || biomeKey.contains("stony");
    }

    /**
     * Scoops a shallow basin and fills it with water.
     *
     * <p>The pool has to occupy the surface course itself: putting water one block <em>below</em>
     * {@code groundY} left the original surface block sitting on top of it, so the pool was an
     * invisible pocket underground and only the rim ever showed.</p>
     */
    private void placePool(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long poolSeed,
                            int worldX, int worldZ, int groundY, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > radius + 0.3f) continue;

                int wx = worldX + dx;
                int wz = worldZ + dz;
                boolean isEdge = dist > radius - 1f;

                if (isEdge) {
                    // Rim sits flush with the surrounding ground.
                    if (!SurfaceStamp.stateAt(chunk, wx, groundY, wz).isAir()) {
                        BlockState edgeBlock = EDGE_BLOCKS[Math.floorMod(
                                (int) (SurfaceNoise.unitHash(poolSeed, wx, wz) * EDGE_BLOCKS.length),
                                EDGE_BLOCKS.length)];
                        SurfaceStamp.placeReplacing(chunk, worldSurface, motionBlocking, wx, groundY, wz, edgeBlock);
                    }
                } else {
                    // Interior: clear the lip so the pool is open to the sky, then water the basin.
                    SurfaceStamp.carve(chunk, worldSurface, motionBlocking, wx, groundY + 1, wz);
                    if (!SurfaceStamp.stateAt(chunk, wx, groundY, wz).isAir()) {
                        SurfaceStamp.fill(chunk, worldSurface, motionBlocking, wx, groundY, wz,
                                Blocks.WATER.defaultBlockState());
                    }
                }
            }
        }
    }
}
