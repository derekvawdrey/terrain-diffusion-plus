#!/usr/bin/env python3
"""CLI entry point for biome-lab.

Examples
--------
Fast validators only (no Monte Carlo, no --pipeline-data needed), against the repo's own default
catalog:

    python3 tools/biome-lab/run.py \\
        --catalog common/src/main/resources/biome_catalog.json \\
        --validate-only

Same, against a live instance config:

    python3 tools/biome-lab/run.py \\
        --catalog ~/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json \\
        --validate-only

Full run (validators + Monte Carlo + report), against the live instance config and real climate
data:

    python3 tools/biome-lab/run.py \\
        --catalog ~/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json \\
        --pipeline-data ~/.local/share/PrismLauncher/instances/1.21.1/minecraft/terrain-diffusion-models/pipeline_data.json \\
        --min-samples 1000000 \\
        --report biome_lab_report.md

Generate fix suggestions and write a patched copy (never touches the original catalog file):

    python3 tools/biome-lab/run.py --catalog <path> --fix --fix-output ./biome_catalog.fixed.json

See README.md for what each report section means and for the noise-quantile data regeneration
steps (java/NoiseProbe.java).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from biomelab import catalog as catalog_mod
from biomelab import climate as climate_mod
from biomelab import fixes as fixes_mod
from biomelab import montecarlo as mc
from biomelab import noise_data
from biomelab import report as report_mod
from biomelab import validators


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--catalog", required=True, help="Path to a biome_catalog.json to test.")
    p.add_argument("--pipeline-data", default=None,
                   help="Path to pipeline_data.json (real WorldClim/ETOPO quantile tables). "
                        "Required for Monte Carlo; not needed for --validate-only.")
    p.add_argument("--noise-data-dir", default=None,
                   help="Directory of java/NoiseProbe.java output (default: "
                        "tools/biome-lab/data/noise_quantiles, shipped in this repo).")
    p.add_argument("--validate-only", action="store_true",
                   help="Run only the fast static validators; skip Monte Carlo entirely.")
    p.add_argument("--force-montecarlo", action="store_true",
                   help="Run Monte Carlo even if static validators found dead conditions.")
    p.add_argument("--fix", action="store_true",
                   help="Compute auto-fix suggestions for discreteness/noise-ceiling dead "
                        "conditions and write a patched catalog copy (see --fix-output). Never "
                        "modifies --catalog itself.")
    p.add_argument("--fix-output", default=None,
                   help="Where to write the patched catalog when --fix is set. Default: "
                        "./<catalog-stem>.fixed.json in the CURRENT directory (deliberately not "
                        "next to the input file, so this can never accidentally overwrite a live "
                        "instance config).")
    p.add_argument("--report", default="biome_lab_report.md", help="Markdown report output path.")
    p.add_argument("--min-samples", type=int, default=500_000, help="Monte Carlo sample count.")
    p.add_argument("--min-area-fraction", type=float, default=0.0005,
                   help="Encounterability bar: biomes below this overall area fraction are flagged.")
    p.add_argument("--low-pass-rate-threshold", type=float, default=0.005,
                   help="Rules with a nonzero joint pass rate below this (e.g. 0.005 = 0.5%%) are "
                        "flagged as 'rare' -- individually satisfiable but compounding down to "
                        "near-invisible. Requires Monte Carlo (--pipeline-data), not part of "
                        "--validate-only.")
    p.add_argument("--fix-target-rate", type=float, default=0.02,
                   help="When --fix widens a 'rare' rule's tightest conditions, target this "
                        "joint pass rate (e.g. 0.02 = 2%%).")
    p.add_argument("--fix-max-widen", type=int, default=2,
                   help="Max number of conditions --fix will widen per over-constrained rule.")
    p.add_argument("--seed", type=int, default=0, help="Monte Carlo RNG seed.")
    return p.parse_args()


def main():
    args = parse_args()

    cat = catalog_mod.load(args.catalog)
    print(f"Loaded {len(cat.settlements)} biomes from {cat.path}")

    noise_dir = Path(args.noise_data_dir) if args.noise_data_dir else None
    try:
        families = noise_data.load_families(noise_dir)
    except noise_data.NoiseDataError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2

    vreport = validators.run(cat, families)
    n_dead = len(vreport.dead_findings)
    n_redundant = len(vreport.redundant_findings)
    print(f"Static validators: {n_dead} dead condition(s), {n_redundant} redundant condition(s) "
          f"across {len(vreport.affected_biomes)} biome(s)")
    for f in vreport.dead_findings:
        print(f"  DEAD  [{f.category}] {f.biome_key} ({f.zone}, rarity={f.rarity}): {f.condition_desc}")
    for f in vreport.redundant_findings:
        print(f"  REDUNDANT [{f.category}] {f.biome_key} ({f.zone}, rarity={f.rarity}): {f.condition_desc}")

    fix_suggestions = []
    if args.fix:
        fix_suggestions = fixes_mod.build_suggestions(vreport.dead_findings, families)
        applied = fixes_mod.apply_suggestions(fix_suggestions)
        print(f"Applied {applied}/{len(fix_suggestions)} structural fix(es) "
              f"(discreteness/noise-ceiling).")

    mc_result = None
    low_pass_findings = []
    run_mc = (not args.validate_only) and (args.force_montecarlo or vreport.passed())
    if args.validate_only:
        print("--validate-only set: skipping Monte Carlo.")
    elif not run_mc:
        print("Static validators found dead conditions: skipping Monte Carlo "
              "(pass --force-montecarlo to run it anyway).")
    elif not args.pipeline_data:
        print("No --pipeline-data given: skipping Monte Carlo. Pass --pipeline-data <path to "
              "pipeline_data.json> to enable it.")
    else:
        print(f"Running Monte Carlo with {args.min_samples:,} samples (seed={args.seed})...")
        pipeline = climate_mod.load_pipeline_data(args.pipeline_data)
        samples = climate_mod.simulate(pipeline, families, args.min_samples, seed=args.seed)
        result = mc.classify(cat, samples)
        af = mc.area_fractions(result)
        dm_overall = mc.diversity_metrics(af.overall)
        enc = mc.encounterability(cat, af.overall, args.min_area_fraction)
        ranges = mc.climate_ranges(cat, result)
        bottlenecks = mc.rule_bottlenecks(cat, samples)
        low_pass_findings = validators.check_low_pass_rate(bottlenecks, args.low_pass_rate_threshold)
        mc_result = {
            "n_samples": args.min_samples,
            "area_fractions": af,
            "diversity_overall": dm_overall,
            "collisions": result.collisions,
            "encounterability": enc,
            "climate_ranges": ranges,
            "bottlenecks": bottlenecks,
            "low_pass_findings": low_pass_findings,
        }
        print(f"Monte Carlo done. Effective # biomes: {dm_overall.effective_number_of_biomes:.2f}, "
              f"distinct biomes reached: {dm_overall.n_biomes_present}/{len(cat.settlements)}")
        below_bar = [r for r in enc if r.below_min_bar]
        if below_bar:
            print(f"{len(below_bar)} biome(s) below the {args.min_area_fraction * 100:.3f}% "
                  f"encounterability bar.")
        if low_pass_findings:
            print(f"{len(low_pass_findings)} rule(s) individually valid but compounding to a "
                  f"joint pass rate below {args.low_pass_rate_threshold * 100:.2f}%.")

        if args.fix and low_pass_findings:
            widen_suggestions = []
            still_short = []
            for f in low_pass_findings:
                row = f.source_row
                if row is None:
                    continue
                sugg, resulting_rate = fixes_mod.suggest_widen_fixes(
                    f, row, samples, target_joint_rate=args.fix_target_rate,
                    max_conditions_to_widen=args.fix_max_widen)
                widen_suggestions.extend(sugg)
                if resulting_rate < args.fix_target_rate:
                    still_short.append((f.biome_key, f.zone, f.rarity, resulting_rate))
            applied_widen = fixes_mod.apply_suggestions(widen_suggestions)
            fix_suggestions = fix_suggestions + widen_suggestions
            print(f"Applied {applied_widen}/{len(widen_suggestions)} widening fix(es) for "
                  f"low-joint-pass-rate rules (target {args.fix_target_rate * 100:.2f}%).")
            if still_short:
                print(f"{len(still_short)} rule(s) still below target after widening up to "
                      f"{args.fix_max_widen} condition(s) -- likely needs a human/agent decision "
                      f"(e.g. reconsidering which zone/tier the biome belongs in), not just more "
                      f"widening:")
                for key, zone, prio, rate in still_short:
                    print(f"  {key} ({zone}, prio={prio}): only reached {rate * 100:.4f}%")

    if args.fix:
        out_path = Path(args.fix_output) if args.fix_output else Path.cwd() / f"{Path(args.catalog).stem}.fixed.json"
        out_path.parent.mkdir(parents=True, exist_ok=True)
        import json
        out_path.write_text(json.dumps(cat.raw, indent=2))
        print(f"Wrote {out_path} ({len(fix_suggestions)} total suggested fix(es) applied; "
              f"original catalog untouched)")

    content = report_mod.render(cat, vreport, mc_result, fix_suggestions, args)
    report_mod.write(args.report, content)
    print(f"Report written to {args.report}")

    return 1 if n_dead > 0 else 0


if __name__ == "__main__":
    sys.exit(main())
