package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.server.world.ServerWorld;

/**
 * Runtime access for world-scoped terrain scale.
 */
public final class WorldScaleManager {
    public static final int DEFAULT_SCALE = 2;
    private static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 6;
    private static final String CURRENT_SCALE_SYSTEM_PROPERTY = "terrain-diffusion-mc.current-scale";
    private static final String CURRENT_SCALE_ENVIRONMENT_VARIABLE = "TERRAIN_DIFFUSION_MC_CURRENT_SCALE";

    private static final Integer CONFIGURED_CURRENT_SCALE = resolveConfiguredCurrentScale();
    private static volatile int currentScale = CONFIGURED_CURRENT_SCALE != null
            ? CONFIGURED_CURRENT_SCALE
            : DEFAULT_SCALE;

    private WorldScaleManager() {
    }

    /**
     * Loads or creates per-world scale settings and sets the active runtime value.
     *
     * <p>A configured current-scale override takes precedence over persisted state.
     * Without one, an existing world keeps its saved scale. A new world uses its pending
     * world-creation selection when present, otherwise {@value #DEFAULT_SCALE}.
     */
    public static void initializeForWorld(ServerWorld serverWorld) {
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getPersistentStateManager()
                .getOrCreate(WorldScaleSettingsState.TYPE);

        if (CONFIGURED_CURRENT_SCALE != null) {
            worldScaleSettingsState.setScale(CONFIGURED_CURRENT_SCALE);
        } else if (!worldScaleSettingsState.hasExplicitScale()) {
            Integer pendingScale = WorldScaleSelectionState.consumePendingScale();
            int resolvedScale = pendingScale != null ? pendingScale : DEFAULT_SCALE;
            worldScaleSettingsState.setScale(resolvedScale);
        }

        currentScale = clampScale(worldScaleSettingsState.getScale());
    }

    /**
     * Returns the currently active world scale.
     */
    public static int getCurrentScale() {
        return currentScale;
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
