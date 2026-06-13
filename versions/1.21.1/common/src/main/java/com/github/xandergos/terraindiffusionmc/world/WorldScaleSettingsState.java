package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persisted per-world settings for terrain diffusion.
 */
public final class WorldScaleSettingsState extends SavedData {
    public static final String DATA_NAME = "terrain_diffusion_world_settings";
    private static final String SCALE_KEY = "scale";
    private static final String EXPLICIT_SCALE_KEY = "explicit_scale";

    public static final SavedData.Factory<WorldScaleSettingsState> FACTORY = new SavedData.Factory<>(
            WorldScaleSettingsState::createDefault,
            WorldScaleSettingsState::load,
            null
    );

    private int scale;
    private boolean explicitScale;

    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
    }

    /**
     * Creates a default state for worlds that do not yet have saved terrain diffusion settings.
     */
    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false);
    }

    private static WorldScaleSettingsState load(CompoundTag tag, HolderLookup.Provider provider) {
        int configuredScale = tag.contains(SCALE_KEY) ? tag.getInt(SCALE_KEY) : WorldScaleManager.DEFAULT_SCALE;
        boolean hasExplicitScale = tag.contains(EXPLICIT_SCALE_KEY) && tag.getBoolean(EXPLICIT_SCALE_KEY);
        return new WorldScaleSettingsState(configuredScale, hasExplicitScale);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt(SCALE_KEY, scale);
        tag.putBoolean(EXPLICIT_SCALE_KEY, explicitScale);
        return tag;
    }

    /**
     * Returns the currently persisted world scale.
     */
    public int getScale() {
        return scale;
    }

    /**
     * Returns whether this world has an explicitly chosen scale.
     */
    public boolean hasExplicitScale() {
        return explicitScale;
    }

    /**
     * Applies a new persisted world scale and marks the state dirty.
     */
    public void setScale(int configuredScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = true;
        setDirty();
    }
}
