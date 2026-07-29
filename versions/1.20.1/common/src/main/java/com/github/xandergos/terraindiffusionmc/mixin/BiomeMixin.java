package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.ScaledAltitude;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Scales vanilla altitude cooling back to the equivalent model altitude. */
@Mixin(Biome.class)
public abstract class BiomeMixin {
    @ModifyVariable(method = "getTemperature(Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), argsOnly = true)
    private BlockPos terrainDiffusion$useEquivalentAltitude(BlockPos position) {
        if (!WorldScaleManager.isTerrainDiffusionWorldActive()) return position;
        int scale = WorldScaleManager.getCurrentScale();
        if (scale <= 1) return position;
        return new BlockPos(position.getX(), ScaledAltitude.equivalentY(position.getY(), scale), position.getZ());
    }
}
