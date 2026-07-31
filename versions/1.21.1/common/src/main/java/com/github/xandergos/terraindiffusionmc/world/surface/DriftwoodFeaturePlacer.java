package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Driftwood placer -- scattered horizontal logs on beach surfaces.
 * Based on {@link BoulderFeaturePlacer} (single-anchor pattern) but places
 * multiple oriented log blocks in a pile configuration on beach biomes.
 *
 * <p>Logs are placed horizontally (AXIS_X or AXIS_Z) in a scattered pile
 * within a 3x3 area. Mix of oak and spruce logs with some stacking.</p>
 */
public final class DriftwoodFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x44524946L;
    private static final int CELL_SIZE = 24;
    private static final float SPAWN_CHANCE = 0.4f;
    private static final float MAX_SLOPE_BLOCKS = 0.15f;
    private static final float MIN_ELEVATION_BLOCKS = 0f;
    private static final float MAX_ELEVATION_BLOCKS = 3f;
    private static final int MIN_LOGS = 2;
    private static final int MAX_LOGS = 4;
    private static final int SCATTER_RADIUS = 1;

    private static final BlockState OAK_LOG_X = Blocks.OAK_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
    private static final BlockState OAK_LOG_Z = Blocks.OAK_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
    private static final BlockState SPRUCE_LOG_X = Blocks.SPRUCE_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
    private static final BlockState SPRUCE_LOG_Z = Blocks.SPRUCE_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);

    @Override
    public String id() {
        return "driftwood";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return 5;
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
        if (biomeKey == null || (!biomeKey.contains("beach") && !biomeKey.contains("stony"))) return;
        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        stamp(chunk, site);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site) {
        int logCount = SurfaceStamp.randRange(site.seed(), 0, 0, MIN_LOGS, MAX_LOGS);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int i = 0; i < logCount; i++) {
            long logSeed = SurfaceNoise.hash(site.seed(), i, 3000 + i);
            int dx = Math.round((float) (SurfaceNoise.unitHash(logSeed, 1, 0) * SCATTER_RADIUS * 2 - SCATTER_RADIUS));
            int dz = Math.round((float) (SurfaceNoise.unitHash(logSeed, 2, 0) * SCATTER_RADIUS * 2 - SCATTER_RADIUS));
            boolean stacked = SurfaceNoise.unitHash(logSeed, 3, 0) < 0.5f;

            int logWorldX = site.worldX() + dx;
            int logWorldZ = site.worldZ() + dz;
            int logLocalX = logWorldX - minX;
            int logLocalZ = logWorldZ - minZ;
            if (logLocalX < 0 || logLocalX > 15 || logLocalZ < 0 || logLocalZ > 15) continue;

            boolean isXAxis = SurfaceNoise.unitHash(logSeed, 4, 0) < 0.5f;
            BlockState logState = logStateFor(logSeed, isXAxis);

            // Rest each log on whatever is under it rather than on the site's own ground height,
            // and only stack a second log where the first one actually landed -- the old fixed
            // +1 offset left half the pile hanging a block above the sand.
            int logGroundY = SurfaceStamp.surfaceY(chunk, logLocalX, logLocalZ);
            if (logGroundY <= chunk.getMinBuildHeight()) continue;

            if (!SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking,
                    logWorldX, logGroundY + 1, logWorldZ, logState)) {
                continue;
            }
            if (stacked) {
                SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking,
                        logWorldX, logGroundY + 2, logWorldZ, logStateFor(logSeed, !isXAxis));
            }
        }
    }

    private static BlockState logStateFor(long seed, boolean isXAxis) {
        boolean isSpruce = SurfaceNoise.unitHash(seed, 5, 0) < 0.3f;
        if (isSpruce) {
            return isXAxis ? SPRUCE_LOG_X : SPRUCE_LOG_Z;
        }
        return isXAxis ? OAK_LOG_X : OAK_LOG_Z;
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
