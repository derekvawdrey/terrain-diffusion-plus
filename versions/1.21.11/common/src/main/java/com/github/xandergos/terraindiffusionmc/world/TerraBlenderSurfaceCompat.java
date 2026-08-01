package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Optional TerraBlender integration. TerraBlender injects modded surface rules (Biomes O' Plenty,
 * etc.) only into generators it recognizes — a NoiseBasedChunkGenerator with a vanilla
 * MultiNoiseBiomeSource — so our custom biome source is skipped and modded biomes fall through to
 * the generic grass/dirt defaults in our surface rule.
 *
 * TerraBlender's own NoiseGeneratorSettings mixin ({@code IExtendedNoiseGeneratorSettings}) is
 * applied to every settings instance and wraps {@code surfaceRule()} with the namespaced modded
 * rules once a rule category is set. Flagging our settings as OVERWORLD is therefore the entire
 * integration: modded biomes get their mod's surface rules, all other biomes fall back to ours.
 *
 * Accessed reflectively so TerraBlender remains an optional runtime dependency.
 */
public final class TerraBlenderSurfaceCompat {
    private static final Logger LOG = LoggerFactory.getLogger(TerraBlenderSurfaceCompat.class);

    private TerraBlenderSurfaceCompat() {
    }

    public static void apply(ChunkGenerator generator) {
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            return;
        }
        NoiseGeneratorSettings settings = noiseGenerator.generatorSettings().value();
        try {
            Class<?> extendedSettings = Class.forName("terrablender.worldgen.IExtendedNoiseGeneratorSettings");
            if (!extendedSettings.isInstance(settings)) {
                LOG.warn("TerraBlender is present but its NoiseGeneratorSettings mixin is missing; "
                        + "modded surface rules will not be merged.");
                return;
            }
            Class<?> categoryClass = Class.forName("terrablender.api.SurfaceRuleManager$RuleCategory");
            Object overworldCategory = categoryClass.getField("OVERWORLD").get(null);
            Method setRuleCategory = extendedSettings.getMethod("setRuleCategory", categoryClass);
            setRuleCategory.invoke(settings, overworldCategory);
            LOG.info("TerraBlender detected; merging modded surface rules into the terrain diffusion surface.");
        } catch (ClassNotFoundException ignored) {
            // TerraBlender not installed; modded biomes keep the generic fallback surface.
        } catch (ReflectiveOperationException e) {
            LOG.warn("Failed to merge TerraBlender surface rules; modded biomes will use fallback surfaces.", e);
        }
    }
}
