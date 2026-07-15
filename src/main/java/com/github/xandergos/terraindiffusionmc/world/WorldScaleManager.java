package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime access for world-scoped terrain scale.
 */
public final class WorldScaleManager {
    private static final Logger LOG = LoggerFactory.getLogger(WorldScaleManager.class);
    public static final int DEFAULT_SCALE = 2;
    private static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 6;
    private static final String CURRENT_SCALE_SYSTEM_PROPERTY = "terrain-diffusion-mc.current-scale";
    private static final String CURRENT_SCALE_ENVIRONMENT_VARIABLE = "TERRAIN_DIFFUSION_MC_CURRENT_SCALE";

    private static final Integer CONFIGURED_CURRENT_SCALE = resolveConfiguredCurrentScale();
    private static volatile int currentScale = getStartupScale();

    private WorldScaleManager() {
    }

    /**
     * Loads or creates per-world scale settings and sets the active runtime value.
     *
     * <p>A new world's pending world-creation selection takes precedence over a configured
     * current-scale override. Every existing world keeps its persisted scale regardless of
     * startup overrides. Without either, new worlds use {@value #DEFAULT_SCALE}.
     */
    public static void initializeForWorld(ServerWorld serverWorld) {
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getPersistentStateManager()
                .getOrCreate(WorldScaleSettingsState.TYPE);
        boolean hasSavedScale = worldScaleSettingsState.hasExplicitScale();
        String scaleSource;

        if (!hasSavedScale) {
            Integer pendingScale = WorldScaleSelectionState.consumePendingScale();
            int resolvedScale = getStartupScale();
            if (pendingScale != null) {
                resolvedScale = pendingScale;
                scaleSource = "world creation selection";
            } else if (CONFIGURED_CURRENT_SCALE != null) {
                scaleSource = "startup override";
            } else {
                scaleSource = "default";
            }
            worldScaleSettingsState.setScale(resolvedScale);
        } else {
            scaleSource = "saved world";
        }

        currentScale = clampScale(worldScaleSettingsState.getScale());
        LOG.info("{} {} world '{}' with terrain scale {} ({})",
                hasSavedScale ? "Opened existing" : "Initialized new/unconfigured",
                serverWorld.getServer().isDedicated() ? "dedicated-server" : "single-player",
                serverWorld.getRegistryKey().getValue(),
                currentScale,
                scaleSource);
    }

    /**
     * Returns the currently active world scale.
     */
    public static int getCurrentScale() {
        return currentScale;
    }

    /**
     * Returns the configured startup override, or the fixed default when none is configured.
     */
    static int getStartupScale() {
        return CONFIGURED_CURRENT_SCALE != null ? CONFIGURED_CURRENT_SCALE : DEFAULT_SCALE;
    }

    /**
     * Updates world scale for the currently loaded world and persists it immediately.
     */
    public static void setCurrentScale(ServerWorld serverWorld, int configuredScale) {
        int clampedScale = clampScale(configuredScale);
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getPersistentStateManager()
                .getOrCreate(WorldScaleSettingsState.TYPE);
        worldScaleSettingsState.setScale(clampedScale);
        currentScale = clampedScale;
        LOG.info("Changed terrain scale for world '{}' to {} (programmatic update)",
                serverWorld.getRegistryKey().getValue(),
                currentScale);
    }

    /**
     * Clamps world scale to supported runtime bounds.
     */
    public static int clampScale(int configuredScale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, configuredScale));
    }

    private static Integer resolveConfiguredCurrentScale() {
        String systemPropertyScale = System.getProperty(CURRENT_SCALE_SYSTEM_PROPERTY);
        if (systemPropertyScale != null) {
            return parseConfiguredCurrentScale(systemPropertyScale,
                    "system property " + CURRENT_SCALE_SYSTEM_PROPERTY);
        }

        String environmentScale = System.getenv(CURRENT_SCALE_ENVIRONMENT_VARIABLE);
        if (environmentScale != null) {
            return parseConfiguredCurrentScale(environmentScale,
                    "environment variable " + CURRENT_SCALE_ENVIRONMENT_VARIABLE);
        }

        return TerrainDiffusionConfig.currentWorldScale()
                .map(value -> parseConfiguredCurrentScale(value, "configuration property world.current_scale"))
                .orElse(null);
    }

    private static Integer parseConfiguredCurrentScale(String rawScale, String source) {
        try {
            int configuredScale = Integer.parseInt(rawScale.trim());
            if (configuredScale >= MIN_SCALE && configuredScale <= MAX_SCALE) {
                return configuredScale;
            }
        } catch (NumberFormatException ignored) {
        }
        System.err.println("Invalid world scale from " + source + ": " + rawScale
                + "; expected an integer from " + MIN_SCALE + " to " + MAX_SCALE
                + ", ignoring override");
        return null;
    }
}
