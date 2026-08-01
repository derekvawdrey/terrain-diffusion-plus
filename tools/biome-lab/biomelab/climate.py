"""Monte Carlo climate/terrain sampler.

Ports the exact derivation chain from BiomeClassifier.classifyPixel (see
common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java) so the
resulting samples can be fed into engine.py's BiomeRuleEngine port for area-fraction / diversity /
encounterability analysis.

Ground-truth inputs
--------------------
Real elevation/temp/precip statistics come from pipeline_data.json (WorldClim/ETOPO
quantile-matched tables, NOT shipped in this repo -- point --pipeline-data at the real file, e.g.
~/.local/share/PrismLauncher/instances/1.21.1/minecraft/terrain-diffusion-models/pipeline_data.json).
`data_quantile_tables` has 5 channels: [elev, temp, temp_std, precip, precip_std], each a 64-point
quantile table at percentiles eps + i*(1-2*eps)/63, eps=1e-4. IMPORTANT: channel 0 is REAL
ELEVATION IN METERS already (median around -2000m since most of Earth is ocean, p90 a few hundred
meters, tail out to several thousand meters) -- it is only sqrt-transformed as the LAST step inside
SyntheticMapFactory's own encoding for feeding the diffusion model, not in the raw table. Sampling
happens via inverse-CDF (draw u ~ Uniform(0,1), interpolate the quantile table at percentile u*100).

*Noise fields (temp/snow/precip noise components, variantNoise, cherryNoise, ...) are sampled the
same way from the empirical FastNoiseLite quantile tables measured by java/NoiseProbe.java (see
noise_data.py) -- again inverse-CDF, not a from-scratch Perlin reimplementation.

Known fidelity gaps (deliberately approximated -- see README.md "Fidelity gaps" section)
------------------------------------------------------------------------------------------
1. **Elevation-temperature lapse rate.** The real pipeline corrects the neural model's raw
   temperature field against *actual* local elevation via a windowed weighted regression
   (LaplacianUtils.localBaselineTemperature), which needs spatial windows an i.i.d. sampler
   doesn't have. We approximate it: draw regionNoise per pixel (same field
   BiomeClassifier.sampleRegionNoise uses), compute the same warm-region-gated betaMax the real
   regression clamps to, and use beta = clip(-0.0065, betaMin=-0.012, betaMax) -- i.e. we always
   use the model's own *fallback* beta (used when there isn't enough spatial variance/land to fit
   a regression), which is the closest single-pixel-safe stand-in for "what would the regression
   have found". This under-represents how *variable* real lapse rates are pixel-to-pixel but gets
   the aggregate warm-region-vs-normal-region skew right.
2. **Slope.** There is no real spatial gradient field for an i.i.d. sampler to differentiate.
   Approximated with a calibrated Gamma(1.2, 0.22) distribution (~8.5% >= 0.62 "medium",
   ~4.2% > 0.78 "steep", roughly matching the real classifier's own thresholds). THIS IS THE
   SINGLE BIGGEST FIDELITY GAP in the whole pipeline -- treat every mountain/bareSlope-zone area
   fraction and every biome that heavily gates on `slope` with proportional skepticism.
3. **Beach/coastline.** isCoastlineCandidate() in BiomeClassifier.java requires specific counts of
   water/land/higher-land neighbours in an 11x11 window -- a real spatial adjacency test with no
   i.i.d. equivalent. Approximated as a flat calibrated probability (BEACH_CANDIDATE_PROBABILITY)
   applied to pixels that pass the *non-spatial* prefilter (0 <= elevationM <= 18, slope <= 0.35).
   Treat beach-zone area fractions as order-of-magnitude estimates only.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from . import noise_data

# ---- Constants ported from LaplacianUtils.java ----
LAPSE_BETA_MIN = -0.012
LAPSE_FALLBACK_BETA = -0.0065
REGION_NOISE_MAX = 0.73
WARM_REGION_THRESHOLD = 0.2
WARM_REGION_BETA_MAX_CAP = 0.008

# ---- Slope calibration (approximation -- see module docstring, gap #2) ----
SLOPE_GAMMA_SHAPE = 1.2
SLOPE_GAMMA_SCALE = 0.22

# ---- Beach/coastline calibration (approximation -- see module docstring, gap #3) ----
BEACH_CANDIDATE_PROBABILITY = 0.15

N_QUANTILES = 64
QUANTILE_EPS = 1e-4


def _quantile_percentiles(n=N_QUANTILES, eps=QUANTILE_EPS) -> np.ndarray:
    i = np.arange(n)
    return (eps + i * (1.0 - 2 * eps) / (n - 1)) * 100.0


@dataclass
class PipelineData:
    elev_table: np.ndarray
    temp_table: np.ndarray
    temp_std_table: np.ndarray
    precip_table: np.ndarray
    precip_std_table: np.ndarray
    pcts: np.ndarray
    a_temp_std: float
    b_temp_std: float
    temp_std_p1: float
    temp_std_p99: float

    def inv_elev(self, u):
        return np.interp(u * 100.0, self.pcts, self.elev_table)

    def inv_temp(self, u):
        return np.interp(u * 100.0, self.pcts, self.temp_table)

    def inv_temp_std(self, u):
        return np.interp(u * 100.0, self.pcts, self.temp_std_table)

    def inv_precip(self, u):
        return np.interp(u * 100.0, self.pcts, self.precip_table)

    def inv_precip_std(self, u):
        return np.interp(u * 100.0, self.pcts, self.precip_std_table)


def load_pipeline_data(path: str | Path) -> PipelineData:
    path = Path(path)
    d = json.loads(path.read_text())
    n = d["n_quantiles"]
    tables = d["data_quantile_tables"]
    elev = np.array(tables[0], dtype=np.float64)
    temp = np.array(tables[1], dtype=np.float64)
    temp_std = np.array(tables[2], dtype=np.float64)
    precip = np.array(tables[3], dtype=np.float64)
    precip_std = np.array(tables[4], dtype=np.float64)

    # Sanity check: channel 0 must be real elevation in meters, not a squared/transformed value.
    # Median should be solidly negative (most of the quantile-matched Earth is ocean) and the
    # tail should be O(1e3) meters, not O(1e6+). This guards against ever repeating the
    # sqrt-then-forgetting-to-un-sqrt mistake made earlier in this project's history.
    median = float(np.interp(50.0, _quantile_percentiles(n), elev))
    p99 = float(np.interp(99.0, _quantile_percentiles(n), elev))
    if median > 0 or abs(median) > 20000 or abs(p99) > 20000:
        raise ValueError(
            f"pipeline_data.json elevation channel looks wrong (median={median:.1f}m, "
            f"p99={p99:.1f}m) -- expected a negative median (~-2000m) and a tail in the "
            f"low thousands of meters. Did something re-square an already-real elevation table?"
        )

    return PipelineData(
        elev_table=elev, temp_table=temp, temp_std_table=temp_std,
        precip_table=precip, precip_std_table=precip_std,
        pcts=_quantile_percentiles(n),
        a_temp_std=float(d["a_temp_std"]), b_temp_std=float(d["b_temp_std"]),
        temp_std_p1=float(d["temp_std_p1"]), temp_std_p99=float(d["temp_std_p99"]),
    )


def _smoothstep(t: np.ndarray) -> np.ndarray:
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def _clip01(v):
    return np.clip(v, 0.0, 1.0)


def warm_region_beta_max(region_noise: np.ndarray) -> np.ndarray:
    """Port of LaplacianUtils.warmRegionBetaMax."""
    n = np.abs(region_noise)
    t = np.clip((n - WARM_REGION_THRESHOLD) / (REGION_NOISE_MAX - WARM_REGION_THRESHOLD), 0.0, 1.0)
    return _smoothstep(t) * WARM_REGION_BETA_MAX_CAP


@dataclass
class ClimateSamples:
    """One row per Monte Carlo sample. Field names match TerrainClimateSample's accessors so
    engine.py can evaluate biome_catalog.json conditions directly against this object's fields.
    """
    n: int
    elevationM: np.ndarray
    temperatureC: np.ndarray
    temperatureSeasonality: np.ndarray
    precipitationMm: np.ndarray
    precipitationCv: np.ndarray
    moisture: np.ndarray
    aridity: np.ndarray
    treeMoisture: np.ndarray
    treeCoverage: np.ndarray
    sparsity: np.ndarray
    slope: np.ndarray
    growingSeasonDays: np.ndarray
    ocean: np.ndarray
    snowy: np.ndarray
    bareSlope: np.ndarray
    mountain: np.ndarray
    lowland: np.ndarray
    # noise fields
    variantNoise: np.ndarray
    cherryNoise: np.ndarray
    paleNoise: np.ndarray
    clearingNoise: np.ndarray
    flowerNoise: np.ndarray
    regionNoise: np.ndarray
    japanRegion: np.ndarray
    # extras useful for reporting/diagnostics/engine resolution, not condition variables themselves
    raw_elevation: np.ndarray  # signed real elevation (same as elevationM had it not been clamped)
    beach_candidate_prefilter: np.ndarray  # non-spatial prefilter before the calibrated probability
    beachBand: np.ndarray  # final approximated coastline flag, used for zone assignment
    worldX: np.ndarray  # synthetic per-sample world position, for BiomeRuleEngine's competitionNoise
    worldZ: np.ndarray

    def numeric(self, name: str) -> np.ndarray:
        return getattr(self, name)


def japan_threshold(share: float, families: dict) -> float:
    """Noise cutoff that puts exactly `share` of the world inside the Japan region.

    Mirrors BiomeClassifier.japanThreshold: invert the field's measured CDF at 1 - share. The
    Java side interpolates a 21-point copy of the same table, so the two agree to within the
    table's resolution.
    """
    family = noise_data.family_for_variable("japanRegion", families)
    if family is None:
        raise KeyError("No noise family mapped for 'japanRegion'")
    if share >= 1.0:
        return float(family.min)
    if share <= 0.0:
        return float(family.max)
    return float(family.inverse_cdf(np.array([1.0 - share]))[0])


def simulate(pipeline: PipelineData, families: dict, n: int, seed: int = 0,
             japan_share: float = 1.0) -> ClimateSamples:
    rng = np.random.default_rng(seed)

    def fam(name):
        f = noise_data.family_for_variable(name, families)
        if f is None:
            raise KeyError(f"No noise family mapped for '{name}'")
        return f

    # ---- 1. Real elevation/temp/precip draws from the quantile-matched WorldClim/ETOPO tables ----
    elev_raw = pipeline.inv_elev(rng.uniform(0, 1, n))
    temp_raw = pipeline.inv_temp(rng.uniform(0, 1, n))
    temp_std_raw = pipeline.inv_temp_std(rng.uniform(0, 1, n))
    precip_raw = pipeline.inv_precip(rng.uniform(0, 1, n))
    precip_std_raw = pipeline.inv_precip_std(rng.uniform(0, 1, n))

    altM = np.maximum(0.0, elev_raw)

    # ---- 2. Lapse-rate correction (approximation -- see module docstring, gap #1) ----
    region_noise = fam("regionNoise").sample(rng, n)
    beta_max = warm_region_beta_max(region_noise)
    beta = np.clip(LAPSE_FALLBACK_BETA, LAPSE_BETA_MIN, beta_max)
    # np.clip(scalar_within_range, lo, hi_array) broadcasts; fallback beta (-0.0065) always sits
    # inside [betaMin, 0] so this only matters at all when beta_max rises into warm regions,
    # otherwise it's just the fallback constant.
    temp_lapse = temp_raw + beta * altM
    temp_lapse = np.clip(temp_lapse, -10.0, 40.0)

    # ---- 3. temp_std correlation-correction, exact port of SyntheticMapFactory.sample() ----
    baseline = pipeline.a_temp_std * temp_lapse + pipeline.b_temp_std
    denom = (pipeline.temp_std_p99 - pipeline.temp_std_p1)
    t01 = (temp_std_raw - pipeline.temp_std_p1) / denom
    baseline_clipped = np.maximum(pipeline.temp_std_p1, -baseline)
    temp_std = t01 * (pipeline.temp_std_p99 - baseline_clipped) + baseline_clipped + baseline
    temp_std = np.maximum(temp_std, 20.0)

    # ---- 4. precip_std correction, exact port of SyntheticMapFactory.sample() ----
    precip_std = precip_std_raw * np.maximum(0.0, (185.0 - 0.04111 * precip_raw) / 185.0)

    # ---- 5. Per-pixel noise perturbations, exact port of BiomeClassifier.classify()'s inner loop ----
    temp_noise_coarse = fam("tempNoiseCoarse").sample(rng, n)
    temp_noise_fine = fam("tempNoiseFine").sample(rng, n)
    temp_noise = 0.4 * temp_noise_coarse + 0.2 * temp_noise_fine

    precip_noise_raw = fam("precipNoise").sample(rng, n)
    precip_noise_factor = 1.0 + 0.2 * precip_noise_raw

    snow_noise_coarse = fam("snowNoiseCoarse").sample(rng, n)
    snow_noise_fine = fam("snowNoiseFine").sample(rng, n)
    snow_noise = 3.0 * snow_noise_coarse + 2.0 * snow_noise_fine

    variant_noise = fam("variantNoise").sample(rng, n)
    cherry_noise = fam("cherryNoise").sample(rng, n)
    pale_noise = fam("paleNoise").sample(rng, n)
    clearing_noise = fam("clearingNoise").sample(rng, n)
    flower_noise = fam("flowerNoise").sample(rng, n)

    # japanRegion is a *margin*, not a raw field: BiomeClassifier.sampleJapanRegion returns
    # noise - threshold, so a rule can just ask for "japanRegion gte 0" and the region resizes
    # with the biome.japan_region_share config instead of every rule needing a new threshold.
    # Drawn independently of regionNoise because the Japan region is an overlay on the A/B/C
    # provinces rather than a fourth one.
    japan_region = fam("japanRegion").sample(rng, n) - japan_threshold(japan_share, families)

    # ---- 6. classifyPixel's exact derivation chain ----
    temp = temp_lapse + temp_noise
    tSeason = temp_std  # no extra noise added at classify time (matches Java)
    precip = np.maximum(0.0, precip_raw) * precip_noise_factor
    pCV = precip_std  # no extra noise added at classify time (matches Java)

    tStd = tSeason / 100.0
    tEff = np.maximum(0.0, temp + 0.5 * tStd)
    pet = np.maximum(250.0, 250.0 + 25.0 * tEff + 0.7 * tEff * tEff)
    aridity = precip / np.maximum(1.0, pet)
    seasonPenalty = 1.0 - 0.35 * _clip01(pCV / 100.0)
    treeMoisture = aridity * seasonPenalty

    amplitude = tStd * 1.414
    x = np.divide(5.0 - temp, amplitude, out=np.full_like(temp, np.inf), where=amplitude >= 0.1)
    growing_from_asin = 365.0 * (0.5 - np.arcsin(np.clip(x, -1.0, 1.0)) / math.pi)
    growingSeason = np.where(
        amplitude < 0.1,
        np.where(temp > 5.0, 365.0, 0.0),
        np.where(x <= -1.0, 365.0, np.where(x >= 1.0, 0.0, growing_from_asin)),
    )

    gsFactor = _clip01((growingSeason - 60.0) / (150.0 - 60.0))
    effTreeMoisture = treeMoisture * gsFactor

    moistureFactor = _clip01((treeMoisture - 0.35) / 0.45)
    bareThreshold = 0.7 + (1.19 - 0.7) * moistureFactor

    treesNone = effTreeMoisture < 0.2
    treesSparse = (~treesNone) & (effTreeMoisture < 0.5)
    treesForest = (~treesNone) & (effTreeMoisture >= 0.5) & (effTreeMoisture < 0.8)
    treesDense = (~treesNone) & (effTreeMoisture >= 0.8) & (effTreeMoisture < 1.3)
    treesRainforest = (~treesNone) & (effTreeMoisture >= 1.3)

    # ---- 7. Slope (approximation -- see module docstring, gap #2) ----
    slope = rng.gamma(SLOPE_GAMMA_SHAPE, SLOPE_GAMMA_SCALE, n)

    slopeMedium = (slope >= 0.62) & (slope < bareThreshold)
    slopeBare = slope >= bareThreshold

    any_tree = treesForest | treesDense | treesRainforest
    treesSparse = np.where(slopeMedium & any_tree, True, treesSparse)
    treesForest = np.where(slopeMedium, False, treesForest)
    treesDense = np.where(slopeMedium, False, treesDense)
    treesRainforest = np.where(slopeMedium, False, treesRainforest)

    staysVegetated = slopeBare & (slope < bareThreshold + 0.35) & (treeMoisture > 0.15) & (variant_noise > 0.3)
    any_tree2 = treesForest | treesDense | treesRainforest
    treesSparse = np.where(staysVegetated & any_tree2, True, treesSparse)
    slopeBare = np.where(staysVegetated, False, slopeBare)

    # goBare applies to pixels that were originally slopeBare and did NOT stay vegetated.
    goBare = (slope >= bareThreshold) & (~staysVegetated)
    treesNone = np.where(goBare, True, treesNone)
    treesSparse = np.where(goBare, False, treesSparse)
    treesForest = np.where(goBare, False, treesForest)
    treesDense = np.where(goBare, False, treesDense)
    treesRainforest = np.where(goBare, False, treesRainforest)

    treeCoverage = np.select(
        [treesRainforest, treesDense, treesForest, treesSparse],
        [1.00, 0.85, 0.62, 0.35],
        default=0.00,
    )
    sparsity = _clip01(1.0 - treeCoverage)

    snowTemp = temp + snow_noise
    isSteep = slope > 0.78
    # Snow only sheds off steep ground below the snow line -- see BiomeClassifier.classifyPixel.
    shedsSnow = isSteep & (altM <= 1500.0)
    hasSnow = ((snowTemp < -2.0) | ((altM > 800.0) & (snowTemp < -0.5))) & (precip > 150.0) & (~shedsSnow)

    isOcean = elev_raw < 0.0
    mountains = altM > 2500.0
    lowland = altM < 200.0

    # ---- 8. Beach/coastline (approximation -- see module docstring, gap #3) ----
    beach_prefilter = (elev_raw >= 0.0) & (elev_raw <= 18.0) & (slope <= 0.35)
    beachBand = beach_prefilter & (rng.uniform(0, 1, n) < BEACH_CANDIDATE_PROBABILITY)

    # Synthetic world position for BiomeRuleEngine's per-pixel competitionNoise resolution.
    # Range is arbitrary (competitionNoise's marginal distribution doesn't depend on it, same
    # reasoning as noise frequency not mattering) -- +-1,000,000 blocks is a generous stand-in for
    # "somewhere in a large generated world".
    worldX = rng.uniform(-1_000_000.0, 1_000_000.0, n)
    worldZ = rng.uniform(-1_000_000.0, 1_000_000.0, n)

    return ClimateSamples(
        n=n,
        # Signed, matching BiomeClassifier.classifyPixel: negative over ocean so the deep_* ocean
        # rules can gate on real seafloor depth. altM (the zero-clamped copy) still drives the
        # mountain/lowland/snowy flags below.
        elevationM=elev_raw,
        temperatureC=temp,
        temperatureSeasonality=tSeason,
        precipitationMm=precip,
        precipitationCv=pCV,
        moisture=treeMoisture,
        aridity=aridity,
        treeMoisture=treeMoisture,
        treeCoverage=treeCoverage,
        sparsity=sparsity,
        slope=slope,
        growingSeasonDays=growingSeason,
        ocean=isOcean,
        snowy=hasSnow,
        bareSlope=slopeBare,
        mountain=mountains,
        lowland=lowland,
        variantNoise=variant_noise,
        cherryNoise=cherry_noise,
        paleNoise=pale_noise,
        clearingNoise=clearing_noise,
        flowerNoise=flower_noise,
        regionNoise=region_noise,
        japanRegion=japan_region,
        raw_elevation=elev_raw,
        beach_candidate_prefilter=beach_prefilter,
        beachBand=beachBand,
        worldX=worldX,
        worldZ=worldZ,
    )
