package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Lets {@link SengokuSurfaceRules} replace a loaded {@code NoiseGeneratorSettings}' surface rule.
 *
 * <p>{@code NoiseGeneratorSettings} is a record, so {@code surfaceRule} is final and there is no
 * setter to call; {@code NoiseGeneratorSettingsMixin} un-finals it and implements this interface
 * on the record. Everything else in the settings -- our noise router, and therefore the diffusion
 * terrain itself -- is left exactly as loaded.</p>
 */
public interface MutableSurfaceRuleSettings {
    void terrainDiffusion$setSurfaceRule(SurfaceRules.RuleSource surfaceRule);
}
