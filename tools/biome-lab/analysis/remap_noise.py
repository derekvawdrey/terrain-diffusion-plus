"""Rescale clearingNoise/flowerNoise thresholds onto their field's real distribution.

Both variables come from the oct3_gain054 FastNoiseLite family, whose measured range is about
[-0.781, +0.769] with a bell-shaped density -- but the catalog's thresholds (0.51 .. 0.89) were
picked as if the field were roughly uniform on [-1, 1]. The result: everything above 0.769 is
literally unreachable (14 dead conditions), and everything from 0.51 up passes well under 1% of
pixels, which is why flower_forest / sunflower_plains / the "forest clearing" variants of plains
and meadow are all effectively invisible in a generated world.

This remaps each distinct threshold to the value that yields a deliberately chosen pass rate,
preserving the author's rarity ORDERING exactly (a stricter original threshold stays stricter).
Complementary pairs matter: meadow's `flowerNoise lte X` is the exact complement of
flower_forest's `flowerNoise gt X` over the same climate niche, so both sides must be remapped to
the identical new X or the niche develops a gap or an overlap.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

LAB = Path("/home/derek/IdeaProjects/terrain-diffusion-plus/tools/biome-lab")
sys.path.insert(0, str(LAB))
from biomelab import noise_data  # noqa: E402

# original threshold -> intended pass rate for the ">" direction
CLEARING_TARGETS = {
    0.89: 0.02,
    0.82: 0.04,
    0.78: 0.06,
    0.67: 0.09,
    0.60: 0.12,
    0.55: 0.15,
    0.51: 0.18,
}
FLOWER_TARGETS = {
    0.72: 0.02,
    0.68: 0.03,
    0.66: 0.04,
    0.64: 0.05,
    0.55: 0.10,
}
# low-side gate: `flowerNoise lt -0.46` was meant as "the unflowered tail", not the bottom 0.7%
FLOWER_LOW_TARGETS = {-0.46: 0.10}


def main():
    path = Path(sys.argv[1])
    fams = noise_data.load_families(LAB / "data" / "noise_quantiles")
    rng = np.random.default_rng(0)
    field = noise_data.family_for_variable("clearingNoise", fams).sample(rng, 8_000_000)

    def hi(target):  # value v such that P(x > v) == target
        return round(float(np.quantile(field, 1.0 - target)), 4)

    def lo(target):  # value v such that P(x < v) == target
        return round(float(np.quantile(field, target)), 4)

    clearing = {k: hi(v) for k, v in CLEARING_TARGETS.items()}
    flower_hi = {k: hi(v) for k, v in FLOWER_TARGETS.items()}
    flower_lo = {k: lo(v) for k, v in FLOWER_LOW_TARGETS.items()}

    print("clearingNoise remap:")
    for k, v in sorted(clearing.items(), reverse=True):
        print(f"  {k:>6} -> {v:>7}   (pass {100*CLEARING_TARGETS[k]:.0f}%)")
    print("flowerNoise remap:")
    for k, v in sorted(flower_hi.items(), reverse=True):
        print(f"  {k:>6} -> {v:>7}   (pass {100*FLOWER_TARGETS[k]:.0f}%)")
    for k, v in flower_lo.items():
        print(f"  {k:>6} -> {v:>7}   (low tail {100*FLOWER_LOW_TARGETS[k]:.0f}%)")

    catalog = json.loads(path.read_text())
    changed = 0
    unmapped = set()
    for entry in catalog:
        for rule in entry.get("rules", []):
            for cond in rule.get("noiseConditions") or []:
                var, op, val = cond["variable"], cond["op"], cond.get("value")
                if var == "clearingNoise" and op in ("gt", "gte"):
                    table = clearing
                elif var == "flowerNoise" and op in ("gt", "gte", "lte", "lt"):
                    table = flower_lo if op in ("lt",) else flower_hi
                else:
                    continue
                if val not in table:
                    unmapped.add((var, op, val))
                    continue
                cond["value"] = table[val]
                changed += 1

    if unmapped:
        print("\nWARNING: thresholds with no mapping entry (left untouched):")
        for u in sorted(unmapped, key=str):
            print("  ", u)

    path.write_text(json.dumps(catalog, indent=2) + "\n")
    print(f"\nRewrote {changed} noise conditions in {path}")


if __name__ == "__main__":
    main()
