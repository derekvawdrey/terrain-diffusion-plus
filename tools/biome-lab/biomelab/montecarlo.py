"""Runs the full Monte Carlo pipeline: climate.simulate() -> zone assignment -> engine.RuleEngine
per zone (mirroring BiomeClassifier.classifyPixel's own zone branching + bareSlope re-selection
pass exactly) -> area fractions / diversity metrics / collision rates / encounterability /
per-biome reachable climate ranges.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from .catalog import Catalog
from .climate import ClimateSamples
from .engine import RuleEngine

# Per-field spatial wavelength in blocks, from BiomeClassifier.java's static initializer. Used to
# turn an area fraction into an "expected blocks of exploration before encountering this biome"
# style estimate for the encounterability report.
NOISE_FIELD_WAVELENGTH = {
    "variantNoise": 650.0,
    "cherryNoise": 320.0,
    "paleNoise": 280.0,
    "clearingNoise": 260.0,
    "flowerNoise": 220.0,
}
COMPETITION_NOISE_WAVELENGTH = 900.0


def assign_zones(samples: ClimateSamples) -> np.ndarray:
    """Exact port of the zone branching in BiomeClassifier.classifyPixel (ocean > beach > mountain
    > lowland; bareSlope is a separate re-selection pass handled by the caller, not a base zone).
    """
    is_mountain = samples.mountain
    is_beach = samples.beachBand & (~is_mountain)
    zone = np.where(is_mountain, "mountain", "lowland")
    zone = np.where(is_beach, "beach", zone)
    zone = np.where(samples.ocean, "ocean", zone)
    return zone


def _getter(samples: ClimateSamples, mask: np.ndarray):
    cache = {}

    def get(var):
        if var not in cache:
            cache[var] = getattr(samples, var)[mask]
        return cache[var]

    return get


@dataclass
class ClassificationResult:
    samples: ClimateSamples
    zone: np.ndarray  # per-sample base zone string
    biome_index: np.ndarray  # final winning biome index per sample
    candidate_counts: np.ndarray  # 1, 2, or 3 -- how many distinct candidates were eligible
    # (settlement_index_a, settlement_index_b) -> collision count, a<b, counted whenever both
    # were eligible candidates for the same pixel regardless of which one actually won
    collisions: dict = field(default_factory=dict)


def classify(catalog: Catalog, samples: ClimateSamples) -> ClassificationResult:
    engine = RuleEngine(catalog)
    n = samples.n
    zone = assign_zones(samples)
    biome_index = np.full(n, catalog.default_index(), dtype=np.int64)
    candidate_counts = np.ones(n, dtype=np.int8)
    collisions: dict[tuple[int, int], int] = {}

    for zname in ("ocean", "beach", "mountain", "lowland"):
        mask = zone == zname
        if not np.any(mask):
            continue
        get = _getter(samples, mask)
        winner, c0, c1, c2 = engine.select_debug(zname, get, int(mask.sum()), catalog.default_index())
        biome_index[mask] = winner
        _record_candidates(candidate_counts, mask, c0, c1, c2, collisions)

    # bareSlope re-selection pass: applies on top of whatever the primary zone selected, for any
    # pixel with bareSlope=True that isn't ocean or mountain (matches classifyPixel exactly).
    bare_mask = samples.bareSlope & (~samples.ocean) & (~samples.mountain)
    if np.any(bare_mask):
        get = _getter(samples, bare_mask)
        default_sub = biome_index[bare_mask]
        winner, c0, c1, c2 = engine.select_debug("bareSlope", get, int(bare_mask.sum()), default_sub)
        biome_index[bare_mask] = winner
        _record_candidates(candidate_counts, bare_mask, c0, c1, c2, collisions)

    return ClassificationResult(samples=samples, zone=zone, biome_index=biome_index,
                                 candidate_counts=candidate_counts, collisions=collisions)


def _record_candidates(candidate_counts, mask, c0, c1, c2, collisions: dict):
    idx = np.nonzero(mask)[0]
    n_cand = np.ones(len(idx), dtype=np.int8)
    n_cand[c1 >= 0] = 2
    n_cand[c2 >= 0] = 3
    candidate_counts[idx] = np.maximum(candidate_counts[idx], n_cand)

    for a, b in ((c0, c1), (c0, c2), (c1, c2)):
        both = (a >= 0) & (b >= 0)
        if not np.any(both):
            continue
        pairs = np.stack([a[both], b[both]], axis=1)
        pairs.sort(axis=1)
        uniq, counts = np.unique(pairs, axis=0, return_counts=True)
        for (x, y), cnt in zip(uniq, counts):
            key = (int(x), int(y))
            collisions[key] = collisions.get(key, 0) + int(cnt)


@dataclass
class AreaFractions:
    overall: dict  # biome_index -> fraction of all samples
    by_zone: dict  # zone -> {biome_index -> fraction of samples in that zone}


def area_fractions(result: ClassificationResult) -> AreaFractions:
    n = result.samples.n
    overall = _fractions(result.biome_index, n)
    by_zone = {}
    for zname in ("ocean", "beach", "mountain", "lowland"):
        mask = result.zone == zname
        cnt = int(mask.sum())
        if cnt == 0:
            by_zone[zname] = {}
            continue
        by_zone[zname] = _fractions(result.biome_index[mask], cnt)
    bare_mask = result.samples.bareSlope & (~result.samples.ocean) & (~result.samples.mountain)
    cnt = int(bare_mask.sum())
    by_zone["bareSlope"] = _fractions(result.biome_index[bare_mask], cnt) if cnt else {}
    return AreaFractions(overall=overall, by_zone=by_zone)


def _fractions(arr: np.ndarray, total: int) -> dict:
    if total == 0:
        return {}
    uniq, counts = np.unique(arr, return_counts=True)
    return {int(u): float(c) / total for u, c in zip(uniq, counts)}


@dataclass
class DiversityMetrics:
    effective_number_of_biomes: float  # exp(Shannon entropy)
    shannon_entropy: float
    hhi: float  # Herfindahl-Hirschman concentration index, sum(p_i^2)
    n_biomes_present: int


def diversity_metrics(fractions: dict) -> DiversityMetrics:
    ps = np.array(list(fractions.values()), dtype=np.float64)
    ps = ps[ps > 0]
    if len(ps) == 0:
        return DiversityMetrics(0.0, 0.0, 0.0, 0)
    entropy = float(-(ps * np.log(ps)).sum())
    return DiversityMetrics(
        effective_number_of_biomes=math.exp(entropy),
        shannon_entropy=entropy,
        hhi=float((ps * ps).sum()),
        n_biomes_present=len(ps),
    )


@dataclass
class EncounterabilityRow:
    biome_index: int
    biome_key: str
    area_fraction: float
    dominant_field: str | None  # which noiseCondition field (if any) most directly gates this biome
    wavelength_blocks: float | None
    expected_blocks_estimate: float | None  # very rough "sqrt(1/area_fraction) * wavelength" heuristic
    below_min_bar: bool


def encounterability(catalog: Catalog, fractions: dict, min_area_fraction: float) -> list[EncounterabilityRow]:
    """Turns an area fraction into a rough "how far do you need to explore before you're likely to
    see this" estimate. This is necessarily a very rough heuristic: real encounter distance
    depends on 2D spatial clustering (governed by whichever noise field's wavelength gates the
    biome most tightly), not just the marginal area fraction. We approximate expected distance
    as wavelength / sqrt(area_fraction) -- if a biome covers fraction p of the map and is
    clustered at wavelength L, patches are roughly L wide and spaced roughly L/sqrt(p) apart.
    """
    rows = []
    for settlement in catalog.settlements:
        frac = fractions.get(settlement.index, 0.0)
        dominant_field = _dominant_noise_field(settlement)
        wavelength = NOISE_FIELD_WAVELENGTH.get(dominant_field) if dominant_field else None
        if wavelength is None:
            wavelength = COMPETITION_NOISE_WAVELENGTH
        estimate = None
        if frac > 0:
            estimate = wavelength / math.sqrt(frac)
        rows.append(EncounterabilityRow(
            biome_index=settlement.index, biome_key=settlement.key, area_fraction=frac,
            dominant_field=dominant_field, wavelength_blocks=wavelength,
            expected_blocks_estimate=estimate,
            below_min_bar=(frac < min_area_fraction),
        ))
    rows.sort(key=lambda r: r.area_fraction)
    return rows


def _dominant_noise_field(settlement) -> str | None:
    """Heuristic: the noise field referenced by the highest-priority rule's noiseConditions, if
    any. Used only to pick a wavelength for the encounterability estimate.
    """
    best_rule = None
    for rule in settlement.rules:
        if not rule.noise_conditions:
            continue
        if best_rule is None or rule.priority > best_rule.priority:
            best_rule = rule
    if best_rule is None:
        return None
    return best_rule.noise_conditions[0].variable


@dataclass
class ClimateRangeRow:
    biome_index: int
    biome_key: str
    n_samples: int
    ranges: dict  # variable -> (p5, p50, p95)
    snowy_fraction: float


NUMERIC_RANGE_VARS = (
    "temperatureC", "precipitationMm", "moisture", "elevationM", "growingSeasonDays", "slope",
)


def climate_ranges(catalog: Catalog, result: ClassificationResult) -> list[ClimateRangeRow]:
    rows = []
    s = result.samples
    for settlement in catalog.settlements:
        mask = result.biome_index == settlement.index
        cnt = int(mask.sum())
        if cnt == 0:
            rows.append(ClimateRangeRow(settlement.index, settlement.key, 0, {}, 0.0))
            continue
        ranges = {}
        for var in NUMERIC_RANGE_VARS:
            arr = getattr(s, var)[mask]
            ranges[var] = (float(np.percentile(arr, 5)), float(np.percentile(arr, 50)), float(np.percentile(arr, 95)))
        snowy_frac = float(s.snowy[mask].mean())
        rows.append(ClimateRangeRow(settlement.index, settlement.key, cnt, ranges, snowy_frac))
    return rows


@dataclass
class BottleneckRow:
    settlement: object  # Settlement, kept for suggest_widen_fixes (needs .index and rule identity)
    rule: object  # Rule, kept so callers can re-locate this exact rule
    biome_key: str
    zone: str
    priority: int
    joint_pass_rate: float
    condition_pass_rates: list  # [(condition_desc, pass_rate), ...] sorted ascending (tightest first)
    condition_objects: list  # Condition objects, same order as condition_pass_rates
    tightest_condition: str | None


def rule_bottlenecks(catalog: Catalog, samples: ClimateSamples, top_n_tightest_only: bool = True) -> list[BottleneckRow]:
    """For every rule in the catalog: joint pass rate (fraction of Monte Carlo samples satisfying
    ALL of its conditions simultaneously) plus each individual condition's own pass rate, so a
    human/agent can immediately see which single condition is the tightest bottleneck in a
    compounding-narrow-AND-conditions case. This was the single most useful diagnostic from this
    project's throwaway prototype -- kept here deliberately. Also the data source for
    validators.check_low_pass_rate + fixes.suggest_widen_fixes (see fixes.py's module docstring).
    """
    from .engine import _eval_condition  # reuse the exact same evaluator the real engine uses
    n = samples.n
    rows = []
    for settlement, rule in catalog.all_rules():
        all_conds = list(rule.conditions) + list(rule.noise_conditions)
        if not all_conds:
            continue
        individual = []
        joint = np.ones(n, dtype=bool)
        for c in all_conds:
            m = _eval_condition(c, lambda v: getattr(samples, v))
            rate = float(m.mean())
            individual.append((c, c.describe(), rate))
            joint &= m
        joint_rate = float(joint.mean())
        individual.sort(key=lambda t: t[2])
        rows.append(BottleneckRow(
            settlement=settlement, rule=rule,
            biome_key=settlement.key, zone=rule.zone, priority=rule.priority,
            joint_pass_rate=joint_rate,
            condition_pass_rates=[(desc, rate) for _, desc, rate in individual],
            condition_objects=[c for c, _, _ in individual],
            tightest_condition=individual[0][1] if individual else None,
        ))
    return rows
