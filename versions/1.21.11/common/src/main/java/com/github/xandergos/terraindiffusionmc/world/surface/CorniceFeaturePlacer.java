package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Places overhanging snow ledges on ridge crests in snowy/high-altitude biomes.
 * Determines the downhill direction from terrain gradient and extends a snow block
 * ledge outward, with powder snow underneath for the overhang effect.
 */
public final class CorniceFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x434F524EL;
    private static final float SPAWN_CHANCE = 0.2f;
    private static final float MIN_SLOPE_BLOCKS = 0.5f;
    private static final float MIN_ELEVATION_BLOCKS = 90f;
    private static final int CELL_SIZE = 36;
    private static final int SAMPLE_STEP = 4;

    @Override
    public String id() {
        return "cornice";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return 8;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        int localX = site.worldX() - chunk.getPos().getMinBlockX();
        int localZ = site.worldZ() - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return;

        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        if (TerrainSampling.elevationAt(data, row, col) <= SurfaceStamp.blocksToElevation(MIN_ELEVATION_BLOCKS)) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || (!biomeKey.contains("snow") && !biomeKey.contains("peaks")
                && !biomeKey.contains("frozen") && !biomeKey.contains("ice")
                && !biomeKey.contains("grove"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) < SurfaceStamp.slopeFromBlocks(MIN_SLOPE_BLOCKS)) return;

        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinY()) return;

        int dx = 0;
        int dz = 0;
        float elevCenter = TerrainSampling.elevationAt(data, row, col);
        float[] elevations = new float[4];
        int[] dirsX = {1, -1, 0, 0};
        int[] dirsZ = {0, 0, 1, -1};
        for (int i = 0; i < 4; i++) {
            int r = row + dirsZ[i] * SAMPLE_STEP;
            int c = col + dirsX[i] * SAMPLE_STEP;
            if (TerrainSampling.inBounds(data, r, c)) {
                elevations[i] = TerrainSampling.elevationAt(data, r, c);
            } else {
                elevations[i] = elevCenter;
            }
        }
        float steepestDrop = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float drop = elevCenter - elevations[i];
            if (drop > steepestDrop) {
                steepestDrop = drop;
                dx = dirsX[i];
                dz = dirsZ[i];
            }
        }

        stamp(chunk, site, groundY, dx, dz);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY, int dirX, int dirZ) {
        int length = 2 + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * 3);
        int thickness = 1 + (int) (SurfaceNoise.unitHash(site.seed(), 1, 1) * 2);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockState snowBlock = Blocks.SNOW_BLOCK.defaultBlockState();
        BlockState powderSnow = Blocks.POWDER_SNOW.defaultBlockState();

        for (int step = 0; step < length; step++) {
            int worldX = site.worldX() + dirX * (step + 1);
            int worldZ = site.worldZ() + dirZ * (step + 1);
            int localX = worldX - minX;
            int localZ = worldZ - minZ;
            if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) continue;

            for (int ty = 0; ty < thickness; ty++) {
                int worldY = groundY + 1 - ty;
                pos.set(worldX, worldY, worldZ);
                if (!chunk.getBlockState(pos).isAir()) continue;

                BlockState block = ty == 0 ? snowBlock : powderSnow;
                chunk.setBlockState(pos, block);
                worldSurface.update(localX, worldY, localZ, block);
                motionBlocking.update(localX, worldY, localZ, block);
            }
        }
    }
}
