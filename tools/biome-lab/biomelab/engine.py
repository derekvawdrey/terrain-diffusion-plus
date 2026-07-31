"""Vectorized port of BiomeRuleEngine.java: tiered priority selection + competition-noise
resolution among up to three eligible candidates. See that file's class docstring for the full
rationale; this module mirrors its `select()` method exactly, just batched over numpy arrays
instead of one pixel at a time.
"""
from __future__ import annotations

import numpy as np

from .catalog import Catalog, Condition

COMPETITION_RANK_PENALTY = 0.8
COMPETITION_NOISE_WAVELENGTH = 900.0

_U64 = np.uint64
_MASK64 = np.uint64(0xFFFFFFFFFFFFFFFF)


def _to_u64(arr_int64: np.ndarray) -> np.ndarray:
    return arr_int64.astype(np.int64).astype(np.uint64)


def hash_unit(seed: int, x: np.ndarray, y: np.ndarray) -> np.ndarray:
    """Bit-exact port of BiomeRuleEngine.hashUnit. Java `long` arithmetic is two's-complement
    64-bit and wraps silently on overflow; numpy's uint64 dtype wraps identically (mod 2**64),
    and unsigned right shift (`>>>`) is just `>>` on an unsigned type -- so as long as we do the
    multiply/xor/shift chain entirely in uint64 (after sign-extending x/y from int64, matching
    Java's int -> long widening), the bit pattern matches Java exactly.
    """
    xu = _to_u64(x)
    yu = _to_u64(y)
    seedu = np.uint64(seed & 0xFFFFFFFFFFFFFFFF)
    value = seedu ^ (xu * np.uint64(0x9E3779B97F4A7C15)) ^ (yu * np.uint64(0xC2B2AE3D27D4EB4F))
    value ^= value >> np.uint64(30)
    value *= np.uint64(0xBF58476D1CE4E5B9)
    value ^= value >> np.uint64(27)
    value *= np.uint64(0x94D049BB133111EB)
    value ^= value >> np.uint64(31)
    top = (value >> np.uint64(40)).astype(np.float64)
    return (top / float(1 << 24)) * 2.0 - 1.0


def _fast_floor(v: np.ndarray) -> np.ndarray:
    return np.floor(v).astype(np.int64)


def _smoothstep(v: np.ndarray) -> np.ndarray:
    return v * v * (3.0 - 2.0 * v)


def value_noise(seed: int, x: np.ndarray, y: np.ndarray) -> np.ndarray:
    x0 = _fast_floor(x)
    y0 = _fast_floor(y)
    fx = _smoothstep(x - x0)
    fy = _smoothstep(y - y0)
    a = hash_unit(seed, x0, y0)
    b = hash_unit(seed, x0 + 1, y0)
    c = hash_unit(seed, x0, y0 + 1)
    d = hash_unit(seed, x0 + 1, y0 + 1)
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy


def competition_noise(biome_index: int, world_x: np.ndarray, world_z: np.ndarray) -> np.ndarray:
    return value_noise(biome_index, world_x / COMPETITION_NOISE_WAVELENGTH, world_z / COMPETITION_NOISE_WAVELENGTH)


def _eval_condition(cond: Condition, get) -> np.ndarray:
    if cond.bool_value is not None:
        actual = get(cond.variable)
        return actual == bool(cond.bool_value)
    actual = get(cond.variable)
    op = cond.op
    if op == "eq":
        return actual == cond.value
    if op == "gt":
        return actual > cond.value
    if op == "gte":
        return actual >= cond.value
    if op == "lt":
        return actual < cond.value
    if op == "lte":
        return actual <= cond.value
    if op == "between":
        return (actual >= cond.value) & (actual <= cond.value2)
    raise ValueError(f"Unknown operator {op}")


def _eval_rule(conditions, noise_conditions, get) -> np.ndarray:
    mask = None
    for c in list(conditions) + list(noise_conditions):
        m = _eval_condition(c, get)
        mask = m if mask is None else (mask & m)
    if mask is None:
        return None  # no conditions at all -> matches everything; caller must handle length
    return mask


class RuleEngine:
    """Pre-groups every settlement's rules by zone then by priority tier (descending), exactly
    like BiomeRuleEngine.init(). `select()` evaluates one zone's tiers against a batch of samples.
    """

    def __init__(self, catalog: Catalog):
        self.catalog = catalog
        self.zone_tiers: dict[str, list[list[tuple[int, "list", "list"]]]] = {}
        zone_priority_groups: dict[str, dict[int, list]] = {}
        for settlement, rule in catalog.all_rules():
            zone_priority_groups.setdefault(rule.zone, {}).setdefault(rule.priority, []).append(
                (settlement.index, rule.conditions, rule.noise_conditions)
            )
        for zone, prio_map in zone_priority_groups.items():
            tiers = [prio_map[p] for p in sorted(prio_map.keys(), reverse=True)]
            self.zone_tiers[zone] = tiers

    def select(self, zone: str, get, n: int, default_index) -> np.ndarray:
        """Convenience wrapper around select_debug() that returns only the winning index."""
        return self.select_debug(zone, get, n, default_index)[0]

    def select_debug(self, zone: str, get, n: int, default_index):
        """
        zone: one of "ocean"/"beach"/"mountain"/"lowland"/"bareSlope"
        get: callable(variable_name) -> np.ndarray of length n (climate or noise field)
        n: number of samples in this batch
        default_index: scalar int, or np.ndarray of length n (per-Java: the caller's own current
            biome, used as the "no rule matched" fallback for the bareSlope re-selection pass)
        Returns: (winner, candidate0, candidate1, candidate2), each np.ndarray[int64] of length n.
            candidate1/candidate2 are -1 where no runner-up was found -- useful for the Monte
            Carlo evaluator's cross-tier collision-rate diagnostic (how often does a pixel have
            more than one genuinely eligible biome for its zone?).
        """
        tiers = self.zone_tiers.get(zone)
        default_arr = np.full(n, default_index, dtype=np.int64) if np.isscalar(default_index) else np.asarray(default_index, dtype=np.int64)
        if not tiers:
            empty = np.full(n, -1, dtype=np.int64)
            return default_arr.copy(), empty, empty.copy(), empty.copy()

        candidate0 = np.full(n, -1, dtype=np.int64)
        candidate1 = np.full(n, -1, dtype=np.int64)
        candidate2 = np.full(n, -1, dtype=np.int64)

        for tier in tiers:
            best_index = np.full(n, -1, dtype=np.int64)
            for index, conditions, noise_conditions in tier:
                mask = _eval_rule(conditions, noise_conditions, get)
                if mask is None:
                    mask = np.ones(n, dtype=bool)
                # Equivalent to Java's "if (entry.index <= bestIndex) skip; else if matches,
                # bestIndex = entry.index": final result is the max index among matching entries.
                candidate_index = np.where(mask, index, -1)
                best_index = np.maximum(best_index, candidate_index)

            valid = best_index >= 0
            set0 = valid & (candidate0 < 0)
            candidate0[set0] = best_index[set0]

            set1 = valid & (candidate0 >= 0) & (best_index != candidate0) & (candidate1 < 0)
            candidate1[set1] = best_index[set1]

            set2 = valid & (candidate0 >= 0) & (candidate1 >= 0) & (best_index != candidate0) & (best_index != candidate1) & (candidate2 < 0)
            candidate2[set2] = best_index[set2]

        winner = candidate0.copy()
        have0 = candidate0 >= 0

        world_x = get("worldX")
        world_z = get("worldZ")

        # Only compute competitionNoise where actually needed (candidate1 present) to avoid
        # calling it with an undefined biome index (-1) unnecessarily, though it would be masked
        # out anyway.
        have1 = candidate1 >= 0
        if np.any(have1):
            best_score = np.where(have0, _score_for(candidate0, world_x, world_z, 0), -np.inf)
            score1 = np.where(have1, _score_for(candidate1, world_x, world_z, 1), -np.inf)
            upd1 = have1 & (score1 > best_score)
            winner = np.where(upd1, candidate1, winner)
            best_score = np.where(upd1, score1, best_score)

            have2 = candidate2 >= 0
            if np.any(have2):
                score2 = np.where(have2, _score_for(candidate2, world_x, world_z, 2), -np.inf)
                upd2 = have2 & (score2 > best_score)
                winner = np.where(upd2, candidate2, winner)

        result = np.where(have0, winner, default_arr)
        return result.astype(np.int64), candidate0, candidate1, candidate2


def _score_for(candidate_idx: np.ndarray, world_x: np.ndarray, world_z: np.ndarray, rank: int) -> np.ndarray:
    """competitionNoise(candidate) - rank*PENALTY, but candidate_idx varies per-sample so we can't
    call competition_noise (which expects one scalar biome index) directly -- group by distinct
    index values present in this batch instead. In practice the number of distinct biome indices
    that ever appear as an Nth candidate in one batch is small (bounded by catalog size), so this
    stays cheap.
    """
    out = np.zeros(candidate_idx.shape, dtype=np.float64)
    for idx in np.unique(candidate_idx):
        if idx < 0:
            continue
        m = candidate_idx == idx
        out[m] = competition_noise(int(idx), world_x[m], world_z[m])
    return out - rank * COMPETITION_RANK_PENALTY
