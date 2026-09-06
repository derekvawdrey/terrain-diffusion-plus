package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.BetterCavesAquiferContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs before Better Caves' own handler on the same method; see {@link BetterCavesAquiferContext}. */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public class AquiferContextGuardMixin {
    @Inject(method = "computeSubstance", at = @At("HEAD"))
    private void terrainDiffusion$keepBetterCavesContext(DensityFunction.FunctionContext context, double substance,
                                                          CallbackInfoReturnable<BlockState> cir) {
        BetterCavesAquiferContext.ensure();
    }
}
