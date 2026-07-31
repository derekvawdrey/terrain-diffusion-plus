"""Auto-fix suggestions for the two confirmed dead-condition bug classes (discreteness and noise
ceiling). Produces concrete before/after JSON patches; does NOT modify any file by itself unless
the caller (run.py --fix) explicitly applies them. Hard-bounds findings (e.g. the elevationM /
deep-ocean bug) are NOT auto-fixed here -- there's no single mechanical snap that's obviously
correct (the intended condition might be a different variable entirely, e.g. checking real ocean
depth requires a different signal than elevationM), so those are reported for a human/agent to
decide on deliberately.
"""
from __future__ import annotations

from dataclasses import dataclass

from . import noise_data
from .catalog import Condition, VALID_SPARSITY, VALID_TREE_COVERAGE
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
