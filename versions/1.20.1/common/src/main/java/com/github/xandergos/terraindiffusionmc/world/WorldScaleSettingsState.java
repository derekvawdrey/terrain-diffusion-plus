package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Persisted per-world Terrain Diffusion settings. */
public final class WorldScaleSettingsState extends SavedData {
    public static final String DATA_NAME = "terrain_diffusion_world_settings";
    private static final String SCALE_KEY = "scale";
    private static final String EXPLICIT_KEY = "explicit_settings";
    private static final String LEGACY_EXPLICIT_KEY = "explicit_scale";
    private static final String SOURCE_LIMIT_KEY = "block_sources_below_775m";

    private int scale;
    private boolean explicitSettings;
    private boolean blockLowAltitudeSources;

    private WorldScaleSettingsState(int scale, boolean explicitSettings, boolean blockLowAltitudeSources) {
        this.scale = WorldScaleManager.clampScale(scale);
        this.explicitSettings = explicitSettings;
        this.blockLowAltitudeSources = blockLowAltitudeSources;
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false,
                WorldScaleManager.DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES);
    }

    public static WorldScaleSettingsState load(CompoundTag tag) {
        int scale = tag.contains(SCALE_KEY) ? tag.getInt(SCALE_KEY) : WorldScaleManager.DEFAULT_SCALE;
        boolean explicit = tag.contains(EXPLICIT_KEY) ? tag.getBoolean(EXPLICIT_KEY)
                : tag.contains(LEGACY_EXPLICIT_KEY) && tag.getBoolean(LEGACY_EXPLICIT_KEY);
        boolean sourceLimit = tag.contains(SOURCE_LIMIT_KEY) && tag.getBoolean(SOURCE_LIMIT_KEY);
        return new WorldScaleSettingsState(scale, explicit, sourceLimit);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(SCALE_KEY, scale);
        tag.putBoolean(EXPLICIT_KEY, explicitSettings);
        tag.putBoolean(LEGACY_EXPLICIT_KEY, explicitSettings);
        tag.putBoolean(SOURCE_LIMIT_KEY, blockLowAltitudeSources);
        return tag;
    }

    public int getScale() { return scale; }
    public boolean hasExplicitSettings() { return explicitSettings; }
    public boolean hasExplicitScale() { return explicitSettings; }
    public boolean shouldBlockLowAltitudeSources() { return blockLowAltitudeSources; }

    public void setSettings(int configuredScale, boolean blockSourcesBelow775m) {
        scale = WorldScaleManager.clampScale(configuredScale);
        blockLowAltitudeSources = blockSourcesBelow775m;
        explicitSettings = true;
        setDirty();
    }

    public void setScale(int configuredScale) {
        setSettings(configuredScale, blockLowAltitudeSources);
    }
}
