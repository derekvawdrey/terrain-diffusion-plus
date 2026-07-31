package com.github.xandergos.terraindiffusionmc.biome;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
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

    /** No-arg constructor kept for Gson (which uses Unsafe and never actually calls it). */
    public TerrainBiomeSettlement() {
    }

    /**
     * Programmatic constructor for code that builds settlements at runtime (e.g. the terrain
     * explorer's "Biome Config" flow creating a brand-new catalog entry for a biome that has
     * none yet) instead of deserializing them from JSON. {@code rules} is defensively copied
     * into a mutable list so {@link #addRule(TerrainBiomeRule)} always works afterwards.
     */
    public TerrainBiomeSettlement(short index, String key, String fallbackKey, String kind, int color,
                                   boolean hardBoundary, boolean blendable, boolean isRiver,
                                   boolean isFrozenRiver, boolean canGenerateOverworld,
                                   List<TerrainBiomeRule> rules) {
        this.index = index;
        this.key = key;
        this.fallbackKey = fallbackKey;
        this.kind = kind;
        this.color = color;
        this.hardBoundary = hardBoundary;
        this.blendable = blendable;
        this.isRiver = isRiver;
        this.isFrozenRiver = isFrozenRiver;
        this.canGenerateOverworld = canGenerateOverworld;
        this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
    }

    /**
     * Appends a rule to this settlement's rule list (used when the "Biome Config" apply flow
     * adds a newly generated rule to an already-configured biome). Lazily converts a
     * Gson-deserialized (possibly unmodifiable-in-spirit) list into a mutable one on first use.
     */
    public void addRule(TerrainBiomeRule rule) {
        if (rules == null) {
            rules = new ArrayList<>();
        } else if (!(rules instanceof ArrayList)) {
            rules = new ArrayList<>(rules);
        }
        rules.add(rule);
    }

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
