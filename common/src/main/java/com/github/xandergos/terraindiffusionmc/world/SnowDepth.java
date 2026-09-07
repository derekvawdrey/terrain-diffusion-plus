package com.github.xandergos.terraindiffusionmc.world;

/**
 * How deep the snow lies, from the terrain model's own temperature.
 *
 * <p>Vanilla lays one snow layer wherever it snows. Here the depth follows the mean annual
 * temperature of the ground, starting at the same -3 C where the biome rules call ground
 * snowy: one layer where snow only just settles, deepening to a full block plus layers in the
 * coldest places, so a high pass or a polar plain reads as buried rather than
 * dusted. Encoded as a layer count 1..{@link #MAX_LAYERS}: counts above 8 mean a snow block with
 * {@code count - 8} layers on top.</p>
 */
public final class SnowDepth {
    /** Warmer than this: the single vanilla layer. */
    public static final float TIER1_START_C = -3.0f;
    /** From {@link #TIER1_START_C} down to here the layer count climbs 1..7. */
    public static final float TIER1_END_C = -7.0f;
    /** From {@link #TIER1_END_C} down to here: a snow block plus 1..7 layers. */
    public static final float TIER2_END_C = -12.0f;
    public static final int LAYERS_PER_TIER = 7;
    public static final int SNOW_BLOCK_LAYERS = 8;
    public static final int MAX_LAYERS = SNOW_BLOCK_LAYERS + LAYERS_PER_TIER;

    private SnowDepth() {}

    /** Snow layer count for a mean annual temperature; 1 everywhere it is not cold. */
    public static byte layersFor(float temperatureC) {
        if (!(temperatureC <= TIER1_START_C)) return 1;
        if (temperatureC > TIER1_END_C) {
            float step = (TIER1_START_C - TIER1_END_C) / LAYERS_PER_TIER;
            int layers = 1 + (int) ((TIER1_START_C - temperatureC) / step);
            return (byte) Math.max(1, Math.min(LAYERS_PER_TIER, layers));
        }
        float step = (TIER1_END_C - TIER2_END_C) / LAYERS_PER_TIER;
        int stacked = 1 + (int) ((TIER1_END_C - temperatureC) / step);
        return (byte) Math.min(MAX_LAYERS, SNOW_BLOCK_LAYERS + Math.max(1, Math.min(LAYERS_PER_TIER, stacked)));
    }
}
