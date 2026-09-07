package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.world.SnowDepth;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Snow lies deeper the colder the ground is.
 *
 * <p>Vanilla's freeze-and-snow pass puts exactly one snow layer on every column where it
 * snows. Here the layer count comes from the terrain model's own temperature at that column
 * ({@link SnowDepth}): a dusting where snow only just settles, a snow block with layers on top
 * in the coldest places. Snow that lands on a tree canopy also snows the ground beneath it,
 * since a canopy is not a roof. Where the snow sits on a block that keeps something alive under
 * it (a block entity, as Snow Real Magic uses), the count is capped so that mod can keep tending it.</p>
 */
@Mixin(SnowAndFreezeFeature.class)
public abstract class SnowAndFreezeFeatureMixin {
    private static final int MAX_CANOPY_SCAN = 40;

    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock("
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean terrainDiffusion$deepenSnow(WorldGenLevel level, BlockPos pos, BlockState state, int flags) {
        if (!state.is(Blocks.SNOW) || !TerrainDiffusionConfig.snowDepthScaling()
                || !(level.getLevel().getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return level.setBlock(pos, state, flags);
        }
        int depth = terrainDiffusion$snowDepth(pos);
        if (terrainDiffusion$isCanopy(level, pos)) {
            terrainDiffusion$snowGroundBelow(level, pos, state, depth, flags);
            return level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, 1), flags);
        }
        if (level.getBlockEntity(pos.below()) != null) {
            depth = Math.min(depth, TerrainDiffusionConfig.snowMaxLayersOverVegetation());
        }
        return terrainDiffusion$layDepth(level, pos, state, depth, flags);
    }

    private static boolean terrainDiffusion$layDepth(WorldGenLevel level, BlockPos pos, BlockState snow,
                                                     int depth, int flags) {
        if (depth <= SnowLayerBlock.MAX_HEIGHT) {
            return level.setBlock(pos, snow.setValue(SnowLayerBlock.LAYERS, Math.max(1, depth)), flags);
        }
        BlockPos above = pos.above();
        if (level.isOutsideBuildHeight(above) || !level.getBlockState(above).isAir()) {
            return level.setBlock(pos, snow.setValue(SnowLayerBlock.LAYERS, SnowLayerBlock.MAX_HEIGHT), flags);
        }
        level.setBlock(pos, Blocks.SNOW_BLOCK.defaultBlockState(), flags);
        return level.setBlock(above, snow.setValue(SnowLayerBlock.LAYERS, depth - SnowLayerBlock.MAX_HEIGHT), flags);
    }

    private static boolean terrainDiffusion$isCanopy(WorldGenLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(BlockTags.LEAVES) || below.is(BlockTags.LOGS);
    }

    /** Walks down through the canopy to the first solid ground and lays the depth there too. */
    private static void terrainDiffusion$snowGroundBelow(WorldGenLevel level, BlockPos pos, BlockState snow,
                                                         int depth, int flags) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < MAX_CANOPY_SCAN; i++) {
            cursor.move(Direction.DOWN);
            if (level.isOutsideBuildHeight(cursor)) return;
            BlockState here = level.getBlockState(cursor);
            if (here.isAir() || here.is(BlockTags.LEAVES) || here.is(BlockTags.LOGS)) continue;
            BlockPos target = cursor.above();
            if (!level.getBlockState(target).isAir()) return;
            if (!Blocks.SNOW.defaultBlockState().canSurvive(level, target)) return;
            terrainDiffusion$layDepth(level, target, snow, depth, flags);
            return;
        }
    }

    private static int terrainDiffusion$snowDepth(BlockPos pos) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int blockStartX = (pos.getX() >> tileShift) << tileShift;
        int blockStartZ = (pos.getZ() >> tileShift) << tileShift;
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                blockStartZ, blockStartX, blockStartZ + tileSize, blockStartX + tileSize);
        if (data == null || data.snowLayers == null) return 1;
        int localX = Math.max(0, Math.min(data.width - 1, pos.getX() - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, pos.getZ() - blockStartZ));
        return Math.max(1, Math.min(SnowDepth.MAX_LAYERS, data.snowLayers[localZ][localX]));
    }
}
