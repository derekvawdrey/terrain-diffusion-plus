"""Auto-fix suggestions for three bug classes: the two structural dead-condition ones
(discreteness and noise ceiling) and the "low joint pass rate" one (rules that are individually
satisfiable but whose AND-combined conditions compound down to near-invisible in practice -- see
validators.check_low_pass_rate). Produces concrete before/after JSON patches; does NOT modify any
file by itself unless the caller (run.py --fix) explicitly applies them. Hard-bounds findings
(e.g. the elevationM / deep-ocean bug) are NOT auto-fixed here -- there's no single mechanical
snap that's obviously correct (the intended condition might be a different variable entirely,
e.g. checking real ocean depth requires a different signal than elevationM), so those are
reported for a human/agent to decide on deliberately.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from . import noise_data
from .catalog import HARD_BOUNDS, Condition, VALID_SPARSITY, VALID_TREE_COVERAGE
from .validators import Finding

DISCRETENESS_SNAP_HALFWIDTH = 0.01  # keeps a `between` window instead of unsafe float `eq`


@dataclass
class FixSuggestion:
    finding: Finding
    field_changes: dict  # {"value": new_value} and/or {"value2": new_value2}
    explanation: str


def _nearest(valid_values, target: float) -> float:
    return min(valid_values, key=lambda v: abs(v - target))


def suggest_discreteness_fix(finding: Finding) -> FixSuggestion | None:
    cond: Condition = finding.condition
    valid = VALID_TREE_COVERAGE if cond.variable == "treeCoverage" else VALID_SPARSITY

    if cond.op == "between":
        center = (cond.value + cond.value2) / 2.0
        target = _nearest(valid, center)
    elif cond.op in ("gt", "gte"):
        above = [v for v in valid if v >= cond.value]
        target = min(above) if above else max(valid)
    elif cond.op in ("lt", "lte"):
        below = [v for v in valid if v <= cond.value]
        target = max(below) if below else min(valid)
    elif cond.op == "eq":
        target = _nearest(valid, cond.value)
    else:
        return None

    lo = max(0.0, round(target - DISCRETENESS_SNAP_HALFWIDTH, 4))
    hi = min(1.0, round(target + DISCRETENESS_SNAP_HALFWIDTH, 4))
    return FixSuggestion(
        finding=finding,
        field_changes={"op": "between", "value": lo, "value2": hi},
        explanation=(f"Original '{cond.describe()}' straddled no valid {cond.variable} value "
                     f"(evident target ~{target}); snapped to a tight window "
                     f"[{lo}, {hi}] around the nearest real discrete value {target} instead of "
                     f"using float `eq` (which would be brittle against JSON-vs-Java float "
                     f"rounding)."),
    )


def suggest_noise_ceiling_fix(finding: Finding, families: dict, target_pass_rate: float = 0.02) -> FixSuggestion | None:
    cond: Condition = finding.condition
    fam = noise_data.family_for_variable(cond.variable, families)
    if fam is None:
        return None

    if cond.op in ("gt", "gte"):
        # Want roughly target_pass_rate of samples to exceed the new threshold -> percentile
        # (1 - target_pass_rate).
        new_value = round(float(fam.inverse_cdf(1.0 - target_pass_rate)), 4)
        return FixSuggestion(
            finding=finding,
            field_changes={"value": new_value},
            explanation=(f"Original threshold {cond.value} exceeds {fam.name}'s measured range "
                         f"[{fam.min:.4f}, {fam.max:.4f}] ({fam.samples:,} samples) and can never "
                         f"match. Replaced with {new_value} (the measured "
                         f"{(1 - target_pass_rate) * 100:.1f}th percentile), calibrated so roughly "
                         f"{target_pass_rate * 100:.0f}% of eligible pixels pass -- a 'rare accent' "
                         f"pass rate rather than zero."),
        )
    if cond.op in ("lt", "lte"):
        new_value = round(float(fam.inverse_cdf(target_pass_rate)), 4)
        return FixSuggestion(
            finding=finding,
            field_changes={"value": new_value},
            explanation=(f"Original threshold {cond.value} is below {fam.name}'s measured range "
                         f"[{fam.min:.4f}, {fam.max:.4f}] ({fam.samples:,} samples) and can never "
                         f"match. Replaced with {new_value} (the measured "
                         f"{target_pass_rate * 100:.1f}th percentile), calibrated so roughly "
                         f"{target_pass_rate * 100:.0f}% of eligible pixels pass."),
        )
    # 'eq' / 'between' noise-ceiling dead conditions: no single mechanical snap is obviously
    # right (the whole window may be out of range) -- flag for manual review instead.
    return None


def apply_suggestions(suggestions: list[FixSuggestion]) -> int:
    """Mutates each suggestion's underlying raw JSON dict (Condition.raw) in place. Caller is
    responsible for then serializing Catalog.raw to a file -- this never touches disk itself, so
    it's safe to call against the live catalog's in-memory representation without risking a
    partial write. Returns the number of conditions actually changed.
    """
    applied = 0
    for s in suggestions:
        cond = s.finding.condition
        if cond is None or cond.raw is None:
            continue
        cond.raw.update(s.field_changes)
        applied += 1
    return applied


def build_suggestions(findings: list[Finding], families: dict) -> list[FixSuggestion]:
    out = []
    for f in findings:
        if f.severity != "dead" or f.condition is None:
            continue
        if f.category == "discreteness":
            s = suggest_discreteness_fix(f)
        elif f.category == "noise_ceiling":
            s = suggest_noise_ceiling_fix(f, families)
        else:
            s = None
        if s is not None:
            out.append(s)
    return out


# ---------------------------------------------------------------------------------------------
# "Low joint pass rate" widening (validators.check_low_pass_rate's fix). Unlike the two fixes
# above, there's no closed-form target value to snap to -- the right amount of widening depends
# on how the OTHER conditions in the same rule actually correlate in real climate data, which
# varies rule to rule. So instead of a formula, this binary-searches directly against the real
# Monte Carlo sample arrays: "how far do I have to move this one threshold, holding every other
# condition in the rule fixed, before the rule's true joint pass rate crosses the target" -- the
# same samples the rest of this tool already uses, not an independence assumption.
# ---------------------------------------------------------------------------------------------

def _widen_between(cond: Condition, other_mask: "np.ndarray", samples, target_rate: float,
                    max_expand: float = 4.0, steps: int = 24):
    """Binary-searches the smallest symmetric expansion of a `between [lo, hi]` window (grown
    outward from its center) such that (widened_cond & other_mask) reaches target_rate. Clamped
    to catalog.HARD_BOUNDS for this variable if known, else to the widest value actually observed
    in the Monte Carlo samples (never proposes a bound the real climate model can't produce).
    Returns (new_lo, new_hi, achieved_rate, reached_target: bool).
    """
    lo0, hi0 = cond.value, cond.value2
    center = (lo0 + hi0) / 2.0
    halfwidth = max((hi0 - lo0) / 2.0, 1e-6)
    arr = getattr(samples, cond.variable)
    bound_lo, bound_hi = HARD_BOUNDS.get(cond.variable, (None, None))
    clamp_lo = bound_lo if bound_lo is not None else float(arr.min())
    clamp_hi = bound_hi if bound_hi is not None else float(arr.max())

    def at(f: float):
        new_lo = max(clamp_lo, center - halfwidth * (1 + f))
        new_hi = min(clamp_hi, center + halfwidth * (1 + f))
        return float(((arr >= new_lo) & (arr <= new_hi) & other_mask).mean()), new_lo, new_hi

    best_rate, best_lo, best_hi = at(max_expand)
    if best_rate < target_rate:
        return round(best_lo, 4), round(best_hi, 4), best_rate, False

    f_lo, f_hi = 0.0, max_expand
    for _ in range(steps):
        f_mid = (f_lo + f_hi) / 2.0
        r, _, _ = at(f_mid)
        if r >= target_rate:
            f_hi = f_mid
        else:
            f_lo = f_mid
    rate, new_lo, new_hi = at(f_hi)
    return round(new_lo, 4), round(new_hi, 4), rate, True


def _widen_threshold(cond: Condition, other_mask: "np.ndarray", samples, target_rate: float, steps: int = 24):
    """Same idea as _widen_between but for gt/gte/lt/lte: binary-searches the smallest move of
    the threshold toward more-permissive that reaches target_rate. Returns
    (new_value, achieved_rate, reached_target: bool).
    """
    arr = getattr(samples, cond.variable)
    bound_lo, bound_hi = HARD_BOUNDS.get(cond.variable, (None, None))
    sample_lo, sample_hi = float(arr.min()), float(arr.max())
    more_permissive_is_lower = cond.op in ("gt", "gte")
    extreme = (bound_lo if bound_lo is not None else sample_lo) if more_permissive_is_lower \
        else (bound_hi if bound_hi is not None else sample_hi)

    def rate_at(threshold: float) -> float:
        if cond.op == "gt":
            m = arr > threshold
        elif cond.op == "gte":
            m = arr >= threshold
        elif cond.op == "lt":
            m = arr < threshold
        else:
            m = arr <= threshold
        return float((m & other_mask).mean())

    best_rate = rate_at(extreme)
    if best_rate < target_rate:
        return round(extreme, 4), best_rate, False

    if more_permissive_is_lower:
        lo, hi = extreme, cond.value  # rate_at(lo) >= target > rate_at(hi)
        for _ in range(steps):
            mid = (lo + hi) / 2.0
            if rate_at(mid) >= target_rate:
                lo = mid
            else:
                hi = mid
        final = lo
    else:
        lo, hi = cond.value, extreme  # rate_at(lo) < target <= rate_at(hi)
        for _ in range(steps):
            mid = (lo + hi) / 2.0
            if rate_at(mid) >= target_rate:
                hi = mid
            else:
                lo = mid
        final = hi
    return round(final, 4), rate_at(final), True


def suggest_widen_fixes(finding: Finding, bottleneck_row, samples, target_joint_rate: float = 0.02,
                         max_conditions_to_widen: int = 2) -> tuple[list[FixSuggestion], float]:
    """For a 'low_pass_rate' Finding: widens the tightest 1-2 conditions in the rule (re-evaluated
    against the real Monte Carlo samples at each step, holding the other conditions fixed) until
    the rule's true joint pass rate reaches target_joint_rate or max_conditions_to_widen is used
    up. Boolean and `eq` conditions are skipped (not meaningfully "widenable"). Returns
    (suggestions, resulting_joint_rate); resulting_joint_rate may still be below target if
    widening the allowed number of conditions wasn't enough -- the caller should report that
    rather than silently under-deliver.
    """
    conds = bottleneck_row.condition_objects
    if not conds:
        return [], bottleneck_row.joint_pass_rate

    from .engine import _eval_condition

    def ev(c: Condition):
        return _eval_condition(c, lambda v: getattr(samples, v))

    current_mask = {id(c): ev(c) for c in conds}

    def joint_rate() -> float:
        m = np.ones(samples.n, dtype=bool)
        for c in conds:
            m &= current_mask[id(c)]
        return float(m.mean())

    suggestions = []
    for cond in sorted(conds, key=lambda c: current_mask[id(c)].mean()):
        if len(suggestions) >= max_conditions_to_widen or joint_rate() >= target_joint_rate:
            break
        if cond.bool_value is not None or cond.op in ("eq",):
            continue

        other = np.ones(samples.n, dtype=bool)
        for c in conds:
            if c is not cond:
                other &= current_mask[id(c)]
        if not other.any():
            continue  # some OTHER condition already excludes every sample; widening this one can't help alone

        if cond.op == "between":
            new_lo, new_hi, achieved, reached = _widen_between(cond, other, samples, target_joint_rate)
            field_changes = {"value": new_lo, "value2": new_hi}
            arr = getattr(samples, cond.variable)
            new_mask = (arr >= new_lo) & (arr <= new_hi)
            change_desc = f"[{cond.value}, {cond.value2}] -> [{new_lo}, {new_hi}]"
        elif cond.op in ("gt", "gte", "lt", "lte"):
            new_value, achieved, reached = _widen_threshold(cond, other, samples, target_joint_rate)
            field_changes = {"value": new_value}
            arr = getattr(samples, cond.variable)
            new_mask = {"gt": arr > new_value, "gte": arr >= new_value,
                        "lt": arr < new_value, "lte": arr <= new_value}[cond.op]
            change_desc = f"{cond.value} -> {new_value}"
        else:
            continue

        current_mask[id(cond)] = new_mask
        note = ("" if reached else
                " Still short of the target even at the widest sane bound for this variable -- "
                "widening another condition too, if any remain to try.")
        suggestions.append(FixSuggestion(
            finding=Finding(severity="rare", category="low_pass_rate",
                             biome_key=finding.biome_key, biome_index=finding.biome_index,
                             zone=finding.zone, rarity=finding.rarity,
                             condition_desc=cond.describe(), message="", condition=cond),
            field_changes=field_changes,
            explanation=(f"Widened '{cond.variable}' ({change_desc}) to raise this rule's joint "
                         f"pass rate from {bottleneck_row.joint_pass_rate * 100:.4f}% toward the "
                         f"target {target_joint_rate * 100:.2f}%, holding the other "
                         f"{len(conds) - 1} condition(s) fixed and re-evaluating against real "
                         f"Monte Carlo samples (not an independence assumption).{note}"),
        ))

    return suggestions, joint_rate()
