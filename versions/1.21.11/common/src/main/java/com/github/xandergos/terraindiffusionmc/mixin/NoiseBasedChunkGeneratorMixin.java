package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.ScaledCarvers;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Runs whatever carvers the pack's biomes list, moved up to where our terrain is.
 *
 * <p>We deliberately intercept the biome's carver list rather than the carving step itself: a mod
 * that replaces the step outright never calls this, which is the correct outcome -- it has taken
 * over cave generation and we should stay out of its way. See
 * {@link ScaledCarvers} for what is and is not rewritten.</p>
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {

    @Redirect(method = "applyCarvers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/BiomeGenerationSettings;getCarvers()Ljava/lang/Iterable;"))
    private Iterable<Holder<ConfiguredWorldCarver<?>>> terrainDiffusion$liftCarversToTerrain(
            BiomeGenerationSettings settings) {
        Iterable<Holder<ConfiguredWorldCarver<?>>> carvers = settings.getCarvers();
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (!(self.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return carvers;
        return ScaledCarvers.lift(carvers, WorldScaleManager.getCurrentScale());
    }
}
