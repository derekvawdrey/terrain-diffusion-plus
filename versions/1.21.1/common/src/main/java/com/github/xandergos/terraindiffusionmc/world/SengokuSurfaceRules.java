package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.platform.PlatformMods;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives diffusion terrain Sengoku Jidai's ground blocks when that mod is installed.
 *
 * <h2>Why this is needed at all</h2>
 * <p>Sengoku rethemes the 64 vanilla biomes in place, so its vegetation, biome colours and mob
 * spawns already arrive through the ordinary biome registry with no work on our side. Its
 * <i>surface rule</i> does not: that lives in {@code minecraft:worldgen/noise_settings/overworld},
 * and we replace the overworld with {@code terrain-diffusion-mc:terrain_diffusion}, so the game
 * never consults Sengoku's copy and the ground stays vanilla-ish under Japanese trees.</p>
 *
 * <h2>Why it copies at runtime instead of shipping a merged file</h2>
 * <p>Sengoku is published All Rights Reserved, so a pre-merged noise-settings file cannot be
 * distributed in this mod. Nothing here distributes anything: by the time this runs the player's
 * own installed copy is already loaded in the dynamic registry, and this reads the rule out of
 * memory and points our settings at the very same object. No file is written and no content
 * leaves the machine. ({@code tools/sengoku/build_surface_datapack.py} does the same merge as a
 * local datapack, for anyone who wants to hand-edit the result.)</p>
 *
 * <h2>Caveats</h2>
 * <p>Sengoku's rule was authored against vanilla terrain shape. Its biome-keyed branches carry
 * over cleanly, but anything it infers from terrain -- {@code steep}, {@code above_preliminary_surface}
 * -- is being asked about a landscape it was not tuned on. {@code biome.sengoku_surface_rules=false}
 * turns this off and restores our own surface rule.</p>
 */
public final class SengokuSurfaceRules {
    private static final Logger LOG = LoggerFactory.getLogger(SengokuSurfaceRules.class);
    private static final String SENGOKU_MOD_ID = "sengoku";

    private static final ResourceKey<NoiseGeneratorSettings> TERRAIN_DIFFUSION =
            ResourceKey.create(Registries.NOISE_SETTINGS,
                    ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "terrain_diffusion"));

    private SengokuSurfaceRules() {
    }

    /**
     * Copies the loaded overworld surface rule onto our noise settings, if Sengoku is installed
     * and the feature is enabled. Called once per server load, before any level exists; the
     * registry objects are rebuilt per load, so this deliberately does not latch on a static
     * "already applied" flag. Re-running it is a no-op anyway -- it assigns the same rule object.
     */
    public static void applyIfPresent(RegistryAccess registries) {
        if (!TerrainDiffusionConfig.sengokuSurfaceRules()) return;
        if (!PlatformMods.isLoaded(SENGOKU_MOD_ID)) return;

        try {
            Registry<NoiseGeneratorSettings> registry = registries.registryOrThrow(Registries.NOISE_SETTINGS);
            NoiseGeneratorSettings overworld = registry.get(NoiseGeneratorSettings.OVERWORLD);
            NoiseGeneratorSettings ours = registry.get(TERRAIN_DIFFUSION);
            if (overworld == null || ours == null) {
                LOG.warn("Sengoku surface rules: noise settings missing (overworld={}, ours={}), skipping",
                        overworld != null, ours != null);
                return;
            }
            if (overworld.surfaceRule() == ours.surfaceRule()) return;

            ((MutableSurfaceRuleSettings) (Object) ours).terrainDiffusion$setSurfaceRule(overworld.surfaceRule());
            LOG.info("Applied Sengoku Jidai surface rules to terrain-diffusion terrain");
        } catch (Exception e) {
            // Worth a broken look, not a broken world: on any failure the mod keeps its own
            // surface rule and generation carries on normally.
            LOG.error("Failed to apply Sengoku surface rules, keeping the built-in ones", e);
        }
    }
}
