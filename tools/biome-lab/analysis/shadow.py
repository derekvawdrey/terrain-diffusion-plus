"""Separate the two ways a biome ends up invisible: never ELIGIBLE vs. eligible but always LOSING.

Section 3f of the biome-lab report only diagnoses the first (a rule whose conditions compound to
near-zero). A biome whose rules match plenty of pixels but sit at a lower priority than whatever
else also matches there is just as invisible, and looks perfectly healthy in 3f. This prints both
numbers side by side, plus who actually takes the contested pixels, so the fix is obvious:
loosen the rule (low eligibility) vs. raise the priority / carve a sub-niche (low win share).
"""
from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

import numpy as np

LAB = Path("/home/derek/IdeaProjects/terrain-diffusion-plus/tools/biome-lab")
sys.path.insert(0, str(LAB))

from biomelab import catalog as catalog_mod  # noqa: E402
from biomelab import climate, montecarlo, noise_data  # noqa: E402
from biomelab.engine import _eval_rule  # noqa: E402


def main():
    catalog_path, pipeline_path, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
    want = set(sys.argv[4:])

    cat = catalog_mod.load(catalog_path)
    pdata = climate.load_pipeline_data(pipeline_path)
    families = noise_data.load_families(LAB / "data" / "noise_quantiles")
    samples = climate.simulate(pdata, families, n, seed=0)
    zone = montecarlo.assign_zones(samples)
    result = montecarlo.classify(cat, samples)

    zone_mask = {
        "ocean": zone == "ocean",
        "beach": zone == "beach",
        "mountain": zone == "mountain",
        "lowland": zone == "lowland",
        "bareSlope": samples.bareSlope & (~samples.ocean) & (~samples.mountain),
    }

    def get(var):
        return getattr(samples, var)

    key_by_index = {s.index: s.key for s in cat.settlements}

    for s in cat.settlements:
        if want and s.key not in want:
            continue
        if not s.rules:
            continue
        eligible = np.zeros(n, dtype=bool)
        for rule in s.rules:
            base = zone_mask.get(rule.zone)
            if base is None:
                continue
            eligible |= _eval_rule(rule.conditions, rule.noise_conditions, get) & base
        won = result.biome_index == s.index
        n_el, n_won = int(eligible.sum()), int(won.sum())
        share = (100.0 * n_won / n_el) if n_el else 0.0
        prio = sorted({r.priority for r in s.rules})
        print(f"{s.key:40s} prio={str(prio):12s} eligible {100*n_el/n:7.4f}%  "
              f"won {100*n_won/n:7.4f}%  keeps {share:5.1f}% of what it is eligible for")
        if n_el and share < 60:
            lost = Counter(result.biome_index[eligible & ~won])
            top = ", ".join(f"{key_by_index.get(i, i).split(':')[-1]} {100*c/n_el:.1f}%"
                            for i, c in lost.most_common(4))
            print(f"{'':40s}   loses to: {top}")


if __name__ == "__main__":
    main()
