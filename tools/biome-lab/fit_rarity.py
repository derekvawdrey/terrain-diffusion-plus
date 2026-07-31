"""Migrate a biome catalog from integer `priority` to `rarity` weights, fitted to target shares.

    python3 fit_rarity.py <biome_catalog.json> <pipeline_data.json> [n_samples]

REWRITES THE CATALOG IN PLACE -- back it up first. See MIGRATION-rarity.md for the full walkthrough.

Weights cannot be read off the old priorities -- priority expressed a strict ordering, rarity
expresses a proportion, and there is no formula between them. So this fits them empirically:
start every rule at 1.0, run the real Monte Carlo, and repeatedly nudge each biome's weight toward
the share we want it to have. Because a biome can only ever win pixels it is ELIGIBLE for, every
target is first capped below that ceiling -- a biome that misses its target after fitting needs
its conditions widened, not a bigger weight, and the script says so explicitly rather than
inflating the number until it looks fixed.

Targets: keep each biome's existing character (its share before the migration) but lift anything
below a floor, so every surface biome clears the encounterability bar. Biomes named in
DELIBERATELY_RARE get a lower floor -- they are supposed to be a find, just not a myth.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

LAB = Path(__file__).resolve().parent
sys.path.insert(0, str(LAB))

from biomelab import catalog as catalog_mod  # noqa: E402
from biomelab import climate, montecarlo, noise_data  # noqa: E402
from biomelab.engine import _eval_rule  # noqa: E402

SURFACE_KINDS = {"OVERWORLD", "MOUNTAIN", "BEACH", "OCEAN"}

# Share of the whole world, in percent, that a surface biome should reach at minimum.
FLOOR = 0.15
RARE_FLOOR = 0.06
DELIBERATELY_RARE = {
    "minecraft:mushroom_fields", "minecraft:ice_spikes", "minecraft:jagged_peaks",
    "minecraft:frozen_peaks", "minecraft:eroded_badlands", "minecraft:old_growth_birch_forest",
    "minecraft:deep_frozen_ocean", "minecraft:frozen_ocean", "minecraft:snowy_beach",
    "minecraft:stony_shore",
}
# A biome can realistically hold about this fraction of what it is eligible for; asking for more
# than the ceiling just pins the weight at its cap and hides a conditions problem.
ELIGIBILITY_HEADROOM = 0.85

ITERATIONS = 40
DAMPING = 0.5
W_MIN, W_MAX = 0.02, 10.0

# The catalog's default/fallback biome. Every pixel that matches no rule at all is assigned to it
# by TerrainBiomeRegistry.defaultBiomeIndex(), so its share is whatever the rest of the catalog
# leaves over -- fitting a weight for it is meaningless (it stays over target even pinned at
# W_MIN) and would drag the whole solve. Its excess is reported as a coverage gap instead.
FALLBACK_KEY = "minecraft:plains"


def strip_priority(catalog_raw):
    """Drop `priority` and seed every rule at weight 1.0."""
    for entry in catalog_raw:
        for rule in entry.get("rules", []):
            rule.pop("priority", None)
            rule["rarity"] = 1.0


def main():
    path = Path(sys.argv[1])
    pipeline_path = sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 1_500_000

    raw = json.loads(path.read_text())
    strip_priority(raw)
    path.write_text(json.dumps(raw, indent=2) + "\n")

    cat = catalog_mod.load(path)
    pdata = climate.load_pipeline_data(pipeline_path)
    families = noise_data.load_families(LAB / "data" / "noise_quantiles")
    samples = climate.simulate(pdata, families, n, seed=0)
    zone = montecarlo.assign_zones(samples)
    zone_mask = {
        "ocean": zone == "ocean",
        "beach": zone == "beach",
        "mountain": zone == "mountain",
        "lowland": zone == "lowland",
        "bareSlope": samples.bareSlope & (~samples.ocean) & (~samples.mountain),
    }

    def get(var):
        return getattr(samples, var)

    surface = [s for s in cat.settlements if s.kind in SURFACE_KINDS and s.rules]
    fitted = [s for s in surface if s.key != FALLBACK_KEY]

    # Eligibility ceiling per biome, and the pre-migration share used as the baseline target.
    eligibility = {}
    for s in surface:
        el = np.zeros(n, dtype=bool)
        for rule in s.rules:
            base = zone_mask.get(rule.zone)
            if base is None:
                continue
            el |= _eval_rule(rule.conditions, rule.noise_conditions, get) & base
        eligibility[s.key] = 100.0 * el.mean()

    baseline = measure(cat, samples)

    targets = {}
    for s in fitted:
        floor = RARE_FLOOR if s.key in DELIBERATELY_RARE else FLOOR
        want = max(baseline.get(s.key, 0.0), floor)
        ceiling = eligibility[s.key] * ELIGIBILITY_HEADROOM
        targets[s.key] = min(want, ceiling) if ceiling > 0 else 0.0

    weights = {s.key: 1.0 for s in surface}
    for it in range(ITERATIONS):
        apply_weights(cat, weights)
        share = measure(cat, samples)
        worst = 0.0
        for key, target in targets.items():
            if target <= 0:
                continue
            got = max(share.get(key, 0.0), 1e-6)
            # An uncontested biome wins 100% of its eligibility no matter how small its weight
            # is; chasing a target below that ceiling just pins the weight at W_MIN for nothing.
            if got >= eligibility[key] * 0.995:
                continue
            ratio = target / got
            worst = max(worst, abs(np.log(ratio)))
            weights[key] = float(np.clip(weights[key] * ratio ** DAMPING, W_MIN, W_MAX))
        if it % 10 == 9 or worst < 0.05:
            print(f"  iter {it+1:3d}  max log-ratio error {worst:.3f}")
        if worst < 0.05:
            break

    apply_weights(cat, weights)
    share = measure(cat, samples)

    print(f"\n{'biome':42s} {'eligible':>9s} {'target':>8s} {'got':>8s} {'weight':>8s}")
    unmet = []
    for s in sorted(surface, key=lambda x: x.key):
        got = share.get(s.key, 0.0)
        tgt = targets.get(s.key, 0.0)
        flag = ""
        if tgt > 0 and got < 0.6 * tgt:
            flag = "  <-- eligibility-limited, widen conditions"
            unmet.append(s.key)
        print(f"{s.key:42s} {eligibility[s.key]:8.4f}% {tgt:7.4f}% {got:7.4f}% "
              f"{weights[s.key]:8.3f}{flag}")

    # Persist the fitted weights, rounded for readability.
    for entry in cat.raw:
        key = entry["key"]
        if key not in weights:
            continue
        for rule in entry.get("rules", []):
            rule["rarity"] = round(weights[key], 3)
    path.write_text(json.dumps(cat.raw, indent=2) + "\n")
    gap = share.get(FALLBACK_KEY, 0.0) - eligibility.get(FALLBACK_KEY, 0.0)
    if gap > 0.01:
        print(f"\nCOVERAGE GAP: {gap:.2f}% of the world matches no rule at all and falls back to "
              f"{FALLBACK_KEY} (it wins {share.get(FALLBACK_KEY, 0):.2f}% but is only eligible for "
              f"{eligibility.get(FALLBACK_KEY, 0):.2f}%).")
    print(f"\nwrote fitted rarities to {path}")
    if unmet:
        print("still eligibility-limited:", ", ".join(unmet))


def apply_weights(cat, weights):
    for s in cat.settlements:
        if s.key in weights:
            for rule in s.rules:
                rule.rarity = weights[s.key]


def measure(cat, samples):
    result = montecarlo.classify(cat, samples)
    key_by_index = {s.index: s.key for s in cat.settlements}
    idx, counts = np.unique(result.biome_index, return_counts=True)
    return {key_by_index[i]: 100.0 * c / samples.n for i, c in zip(idx, counts) if i in key_by_index}


if __name__ == "__main__":
    main()
