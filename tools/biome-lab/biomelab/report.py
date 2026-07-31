"""Markdown report generation. One report per run, meant to be equally readable by a human and by
a small local LLM driving run.py in a loop.
"""
from __future__ import annotations

import datetime
from pathlib import Path

from .catalog import Catalog
from .validators import ValidatorReport
from . import montecarlo as mc


def _fmt_pct(x: float) -> str:
    return f"{x * 100:.3f}%"


def _fmt_blocks(x: float | None) -> str:
    if x is None:
        return "n/a"
    if x > 1_000_000:
        return f"~{x / 1_000_000:.1f}M blocks"
    if x > 1000:
        return f"~{x / 1000:.1f}k blocks"
    return f"~{x:.0f} blocks"


def render(
    catalog: Catalog,
    validator_report: ValidatorReport,
    mc_result: dict | None,
    fix_suggestions: list,
    args,
) -> str:
    lines = []
    now = datetime.datetime.now().isoformat(timespec="seconds")
    lines.append("# biome-lab report")
    lines.append("")
    lines.append(f"- Catalog: `{catalog.path}`")
    lines.append(f"- Biomes in catalog: {len(catalog.settlements)}")
    lines.append(f"- Generated: {now}")
    if mc_result is not None:
        lines.append(f"- Monte Carlo samples: {mc_result['n_samples']:,} (seed={args.seed})")
    lines.append("")

    lines.append("## 1. Static validators (fast, no sampling)")
    lines.append("")
    n_dead = len(validator_report.dead_findings)
    n_redundant = len(validator_report.redundant_findings)
    status = "FAIL" if n_dead else "PASS"
    lines.append(f"**Result: {status}** -- {n_dead} dead condition(s) found across "
                 f"{len(validator_report.affected_biomes)} biome(s), {n_redundant} redundant (always-true) condition(s).")
    lines.append("")

    for category, title in (
        ("discreteness", "1a. treeCoverage / sparsity discreteness"),
        ("noise_ceiling", "1b. Noise-ceiling (unreachable noiseConditions thresholds)"),
        ("hard_bounds", "1c. Hard climate-variable bounds"),
    ):
        findings = [f for f in validator_report.all_findings if f.category == category]
        dead = [f for f in findings if f.severity == "dead"]
        redundant = [f for f in findings if f.severity == "redundant"]
        lines.append(f"### {title}")
        lines.append("")
        if not dead and not redundant:
            lines.append("No issues found.")
            lines.append("")
            continue
        if dead:
            lines.append(f"**{len(dead)} dead (unreachable) condition(s):**")
            lines.append("")
            for f in dead:
                lines.append(f"- `{f.biome_key}` (zone={f.zone}, rarity={f.rarity}): "
                             f"`{f.condition_desc}` -- {f.message}")
            lines.append("")
        if redundant:
            lines.append(f"**{len(redundant)} redundant (always-true, no-op) condition(s):**")
            lines.append("")
            for f in redundant:
                lines.append(f"- `{f.biome_key}` (zone={f.zone}, rarity={f.rarity}): "
                             f"`{f.condition_desc}` -- {f.message}")
            lines.append("")

    if validator_report.aliasing:
        lines.append("### 1d. moisture / treeMoisture aliasing (informational)")
        lines.append("")
        for f in validator_report.aliasing:
            lines.append(f"- `{f.biome_key}` (zone={f.zone}, rarity={f.rarity}): {f.message}")
        lines.append("")

    if fix_suggestions:
        lines.append("## 2. Suggested fixes")
        lines.append("")
        for s in fix_suggestions:
            f = s.finding
            lines.append(f"- `{f.biome_key}` (zone={f.zone}, rarity={f.rarity}): "
                         f"`{f.condition_desc}` -> `{s.field_changes}`")
            lines.append(f"  - {s.explanation}")
        lines.append("")

    if mc_result is None:
        lines.append("## 3. Monte Carlo evaluation")
        lines.append("")
        lines.append("_Not run this pass (validators-only, or Monte Carlo was skipped because "
                     "validators failed -- pass `--force-montecarlo` to run it anyway)._")
        lines.append("")
        return "\n".join(lines)

    by_key = {s.index: s.key for s in catalog.settlements}

    lines.append("## 3. Monte Carlo evaluation")
    lines.append("")
    lines.append("**Fidelity gaps (read before trusting any number below):**")
    lines.append("")
    lines.append("- **Slope** has no real spatial gradient signal available to an i.i.d. sampler; "
                 "it's drawn from a calibrated Gamma(1.2, 0.22) distribution tuned to roughly match "
                 "the classifier's own medium/steep thresholds. This is the single biggest fidelity "
                 "gap in the whole pipeline -- treat every `mountain`/`bareSlope` zone number and "
                 "every biome that heavily gates on `slope` with proportional skepticism.")
    lines.append("- **Beach/coastline** is a thin spatial boundary (isCoastlineCandidate's real "
                 "neighbour-count test) that an i.i.d. sampler can't reproduce; approximated as a "
                 "flat calibrated probability applied to a non-spatial elevation/slope prefilter. "
                 "Treat `beach` zone area fractions as order-of-magnitude estimates only.")
    lines.append("- **Elevation-temperature lapse rate** uses the model's own *fallback* beta "
                 "(no spatial window to regress against locally); this gets the aggregate "
                 "warm-region skew right but under-represents pixel-to-pixel lapse variability.")
    lines.append("")

    lines.append("### 3a. Area fractions by zone")
    lines.append("")
    af: mc.AreaFractions = mc_result["area_fractions"]
    for zname, fracs in af.by_zone.items():
        lines.append(f"**{zname}** ({_fmt_pct(sum(fracs.values())) if fracs else '0%'} of samples land in this zone)")
        lines.append("")
        if not fracs:
            lines.append("_(no samples)_")
            lines.append("")
            continue
        lines.append("| biome | area fraction (of zone) |")
        lines.append("|---|---|")
        for idx, frac in sorted(fracs.items(), key=lambda t: -t[1])[:25]:
            lines.append(f"| `{by_key.get(idx, idx)}` | {_fmt_pct(frac)} |")
        if len(fracs) > 25:
            lines.append(f"| _... {len(fracs) - 25} more_ | |")
        lines.append("")

    lines.append("### 3b. Diversity metrics (overall)")
    lines.append("")
    dm: mc.DiversityMetrics = mc_result["diversity_overall"]
    lines.append(f"- Effective number of biomes (exp(Shannon entropy)): **{dm.effective_number_of_biomes:.2f}**")
    lines.append(f"- Shannon entropy: {dm.shannon_entropy:.3f}")
    lines.append(f"- HHI concentration: {dm.hhi:.4f} (higher = more concentrated in few biomes)")
    lines.append(f"- Distinct biomes actually reached: {dm.n_biomes_present} / {len(catalog.settlements)}")
    lines.append("")

    lines.append("### 3c. Cross-tier collision rates")
    lines.append("")
    lines.append("How often two (or three) genuinely eligible biomes competed for the same pixel "
                 "(BiomeRuleEngine's competition-noise resolution), by pair, top 20 by count:")
    lines.append("")
    collisions = mc_result["collisions"]
    if collisions:
        lines.append("| biome A | biome B | pixels where both were eligible |")
        lines.append("|---|---|---|")
        for (a, b), cnt in sorted(collisions.items(), key=lambda t: -t[1])[:20]:
            lines.append(f"| `{by_key.get(a, a)}` | `{by_key.get(b, b)}` | {cnt:,} |")
    else:
        lines.append("_No collisions observed -- every pixel had at most one eligible candidate "
                     "biome per zone in this sample._")
    lines.append("")

    lines.append("### 3d. Encounterability")
    lines.append("")
    lines.append(f"Rough \"expected exploration distance\" estimate = "
                 f"gating-noise-field-wavelength / sqrt(area_fraction). Flagging biomes below "
                 f"{_fmt_pct(args.min_area_fraction)} area fraction as the configured minimum bar.")
    lines.append("")
    lines.append("| biome | area fraction | gating field | wavelength | expected exploration distance | below bar? |")
    lines.append("|---|---|---|---|---|---|")
    for row in mc_result["encounterability"]:
        flag = "**YES**" if row.below_min_bar else ""
        lines.append(f"| `{row.biome_key}` | {_fmt_pct(row.area_fraction)} | "
                     f"{row.dominant_field or 'n/a'} | {_fmt_blocks(row.wavelength_blocks)} | "
                     f"{_fmt_blocks(row.expected_blocks_estimate)} | {flag} |")
    lines.append("")

    lines.append("### 3e. Reachable climate ranges (\"does it make sense where it's placed\")")
    lines.append("")
    lines.append("p5 / p50 / p95 of each variable among pixels where this biome actually won, so "
                 "you can eyeball whether e.g. a biome literally named 'desert' is reachable under "
                 "desert-like conditions. Biomes with 0 samples never won a single pixel.")
    lines.append("")
    lines.append("| biome | n | tempC (p5/p50/p95) | precipMm (p5/p50/p95) | moisture (p5/p50/p95) | elevM (p5/p50/p95) | snowy % |")
    lines.append("|---|---|---|---|---|---|---|")
    for row in mc_result["climate_ranges"]:
        if row.n_samples == 0:
            lines.append(f"| `{row.biome_key}` | 0 | _never won any pixel_ | | | | |")
            continue
        t = row.ranges["temperatureC"]
        p = row.ranges["precipitationMm"]
        m = row.ranges["moisture"]
        e = row.ranges["elevationM"]
        lines.append(f"| `{row.biome_key}` | {row.n_samples:,} | "
                     f"{t[0]:.1f}/{t[1]:.1f}/{t[2]:.1f} | {p[0]:.0f}/{p[1]:.0f}/{p[2]:.0f} | "
                     f"{m[0]:.2f}/{m[1]:.2f}/{m[2]:.2f} | {e[0]:.0f}/{e[1]:.0f}/{e[2]:.0f} | "
                     f"{row.snowy_fraction * 100:.1f}% |")
    lines.append("")

    lines.append("### 3f. Rule bottleneck diagnostics")
    lines.append("")
    lines.append("Per-rule joint pass rate vs. each individual condition's own pass rate (tightest "
                 "first) -- the fastest way to spot which single condition is strangling a rule in "
                 "a compounding-narrow-AND case. Showing the 20 rules with the lowest nonzero joint "
                 "pass rate.")
    lines.append("")
    bottlenecks = [b for b in mc_result["bottlenecks"] if b.joint_pass_rate > 0]
    bottlenecks.sort(key=lambda b: b.joint_pass_rate)
    for b in bottlenecks[:20]:
        lines.append(f"- `{b.biome_key}` (zone={b.zone}, rarity={b.rarity}): joint pass rate "
                     f"{_fmt_pct(b.joint_pass_rate)}, tightest condition: `{b.tightest_condition}` "
                     f"({_fmt_pct(b.condition_pass_rates[0][1])})")
        cond_str = ", ".join(f"{d} ({_fmt_pct(r)})" for d, r in b.condition_pass_rates)
        lines.append(f"  - all conditions: {cond_str}")
    lines.append("")

    zero_rate_rules = [b for b in mc_result["bottlenecks"] if b.joint_pass_rate == 0.0]
    if zero_rate_rules:
        lines.append(f"**{len(zero_rate_rules)} rule(s) had a joint pass rate of exactly 0 in this "
                     f"sample** (either genuinely dead per the static validators above, or just "
                     f"extremely rare -- increase `--min-samples` to tell the two apart):")
        lines.append("")
        for b in zero_rate_rules[:30]:
            lines.append(f"- `{b.biome_key}` (zone={b.zone}, rarity={b.rarity}), tightest: "
                         f"`{b.tightest_condition}`")
        lines.append("")

    low_pass = mc_result.get("low_pass_findings") or []
    lines.append("### 3g. Low joint pass rate (individually valid, compounds to near-invisible)")
    lines.append("")
    lines.append("Rules where every condition is individually satisfiable and none trip the "
                 "structural discreteness/noise-ceiling checks in section 1, but the AND of all "
                 "of them together is so restrictive the biome is effectively invisible in normal "
                 "play. Different failure mode from section 1's dead conditions: nothing here is "
                 "broken, several moderately-narrow conditions are just compounding "
                 "multiplicatively. Run with `--fix` to widen the tightest 1-2 conditions per rule "
                 "toward `--fix-target-rate` (default 2%), re-simulated against real Monte Carlo "
                 "samples rather than an independence assumption -- see section 2 for what was "
                 "widened, if `--fix` was passed.")
    lines.append("")
    if not low_pass:
        lines.append(f"No rules below the {_fmt_pct(args.low_pass_rate_threshold)} threshold.")
    else:
        lines.append(f"**{len(low_pass)} rule(s)** below {_fmt_pct(args.low_pass_rate_threshold)}:")
        lines.append("")
        for f in sorted(low_pass, key=lambda f: f.biome_key)[:30]:
            lines.append(f"- `{f.biome_key}` (zone={f.zone}, rarity={f.rarity}): {f.message}")
    lines.append("")

    return "\n".join(lines)


def write(path: str | Path, content: str):
    Path(path).write_text(content)
