package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.ScaledAltitude;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * Inside a structure search (see {@code LocalTerrainProvider.beginFarLookup}) a surface-height
 * probe is answered from the terrain heightmap instead of by running the whole noise column.
 *
 * <p>{@code getBaseHeight} / {@code getFirstOccupiedHeight} build a {@code NoiseChunk} for one
 * column and evaluate the density router at every block from the top of an 800-block world
 * down to the surface: a few milliseconds each. A buried-treasure search probes every chunk in
 * a 100-chunk radius, 40 000 columns, which is minutes of server-thread time before the search
 * even reaches the biome test. The terrain provider already knows the surface: the density
 * function it feeds is exactly {@code surface - y}, so reading the heightmap gives the same
 * answer the column would, minus rivers and carving, which no structure placement depends on.</p>
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class FarLookupHeightMixin {
    @Inject(method = "iterateNoiseColumn", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$farLookupHeight(LevelHeightAccessor heightAccessor, RandomState randomState,
                                                   int x, int z, MutableObject<NoiseColumn> column,
                                                   Predicate<BlockState> stopPredicate,
                                                   CallbackInfoReturnable<OptionalInt> cir) {
        if (column != null || stopPredicate == null || !LocalTerrainProvider.inFarLookup()) return;
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (!(self.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int blockStartX = (x >> tileShift) << tileShift;
        int blockStartZ = (z >> tileShift) << tileShift;
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(
                blockStartZ, blockStartX, blockStartZ + tileSize, blockStartX + tileSize);
        if (data == null || data.heightmap == null) return;
        int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));
        // The density function puts the top solid block at surface - 1, so the "first free"
        // height a column walk reports is the surface itself.
        int surface = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        boolean stopsAtWater = stopPredicate.test(Blocks.WATER.defaultBlockState());
        int result = stopsAtWater ? Math.max(surface, ScaledAltitude.SEA_LEVEL) : surface;
        result = Math.max(heightAccessor.getMinBuildHeight(), Math.min(heightAccessor.getMaxBuildHeight(), result));
        cir.setReturnValue(OptionalInt.of(result));
    }
}
