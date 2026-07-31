"""Vectorized port of BiomeRuleEngine.java: rarity-weighted selection among every eligible biome.

Mirrors that file's `select()` exactly, batched over numpy arrays instead of one pixel at a time.
Each eligible biome i gets the Efraimidis-Spirakis key ln(u_i)/w_i -- where u_i is its own smooth
competitionNoise field mapped onto (0,1) -- and the largest key wins, which reproduces
P(i) = w_i / sum(w) while keeping the result spatially coherent. Override rules, if any match,
compete only among themselves.
"""
from __future__ import annotations

import numpy as np

from .catalog import Catalog, Condition

U_EPSILON = 1e-6

# CDF of competition_noise's output rescaled to [0,1], on a uniform grid, linearly interpolated.
# The Efraimidis-Spirakis key is only exact for a UNIFORM u, and value noise is not uniform --
# blending four hash values bilinearly concentrates it around the midpoint (std 0.214 vs a
# uniform's 0.289), which would hand high weights far more than their share. Identical copy of
# BiomeRuleEngine.NOISE_CDF; regenerate both together if value_noise ever changes.
NOISE_CDF = np.array([
    0.000000, 0.002161, 0.008784, 0.019927, 0.035609, 0.055724, 0.080132, 0.108747,
    0.141309, 0.177474, 0.216943, 0.259346, 0.304313, 0.351328, 0.399982, 0.449803,
    0.500061, 0.550323, 0.600022, 0.648588, 0.695640, 0.740613, 0.783037, 0.822494,
    0.858638, 0.891144, 0.919765, 0.944205, 0.964329, 0.980018, 0.991182, 0.997837,
    1.000000,
])
_NOISE_CDF_X = np.linspace(0.0, 1.0, len(NOISE_CDF))


def uniform_noise(noise: np.ndarray) -> np.ndarray:
    """Port of BiomeRuleEngine.uniformNoise: noise in [-1,1] -> uniform on (0,1)."""
    x = np.clip((noise + 1.0) * 0.5, 0.0, 1.0)
    return np.clip(np.interp(x, _NOISE_CDF_X, NOISE_CDF), U_EPSILON, 1.0 - U_EPSILON)
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
    """Pre-groups every settlement's rules by zone, exactly like BiomeRuleEngine.init().
    `select()` evaluates one zone's rules against a batch of samples.
    """

    def __init__(self, catalog: Catalog):
        self.catalog = catalog
        self.zone_rules: dict[str, list[tuple[int, float, bool, list, list]]] = {}
        for settlement, rule in catalog.all_rules():
            if rule.rarity <= 0:
                continue  # weightless rules can never be selected
            self.zone_rules.setdefault(rule.zone, []).append(
                (settlement.index, rule.rarity, rule.override, rule.conditions, rule.noise_conditions)
            )

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
            candidate0..2 are the three highest-keyed eligible biomes (-1 where fewer were
            eligible), retained for the Monte Carlo evaluator's collision-rate diagnostic: how
            often does a pixel have more than one genuinely eligible biome for its zone?
        """
        rules = self.zone_rules.get(zone)
        default_arr = (np.full(n, default_index, dtype=np.int64) if np.isscalar(default_index)
                       else np.asarray(default_index, dtype=np.int64))
        if not rules:
            empty = np.full(n, -1, dtype=np.int64)
            return default_arr.copy(), empty, empty.copy(), empty.copy()

        world_x = get("worldX")
        world_z = get("worldZ")

        # Track the three best keys seen so far, for normal and override candidates separately.
        neg_inf = np.full(n, -np.inf)
        keys = [neg_inf.copy(), neg_inf.copy(), neg_inf.copy()]
        idxs = [np.full(n, -1, dtype=np.int64) for _ in range(3)]
        ov_key = neg_inf.copy()
        ov_idx = np.full(n, -1, dtype=np.int64)

        for index, rarity, override, conditions, noise_conditions in rules:
            mask = _eval_rule(conditions, noise_conditions, get)
            if mask is None:
                mask = np.ones(n, dtype=bool)
            if not np.any(mask):
                continue
            u = uniform_noise(competition_noise(index, world_x, world_z))
            key = np.where(mask, np.log(u) / rarity, -np.inf)

            if override:
                better = key > ov_key
                ov_idx = np.where(better, index, ov_idx)
                ov_key = np.where(better, key, ov_key)
                continue

            # Insert into the running top-3 strictly by key. A biome with several matching
            # rules can briefly occupy two slots; that is harmless for the winner (slot 0 still
            # holds the global max key, which is that biome's best rule) and is de-duplicated
            # below before the collision diagnostic looks at the runner-up slots.
            for s_i in range(3):
                better = key > keys[s_i]
                if not np.any(better):
                    continue
                for s_j in range(2, s_i, -1):
                    keys[s_j] = np.where(better, keys[s_j - 1], keys[s_j])
                    idxs[s_j] = np.where(better, idxs[s_j - 1], idxs[s_j])
                keys[s_i] = np.where(better, key, keys[s_i])
                idxs[s_i] = np.where(better, index, idxs[s_i])
                break

        # Collapse repeats so candidate1/candidate2 mean "a DIFFERENT eligible biome", which is
        # what the collision-rate diagnostic counts.
        dup1 = idxs[1] == idxs[0]
        idxs[1] = np.where(dup1, idxs[2], idxs[1])
        idxs[2] = np.where(dup1, -1, idxs[2])
        dup2 = (idxs[2] == idxs[0]) | ((idxs[2] == idxs[1]) & (idxs[1] >= 0))
        idxs[2] = np.where(dup2, -1, idxs[2])

        winner = np.where(idxs[0] >= 0, idxs[0], default_arr)
        winner = np.where(ov_idx >= 0, ov_idx, winner)
        return winner.astype(np.int64), idxs[0], idxs[1], idxs[2]
