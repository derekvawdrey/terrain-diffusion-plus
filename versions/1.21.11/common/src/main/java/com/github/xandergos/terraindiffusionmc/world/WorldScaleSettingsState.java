package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persisted per-world Terrain Diffusion settings. */
public final class WorldScaleSettingsState extends SavedData {
    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("scale", WorldScaleManager.DEFAULT_SCALE).forGetter(WorldScaleSettingsState::getScale),
            Codec.BOOL.optionalFieldOf("explicit_settings", false).forGetter(WorldScaleSettingsState::hasExplicitSettings),
            Codec.BOOL.optionalFieldOf("explicit_scale", false).forGetter(WorldScaleSettingsState::hasExplicitSettings),
            Codec.BOOL.optionalFieldOf("block_sources_below_775m", false).forGetter(WorldScaleSettingsState::shouldBlockLowAltitudeSources)
    ).apply(instance, (scale, explicitSettings, legacyExplicitScale, sourceLimit) ->
            new WorldScaleSettingsState(scale, explicitSettings || legacyExplicitScale, sourceLimit)));

    private int scale;
    private boolean explicitSettings;
    private boolean blockLowAltitudeSources;

    private WorldScaleSettingsState(int scale, boolean explicitSettings, boolean sourceLimit) {
        this.scale = WorldScaleManager.clampScale(scale);
        this.explicitSettings = explicitSettings;
        this.blockLowAltitudeSources = sourceLimit;
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false,
                WorldScaleManager.DEFAULT_BLOCK_LOW_ALTITUDE_SOURCES);
    }

    public static final SavedDataType<WorldScaleSettingsState> TYPE =
            new SavedDataType<>("terrain_diffusion_world_settings", WorldScaleSettingsState::createDefault, CODEC, null);

    public int getScale() { return scale; }
    public boolean hasExplicitSettings() { return explicitSettings; }
    public boolean hasExplicitScale() { return explicitSettings; }
    public boolean shouldBlockLowAltitudeSources() { return blockLowAltitudeSources; }
    public void setSettings(int configuredScale, boolean sourceLimit) {
        scale = WorldScaleManager.clampScale(configuredScale);
        blockLowAltitudeSources = sourceLimit;
        explicitSettings = true;
        setDirty();
    }
    public void setScale(int configuredScale) { setSettings(configuredScale, blockLowAltitudeSources); }
}
