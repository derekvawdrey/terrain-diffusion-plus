package com.github.xandergos.terraindiffusionmc.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based biome classifier port of _classify_biome in minecraft_api.py.
 *
 * <p>Uses fixed-seed FastNoiseLite instances for climate and elevation noise perturbations.
 * Biome IDs match the Python server's _BIOME_ID mapping.
 */
public final class BiomeClassifier {
    private static final Logger LOG = LoggerFactory.getLogger(BiomeClassifier.class);
    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;
    private static final FastNoiseLite BIOME_NOISE, BIOME_NOISE_WARP;

    static {
        TEMP_NOISE = makeFnlPerlin(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnlPerlin(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnlPerlin(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnlPerlin(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnlPerlin(54321, 1f/128f, 2, 2f, 0.5f);
        BIOME_NOISE = makeFnlCell(12345, 1f/1000f);
        BIOME_NOISE_WARP = makeFnlWarp(12345, 1f/100f, 115f, 2, 2.0f, 0.54f);
    }

    //maybe look into using fastnoise2 instead.
    private static FastNoiseLite makeFnlPerlin(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    private static FastNoiseLite makeFnlCell(int seed, float freq) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Cellular);
        fnl.SetFrequency(freq);
        fnl.SetCellularReturnType(FastNoiseLite.CellularReturnType.CellValue);
        return fnl;
    }

    private static FastNoiseLite makeFnlWarp(int seed, float freq, float amp, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        fnl.SetFrequency(freq);
        fnl.SetDomainWarpAmp(amp);
        fnl.SetFractalType(FastNoiseLite.FractalType.DomainWarpProgressive);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    private enum elevation {
        DEEP_OCEAN,
        OCEAN,
        LOWLAND,
        MIDLAND,
        HIGHLAND,
        MOUNTAIN
    }

    private enum temperature {
        FROZEN,
        COLD,
        COOL,
        TEMPERATE,
        WARM,
        HOT
    }

    private enum slope {
        NONE,
        MEDIUM,
        BARE
    }

    private enum treeCoverage {
        BARREN,
        NONE,
        SPARSE,
        FOREST,
        DENSE,
        RAINFOREST
    }

    //borrowed from ThatDamnWittyWhizHard's PR
    private enum moisture {
        VERY_DRY,
        DRY,
        SEMI_DRY,
        MOIST,
        VERY_MOIST,
        SATURATED
    }


    //records are automatically given hash functions which makes it easier to use with HashMap.
    record climate(
            elevation elev,
            temperature temp,
            slope slope,
            treeCoverage treecover,
            moisture moist,
            boolean snow
    ){};


    // Maps a fully-specified location condition to the biome IDs valid there.
    private static final HashMap<climate, short[]> BIOME_MAP;

    /**
     * Registers a biome for all condition combinations described by the condition arrays given.
     *
     * <p>For each condition group, the conditions present form an OR:
     * the biome is registered for every combination. Null arrays are
     * treated as "don't care" and expanded over all reasonable values.
     */
    private static void addBiome(Map<climate, List<Short>> builder, short id,
                                 elevation[] elevations,
                                 temperature[] temperatures,
                                 slope[] slopes,
                                 treeCoverage[] treeCoverages,
                                 moisture[] moistures,
                                 boolean[] hasSnow) {

        //if any of the inputs are null, we consider that a "dont care" and fill in every combination.
        if (elevations == null) elevations = new elevation[]{elevation.LOWLAND, elevation.MIDLAND, elevation.HIGHLAND, elevation.MOUNTAIN};
        if (temperatures == null) temperatures = temperature.values();
        if (slopes == null) slopes = new slope[]{slope.MEDIUM, slope.NONE};
        if (treeCoverages == null) treeCoverages = treeCoverage.values();
        if (moistures == null) moistures = moisture.values();
        if (hasSnow == null) hasSnow = new boolean[] {false, true}; //this seems weird but trust me it makes sense i think

        //blech
        for (elevation elev : elevations)
            for (temperature temp : temperatures)
                for (slope slope : slopes)
                    for (treeCoverage trees : treeCoverages)
                        for (moisture moist : moistures)
                            for (boolean snow : hasSnow)
                            {
                                climate c = new climate(elev, temp, slope, trees, moist, snow);
                                builder.computeIfAbsent(c, k -> new ArrayList<>()).add(id);
                            }

    }


    static {
        long startTime = System.nanoTime();
        Map<climate, List<Short>> builder = new HashMap<>(1024);


        addBiome(builder, BiomePalette.FROZEN_OCEAN,
                new elevation[] {elevation.OCEAN},
                new temperature[] {temperature.FROZEN},
                new slope[] {slope.BARE, slope.MEDIUM, slope.NONE},
                null,
                null,
                null
        );

        addBiome(builder, BiomePalette.COLD_OCEAN,
                new elevation[] {elevation.OCEAN},
                new temperature[] {temperature.COLD},
                new slope[] {slope.BARE, slope.MEDIUM, slope.NONE},
                null,
                null,
                null
        );

        addBiome(builder, BiomePalette.WARM_OCEAN,
                new elevation[] {elevation.OCEAN},
                new temperature[] {temperature.WARM, temperature.HOT},
                new slope[] {slope.BARE, slope.MEDIUM, slope.NONE},
                null,
                null,
                null
        );

        addBiome(builder, BiomePalette.OCEAN,
                new elevation[] {elevation.OCEAN},
                new temperature[] {temperature.COOL, temperature.TEMPERATE},
                new slope[] {slope.BARE, slope.MEDIUM, slope.NONE},
                null,
                null,
                null
        );

        addBiome(builder, BiomePalette.DEEP_OCEAN,
                new elevation[] {elevation.DEEP_OCEAN},
                null,
                new slope[] {slope.BARE, slope.MEDIUM, slope.NONE},
                null,
                null,
                null
        );


        //slope overrides, this should probably be hardcoded or implemented an entirely different way but idk.
        addBiome(builder, BiomePalette.FROZEN_PEAKS,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND, elevation.HIGHLAND, elevation.MOUNTAIN},
                null,
                new slope[] {slope.BARE},
                null,
                null,
                new boolean[] {true}
        );

        addBiome(builder, BiomePalette.STONY_PEAKS,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND, elevation.HIGHLAND, elevation.MOUNTAIN},
                null,
                new slope[] {slope.BARE},
                null,
                null,
                new boolean[] {false}
        );
        //----


        addBiome(builder, BiomePalette.SNOWY_SLOPES,
                new elevation[] {elevation.MOUNTAIN},
                null,
                null,
                new treeCoverage[]{treeCoverage.NONE},
                null,
                new boolean[] {true}
        );

        addBiome(builder, BiomePalette.SNOWY_PLAINS,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND},
                null,
                null,
                new treeCoverage[]{treeCoverage.NONE, treeCoverage.BARREN},
                null,
                new boolean[] {true}
        );

        addBiome(builder, BiomePalette.SNOWY_TAIGA_SPARSE,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND, elevation.HIGHLAND, elevation.MOUNTAIN},
                null,
                null,
                new treeCoverage[]{treeCoverage.SPARSE},
                null,
                new boolean[] {true}
        );

        addBiome(builder, BiomePalette.SNOWY_TAIGA,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND, elevation.HIGHLAND, elevation.MOUNTAIN},
                null,
                null,
                new treeCoverage[]{treeCoverage.FOREST, treeCoverage.DENSE, treeCoverage.RAINFOREST},
                null,
                new boolean[] {true}
        );

        addBiome(builder, BiomePalette.WINDSWEPT_HILLS,
                new elevation[] {elevation.MOUNTAIN},
                null,
                null,
                new treeCoverage[]{treeCoverage.BARREN},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.DESERT,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND},
                new temperature[]{temperature.WARM, temperature.HOT},
                null,
                new treeCoverage[]{treeCoverage.NONE, treeCoverage.BARREN},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.BADLANDS, //testing the cell noise switcher
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND},
                new temperature[]{temperature.WARM, temperature.HOT},
                null,
                new treeCoverage[]{treeCoverage.NONE, treeCoverage.BARREN},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.GROVE,
                new elevation[] {elevation.MIDLAND, elevation.LOWLAND, elevation.HIGHLAND},
                null,
                null,
                new treeCoverage[]{treeCoverage.NONE, treeCoverage.BARREN},
                new moisture[] {moisture.SEMI_DRY, moisture.DRY, moisture.VERY_DRY},
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.PLAINS,
                new elevation[] {elevation.MIDLAND, elevation.LOWLAND, elevation.HIGHLAND},
                null,
                null,
                new treeCoverage[]{treeCoverage.NONE, treeCoverage.BARREN},
                new moisture[] {moisture.MOIST, moisture.VERY_MOIST, moisture.SATURATED},
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.JUNGLE,
                new elevation[] {elevation.MIDLAND, elevation.LOWLAND},
                new temperature[] {temperature.WARM, temperature.HOT},
                null,
                new treeCoverage[]{treeCoverage.FOREST, treeCoverage.DENSE, treeCoverage.RAINFOREST},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.SAVANNA,
                new elevation[] {elevation.MIDLAND, elevation.LOWLAND},
                new temperature[] {temperature.WARM, temperature.HOT},
                null,
                new treeCoverage[]{treeCoverage.SPARSE},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.FOREST_SPARSE,
                new elevation[] {elevation.MIDLAND, elevation.LOWLAND},
                new temperature[] {temperature.WARM, temperature.TEMPERATE},
                null,
                new treeCoverage[]{treeCoverage.SPARSE},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.SWAMP,
                new elevation[] {elevation.LOWLAND},
                new temperature[] {temperature.WARM, temperature.HOT},
                null,
                new treeCoverage[]{treeCoverage.DENSE, treeCoverage.RAINFOREST},
                null,
                new boolean[] {false}
        );


        addBiome(builder, BiomePalette.FOREST,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND},
                new temperature[] {temperature.WARM, temperature.TEMPERATE},
                null,
                new treeCoverage[]{treeCoverage.DENSE, treeCoverage.FOREST},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.TAIGA_SPARSE,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND, elevation.MOUNTAIN},
                new temperature[] {temperature.COOL, temperature.COLD},
                null,
                new treeCoverage[]{treeCoverage.SPARSE},
                null,
                new boolean[] {false}
        );

        addBiome(builder, BiomePalette.TAIGA,
                new elevation[] {elevation.LOWLAND, elevation.MIDLAND, elevation.MOUNTAIN},
                new temperature[] {temperature.COOL, temperature.COLD, temperature.FROZEN},
                null,
                new treeCoverage[]{treeCoverage.DENSE, treeCoverage.FOREST, treeCoverage.RAINFOREST},
                null,
                new boolean[] {false}
        );


        // Convert List<Short> values to short[] for cache-friendly access
        HashMap<climate, short[]> result = new HashMap<>(builder.size() * 2);
        builder.forEach((key, list) -> {
            short[] arr = new short[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            result.put(key, arr);
        });
        BIOME_MAP = result;
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        LOG.info("BIOME_MAP built: {} entries in {} ms", BIOME_MAP.size(), elapsedMs);
    }

    /**
     * Classify biomes for a grid of pixels.
     *
     * @param elev       elevation in meters, (H, W) row-major
     * @param climate    climate data (5, H, W) row-major or null
     * @param i0         top-left row in world space (for noise sampling)
     * @param j0         top-left col in world space
     * @param elevPadded elevation with 1-pixel padding, (H+2, W+2) row-major
     * @param H          height
     * @param W          width
     * @param pixelSizeM physical size of one pixel in meters
     * @return short array (H, W) with biome IDs
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = BiomePalette.OCEAN;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];
        float[] biomeNoise = new float[H * W];

        //im not sure it matters much to define it out here, probably only lowers the allocation rate by like 1kb lmao
        FastNoiseLite.Vector2 warpvector = new FastNoiseLite.Vector2(0,0);

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c, ny = i0 + r;
                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;

                warpvector.x = nx; warpvector.y = ny; //mfw this is the only thing that fastnoiselite uses vectors for wtf
                BIOME_NOISE_WARP.DomainWarp(warpvector);
                biomeNoise[idx] = (BIOME_NOISE.GetNoise(warpvector.x, warpvector.y)+1f)/2f;
            }
        }

        // Compute slope from padded elevation using Sobel (divide by pixelSizeM for ratio)
        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        // Process per-pixel
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float temp     = climate[idx] + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precip   = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float tEff     = Math.max(0f, temp + 0.5f * tStd);
                float pet      = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                // Slope-dependent bare threshold
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = treesForest && false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow classification
                float snowTemp = temp + snowNoise[idx];
                boolean isSteep = slope > 0.78f;
                boolean hasSnow = snowTemp < 0f && precip > 150f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;

                boolean frozen    = temp < -5f;
                boolean cold      = temp >= -5f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                elevation Elev;

                if (isOcean)        Elev = elevation.OCEAN;
                else if (mountains) Elev = elevation.MOUNTAIN;
                else if (lowland)   Elev = elevation.LOWLAND;
                else                Elev = elevation.MIDLAND;

                temperature Temp;

                if (frozen)         Temp = temperature.FROZEN;
                else if (cold)      Temp = temperature.COLD;
                else if (cool)      Temp = temperature.COOL;
                else if (temperate) Temp = temperature.TEMPERATE;
                else if (warm)      Temp = temperature.WARM;
                else                Temp = temperature.HOT;

                BiomeClassifier.slope Slope;

                if (slopeBare)          Slope = BiomeClassifier.slope.BARE;
                else if (slopeMedium)   Slope = BiomeClassifier.slope.MEDIUM;
                else                    Slope = BiomeClassifier.slope.NONE;

                treeCoverage Cover;

                if (barren) Cover =             treeCoverage.BARREN;
                else if (treesNone) Cover =     treeCoverage.NONE;
                else if (treesSparse) Cover =   treeCoverage.SPARSE;
                else if (treesForest) Cover =   treeCoverage.FOREST;
                else if (treesDense) Cover =    treeCoverage.DENSE;
                else Cover =                    treeCoverage.RAINFOREST;

                moisture Moisture;

                if (treeMoisture < 0.12f || precip < 180f)          Moisture = moisture.VERY_DRY;
                else if (treeMoisture < 0.35f || precip < 350f)     Moisture = moisture.DRY;
                else if (treeMoisture < 0.55f || precip < 520f)     Moisture = moisture.SEMI_DRY;
                else if (treeMoisture >= 1.45f || precip > 1650f)   Moisture = moisture.SATURATED;
                else if (treeMoisture >= 1.15f || precip > 1250f)   Moisture = moisture.VERY_MOIST;
                else                                                Moisture = moisture.MOIST;

                                                        //probably an insane memory leak
                BiomeClassifier.climate climateResult = new climate(Elev, Temp, Slope, Cover, Moisture, hasSnow);


                // Look up matching biomes and randomize based on cell noise
                short[] biomes = BIOME_MAP.get(climateResult);
                out[idx] = (biomes != null) ? biomes[0] : BiomePalette.OCEAN;
                if (biomes == null || biomes.length == 0) {
                    //using ocean for a fallback as its more obvious when there's a null condition
                    //may seem counterintuitive but im doing this so i know where i need to fix
                    //rather than just cover it up
                    out[idx] = BiomePalette.OCEAN;
                }
                else if (biomes.length == 1) {
                    out[idx] = biomes[0];
                }
                else {
                    out[idx] = biomes[ Math.min((int)(biomeNoise[idx]*biomes.length), biomes.length - 1) ];
                }
            }
        }
        return out;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) paddedS array → (H, W) output
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }
        return slope;
    }
}
