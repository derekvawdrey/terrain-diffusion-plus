package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.Optional;

/**
 * Persisted per-world settings for terrain diffusion.
 *
 * <p>This is stored in the world save via Minecraft's persistent state manager.
 */
public final class WorldScaleSettingsState extends PersistentState {
    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("scale").forGetter(state -> Optional.of(state.getScale())),
            Codec.BOOL.optionalFieldOf("explicit_scale", false).forGetter(WorldScaleSettingsState::hasExplicitScale)
    ).apply(instance, (savedScale, hasExplicitScale) -> new WorldScaleSettingsState(
            savedScale.orElse(WorldScaleManager.DEFAULT_SCALE),
            hasExplicitScale
    )));

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

    /**
     * Type descriptor used by the persistent state manager.
     */
    public static final PersistentStateType<WorldScaleSettingsState> TYPE =
            new PersistentStateType<>("terrain_diffusion_world_settings", WorldScaleSettingsState::createDefault, CODEC, null);

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
        markDirty();
    }
}
