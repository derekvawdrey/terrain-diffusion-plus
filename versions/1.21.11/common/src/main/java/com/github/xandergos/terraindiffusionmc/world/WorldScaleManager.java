package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.server.level.ServerLevel;

/** Runtime access for world-scoped terrain generation settings. */
public final class WorldScaleManager {
    public static final int DEFAULT_SCALE = 2;
    private static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 6;
    public static final float MINIMUM_SOURCE_ELEVATION_METERS = 775.0f;
    public static final boolean DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES = false;

    private static volatile int currentScale = DEFAULT_SCALE;
    private static volatile boolean blockLowAltitudeSources = DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES;
    private static volatile boolean terrainDiffusionWorldActive;

    private WorldScaleManager() {}

    public static void initializeForWorld(ServerLevel serverWorld, boolean terrainDiffusionWorld) {
        terrainDiffusionWorldActive = terrainDiffusionWorld;
        if (!terrainDiffusionWorld) {
            currentScale = DEFAULT_SCALE;
            blockLowAltitudeSources = DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES;
            return;
        }
        WorldScaleSettingsState state = serverWorld.getDataStorage().computeIfAbsent(WorldScaleSettingsState.TYPE);
        if (!state.hasExplicitSettings()) {
            WorldScaleSelectionState.PendingSettings pending = WorldScaleSelectionState.consumePendingSettings();
            int scale = pending != null ? pending.scale() : DEFAULT_SCALE;
            boolean sourcePolicy = pending != null
                    ? pending.blockLowAltitudeSources() : DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES;
            state.setSettings(scale, sourcePolicy);
        }
        currentScale = clampScale(state.getScale());
        blockLowAltitudeSources = state.shouldBlockLowAltitudeSources();
    }

    /** Compatibility entrypoint for older loader hooks. */
    public static void initializeForWorld(ServerLevel serverWorld) {
        initializeForWorld(serverWorld, true);
    }

    public static int getCurrentScale() { return currentScale; }
    public static boolean shouldBlockLowAltitudeSources() { return blockLowAltitudeSources; }
    public static boolean isTerrainDiffusionWorldActive() { return terrainDiffusionWorldActive; }

    public static void setCurrentSettings(ServerLevel serverWorld, int configuredScale,
                                          boolean blockSourcesBelow775m) {
        WorldScaleSettingsState state = serverWorld.getDataStorage().computeIfAbsent(WorldScaleSettingsState.TYPE);
        state.setSettings(configuredScale, blockSourcesBelow775m);
        currentScale = state.getScale();
        blockLowAltitudeSources = state.shouldBlockLowAltitudeSources();
    }

    public static void setCurrentScale(ServerLevel serverWorld, int configuredScale) {
        setCurrentSettings(serverWorld, configuredScale, blockLowAltitudeSources);
    }

    public static int clampScale(int configuredScale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, configuredScale));
    }
}
