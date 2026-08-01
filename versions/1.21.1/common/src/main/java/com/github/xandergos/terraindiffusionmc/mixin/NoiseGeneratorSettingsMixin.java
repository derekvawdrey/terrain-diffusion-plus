package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.MutableSurfaceRuleSettings;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes the record's {@code surfaceRule} component assignable, so an already-loaded settings
 * object can have its surface rule swapped after datapack load. Safe to do in place because
 * {@code NoiseBasedChunkGenerator.buildSurface} reads {@code settings.value().surfaceRule()} on
 * every call rather than caching it.
 */
@Mixin(NoiseGeneratorSettings.class)
public abstract class NoiseGeneratorSettingsMixin implements MutableSurfaceRuleSettings {

    @Mutable
    @Shadow
    @Final
    private SurfaceRules.RuleSource surfaceRule;

    @Override
    public void terrainDiffusion$setSurfaceRule(SurfaceRules.RuleSource surfaceRule) {
        this.surfaceRule = surfaceRule;
    }
}
