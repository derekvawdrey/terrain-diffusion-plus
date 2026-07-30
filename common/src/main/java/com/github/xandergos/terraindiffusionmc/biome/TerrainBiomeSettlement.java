package com.github.xandergos.terraindiffusionmc.biome;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Full data-driven definition of a single biome, including profile metadata
 * and the rules that determine when it is selected.
 *
 * <p>This replaces the dual-role of {@code TerrainBiomeProfile} (metadata)
 * plus the hardcoded decision tree in {@code BiomeClassifier} (rules).</p>
 */
public final class TerrainBiomeSettlement {
    @SerializedName("index")
    private short index;

    @SerializedName("key")
    private String key;

    @SerializedName("fallbackKey")
    private String fallbackKey;

    @SerializedName("kind")
    private String kind;

    @SerializedName("color")
    private int color;

    @SerializedName("hardBoundary")
    private boolean hardBoundary;

    @SerializedName("blendable")
    private boolean blendable;

    @SerializedName("river")
    private boolean isRiver;

    @SerializedName("frozenRiver")
    private boolean isFrozenRiver;

    @SerializedName("canGenerateOverworld")
    private boolean canGenerateOverworld;

    @SerializedName("rules")
    private List<TerrainBiomeRule> rules;

    public short index() { return index; }
    public String key() { return key; }
    public String fallbackKey() { return fallbackKey; }
    public String kind() { return kind; }
    public int color() { return color; }
    public boolean hardBoundary() { return hardBoundary; }
    public boolean blendable() { return blendable; }
    public boolean isRiver() { return isRiver; }
    public boolean isFrozenRiver() { return isFrozenRiver; }
    public boolean canGenerateOverworld() { return canGenerateOverworld; }
    public List<TerrainBiomeRule> rules() { return rules != null ? rules : List.of(); }
}
