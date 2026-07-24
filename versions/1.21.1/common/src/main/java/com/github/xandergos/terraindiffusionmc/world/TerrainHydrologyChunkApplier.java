package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Materializes the water surfaces calculated by the shared fluvial pipeline.
 */
public final class TerrainHydrologyChunkApplier {
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    private TerrainHydrologyChunkApplier() {
    }

    public static void apply(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileMinX = Math.floorDiv(chunkMinX, tileSize) * tileSize;
        int tileMinZ = Math.floorDiv(chunkMinZ, tileSize) * tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                tileMinZ, tileMinX, tileMinZ + tileSize, tileMinX + tileSize);
        if (data == null || data.heightmap == null || data.waterMask == null || data.waterSurface == null) {
            return;
        }

        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++) {
            int dataZ = chunkMinZ + localZ - tileMinZ;
            if (dataZ < 0 || dataZ >= data.height) {
                continue;
            }
            for (int localX = 0; localX < 16; localX++) {
                int dataX = chunkMinX + localX - tileMinX;
                if (dataX < 0 || dataX >= data.width
                        || data.waterMask[dataZ][dataX] < HeightmapData.MIN_IN_GAME_WATER_MASK) {
                    continue;
                }

                short surfaceMeters = data.waterSurface[dataZ][dataX];
                if (surfaceMeters == HeightmapData.NO_WATER_SURFACE) {
                    continue;
                }

                int bedY = HeightConverter.convertToMinecraftHeight(data.heightmap[dataZ][dataX]);
                int surfaceY = HeightConverter.convertToMinecraftHeight(surfaceMeters);
                int firstWaterY = Math.max(minY, bedY);
                int lastWaterY = Math.min(maxY, surfaceY);
                if (firstWaterY > lastWaterY) {
                    continue;
                }

                int blockX = chunkMinX + localX;
                int blockZ = chunkMinZ + localZ;
                for (int y = firstWaterY; y <= lastWaterY; y++) {
                    cursor.set(blockX, y, blockZ);
                    if (chunk.getBlockState(cursor).isAir()) {
                        chunk.setBlockState(cursor, WATER, false);
                    }
                }
            }
        }
    }
}
