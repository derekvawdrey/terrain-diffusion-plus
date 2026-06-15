package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;
import com.github.xandergos.terraindiffusionmc.biome.TerrainClimateSample;

/**
 * Climate-based biome classifier.
 *
 * <p>The returned values are internal TerrainBiomeCatalog indexes. They are not
 * Minecraft registry IDs. Version-specific code resolves each catalog index to
 * the matching vanilla biome key, with fallbacks for older versions.</p>
 */
public final class BiomeClassifier {

    private static final int DETAIL_SHORELINE_PADDING = 24;

    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;
    private static final FastNoiseLite BIOME_VARIANT_NOISE, CHERRY_GROVE_NOISE, PALE_GARDEN_NOISE;
    private static final FastNoiseLite FOREST_CLEARING_NOISE, FLOWER_PATCH_NOISE;

    static {
        TEMP_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        BIOME_VARIANT_NOISE = makeFnl(24680, 1f/650f, 3, 2f, 0.55f);

        CHERRY_GROVE_NOISE = makeFnl(97531, 1f/320f, 3, 2f, 0.55f);
        PALE_GARDEN_NOISE = makeFnl(86420, 1f/280f, 3, 2f, 0.55f);

        FOREST_CLEARING_NOISE = makeFnl(112233, 1f/260f, 3, 2f, 0.54f);
        FLOWER_PATCH_NOISE = makeFnl(445566, 1f/220f, 3, 2f, 0.54f);
    }

    /** Extra elevation/climate pixels used by Explorer detail biome rendering. */
    public static int detailShorelinePadding() {
        return DETAIL_SHORELINE_PADDING;
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
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
     * @return short array (H, W) with TerrainBiomeCatalog indexes
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = TerrainBiomeCatalog.PLAINS;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];
        float[] variantNoise = new float[H * W];
        float[] cherryNoise = new float[H * W];
        float[] paleNoise = new float[H * W];
        float[] clearingNoise = new float[H * W];
        float[] flowerNoise = new float[H * W];

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

                variantNoise[idx] = BIOME_VARIANT_NOISE.GetNoise(nx, ny);
                cherryNoise[idx] = CHERRY_GROVE_NOISE.GetNoise(nx, ny);
                paleNoise[idx] = PALE_GARDEN_NOISE.GetNoise(nx, ny);
                clearingNoise[idx] = FOREST_CLEARING_NOISE.GetNoise(nx, ny);
                flowerNoise[idx] = FLOWER_PATCH_NOISE.GetNoise(nx, ny);
            }
        }

        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                boolean coastline = isCoastlineCandidate(elev, H, W, r, c, elev[idx], slopeRatio[idx]);
                out[idx] = classifyPixel(elev[idx], climate, H, W, idx, tempNoise[idx],
                        precipNoiseFact[idx], snowNoise[idx], variantNoise[idx], cherryNoise[idx], paleNoise[idx],
                        clearingNoise[idx], flowerNoise[idx], slopeRatio[idx], coastline);
            }
        }
        smoothIsolatedTransitions(out, H, W);
        smoothOrganicTransitions(out, H, W);
        return out;
    }

    private static void smoothIsolatedTransitions(short[] biomes, int H, int W) {
        short[] src = biomes.clone();
        for (int r = 1; r < H - 1; r++) {
            for (int c = 1; c < W - 1; c++) {
                int idx = r * W + c;
                short current = src[idx];
                if (isHardBoundaryBiome(current)) continue;

                int same = 0;
                short best = current;
                int bestCount = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        short candidate = src[(r + dr) * W + (c + dc)];
                        if (candidate == current) same++;
                        if (isHardBoundaryBiome(candidate)) continue;
                        int count = 0;
                        for (int er = -1; er <= 1; er++) {
                            for (int ec = -1; ec <= 1; ec++) {
                                if (src[(r + er) * W + (c + ec)] == candidate) count++;
                            }
                        }
                        if (count > bestCount) {
                            bestCount = count;
                            best = candidate;
                        }
                    }
                }

                // Remove isolated one/two-pixel biome speckles while keeping real
                // biome pockets and hard coast/water/mountain boundaries intact.
                if (same <= 2 && best != current && bestCount >= 5) {
                    biomes[idx] = best;
                }
            }
        }
    }

    private static void smoothOrganicTransitions(short[] biomes, int H, int W) {
        short[] src = biomes.clone();

        // 3x3 local majority. This rounds off jagged edges and creates
        // less blocky transitions without touching coast/ocean/peak boundaries.
        for (int r = 1; r < H - 1; r++) {
            for (int c = 1; c < W - 1; c++) {
                int idx = r * W + c;
                short current = src[idx];
                if (!isBlendableLandBiome(current)) continue;

                short best = current;
                int bestCount = 0;
                int currentCount = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        short candidate = src[(r + dr) * W + (c + dc)];
                        if (!isBlendableLandBiome(candidate)) continue;
                        int count = 0;
                        for (int er = -1; er <= 1; er++) {
                            for (int ec = -1; ec <= 1; ec++) {
                                short neighbour = src[(r + er) * W + (c + ec)];
                                if (neighbour == candidate) count++;
                            }
                        }
                        if (candidate == current) currentCount = Math.max(currentCount, count);
                        if (count > bestCount) {
                            bestCount = count;
                            best = candidate;
                        }
                    }
                }

                if (best != current && bestCount >= 6 && currentCount <= 3) {
                    biomes[idx] = best;
                }
            }
        }

        // Broader 5x5 dominant-region smoothing. This reduces square
        // patchwork inside inland biome masses while keeping distinct pockets.
        src = biomes.clone();
        for (int r = 2; r < H - 2; r++) {
            for (int c = 2; c < W - 2; c++) {
                int idx = r * W + c;
                short current = src[idx];
                if (!isBlendableLandBiome(current)) continue;

                short best = current;
                int bestCount = 0;
                int currentCount = 0;
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        short candidate = src[(r + dr) * W + (c + dc)];
                        if (!isBlendableLandBiome(candidate)) continue;
                        int count = 0;
                        for (int er = -2; er <= 2; er++) {
                            for (int ec = -2; ec <= 2; ec++) {
                                short neighbour = src[(r + er) * W + (c + ec)];
                                if (neighbour == candidate) count++;
                            }
                        }
                        if (candidate == current) currentCount = Math.max(currentCount, count);
                        if (count > bestCount) {
                            bestCount = count;
                            best = candidate;
                        }
                    }
                }

                if (best != current && bestCount >= 15 && currentCount <= 7) {
                    biomes[idx] = best;
                }
            }
        }
    }

    private static boolean isBlendableLandBiome(short biome) {
        if (isHardBoundaryBiome(biome)) return false;
        return biome != TerrainBiomeCatalog.RIVER
                && biome != TerrainBiomeCatalog.FROZEN_RIVER
                && biome != TerrainBiomeCatalog.LUSH_CAVES
                && biome != TerrainBiomeCatalog.DRIPSTONE_CAVES
                && biome != TerrainBiomeCatalog.DEEP_DARK;
    }

    private static boolean isHardBoundaryBiome(short biome) {
        return biome == TerrainBiomeCatalog.WARM_OCEAN
                || biome == TerrainBiomeCatalog.LUKEWARM_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_LUKEWARM_OCEAN
                || biome == TerrainBiomeCatalog.OCEAN
                || biome == TerrainBiomeCatalog.DEEP_OCEAN
                || biome == TerrainBiomeCatalog.COLD_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_COLD_OCEAN
                || biome == TerrainBiomeCatalog.FROZEN_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_FROZEN_OCEAN
                || biome == TerrainBiomeCatalog.BEACH
                || biome == TerrainBiomeCatalog.SNOWY_BEACH
                || biome == TerrainBiomeCatalog.STONY_SHORE
                || biome == TerrainBiomeCatalog.FROZEN_PEAKS
                || biome == TerrainBiomeCatalog.JAGGED_PEAKS
                || biome == TerrainBiomeCatalog.STONY_PEAKS;
    }

    private static short classifyPixel(float elevation, float[] climate, int H, int W, int idx,
                                       float tempNoise, float precipNoiseFactor, float snowNoise,
                                       float variantNoise, float cherryNoise, float paleNoise, float clearingNoise, float flowerNoise, float slope, boolean coastline) {
        float altM = Math.max(0f, elevation);

        // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
        float temp = climate[idx] + tempNoise;
        float tSeason = climate[H * W + idx];
        float precip = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFactor;
        float pCV = climate[3 * H * W + idx];

        float tStd = tSeason / 100f;
        float tEff = Math.max(0f, temp + 0.5f * tStd);
        float pet = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
        float aridity = precip / Math.max(1f, pet);
        float seasonPenalty = 1f - 0.35f * clamp01(pCV / 100f);
        float treeMoisture = aridity * seasonPenalty;

        float amplitude = tStd * 1.414f;
        float growingSeason;
        if (amplitude < 0.1f) {
            growingSeason = temp > 5f ? 365f : 0f;
        } else {
            float x = (5f - temp) / amplitude;
            if (x <= -1f) growingSeason = 365f;
            else if (x >= 1f) growingSeason = 0f;
            else growingSeason = 365f * (0.5f - (float) Math.asin(clamp(x, -1f, 1f)) / (float) Math.PI);
        }

        float gsFactor = clamp01((growingSeason - 60f) / (150f - 60f));
        float effTreeMoisture = treeMoisture * gsFactor;

        float moistureFactor = clamp01((treeMoisture - 0.35f) / 0.45f);
        float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

        boolean treesNone = effTreeMoisture < 0.2f;
        boolean tooArid = treeMoisture < 0.05f;
        boolean tooCold = growingSeason < 60f;
        boolean barren = tooArid || tooCold;
        boolean treesSparse = !treesNone && effTreeMoisture < 0.5f;
        boolean treesForest = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
        boolean treesDense = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
        boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

        boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
        boolean slopeBare = slope >= bareThreshold;
        if (slopeMedium) {
            if (treesForest || treesDense || treesRainforest) treesSparse = true;
            treesForest = false;
            treesDense = false;
            treesRainforest = false;
        }
        if (slopeBare) {
            treesNone = true;
            treesSparse = false;
            treesForest = false;
            treesDense = false;
            treesRainforest = false;
        }

        float treeCoverage;
        if (treesRainforest) treeCoverage = 1.00f;
        else if (treesDense) treeCoverage = 0.85f;
        else if (treesForest) treeCoverage = 0.62f;
        else if (treesSparse) treeCoverage = 0.35f;
        else treeCoverage = 0.00f;
        float sparsity = clamp01(1.0f - treeCoverage);

        float snowTemp = temp + snowNoise;
        boolean isSteep = slope > 0.78f;

        boolean hasSnow = (snowTemp < -2f || (altM > 800f && snowTemp < -0.5f))
                && precip > 150f && !isSteep;

        boolean isOcean = elevation < 0f;
        boolean deepOcean = elevation < -250f;

        boolean beachBand = coastline;
        boolean mountains = altM > 2500f;
        boolean highland = altM > 900f;
        boolean lowland = altM < 200f;
        boolean frozen = temp < -5f;
        boolean cold = temp >= -5f && temp < 5f;
        boolean cool = temp >= 5f && temp < 12f;
        boolean temperate = temp >= 12f && temp < 20f;
        boolean warm = temp >= 20f && temp < 26f;
        boolean hot = temp >= 26f;

        TerrainClimateSample sample = new TerrainClimateSample(altM, temp, tSeason, precip, pCV,
                treeMoisture, aridity, treeMoisture, treeCoverage, sparsity, slope, growingSeason,
                isOcean, hasSnow, slopeBare, mountains, lowland);

        short biome;
        if (isOcean) {
            biome = selectOcean(temp, deepOcean);
        } else if (beachBand && !mountains) {
            biome = selectBeach(sample, frozen || hasSnow, slopeMedium);
        } else if (mountains) {
            biome = selectMountain(sample, frozen, cold, cool, warm, hot, barren,
                    treesNone, treesSparse, treesForest, treesDense, treesRainforest, variantNoise, cherryNoise);
        } else {
            biome = selectLowland(sample, frozen, cold, cool, temperate, warm, hot, barren,
                    treesNone, treesSparse, treesForest, treesDense, treesRainforest, highland, variantNoise, cherryNoise, paleNoise, clearingNoise, flowerNoise);
        }

        if (slopeBare && !isOcean && !mountains) {
            biome = hasSnow ? TerrainBiomeCatalog.FROZEN_PEAKS : TerrainBiomeCatalog.STONY_PEAKS;
        }

        return biome;
    }

    private static short selectOcean(float temp, boolean deep) {
        if (temp < -5f) return deep ? TerrainBiomeCatalog.DEEP_FROZEN_OCEAN : TerrainBiomeCatalog.FROZEN_OCEAN;
        if (temp < 5f) return deep ? TerrainBiomeCatalog.DEEP_COLD_OCEAN : TerrainBiomeCatalog.COLD_OCEAN;
        if (temp < 20f) return deep ? TerrainBiomeCatalog.DEEP_OCEAN : TerrainBiomeCatalog.OCEAN;
        if (temp < 26f) return deep ? TerrainBiomeCatalog.DEEP_LUKEWARM_OCEAN : TerrainBiomeCatalog.LUKEWARM_OCEAN;
        return TerrainBiomeCatalog.WARM_OCEAN;
    }

    private static short selectBeach(TerrainClimateSample sample, boolean snowy, boolean stony) {
        if (stony || sample.slope() > 0.28f) return TerrainBiomeCatalog.STONY_SHORE;
        if (snowy || sample.temperatureC() < 1f) return TerrainBiomeCatalog.SNOWY_BEACH;
        return TerrainBiomeCatalog.BEACH;
    }

    private static short selectMountain(TerrainClimateSample sample, boolean frozen, boolean cold, boolean cool,
                                        boolean warm, boolean hot, boolean barren, boolean treesNone,
                                        boolean treesSparse, boolean treesForest, boolean treesDense,
                                        boolean treesRainforest, float variantNoise, float cherryNoise) {
        if (sample.bareSlope()) {
            if (sample.snowy()) return sample.slope() > 1.1f || sample.elevationM() > 3300f
                    ? TerrainBiomeCatalog.JAGGED_PEAKS
                    : TerrainBiomeCatalog.FROZEN_PEAKS;
            return TerrainBiomeCatalog.STONY_PEAKS;
        }

        if (sample.snowy() || frozen) {
            if (treesNone || sample.sparsity() > 0.75f) return TerrainBiomeCatalog.SNOWY_SLOPES;
            if (treesSparse || treesForest) return TerrainBiomeCatalog.GROVE;
            return TerrainBiomeCatalog.SNOWY_TAIGA;
        }

        if (warm || hot) {
            if (sample.sparsity() > 0.55f) return sample.slope() > 0.45f
                    ? TerrainBiomeCatalog.WINDSWEPT_SAVANNA
                    : TerrainBiomeCatalog.SAVANNA_PLATEAU;
            return TerrainBiomeCatalog.WOODED_BADLANDS;
        }

        if (treesNone) {
            if (barren || sample.moisture() < 0.28f || sample.precipitationMm() < 300f) {
                return sample.slope() > 0.45f ? TerrainBiomeCatalog.WINDSWEPT_GRAVELLY_HILLS : TerrainBiomeCatalog.WINDSWEPT_HILLS;
            }
            return (cool || cold) ? TerrainBiomeCatalog.MEADOW : TerrainBiomeCatalog.PLAINS;
        }

        if (treesSparse || treesForest || sample.sparsity() > 0.50f) {
            if (sample.slope() > 0.38f) return TerrainBiomeCatalog.WINDSWEPT_FOREST;
            if (cool || cold) return TerrainBiomeCatalog.TAIGA;
            if (isCherryCandidate(sample, cherryNoise, true)) return TerrainBiomeCatalog.CHERRY_GROVE;
            return TerrainBiomeCatalog.MEADOW;
        }

        if (treesDense || treesRainforest) {
            if (cool || cold) return sample.moisture() > 0.95f ? TerrainBiomeCatalog.OLD_GROWTH_SPRUCE_TAIGA : TerrainBiomeCatalog.TAIGA;
            if (isCherryCandidate(sample, cherryNoise, true)) return TerrainBiomeCatalog.CHERRY_GROVE;
            return TerrainBiomeCatalog.FOREST;
        }

        return TerrainBiomeCatalog.MEADOW;
    }

    private static short selectLowland(TerrainClimateSample sample, boolean frozen, boolean cold, boolean cool,
                                       boolean temperate, boolean warm, boolean hot, boolean barren,
                                       boolean treesNone, boolean treesSparse, boolean treesForest,
                                       boolean treesDense, boolean treesRainforest, boolean highland,
                                       float variantNoise, float cherryNoise, float paleNoise, float clearingNoise, float flowerNoise) {
        if (sample.snowy() && treesNone) {
            return (sample.sparsity() > 0.85f && variantNoise > 0.20f) ? TerrainBiomeCatalog.ICE_SPIKES : TerrainBiomeCatalog.SNOWY_PLAINS;
        }
        if (sample.snowy()) {
            return (treesSparse && sample.sparsity() > 0.70f) ? TerrainBiomeCatalog.SNOWY_PLAINS : TerrainBiomeCatalog.SNOWY_TAIGA;
        }

        if (treesNone) {
            if (temperate && !barren && sample.moisture() >= 0.36f && sample.moisture() <= 0.78f
                    && sample.precipitationMm() > 330f && sample.slope() < 0.36f) {
                // At the open/wooded boundary, add occasional wooded and flower
                // pockets so the transition is ecological rather than a hard
                // binary line.
                if (flowerNoise > 0.72f && variantNoise > 0.20f) return TerrainBiomeCatalog.SUNFLOWER_PLAINS;
                if (clearingNoise > 0.78f && variantNoise > 0.25f) return flowerNoise > 0.55f
                        ? TerrainBiomeCatalog.FLOWER_FOREST
                        : TerrainBiomeCatalog.FOREST;
            }
            if ((warm || hot) && sample.moisture() < 0.30f) {
                // Red sand is not a separate biome; modern Minecraft exposes it
                // through the badlands family. Make those variants reachable
                // instead of collapsing every hot arid cell to desert/savanna.
                if (sample.precipitationMm() < 180f && sample.moisture() < 0.16f && !highland) {
                    return TerrainBiomeCatalog.DESERT;
                }
                if (highland || sample.slope() > 0.22f || variantNoise > 0.18f) {
                    return (sample.slope() > 0.38f || variantNoise > 0.42f)
                            ? TerrainBiomeCatalog.WOODED_BADLANDS
                            : TerrainBiomeCatalog.BADLANDS;
                }
                return hot && sample.precipitationMm() < 420f ? TerrainBiomeCatalog.BADLANDS : TerrainBiomeCatalog.SAVANNA;
            }
            if (barren && (cold || cool || temperate)) {
                return highland ? TerrainBiomeCatalog.MEADOW : TerrainBiomeCatalog.PLAINS;
            }
            if (sample.moisture() < 0.35f || sample.precipitationMm() < 350f) {
                if (warm || hot) return TerrainBiomeCatalog.SAVANNA;
                return highland ? TerrainBiomeCatalog.MEADOW : TerrainBiomeCatalog.PLAINS;
            }
            if (temperate && variantNoise > 0.40f) return TerrainBiomeCatalog.SUNFLOWER_PLAINS;
            return TerrainBiomeCatalog.PLAINS;
        }

        if (treesSparse || treesForest) {
            if (hot) return sample.moisture() < 0.45f ? TerrainBiomeCatalog.BADLANDS : TerrainBiomeCatalog.SPARSE_JUNGLE;
            if (warm) {
                if (highland && sample.sparsity() > 0.50f && sample.moisture() < 0.75f) return TerrainBiomeCatalog.SAVANNA_PLATEAU;
                if (sample.sparsity() > 0.55f || sample.moisture() < 0.65f) return TerrainBiomeCatalog.SAVANNA;
                return TerrainBiomeCatalog.SPARSE_JUNGLE;
            }
            if (temperate) {
                if (isOpenClearingCandidate(sample, clearingNoise, false)) {
                    return selectOpenFlowerPatch(sample, highland, flowerNoise, variantNoise);
                }
                if (isPaleGardenCandidate(sample, paleNoise, false)) return TerrainBiomeCatalog.PALE_GARDEN;
                if (isCherryCandidate(sample, cherryNoise, highland)) return TerrainBiomeCatalog.CHERRY_GROVE;
                if (sample.sparsity() > 0.72f) return variantNoise > 0.22f ? TerrainBiomeCatalog.FLOWER_FOREST : TerrainBiomeCatalog.PLAINS;
                if (variantNoise > 0.48f && sample.moisture() > 0.58f) return TerrainBiomeCatalog.FLOWER_FOREST;
                if (flowerNoise > 0.66f && sample.moisture() > 0.58f && variantNoise > 0.10f) return TerrainBiomeCatalog.FLOWER_FOREST;
                if (variantNoise < -0.35f) return TerrainBiomeCatalog.BIRCH_FOREST;
                return sample.moisture() > 1.12f ? TerrainBiomeCatalog.DARK_FOREST : TerrainBiomeCatalog.FOREST;
            }
            return TerrainBiomeCatalog.TAIGA;
        }

        if (treesDense) {
            if (hot) return sample.moisture() > 1.45f && variantNoise > 0.15f ? TerrainBiomeCatalog.BAMBOO_JUNGLE : TerrainBiomeCatalog.JUNGLE;
            if (warm && sample.lowland()) return sample.moisture() > 1.35f ? TerrainBiomeCatalog.MANGROVE_SWAMP : TerrainBiomeCatalog.SWAMP;
            if (isOpenClearingCandidate(sample, clearingNoise, true)) {
                return selectOpenFlowerPatch(sample, highland, flowerNoise, variantNoise);
            }
            if (isPaleGardenCandidate(sample, paleNoise, true)) return TerrainBiomeCatalog.PALE_GARDEN;
            if (cool || cold) return sample.moisture() > 1.10f ? TerrainBiomeCatalog.OLD_GROWTH_PINE_TAIGA : TerrainBiomeCatalog.TAIGA;
            if (isCherryCandidate(sample, cherryNoise, highland)) return TerrainBiomeCatalog.CHERRY_GROVE;
            if (sample.moisture() > 1.15f && variantNoise > 0.10f) return TerrainBiomeCatalog.DARK_FOREST;
            if (variantNoise < -0.40f) return TerrainBiomeCatalog.BIRCH_FOREST;
            return TerrainBiomeCatalog.FOREST;
        }

        if (hot || (warm && sample.temperatureC() >= 18f && sample.temperatureSeasonality() / 100f < 5f)) {
            return sample.moisture() > 1.55f && variantNoise > 0.05f ? TerrainBiomeCatalog.BAMBOO_JUNGLE : TerrainBiomeCatalog.JUNGLE;
        }
        if (isOpenClearingCandidate(sample, clearingNoise, true)) {
            return selectOpenFlowerPatch(sample, highland, flowerNoise, variantNoise);
        }
        if (isPaleGardenCandidate(sample, paleNoise, true)) return TerrainBiomeCatalog.PALE_GARDEN;
        if (sample.lowland()) return warm ? TerrainBiomeCatalog.MANGROVE_SWAMP : TerrainBiomeCatalog.SWAMP;
        if (cool || cold) return sample.moisture() > 1.10f ? TerrainBiomeCatalog.OLD_GROWTH_SPRUCE_TAIGA : TerrainBiomeCatalog.TAIGA;
        if (isCherryCandidate(sample, cherryNoise, highland)) return TerrainBiomeCatalog.CHERRY_GROVE;
        if (sample.moisture() > 1.20f) return TerrainBiomeCatalog.DARK_FOREST;
        return TerrainBiomeCatalog.FOREST;
    }

    private static boolean isOpenClearingCandidate(TerrainClimateSample sample, float clearingNoise, boolean denseContext) {
        if (sample.temperatureC() < 5f || sample.temperatureC() > 23f) return false;
        if (sample.moisture() < 0.52f || sample.moisture() > 1.35f) return false;
        if (sample.precipitationMm() < 380f || sample.slope() > 0.38f) return false;
        if (sample.snowy() || sample.bareSlope()) return false;

        float threshold = denseContext ? 0.82f : 0.60f;
        if (!denseContext && sample.sparsity() > 0.50f) threshold -= 0.05f;
        if (!denseContext && sample.sparsity() > 0.65f) threshold -= 0.04f;
        if (sample.moisture() > 1.12f) threshold += 0.07f;
        return clearingNoise > threshold;
    }

    private static short selectOpenFlowerPatch(TerrainClimateSample sample, boolean highland, float flowerNoise, float variantNoise) {
        if (highland || sample.elevationM() > 850f) {
            return flowerNoise > 0.64f ? TerrainBiomeCatalog.FLOWER_FOREST : TerrainBiomeCatalog.MEADOW;
        }
        if (flowerNoise > 0.68f && variantNoise > 0.18f) {
            return TerrainBiomeCatalog.SUNFLOWER_PLAINS;
        }
        if (flowerNoise < -0.46f && variantNoise > 0.26f && sample.moisture() > 0.72f) {
            return TerrainBiomeCatalog.FLOWER_FOREST;
        }
        return TerrainBiomeCatalog.PLAINS;
    }

    private static boolean isCherryCandidate(TerrainClimateSample sample, float cherryNoise, boolean highlandContext) {
        if (sample.temperatureC() < 6f || sample.temperatureC() > 18.5f) return false;
        if (sample.moisture() < 0.58f || sample.moisture() > 1.35f) return false;
        if (sample.precipitationMm() < 360f || sample.precipitationMm() > 2600f) return false;
        if (sample.slope() > 0.52f) return false;

        if (!highlandContext && sample.elevationM() < 560f && sample.slope() < 0.12f) return false;

        float threshold = (highlandContext || sample.elevationM() > 760f || sample.slope() > 0.17f) ? 0.18f : 0.32f;
        if (sample.moisture() > 0.90f) threshold -= 0.04f;
        if (sample.precipitationMm() > 1100f) threshold -= 0.03f;
        if (sample.elevationM() > 1000f) threshold -= 0.03f;
        return cherryNoise > threshold;
    }

    private static boolean isPaleGardenCandidate(TerrainClimateSample sample, float paleNoise, boolean denseContext) {
        if (sample.temperatureC() < 5f || sample.temperatureC() > 20f) return false;
        if (sample.moisture() < 0.82f || sample.precipitationMm() < 620f) return false;
        if (sample.elevationM() > 720f) return false;
        if (sample.slope() > 0.48f || sample.sparsity() > 0.58f) return false;

        float threshold = denseContext ? 0.02f : 0.34f;
        if (sample.moisture() > 1.10f) threshold -= 0.04f;
        if (sample.precipitationMm() > 1000f) threshold -= 0.04f;
        if (sample.elevationM() < 250f) threshold -= 0.02f;
        return paleNoise > threshold;
    }

    private static boolean isCoastlineCandidate(float[] elev, int H, int W, int r, int c,
                                                float elevation, float slope) {

        if (elevation < 0f || elevation > 18f || slope > 0.35f) return false;

        int waterCount = 0;
        int deepWaterCount = 0;
        int landCount = 0;
        int higherLandCount = 0;
        int radius = 5;
        for (int dr = -radius; dr <= radius; dr++) {
            int rr = r + dr;
            if (rr < 0 || rr >= H) continue;
            for (int dc = -radius; dc <= radius; dc++) {
                int cc = c + dc;
                if (cc < 0 || cc >= W) continue;
                if (dr == 0 && dc == 0) continue;
                float neighbour = elev[rr * W + cc];
                if (neighbour < 0f) {
                    waterCount++;
                    if (neighbour < -12f) deepWaterCount++;
                } else {
                    landCount++;
                    if (neighbour > 6f) higherLandCount++;
                }
            }
        }

        return waterCount >= 8 && landCount >= 8 && higherLandCount >= 3
                && (deepWaterCount >= 2 || waterCount >= 18);
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
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

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
