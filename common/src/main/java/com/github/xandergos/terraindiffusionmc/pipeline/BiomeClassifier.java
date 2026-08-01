package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.biome.BiomeRuleEngine;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeNoiseSample;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.biome.TerrainClimateSample;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;

/**
 * Climate-based biome classifier.
 *
 * <p>The returned values are internal biome settlement indexes. They are not
 * Minecraft registry IDs. Version-specific code resolves each index to
 * the matching biome key via the registry.</p>
 *
 * <p>Biome selection is data-driven: rules are defined in {@code biome_catalog.json}
 * and evaluated by {@link BiomeRuleEngine}. The hardcoded decision tree has been
 * replaced with rule-based evaluation.</p>
 */
public final class BiomeClassifier {

    private static final int DETAIL_SHORELINE_PADDING = 24;

    private static final TerrainBiomeRegistry REGISTRY = TerrainBiomeRegistry.instance();
    private static final BiomeRuleEngine ENGINE = new BiomeRuleEngine(REGISTRY);

    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;
    private static final FastNoiseLite BIOME_VARIANT_NOISE, CHERRY_GROVE_NOISE, PALE_GARDEN_NOISE;
    private static final FastNoiseLite FOREST_CLEARING_NOISE, FLOWER_PATCH_NOISE;
    private static final FastNoiseLite REGION_NOISE;
    private static final FastNoiseLite REGION_WARP_X, REGION_WARP_Z;
    private static final FastNoiseLite JAPAN_NOISE;

    /**
     * Domain-warp displacement amplitude for {@link #sampleRegionNoise}, in blocks. Sized against
     * the 5000-block region wavelength: enough to crinkle province borders into coastline-like
     * shapes, small enough that provinces keep their overall position and ~1/3 area shares.
     */
    private static final float REGION_WARP_AMPLITUDE = 650f;

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

        // Large-wavelength (~5000 block) noise for gating rare/region-specific biomes
        // and river density. Low octave count keeps it cheap and smooth across tile
        // boundaries so region membership doesn't jitter at generation-tile seams.
        REGION_NOISE = makeFnl(778899, 1f/5000f, 2, 2f, 0.5f);

        // Domain-warp fields for the region noise. A 2-octave field's tercile contours are so
        // smooth they render as concentric circular arcs around its extrema at map scale; warping
        // the sampling coordinate wrinkles those borders organically at 1500/750/375-block scales
        // WITHOUT changing the field's value distribution -- so the province tercile thresholds
        // fitted by the biome lab (and the lab's distributional regionNoise model) stay valid.
        REGION_WARP_X = makeFnl(881122, 1f/1500f, 3, 2f, 0.5f);
        REGION_WARP_Z = makeFnl(993344, 1f/1500f, 3, 2f, 0.5f);

        // Independent of REGION_NOISE on purpose: the Japan region is an overlay, not a fourth
        // province, so it must not perturb the A/B/C tercile shares the catalog was fitted
        // against. Same construction (2 octaves, gain 0.5, 5000-block wavelength) so it shares
        // that field's measured marginal distribution -- which is what JAPAN_QUANTILES inverts.
        JAPAN_NOISE = makeFnl(553311, 1f/5000f, 2, 2f, 0.5f);
    }

    /**
     * Measured value distribution of the 2-octave/gain-0.5 noise family, as values at the 0th,
     * 5th, ... 100th percentile. Lets {@link #japanThreshold} turn a configured area share
     * straight into the noise cutoff that yields it. Copied from biome-lab's
     * {@code data/noise_quantiles/oct2_gain050.json} (20.25M samples); its 33.3/66.7 entries
     * reproduce the +-0.0967 province terciles the catalog already uses, which is the check that
     * this table and the live field agree.
     */
    private static final float[] JAPAN_QUANTILES = {
        -0.803125f, -0.379871f, -0.299978f, -0.242870f, -0.196260f, -0.155813f, -0.119302f,
        -0.085727f, -0.054745f, -0.026083f, 0.000000f, 0.026099f, 0.054746f, 0.085725f,
        0.119302f, 0.155824f, 0.196258f, 0.242851f, 0.300061f, 0.379794f, 0.807837f
    };

    /**
     * Noise value above which a pixel counts as inside the Japan region, for the configured area
     * share. Resolved once: {@code biome.japan_region_share} is a world-shaping setting, so it is
     * read at class-init like the rest of the field construction rather than per pixel.
     */
    private static final float JAPAN_THRESHOLD = japanThreshold(TerrainDiffusionConfig.japanRegionShare());

    private static float japanThreshold(float share) {
        if (share >= 1f) return JAPAN_QUANTILES[0];
        if (share <= 0f) return JAPAN_QUANTILES[JAPAN_QUANTILES.length - 1];
        float position = (1f - share) * (JAPAN_QUANTILES.length - 1);
        int lo = (int) position;
        if (lo >= JAPAN_QUANTILES.length - 1) return JAPAN_QUANTILES[JAPAN_QUANTILES.length - 1];
        float frac = position - lo;
        return JAPAN_QUANTILES[lo] + (JAPAN_QUANTILES[lo + 1] - JAPAN_QUANTILES[lo]) * frac;
    }

    /**
     * Large-wavelength region noise sampled by world coordinate -- the single implementation of
     * the region field, domain warp included. Every consumer (the classify loop, hydrology's
     * river-density gating, the warm-region lapse clamp, the explorer's spawn overlay) must go
     * through here or province membership would disagree between systems.
     */
    public static float sampleRegionNoise(float worldX, float worldZ) {
        float wx = worldX + REGION_WARP_AMPLITUDE * REGION_WARP_X.GetNoise(worldX, worldZ);
        float wz = worldZ + REGION_WARP_AMPLITUDE * REGION_WARP_Z.GetNoise(worldX, worldZ);
        return REGION_NOISE.GetNoise(wx, wz);
    }

    /**
     * Signed membership in the Japan region: {@code >= 0} inside, {@code < 0} outside, with the
     * magnitude being distance from the border in noise units. Returning a margin rather than the
     * raw field is what lets {@code biome.japan_region_share} resize the region without every
     * gated catalog rule needing its threshold rewritten -- a rule just asks for
     * {@code japanRegion gte 0}. Shares the region field's domain warp so the two kinds of border
     * have the same coastline-like character.
     */
    public static float sampleJapanRegion(float worldX, float worldZ) {
        float wx = worldX + REGION_WARP_AMPLITUDE * REGION_WARP_X.GetNoise(worldX, worldZ);
        float wz = worldZ + REGION_WARP_AMPLITUDE * REGION_WARP_Z.GetNoise(worldX, worldZ);
        return JAPAN_NOISE.GetNoise(wx, wz) - JAPAN_THRESHOLD;
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
        short defaultBiome = REGISTRY.defaultBiomeIndex();
        HydrologyParallel.forEachIndex(0, H * W, index -> out[index] = defaultBiome);

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
        float[] regionNoise = new float[H * W];
        float[] japanRegion = new float[H * W];

        HydrologyParallel.forEachRow(0, H, W, r -> {
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
                regionNoise[idx] = sampleRegionNoise(nx, ny);
                japanRegion[idx] = sampleJapanRegion(nx, ny);
            }
        });

        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                boolean coastline = isCoastlineCandidate(elev, H, W, r, c, elev[idx], slopeRatio[idx]);
                out[idx] = classifyPixel(elev[idx], climate, H, W, idx, tempNoise[idx],
                        precipNoiseFact[idx], snowNoise[idx], variantNoise[idx], cherryNoise[idx], paleNoise[idx],
                        clearingNoise[idx], flowerNoise[idx], regionNoise[idx], japanRegion[idx],
                        slopeRatio[idx], coastline,
                        j0 + c, i0 + r);
            }
        });
        smoothIsolatedTransitions(out, H, W);
        smoothOrganicTransitions(out, H, W);
        return out;
    }

    private static void smoothIsolatedTransitions(short[] biomes, int H, int W) {
        short[] src = biomes.clone();
        HydrologyParallel.forEachRow(1, H - 1, W, r -> {
            int[] counts = new int[REGISTRY.indexUpperBound()];
            short[] touched = new short[9];
            for (int c = 1; c < W - 1; c++) {
                int idx = r * W + c;
                short current = src[idx];
                if (REGISTRY.isHardBoundary(current)) continue;

                int touchedCount = collectBiomeCounts(
                        src, W, r, c, 1, false, counts, touched);
                int same = counts[current];
                short best = current;
                int bestCount = 0;
                for (int candidateIndex = 0; candidateIndex < touchedCount; candidateIndex++) {
                    short candidate = touched[candidateIndex];
                    int count = counts[candidate];
                    if (count > bestCount) {
                        bestCount = count;
                        best = candidate;
                    }
                }

                if (same <= 2 && best != current && bestCount >= 5) {
                    biomes[idx] = best;
                }
                clearBiomeCounts(counts, touched, touchedCount);
            }
        });
    }

    private static void smoothOrganicTransitions(short[] biomes, int H, int W) {
        short[] src = biomes.clone();

        // 3x3 local majority. This rounds off jagged edges and creates
        // less blocky transitions without touching coast/ocean/peak boundaries.
        short[] localSource = src;
        HydrologyParallel.forEachRow(1, H - 1, W, r -> {
            int[] counts = new int[REGISTRY.indexUpperBound()];
            short[] touched = new short[9];
            for (int c = 1; c < W - 1; c++) {
                int idx = r * W + c;
                short current = localSource[idx];
                if (!REGISTRY.isBlendableLandBiome(current)) continue;

                int touchedCount = collectBiomeCounts(
                        localSource, W, r, c, 1, true, counts, touched);
                short best = current;
                int bestCount = 0;
                int currentCount = counts[current];
                for (int candidateIndex = 0; candidateIndex < touchedCount; candidateIndex++) {
                    short candidate = touched[candidateIndex];
                    int count = counts[candidate];
                    if (count > bestCount) {
                        bestCount = count;
                        best = candidate;
                    }
                }

                if (best != current && bestCount >= 6 && currentCount <= 3) {
                    biomes[idx] = best;
                }
                clearBiomeCounts(counts, touched, touchedCount);
            }
        });

        src = biomes.clone();
        short[] broadSource = src;
        HydrologyParallel.forEachRow(2, H - 2, W, r -> {
            int[] counts = new int[REGISTRY.indexUpperBound()];
            short[] touched = new short[25];
            for (int c = 2; c < W - 2; c++) {
                int idx = r * W + c;
                short current = broadSource[idx];
                if (!REGISTRY.isBlendableLandBiome(current)) continue;

                int touchedCount = collectBiomeCounts(
                        broadSource, W, r, c, 2, true, counts, touched);
                short best = current;
                int bestCount = 0;
                int currentCount = counts[current];
                for (int candidateIndex = 0; candidateIndex < touchedCount; candidateIndex++) {
                    short candidate = touched[candidateIndex];
                    int count = counts[candidate];
                    if (count > bestCount) {
                        bestCount = count;
                        best = candidate;
                    }
                }

                if (best != current && bestCount >= 15 && currentCount <= 7) {
                    biomes[idx] = best;
                }
                clearBiomeCounts(counts, touched, touchedCount);
            }
        });
    }

    private static int collectBiomeCounts(short[] source, int width, int row, int col, int radius,
                                          boolean blendableOnly, int[] counts, short[] touched) {
        int touchedCount = 0;
        for (int dr = -radius; dr <= radius; dr++) {
            for (int dc = -radius; dc <= radius; dc++) {
                short candidate = source[(row + dr) * width + col + dc];
                if (blendableOnly ? !REGISTRY.isBlendableLandBiome(candidate) : REGISTRY.isHardBoundary(candidate)) {
                    continue;
                }
                if (counts[candidate]++ == 0) touched[touchedCount++] = candidate;
            }
        }
        return touchedCount;
    }

    private static void clearBiomeCounts(int[] counts, short[] touched, int touchedCount) {
        for (int index = 0; index < touchedCount; index++) counts[touched[index]] = 0;
    }

    private static short classifyPixel(float elevation, float[] climate, int H, int W, int idx,
                                        float tempNoise, float precipNoiseFactor, float snowNoise,
                                        float variantNoise, float cherryNoise, float paleNoise, float clearingNoise, float flowerNoise, float regionNoise, float japanRegion, float slope, boolean coastline,
                                        float worldX, float worldZ) {
        float temp = climate[idx] + tempNoise;
        float tSeason = climate[H * W + idx];
        float precip = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFactor;
        float pCV = climate[3 * H * W + idx];

        TerrainClimateSample sample = deriveSample(elevation, temp, tSeason, precip, pCV,
                snowNoise, variantNoise, slope);
        TerrainBiomeNoiseSample noiseValues = new TerrainBiomeNoiseSample(
                variantNoise, cherryNoise, paleNoise, clearingNoise, flowerNoise, regionNoise, japanRegion);
        return selectBiome(sample, noiseValues, coastline, worldX, worldZ);
    }

    /**
     * The pure derivation {@code classifyPixel} performs between raw climate values and the
     * {@link TerrainClimateSample} the rule engine sees: PET/moisture chain, tree buckets,
     * slope adjustments, snow test, zone flags. Extracted so {@link #probeCoarsePixel} can run
     * the identical logic on hypothetical (coarse-map) pixels.
     */
    private static TerrainClimateSample deriveSample(float elevation, float temp, float tSeason,
                                                      float precip, float pCV, float snowNoise,
                                                      float variantNoise, float slope) {
        float altM = Math.max(0f, elevation);

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
            // Slopes just past the bare threshold in moist/temperate climates sometimes keep
            // clinging scrub or trees instead of going fully barren, so mountainsides aren't
            // uniformly rock. Gated by variantNoise for spatially coherent patches (not pixel
            // jitter) and capped well short of true near-vertical terrain.
            boolean staysVegetated = slope < bareThreshold + 0.35f && treeMoisture > 0.15f
                    && variantNoise > 0.3f;
            if (staysVegetated) {
                slopeBare = false;
                if (treesForest || treesDense || treesRainforest) treesSparse = true;
            } else {
                treesNone = true;
                treesSparse = false;
                treesForest = false;
                treesDense = false;
                treesRainforest = false;
            }
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

        // Steep faces normally shed snow, but applying that unconditionally made snow and
        // steepness mutually exclusive -- which silently made the two biomes defined as *both*
        // (frozen_peaks and jagged_peaks, whose rules are `bareSlope && snowy`) essentially
        // unreachable, since bareSlope needs slope >= 0.7 and snow needed slope <= 0.78. High
        // alpine terrain holds snow on steep ground in reality, so the shedding rule now only
        // applies below the snow line.
        boolean shedsSnow = isSteep && altM <= 1500f;

        boolean hasSnow = (snowTemp < -2f || (altM > 800f && snowTemp < -0.5f))
                && precip > 150f && !shedsSnow;

        boolean isOcean = elevation < 0f;
        boolean mountains = altM > 2500f;
        boolean lowland = altM < 200f;

        // elevationM carries the SIGNED elevation, so ocean-zone rules can discriminate on real
        // seafloor depth (the deep_* ocean biomes gate on `elevationM < -250`). altM stays clamped
        // for the derived flags below, which are all land concepts. Land pixels are unaffected:
        // every non-ocean zone is only reached when elevation >= 0, where the two are identical.
        return new TerrainClimateSample(elevation, temp, tSeason, precip, pCV,
                treeMoisture, aridity, treeMoisture, treeCoverage, sparsity, slope, growingSeason,
                isOcean, hasSnow, slopeBare, mountains, lowland);
    }

    /** The zone {@code classifyPixel} dispatches this sample to (before the bareSlope pass). */
    public static String zoneFor(TerrainClimateSample sample, boolean coastline) {
        if (sample.ocean()) return "ocean";
        if (coastline && !sample.mountain()) return "beach";
        if (sample.mountain()) return "mountain";
        return "lowland";
    }

    private static short selectBiome(TerrainClimateSample sample, TerrainBiomeNoiseSample noiseValues,
                                      boolean coastline, float worldX, float worldZ) {
        short defaultIndex = REGISTRY.defaultBiomeIndex();
        short biome = ENGINE.select(zoneFor(sample, coastline), sample, noiseValues,
                defaultIndex, worldX, worldZ);

        if (sample.bareSlope() && !sample.ocean() && !sample.mountain()) {
            biome = ENGINE.select("bareSlope", sample, noiseValues, biome, worldX, worldZ);
        }

        return biome;
    }

    /** Result of {@link #probeCoarsePixel}: the winning biome plus everything the rule engine
     *  saw, so callers can also test individual biomes' rules against the same pixel. */
    public record CoarseProbe(short winner, TerrainClimateSample sample,
                               TerrainBiomeNoiseSample noise) {}

    /**
     * Classifies a hypothetical pixel described by coarse-map climate values, for the explorer's
     * exact-spawn overlay. Runs the identical derivation and rule-engine selection as
     * {@code classifyPixel}, sampling every fixed-seed noise field (temperature/precipitation
     * jitter, snow, the variant/cherry/pale/clearing/flower gates and the large-wavelength region
     * field) at the given world-block coordinate -- these fields are deterministic in world space,
     * so noise-gated rules (e.g. the region provinces) resolve to their true spatial answer
     * instead of being unknowable the way they are for interval-based candidate filters.
     *
     * <p>What it cannot reproduce from coarse data: the sub-cell elevation/temperature detail,
     * real slopes, and the coastline test ({@code coastline} is always false, so beach-zone rules
     * never match). Pass a representative {@code slope} for the microsite being asked about.</p>
     */
    public static CoarseProbe probeCoarsePixel(float elevation, float baseTemp, float tSeason,
                                                float basePrecip, float pCV, float slope,
                                                float blockX, float blockZ) {
        float tnc = TEMP_NOISE.GetNoise(blockX, blockZ);
        float tnf = TEMP_NOISE_FINE.GetNoise(blockX, blockZ);
        float temp = baseTemp + 0.4f * tnc + 0.2f * tnf;

        float precip = Math.max(0f, basePrecip) * (1.0f + 0.2f * PRECIP_NOISE.GetNoise(blockX, blockZ));

        float snc = SNOW_NOISE.GetNoise(blockX, blockZ);
        float snf = SNOW_NOISE_FINE.GetNoise(blockX, blockZ);
        float snowNoise = 3.0f * snc + 2.0f * snf;

        float variantNoise = BIOME_VARIANT_NOISE.GetNoise(blockX, blockZ);
        TerrainClimateSample sample = deriveSample(elevation, temp, tSeason, precip, pCV,
                snowNoise, variantNoise, slope);
        TerrainBiomeNoiseSample noise = new TerrainBiomeNoiseSample(
                variantNoise,
                CHERRY_GROVE_NOISE.GetNoise(blockX, blockZ),
                PALE_GARDEN_NOISE.GetNoise(blockX, blockZ),
                FOREST_CLEARING_NOISE.GetNoise(blockX, blockZ),
                FLOWER_PATCH_NOISE.GetNoise(blockX, blockZ),
                sampleRegionNoise(blockX, blockZ),
                sampleJapanRegion(blockX, blockZ));

        short winner = selectBiome(sample, noise, false, blockX, blockZ);
        return new CoarseProbe(winner, sample, noise);
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
        HydrologyParallel.forEachRow(0, H, W, r -> {
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
        });
        return slope;
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
