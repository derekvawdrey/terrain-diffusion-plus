"""Fast, no-Monte-Carlo-needed static validators. Meant for a tight loop (a local LLM editing
biome_catalog.json can run --validate-only after every edit in well under a second).

Three checks, all driven by real, re-derived constants (not hardcoded guesses):

1. treeCoverage/sparsity discreteness -- classifyPixel only ever produces one of 5 exact
   treeCoverage values (and the matching 5 sparsity values). A `between`/`gt`/`lt`/etc condition
   whose satisfiable range doesn't straddle one of those 5 values can never match any real pixel.

2. Noise ceiling -- every noiseConditions threshold is checked against the *measured* min/max for
   that noise field's (octaves, gain) family (see java/NoiseProbe.java + noise_data.py). A
   threshold beyond the measured range can never be satisfied.

3. Hard climate-variable bounds -- some condition variables are architecturally guaranteed
   non-negative (or otherwise range-bounded) by BiomeClassifier.classifyPixel itself, regardless
   of what the upstream climate model produces (see catalog.HARD_BOUNDS for the derivation of
   each). The most consequential one found in this codebase: `elevationM` is fed
   `Math.max(0f, elevation)`, so it is NEVER negative -- even for ocean pixels. Every
   `deep_*_ocean` biome in both shipped catalogs gates its only rule on `elevationM lt -250`,
   which can thus never match, making all four biomes 100% unreachable.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

from . import noise_data
from .catalog import Catalog, Condition, HARD_BOUNDS, VALID_SPARSITY, VALID_TREE_COVERAGE

EPS = 1e-6


@dataclass
class Finding:
    severity: str  # "dead" | "redundant" | "info" | "rare"
    category: str  # "discreteness" | "noise_ceiling" | "hard_bounds" | "low_pass_rate"
    biome_key: str
    biome_index: int
    zone: str
    priority: int
    condition_desc: str
    message: str
    condition: Condition = field(repr=False, default=None)
    # Only set by check_low_pass_rate: the exact originating BottleneckRow. NOT safe to
    # re-derive from (biome_key, zone, priority) -- a single biome can have several rule variants
    # at the identical zone+priority (e.g. minecraft:cherry_grove has ~16), so that tuple isn't a
    # unique key back to "this specific rule." Carry the object reference instead.
    source_row: object = field(repr=False, default=None, compare=False)


def _candidate_satisfies(cond: Condition, candidate: float, eps=EPS) -> bool:
    """Does a hypothetical pixel with cond.variable == candidate satisfy this single condition?"""
    op = cond.op
    if op == "eq":
        return abs(candidate - cond.value) < eps
    if op == "gt":
        return candidate > cond.value - eps
    if op == "gte":
        return candidate >= cond.value - eps
    if op == "lt":
        return candidate < cond.value + eps
    if op == "lte":
        return candidate <= cond.value + eps
    if op == "between":
        return cond.value - eps <= candidate <= cond.value2 + eps
    return True  # unknown op: don't flag


def _group_reachable(conds: list[Condition], valid_values, eps=EPS) -> bool:
    """True if some discrete valid value satisfies EVERY condition in `conds` simultaneously
    (they're AND-combined within a rule, exactly like TerrainBiomeRule.matches). Testing each of
    the handful of real discrete candidates directly -- rather than doing manual interval algebra
    on gt/gte/lt/lte/between/eq -- sidesteps a subtle bug: a rule can gate the *same* variable
    with two separate conditions (e.g. `treeCoverage gte 0.02` AND `treeCoverage lt 0.15`, seen in
    the live catalog's biomesoplenty:tundra rule) where each condition is individually
    satisfiable but their conjunction straddles no real discrete value.
    """
    return any(all(_candidate_satisfies(c, v, eps) for c in conds) for v in valid_values)


def check_discreteness(catalog: Catalog) -> list[Finding]:
    findings = []
    for settlement, rule in catalog.all_rules():
        for var_name, valid in (("treeCoverage", VALID_TREE_COVERAGE), ("sparsity", VALID_SPARSITY)):
            conds = [c for c in rule.conditions if c.variable == var_name]
            if not conds:
                continue
            if _group_reachable(conds, valid):
                continue
            joint_desc = " AND ".join(c.describe() for c in conds)
            for cond in conds:
                findings.append(Finding(
                    severity="dead", category="discreteness",
                    biome_key=settlement.key, biome_index=settlement.index,
                    zone=rule.zone, priority=rule.priority,
                    condition_desc=cond.describe(),
                    message=(f"{var_name} only ever takes values {valid}. "
                             + (f"'{cond.describe()}' straddles none of them and can never match."
                                if len(conds) == 1 else
                                f"This rule's combined conditions on {var_name} ('{joint_desc}') "
                                f"straddle none of them and can never jointly match, even though "
                                f"'{cond.describe()}' looks satisfiable in isolation. Not "
                                f"auto-fixed (ambiguous which discrete bucket was intended) -- "
                                f"needs a human/agent decision.")),
                    # Only offer an auto-fix suggestion for the unambiguous single-condition case.
                    condition=cond if len(conds) == 1 else None,
                ))
    return findings


def check_noise_ceiling(catalog: Catalog, families: dict) -> list[Finding]:
    findings = []
    for settlement, rule in catalog.all_rules():
        for cond in rule.noise_conditions:
            fam = noise_data.family_for_variable(cond.variable, families)
            if fam is None:
                continue
            lo, hi = fam.min, fam.max
            unreachable = False
            if cond.op == "eq" and not (lo - EPS <= cond.value <= hi + EPS):
                unreachable = True
            elif cond.op in ("gt", "gte") and cond.value > hi:
                unreachable = True
            elif cond.op in ("lt", "lte") and cond.value < lo:
                unreachable = True
            elif cond.op == "between" and (cond.value > hi or cond.value2 < lo):
                unreachable = True
            if unreachable:
                findings.append(Finding(
                    severity="dead", category="noise_ceiling",
                    biome_key=settlement.key, biome_index=settlement.index,
                    zone=rule.zone, priority=rule.priority,
                    condition_desc=cond.describe(),
                    message=(f"{cond.variable} (family {fam.name}, octaves={fam.octaves}, "
                             f"gain={fam.gain}) was measured over {fam.samples:,} samples to stay "
                             f"within [{lo:.4f}, {hi:.4f}] -- '{cond.describe()}' falls outside "
                             f"that and can never match."),
                    condition=cond,
                ))
    return findings


def _group_interval(conds: list[Condition]) -> tuple[float, float]:
    """Folds a list of AND-combined numeric conditions on the same variable into the tightest
    [lo, hi] interval they jointly allow (ignoring open/closed-endpoint strictness, which doesn't
    matter for a continuous variable and a report meant to catch *entirely* infeasible/
    out-of-bounds combinations, not measure-zero edge cases).
    """
    lo, hi = -math.inf, math.inf
    for c in conds:
        if c.op == "eq":
            lo, hi = max(lo, c.value), min(hi, c.value)
        elif c.op in ("gt", "gte"):
            lo = max(lo, c.value)
        elif c.op in ("lt", "lte"):
            hi = min(hi, c.value)
        elif c.op == "between":
            lo, hi = max(lo, c.value), min(hi, c.value2)
    return lo, hi


def check_hard_bounds(catalog: Catalog) -> list[Finding]:
    findings = []
    for settlement, rule in catalog.all_rules():
        by_var: dict[str, list[Condition]] = {}
        for cond in rule.conditions:
            if cond.variable in HARD_BOUNDS and cond.bool_value is None:
                by_var.setdefault(cond.variable, []).append(cond)

        for var_name, conds in by_var.items():
            bound_lo, bound_hi = HARD_BOUNDS[var_name]
            bound_lo = bound_lo if bound_lo is not None else -math.inf
            bound_hi = bound_hi if bound_hi is not None else math.inf
            group_lo, group_hi = _group_interval(conds)

            # Dead: the rule's own combined interval on this variable doesn't overlap the
            # variable's hard-coded feasible range at all.
            overlap_lo, overlap_hi = max(group_lo, bound_lo), min(group_hi, bound_hi)
            if overlap_lo > overlap_hi + EPS:
                joint_desc = " AND ".join(c.describe() for c in conds)
                for cond in conds:
                    findings.append(Finding(
                        severity="dead", category="hard_bounds",
                        biome_key=settlement.key, biome_index=settlement.index,
                        zone=rule.zone, priority=rule.priority,
                        condition_desc=cond.describe(),
                        message=(f"{var_name} is architecturally bounded to "
                                 f"[{bound_lo if bound_lo != -math.inf else '-inf'}, "
                                 f"{bound_hi if bound_hi != math.inf else '+inf'}] by "
                                 f"BiomeClassifier.classifyPixel (see catalog.HARD_BOUNDS). "
                                 + (f"'{cond.describe()}' falls entirely outside that and can "
                                    f"never match."
                                    if len(conds) == 1 else
                                    f"This rule's combined conditions on {var_name} "
                                    f"('{joint_desc}') fall entirely outside that and can never "
                                    f"jointly match, even though '{cond.describe()}' looks "
                                    f"satisfiable in isolation.")),
                        condition=cond if len(conds) == 1 else None,
                    ))
                continue  # dead already reported; don't also check redundancy below

            # Redundant: a single condition whose own threshold is already fully implied by the
            # hard bound (e.g. `elevationM gte -250` when elevationM can never be < 0 anyway).
            if len(conds) == 1:
                cond = conds[0]
                redundant = (
                    (cond.op in ("gt", "gte") and bound_lo > -math.inf and cond.value <= bound_lo)
                    or (cond.op in ("lt", "lte") and bound_hi < math.inf and cond.value >= bound_hi)
                )
                if redundant:
                    findings.append(Finding(
                        severity="redundant", category="hard_bounds",
                        biome_key=settlement.key, biome_index=settlement.index,
                        zone=rule.zone, priority=rule.priority,
                        condition_desc=cond.describe(),
                        message=(f"{var_name} is architecturally bounded to "
                                 f"[{bound_lo if bound_lo != -math.inf else '-inf'}, "
                                 f"{bound_hi if bound_hi != math.inf else '+inf'}] -- "
                                 f"'{cond.describe()}' is always true and does nothing."),
                        condition=cond,
                    ))
    return findings


def check_moisture_treemoisture_aliasing(catalog: Catalog) -> list[Finding]:
    """Informational only: `moisture` and `treeMoisture` conditions read the literal same
    underlying value in TerrainClimateSample (both are populated from BiomeClassifier's single
    `treeMoisture` local). A rule using both is not wrong, but it's worth a human noticing that
    they are NOT independent axes -- e.g. `moisture between 0.3 0.6` AND `treeMoisture gt 0.5`
    is really just `treeMoisture between (max(0.3,0.5)) 0.6`, not a 2D constraint.
    """
    findings = []
    for settlement, rule in catalog.all_rules():
        vars_used = {c.variable for c in rule.conditions}
        if "moisture" in vars_used and "treeMoisture" in vars_used:
            findings.append(Finding(
                severity="info", category="aliasing",
                biome_key=settlement.key, biome_index=settlement.index,
                zone=rule.zone, priority=rule.priority,
                condition_desc="moisture & treeMoisture",
                message=("This rule conditions on both 'moisture' and 'treeMoisture', but "
                         "TerrainClimateSample populates both from the exact same underlying "
                         "value -- they are not independent constraints, just two names for one "
                         "number. Not necessarily a bug, but double-check the intent."),
                condition=None,
            ))
    return findings


def check_low_pass_rate(bottleneck_rows: list, threshold: float = 0.005) -> list[Finding]:
    """Requires a Monte Carlo run first (unlike the three checks above) -- NOT part of
    ValidatorReport / the fast static bundle, so --validate-only stays sampling-free. Call this
    explicitly from run.py after rule_bottlenecks() has run.

    Flags rules that are individually satisfiable and pass every discreteness/noise-ceiling check
    (so NOT "dead" in the structural sense) but whose *joint* pass rate -- all conditions
    AND-combined -- is so low the biome is effectively invisible in normal play. This is a
    genuinely different failure mode from the two structural bugs: e.g.
    biomesoplenty:grassland's rule combines `variantNoise > 0.3` (6.4% individually likely) AND a
    10-20C window (21.8%) AND a moisture window (18.3%) AND `treeCoverage <= 0.05` (27.3%) --
    every single condition is fine in isolation, but four "reasonable" ~20% conditions
    multiplied together land at ~0.07%, matching the ~0.001% actually observed. Severity "rare"
    (distinct from "dead") since these rules DO match real pixels, just vanishingly few of them.
    """
    findings = []
    for row in bottleneck_rows:
        if row.joint_pass_rate <= 0.0 or row.joint_pass_rate >= threshold:
            continue  # exactly-zero rows are (or overlap with) the structural "dead" findings above
        tightest_desc, tightest_rate = row.condition_pass_rates[0] if row.condition_pass_rates else (None, None)
        findings.append(Finding(
            severity="rare", category="low_pass_rate",
            biome_key=row.biome_key, biome_index=row.settlement.index,
            zone=row.zone, priority=row.priority,
            condition_desc=tightest_desc or "(no conditions)",
            message=(f"This rule's conditions are all individually satisfiable, but their "
                     f"conjunction only matches {row.joint_pass_rate * 100:.4f}% of sampled "
                     f"climate -- below the {threshold * 100:.2f}% bar. Tightest single "
                     f"condition: '{tightest_desc}' ({tightest_rate * 100:.3f}% alone), but no "
                     f"single condition is broken; it's the AND of several moderately-narrow "
                     f"conditions compounding multiplicatively. Full condition list: "
                     + ", ".join(f"{d} ({r * 100:.3f}%)" for d, r in row.condition_pass_rates) + "."),
            condition=None,  # ambiguous which of several conditions to touch -- see fixes.suggest_widen_fixes
            source_row=row,
        ))
    return findings


@dataclass
class ValidatorReport:
    discreteness: list[Finding]
    noise_ceiling: list[Finding]
    hard_bounds: list[Finding]
    aliasing: list[Finding]

    @property
    def dead_findings(self) -> list[Finding]:
        return [f for f in (self.discreteness + self.noise_ceiling + self.hard_bounds) if f.severity == "dead"]

    @property
    def redundant_findings(self) -> list[Finding]:
        return [f for f in self.hard_bounds if f.severity == "redundant"]

    @property
    def all_findings(self) -> list[Finding]:
        return self.discreteness + self.noise_ceiling + self.hard_bounds + self.aliasing

    @property
    def affected_biomes(self) -> set[str]:
        return {f.biome_key for f in self.dead_findings}

    def passed(self) -> bool:
        return len(self.dead_findings) == 0


def run(catalog: Catalog, families: dict) -> ValidatorReport:
    return ValidatorReport(
        discreteness=check_discreteness(catalog),
        noise_ceiling=check_noise_ceiling(catalog, families),
        hard_bounds=check_hard_bounds(catalog),
        aliasing=check_moisture_treemoisture_aliasing(catalog),
    )
