package com.github.xandergos.terraindiffusionmc.biome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Common, version-independent catalog of vanilla biomes.
 *
 * <p>The catalog is expressed in climate-space and registry keys. Version
 * modules resolve these keys to the Minecraft API type used by that version.</p>
 */
public final class TerrainBiomeCatalog {
    private TerrainBiomeCatalog() {}

    public static final short PLAINS = 1;
    public static final short SUNFLOWER_PLAINS = 2;
    public static final short SNOWY_PLAINS = 3;
    public static final short ICE_SPIKES = 4;
    public static final short DESERT = 5;
    public static final short SWAMP = 6;
    public static final short MANGROVE_SWAMP = 7;
    public static final short FOREST = 8;
    public static final short FLOWER_FOREST = 9;
    public static final short BIRCH_FOREST = 10;
    public static final short DARK_FOREST = 11;
    public static final short OLD_GROWTH_BIRCH_FOREST = 12;
    public static final short OLD_GROWTH_PINE_TAIGA = 13;
    public static final short OLD_GROWTH_SPRUCE_TAIGA = 14;
    public static final short TAIGA = 15;
    public static final short SNOWY_TAIGA = 16;
    public static final short SAVANNA = 17;
    public static final short SAVANNA_PLATEAU = 18;
    public static final short WINDSWEPT_HILLS = 19;
    public static final short WINDSWEPT_GRAVELLY_HILLS = 20;
    public static final short WINDSWEPT_FOREST = 21;
    public static final short WINDSWEPT_SAVANNA = 22;
    public static final short JUNGLE = 23;
    public static final short SPARSE_JUNGLE = 24;
    public static final short BAMBOO_JUNGLE = 25;
    public static final short BADLANDS = 26;
    public static final short ERODED_BADLANDS = 27;
    public static final short WOODED_BADLANDS = 28;
    public static final short MEADOW = 29;
    public static final short CHERRY_GROVE = 30;
    public static final short GROVE = 31;
    public static final short SNOWY_SLOPES = 32;
    public static final short FROZEN_PEAKS = 33;
    public static final short JAGGED_PEAKS = 34;
    public static final short STONY_PEAKS = 35;
    public static final short MUSHROOM_FIELDS = 36;
    public static final short PALE_GARDEN = 37;

    public static final short RIVER = 38;
    public static final short FROZEN_RIVER = 39;
    public static final short BEACH = 40;
    public static final short WARM_OCEAN = 41;
    public static final short LUKEWARM_OCEAN = 42;
    public static final short DEEP_LUKEWARM_OCEAN = 43;
    public static final short OCEAN = 44;
    public static final short DEEP_OCEAN = 45;
    public static final short COLD_OCEAN = 46;
    public static final short DEEP_COLD_OCEAN = 47;
    public static final short FROZEN_OCEAN = 48;
    public static final short DEEP_FROZEN_OCEAN = 49;
    public static final short STONY_SHORE = 50;
    public static final short SNOWY_BEACH = 51;

    public static final short LUSH_CAVES = 52;
    public static final short DRIPSTONE_CAVES = 53;
    public static final short DEEP_DARK = 54;

    public static final short NETHER_WASTES = 55;
    public static final short CRIMSON_FOREST = 56;
    public static final short WARPED_FOREST = 57;
    public static final short SOUL_SAND_VALLEY = 58;
    public static final short BASALT_DELTAS = 59;

    public static final short THE_END = 60;
    public static final short END_HIGHLANDS = 61;
    public static final short END_MIDLANDS = 62;
    public static final short SMALL_END_ISLANDS = 63;
    public static final short END_BARRENS = 64;
    public static final short THE_VOID = 65;

    private static final List<TerrainBiomeProfile> ALL;
    private static final List<TerrainBiomeProfile> OVERWORLD_CANDIDATES;
    private static final Map<Short, TerrainBiomeProfile> BY_INDEX;
    private static final Map<String, TerrainBiomeProfile> BY_KEY;

    static {
        List<TerrainBiomeProfile> profiles = new ArrayList<>();

        // Overworld land biomes. Ranges are intentionally broad; the classifier
        // computes the climate variables and can use these ranges as a stable
        // ecological contract rather than registry IDs.
        add(profiles, PLAINS, "minecraft:plains", "minecraft:plains", TerrainBiomeKind.OVERWORLD, -5f, 26f, 0.25f, 0.95f, 250f, 1200f, 0f, 1800f, 0f, 0.55f, 0.15f, 1.00f, 40, 0x8DB360);
        add(profiles, SUNFLOWER_PLAINS, "minecraft:sunflower_plains", "minecraft:plains", TerrainBiomeKind.OVERWORLD, 10f, 26f, 0.35f, 0.95f, 350f, 1300f, 0f, 900f, 0f, 0.35f, 0.30f, 1.00f, 35, 0xB5C95A);
        add(profiles, SNOWY_PLAINS, "minecraft:snowy_plains", "minecraft:snowy_plains", TerrainBiomeKind.OVERWORLD, -60f, 2f, 0.20f, 1.10f, 150f, 1800f, 0f, 1800f, 0f, 0.55f, 0.10f, 1.00f, 60, 0xDDE8E8);
        add(profiles, ICE_SPIKES, "minecraft:ice_spikes", "minecraft:snowy_plains", TerrainBiomeKind.OVERWORLD, -60f, -5f, 0.10f, 0.80f, 100f, 900f, 0f, 1200f, 0f, 0.45f, 0.35f, 1.00f, 45, 0xC7E6F4);
        add(profiles, DESERT, "minecraft:desert", "minecraft:desert", TerrainBiomeKind.OVERWORLD, 20f, 60f, 0.00f, 0.24f, 0f, 350f, 0f, 1800f, 0f, 0.60f, 0.60f, 1.00f, 80, 0xFA9418);
        add(profiles, SWAMP, "minecraft:swamp", "minecraft:swamp", TerrainBiomeKind.OVERWORLD, 8f, 26f, 0.95f, 2.50f, 700f, 3200f, 0f, 250f, 0f, 0.35f, 0.00f, 0.40f, 75, 0x07F9B2);
        add(profiles, MANGROVE_SWAMP, "minecraft:mangrove_swamp", "minecraft:swamp", TerrainBiomeKind.OVERWORLD, 20f, 60f, 1.00f, 3.00f, 900f, 3500f, 0f, 160f, 0f, 0.30f, 0.00f, 0.45f, 82, 0x3A7A43);
        add(profiles, FOREST, "minecraft:forest", "minecraft:forest", TerrainBiomeKind.OVERWORLD, 5f, 24f, 0.55f, 1.35f, 450f, 2000f, 0f, 1800f, 0f, 0.55f, 0.00f, 0.65f, 65, 0x056621);
        add(profiles, FLOWER_FOREST, "minecraft:flower_forest", "minecraft:forest", TerrainBiomeKind.OVERWORLD, 8f, 24f, 0.55f, 1.25f, 450f, 1800f, 0f, 1300f, 0f, 0.45f, 0.35f, 1.00f, 42, 0x2D8E49);
        add(profiles, BIRCH_FOREST, "minecraft:birch_forest", "minecraft:forest", TerrainBiomeKind.OVERWORLD, 8f, 23f, 0.45f, 1.10f, 350f, 1600f, 0f, 1500f, 0f, 0.50f, 0.30f, 1.00f, 42, 0x307444);
        add(profiles, DARK_FOREST, "minecraft:dark_forest", "minecraft:forest", TerrainBiomeKind.OVERWORLD, 5f, 24f, 0.90f, 2.20f, 700f, 3000f, 0f, 1500f, 0f, 0.50f, 0.00f, 0.35f, 70, 0x40511A);
        add(profiles, OLD_GROWTH_BIRCH_FOREST, "minecraft:old_growth_birch_forest", "minecraft:birch_forest", TerrainBiomeKind.OVERWORLD, 6f, 22f, 0.75f, 1.55f, 650f, 2400f, 150f, 1700f, 0f, 0.55f, 0.00f, 0.45f, 55, 0x589C6C);
        add(profiles, OLD_GROWTH_PINE_TAIGA, "minecraft:old_growth_pine_taiga", "minecraft:taiga", TerrainBiomeKind.OVERWORLD, -2f, 12f, 0.55f, 1.45f, 450f, 2200f, 150f, 1900f, 0f, 0.60f, 0.00f, 0.45f, 58, 0x596651);
        add(profiles, OLD_GROWTH_SPRUCE_TAIGA, "minecraft:old_growth_spruce_taiga", "minecraft:taiga", TerrainBiomeKind.OVERWORLD, -4f, 10f, 0.75f, 1.85f, 650f, 2600f, 150f, 2100f, 0f, 0.60f, 0.00f, 0.40f, 60, 0x818E79);
        add(profiles, TAIGA, "minecraft:taiga", "minecraft:taiga", TerrainBiomeKind.OVERWORLD, -5f, 12f, 0.35f, 1.35f, 250f, 1900f, 0f, 2300f, 0f, 0.65f, 0.00f, 0.80f, 65, 0x0B6659);
        add(profiles, SNOWY_TAIGA, "minecraft:snowy_taiga", "minecraft:snowy_taiga", TerrainBiomeKind.OVERWORLD, -60f, 3f, 0.35f, 1.60f, 250f, 2200f, 0f, 2300f, 0f, 0.65f, 0.00f, 0.85f, 75, 0x31554A);
        add(profiles, SAVANNA, "minecraft:savanna", "minecraft:savanna", TerrainBiomeKind.OVERWORLD, 18f, 60f, 0.18f, 0.65f, 150f, 900f, 0f, 1800f, 0f, 0.55f, 0.40f, 1.00f, 75, 0xBDB25F);
        add(profiles, SAVANNA_PLATEAU, "minecraft:savanna_plateau", "minecraft:savanna", TerrainBiomeKind.OVERWORLD, 18f, 60f, 0.18f, 0.70f, 150f, 900f, 500f, 2400f, 0.20f, 0.70f, 0.40f, 1.00f, 65, 0xA79D64);
        add(profiles, WINDSWEPT_HILLS, "minecraft:windswept_hills", "minecraft:windswept_hills", TerrainBiomeKind.MOUNTAIN, -5f, 20f, 0.10f, 0.95f, 100f, 1500f, 700f, 3200f, 0.35f, 1.20f, 0.20f, 1.00f, 70, 0x606060);
        add(profiles, WINDSWEPT_GRAVELLY_HILLS, "minecraft:windswept_gravelly_hills", "minecraft:windswept_hills", TerrainBiomeKind.MOUNTAIN, -5f, 22f, 0.00f, 0.70f, 50f, 1100f, 900f, 3200f, 0.45f, 1.30f, 0.55f, 1.00f, 55, 0x888888);
        add(profiles, WINDSWEPT_FOREST, "minecraft:windswept_forest", "minecraft:windswept_forest", TerrainBiomeKind.MOUNTAIN, -2f, 18f, 0.40f, 1.30f, 350f, 1900f, 700f, 2600f, 0.30f, 1.00f, 0.00f, 0.65f, 62, 0x507050);
        add(profiles, WINDSWEPT_SAVANNA, "minecraft:windswept_savanna", "minecraft:savanna", TerrainBiomeKind.MOUNTAIN, 18f, 60f, 0.15f, 0.65f, 100f, 900f, 500f, 2600f, 0.35f, 1.20f, 0.45f, 1.00f, 62, 0xE5DA87);
        add(profiles, JUNGLE, "minecraft:jungle", "minecraft:jungle", TerrainBiomeKind.OVERWORLD, 20f, 60f, 1.05f, 3.00f, 900f, 4000f, 0f, 1600f, 0f, 0.55f, 0.00f, 0.40f, 78, 0x537B09);
        add(profiles, SPARSE_JUNGLE, "minecraft:sparse_jungle", "minecraft:jungle", TerrainBiomeKind.OVERWORLD, 20f, 60f, 0.55f, 1.60f, 450f, 2200f, 0f, 1600f, 0f, 0.55f, 0.40f, 1.00f, 78, 0x628B17);
        add(profiles, BAMBOO_JUNGLE, "minecraft:bamboo_jungle", "minecraft:jungle", TerrainBiomeKind.OVERWORLD, 20f, 60f, 1.20f, 3.20f, 1000f, 4500f, 0f, 1200f, 0f, 0.50f, 0.00f, 0.30f, 60, 0x768E14);
        add(profiles, BADLANDS, "minecraft:badlands", "minecraft:badlands", TerrainBiomeKind.OVERWORLD, 18f, 60f, 0.00f, 0.35f, 0f, 450f, 0f, 2200f, 0f, 0.80f, 0.45f, 1.00f, 70, 0xD94515);
        add(profiles, ERODED_BADLANDS, "minecraft:eroded_badlands", "minecraft:badlands", TerrainBiomeKind.OVERWORLD, 20f, 60f, 0.00f, 0.28f, 0f, 350f, 300f, 2400f, 0.25f, 1.20f, 0.70f, 1.00f, 64, 0xFF6D3D);
        add(profiles, WOODED_BADLANDS, "minecraft:wooded_badlands", "minecraft:badlands", TerrainBiomeKind.OVERWORLD, 16f, 60f, 0.20f, 0.65f, 150f, 850f, 250f, 2200f, 0f, 0.75f, 0.20f, 0.80f, 66, 0xB09765);
        add(profiles, MEADOW, "minecraft:meadow", "minecraft:meadow", TerrainBiomeKind.OVERWORLD, 0f, 16f, 0.35f, 1.20f, 250f, 1700f, 800f, 2600f, 0f, 0.45f, 0.35f, 1.00f, 68, 0x60A573);
        add(profiles, CHERRY_GROVE, "minecraft:cherry_grove", "minecraft:flower_forest", TerrainBiomeKind.OVERWORLD, 3f, 20f, 0.45f, 1.65f, 350f, 2600f, 250f, 2600f, 0f, 0.60f, 0.10f, 0.85f, 62, 0xD08AAE);
        add(profiles, GROVE, "minecraft:grove", "minecraft:grove", TerrainBiomeKind.MOUNTAIN, -8f, 6f, 0.35f, 1.30f, 250f, 2000f, 1000f, 3000f, 0f, 0.65f, 0.15f, 0.90f, 64, 0x4B6D58);
        add(profiles, SNOWY_SLOPES, "minecraft:snowy_slopes", "minecraft:snowy_slopes", TerrainBiomeKind.MOUNTAIN, -60f, 2f, 0.20f, 1.30f, 150f, 2000f, 1000f, 3400f, 0.10f, 0.90f, 0.45f, 1.00f, 78, 0xE7F0F0);
        add(profiles, FROZEN_PEAKS, "minecraft:frozen_peaks", "minecraft:frozen_peaks", TerrainBiomeKind.MOUNTAIN, -60f, 2f, 0.10f, 1.10f, 100f, 1700f, 1800f, 5000f, 0.45f, 2.00f, 0.50f, 1.00f, 80, 0xBFDDE8);
        add(profiles, JAGGED_PEAKS, "minecraft:jagged_peaks", "minecraft:frozen_peaks", TerrainBiomeKind.MOUNTAIN, -60f, 4f, 0.10f, 1.20f, 100f, 1800f, 1800f, 5000f, 0.70f, 2.50f, 0.50f, 1.00f, 75, 0xDCE8EB);
        add(profiles, STONY_PEAKS, "minecraft:stony_peaks", "minecraft:stony_peaks", TerrainBiomeKind.MOUNTAIN, 0f, 26f, 0.00f, 0.90f, 0f, 1400f, 1500f, 5000f, 0.45f, 2.20f, 0.45f, 1.00f, 78, 0x777777);
        add(profiles, MUSHROOM_FIELDS, "minecraft:mushroom_fields", "minecraft:plains", TerrainBiomeKind.OVERWORLD, 8f, 24f, 0.55f, 1.80f, 450f, 2500f, 0f, 900f, 0f, 0.40f, 0.10f, 0.80f, 1, 0xFF00FF);
        add(profiles, PALE_GARDEN, "minecraft:pale_garden", "minecraft:dark_forest", TerrainBiomeKind.OVERWORLD, 3f, 21f, 0.75f, 2.40f, 600f, 3400f, 0f, 1800f, 0f, 0.55f, 0.00f, 0.45f, 58, 0xBFC8B6);

        // Rivers, beaches, and oceans.
        add(profiles, RIVER, "minecraft:river", "minecraft:river", TerrainBiomeKind.RIVER, -5f, 60f, 0.00f, 3.00f, 0f, 4000f, -20f, 500f, 0f, 0.60f, 0.00f, 1.00f, 10, 0x0000FF);
        add(profiles, FROZEN_RIVER, "minecraft:frozen_river", "minecraft:frozen_river", TerrainBiomeKind.RIVER, -60f, 0f, 0.00f, 3.00f, 0f, 3000f, -20f, 500f, 0f, 0.60f, 0.00f, 1.00f, 10, 0xA0A0FF);
        add(profiles, BEACH, "minecraft:beach", "minecraft:beach", TerrainBiomeKind.BEACH, -5f, 60f, 0.00f, 1.50f, 0f, 1800f, -10f, 80f, 0f, 0.35f, 0.35f, 1.00f, 10, 0xFADE55);
        add(profiles, WARM_OCEAN, "minecraft:warm_ocean", "minecraft:warm_ocean", TerrainBiomeKind.OCEAN, 20f, 60f, 0.00f, 3.00f, 0f, 4000f, -12000f, 0f, 0f, 2.00f, 0.00f, 1.00f, 90, 0x43D5EE);
        add(profiles, LUKEWARM_OCEAN, "minecraft:lukewarm_ocean", "minecraft:lukewarm_ocean", TerrainBiomeKind.OCEAN, 12f, 26f, 0.00f, 3.00f, 0f, 4000f, -12000f, 0f, 0f, 2.00f, 0.00f, 1.00f, 90, 0x45ADF2);
        add(profiles, DEEP_LUKEWARM_OCEAN, "minecraft:deep_lukewarm_ocean", "minecraft:lukewarm_ocean", TerrainBiomeKind.OCEAN, 12f, 26f, 0.00f, 3.00f, 0f, 4000f, -12000f, -250f, 0f, 2.00f, 0.00f, 1.00f, 91, 0x2A8FC5);
        add(profiles, OCEAN, "minecraft:ocean", "minecraft:ocean", TerrainBiomeKind.OCEAN, 5f, 20f, 0.00f, 3.00f, 0f, 4000f, -12000f, 0f, 0f, 2.00f, 0.00f, 1.00f, 90, 0x000070);
        add(profiles, DEEP_OCEAN, "minecraft:deep_ocean", "minecraft:deep_ocean", TerrainBiomeKind.OCEAN, 5f, 20f, 0.00f, 3.00f, 0f, 4000f, -12000f, -250f, 0f, 2.00f, 0.00f, 1.00f, 91, 0x000030);
        add(profiles, COLD_OCEAN, "minecraft:cold_ocean", "minecraft:cold_ocean", TerrainBiomeKind.OCEAN, -5f, 8f, 0.00f, 3.00f, 0f, 4000f, -12000f, 0f, 0f, 2.00f, 0.00f, 1.00f, 90, 0x202070);
        add(profiles, DEEP_COLD_OCEAN, "minecraft:deep_cold_ocean", "minecraft:deep_cold_ocean", TerrainBiomeKind.OCEAN, -5f, 8f, 0.00f, 3.00f, 0f, 4000f, -12000f, -250f, 0f, 2.00f, 0.00f, 1.00f, 91, 0x202038);
        add(profiles, FROZEN_OCEAN, "minecraft:frozen_ocean", "minecraft:frozen_ocean", TerrainBiomeKind.OCEAN, -60f, 0f, 0.00f, 3.00f, 0f, 4000f, -12000f, 0f, 0f, 2.00f, 0.00f, 1.00f, 90, 0x7070D6);
        add(profiles, DEEP_FROZEN_OCEAN, "minecraft:deep_frozen_ocean", "minecraft:deep_frozen_ocean", TerrainBiomeKind.OCEAN, -60f, 0f, 0.00f, 3.00f, 0f, 4000f, -12000f, -250f, 0f, 2.00f, 0.00f, 1.00f, 91, 0x404090);
        add(profiles, STONY_SHORE, "minecraft:stony_shore", "minecraft:stony_shore", TerrainBiomeKind.BEACH, -5f, 24f, 0.00f, 1.50f, 0f, 1800f, -10f, 250f, 0.25f, 1.00f, 0.40f, 1.00f, 40, 0xA2A284);
        add(profiles, SNOWY_BEACH, "minecraft:snowy_beach", "minecraft:snowy_beach", TerrainBiomeKind.BEACH, -60f, 2f, 0.00f, 1.50f, 0f, 1800f, -10f, 120f, 0f, 0.45f, 0.35f, 1.00f, 40, 0xFAF0C0);

        // Cave, Nether, End, and utility vanilla biomes. They are present in the
        // resolver so all vanilla registry keys are supported, but the surface
        // terrain classifier does not select Nether/End biomes in the Overworld.
        add(profiles, LUSH_CAVES, "minecraft:lush_caves", "minecraft:lush_caves", TerrainBiomeKind.CAVE, 8f, 26f, 0.80f, 3.00f, 800f, 5000f, -12000f, 1500f, 0f, 2.00f, 0.00f, 1.00f, 0, 0x708A30);
        add(profiles, DRIPSTONE_CAVES, "minecraft:dripstone_caves", "minecraft:dripstone_caves", TerrainBiomeKind.CAVE, -5f, 28f, 0.00f, 1.20f, 0f, 1600f, -12000f, 1500f, 0f, 2.00f, 0.00f, 1.00f, 0, 0x6F5F50);
        add(profiles, DEEP_DARK, "minecraft:deep_dark", "minecraft:deep_dark", TerrainBiomeKind.CAVE, -60f, 20f, 0.00f, 2.00f, 0f, 3000f, -12000f, 0f, 0f, 2.00f, 0.00f, 1.00f, 0, 0x11111F);
        add(profiles, NETHER_WASTES, "minecraft:nether_wastes", "minecraft:nether_wastes", TerrainBiomeKind.NETHER, 40f, 120f, 0f, 0.40f, 0f, 250f, -12000f, 5000f, 0f, 2.00f, 0.00f, 1.00f, 0, 0xBF3B3B);
        add(profiles, CRIMSON_FOREST, "minecraft:crimson_forest", "minecraft:crimson_forest", TerrainBiomeKind.NETHER, 40f, 120f, 0.20f, 0.80f, 0f, 500f, -12000f, 5000f, 0f, 2.00f, 0.00f, 0.70f, 0, 0xDD0808);
        add(profiles, WARPED_FOREST, "minecraft:warped_forest", "minecraft:warped_forest", TerrainBiomeKind.NETHER, 40f, 120f, 0.20f, 0.80f, 0f, 500f, -12000f, 5000f, 0f, 2.00f, 0.00f, 0.70f, 0, 0x49907B);
        add(profiles, SOUL_SAND_VALLEY, "minecraft:soul_sand_valley", "minecraft:soul_sand_valley", TerrainBiomeKind.NETHER, 40f, 120f, 0f, 0.40f, 0f, 250f, -12000f, 5000f, 0f, 2.00f, 0.40f, 1.00f, 0, 0x5E3830);
        add(profiles, BASALT_DELTAS, "minecraft:basalt_deltas", "minecraft:basalt_deltas", TerrainBiomeKind.NETHER, 40f, 120f, 0f, 0.35f, 0f, 250f, -12000f, 5000f, 0.25f, 2.00f, 0.50f, 1.00f, 0, 0x403636);
        add(profiles, THE_END, "minecraft:the_end", "minecraft:the_end", TerrainBiomeKind.END, -60f, 120f, 0f, 3f, 0f, 5000f, -12000f, 5000f, 0f, 2.00f, 0f, 1f, 0, 0x8080FF);
        add(profiles, END_HIGHLANDS, "minecraft:end_highlands", "minecraft:end_highlands", TerrainBiomeKind.END, -60f, 120f, 0f, 3f, 0f, 5000f, -12000f, 5000f, 0f, 2.00f, 0f, 1f, 0, 0xC8C8FF);
        add(profiles, END_MIDLANDS, "minecraft:end_midlands", "minecraft:end_midlands", TerrainBiomeKind.END, -60f, 120f, 0f, 3f, 0f, 5000f, -12000f, 5000f, 0f, 2.00f, 0f, 1f, 0, 0xA0A0FF);
        add(profiles, SMALL_END_ISLANDS, "minecraft:small_end_islands", "minecraft:small_end_islands", TerrainBiomeKind.END, -60f, 120f, 0f, 3f, 0f, 5000f, -12000f, 5000f, 0f, 2.00f, 0f, 1f, 0, 0x6060C0);
        add(profiles, END_BARRENS, "minecraft:end_barrens", "minecraft:end_barrens", TerrainBiomeKind.END, -60f, 120f, 0f, 3f, 0f, 5000f, -12000f, 5000f, 0f, 2.00f, 0f, 1f, 0, 0x404080);
        add(profiles, THE_VOID, "minecraft:the_void", "minecraft:the_void", TerrainBiomeKind.VOID, -60f, 120f, 0f, 3f, 0f, 5000f, -12000f, 5000f, 0f, 2.00f, 0f, 1f, 0, 0x000000);

        Map<Short, TerrainBiomeProfile> byIndex = new LinkedHashMap<>();
        Map<String, TerrainBiomeProfile> byKey = new LinkedHashMap<>();
        List<TerrainBiomeProfile> overworld = new ArrayList<>();
        for (TerrainBiomeProfile profile : profiles) {
            byIndex.put(profile.index(), profile);
            byKey.put(profile.key(), profile);
            if (profile.canGenerateOverworld()) overworld.add(profile);
        }
        ALL = Collections.unmodifiableList(profiles);
        OVERWORLD_CANDIDATES = Collections.unmodifiableList(overworld);
        BY_INDEX = Collections.unmodifiableMap(byIndex);
        BY_KEY = Collections.unmodifiableMap(byKey);
    }

    private static void add(List<TerrainBiomeProfile> profiles, int index, String key, String fallbackKey,
                            TerrainBiomeKind kind, float minTemp, float maxTemp, float minMoisture,
                            float maxMoisture, float minPrecip, float maxPrecip, float minElevation,
                            float maxElevation, float minSlope, float maxSlope, float minSparsity,
                            float maxSparsity, int priority, int color) {
        profiles.add(new TerrainBiomeProfile((short) index, key, fallbackKey, kind, minTemp, maxTemp,
                minMoisture, maxMoisture, minPrecip, maxPrecip, minElevation, maxElevation,
                minSlope, maxSlope, minSparsity, maxSparsity, priority, color));
    }

    public static List<TerrainBiomeProfile> all() {
        return ALL;
    }

    public static List<TerrainBiomeProfile> overworldCandidates() {
        return OVERWORLD_CANDIDATES;
    }

    public static TerrainBiomeProfile byIndex(short index) {
        TerrainBiomeProfile profile = BY_INDEX.get(index);
        return profile != null ? profile : BY_INDEX.get(PLAINS);
    }

    public static TerrainBiomeProfile byKey(String key) {
        return BY_KEY.get(key);
    }

    public static String keyForIndex(short index) {
        return byIndex(index).key();
    }

    public static int colorForIndex(short index) {
        return byIndex(index).color();
    }

    public static Map<Short, String> indexToKeyMap() {
        Map<Short, String> result = new LinkedHashMap<>();
        for (TerrainBiomeProfile profile : ALL) {
            result.put(profile.index(), profile.key());
        }
        return result;
    }

    public static Map<Short, Integer> indexToColorMap() {
        Map<Short, Integer> result = new LinkedHashMap<>();
        for (TerrainBiomeProfile profile : ALL) {
            result.put(profile.index(), profile.color());
        }
        return result;
    }
}
