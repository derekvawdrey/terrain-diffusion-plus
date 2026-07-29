package com.github.xandergos.terraindiffusionmc.world;

import java.util.concurrent.atomic.AtomicReference;

/** In-memory handoff for terrain settings selected in the single-player world creation UI. */
public final class WorldScaleSelectionState {
    private static final AtomicReference<PendingSettings> PENDING_SETTINGS = new AtomicReference<>();

    private WorldScaleSelectionState() {}

    public static void setPendingSettings(int selectedScale, boolean blockLowAltitudeSources) {
        PENDING_SETTINGS.set(new PendingSettings(
                WorldScaleManager.clampScale(selectedScale), blockLowAltitudeSources));
    }

    /** Compatibility helper for callers that only update scale. */
    public static void setPendingScale(int selectedScale) {
        setPendingSettings(selectedScale, getPendingBlockLowAltitudeSourcesOrDefault());
    }

    public static PendingSettings consumePendingSettings() {
        return PENDING_SETTINGS.getAndSet(null);
    }

    public static Integer consumePendingScale() {
        PendingSettings settings = consumePendingSettings();
        return settings == null ? null : settings.scale();
    }

    public static int getPendingScaleOrDefault() {
        PendingSettings settings = PENDING_SETTINGS.get();
        return settings == null ? WorldScaleManager.DEFAULT_SCALE : settings.scale();
    }

    public static boolean getPendingBlockLowAltitudeSourcesOrDefault() {
        PendingSettings settings = PENDING_SETTINGS.get();
        return settings == null
                ? WorldScaleManager.DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES
                : settings.blockLowAltitudeSources();
    }

    public record PendingSettings(int scale, boolean blockLowAltitudeSources) {}
}
