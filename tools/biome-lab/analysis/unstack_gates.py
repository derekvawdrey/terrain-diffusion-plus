"""Stop rules multiplying several independent noise gates together, and loosen a few over-tight ones.

A single noise gate at ~5% is a "rare accent". THREE independent gates AND-ed together is 5% x 4%
x 2% = 0.004%, which is not an accent, it is invisible -- and nothing in the static validators
catches it, because each gate is individually perfectly reachable. That stacking is why
flower_forest and sunflower_plains sat at ~0.02% area despite having 15 and 5 rules respectively:
their per-rule joint pass rates measured 0.0000%-0.0137%.

Now that `rarity` controls how much of a niche a biome takes, a gate no longer has to do that job
as well. Its only remaining purpose is to give the biome spatially coherent patches within its
niche, and one field does that just as well as three. So each rule keeps exactly one gate: the
biome's signature noise field where it has one, otherwise the loosest gate it already carried.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

LAB = Path("/home/derek/IdeaProjects/terrain-diffusion-plus/tools/biome-lab")
sys.path.insert(0, str(LAB))
from biomelab import noise_data  # noqa: E402

SIGNATURE_FIELD = {
    "minecraft:flower_forest": "flowerNoise",
    "minecraft:sunflower_plains": "flowerNoise",
    "minecraft:pale_garden": "paleNoise",
    "minecraft:cherry_grove": "cherryNoise",
}

# Gates that are simply too tight to leave a biome visible, with the replacement threshold.
RETHRESHOLD = {
    # authored in this session at the extreme tail (0.33% of pixels); 0.3 is ~6%, still a patchy
    # scattering of isolated islands but one a player can actually come across
    ("minecraft:mushroom_fields", "variantNoise"): 0.3,
    # the deep tail of birch_forest's own gate; -0.2 keeps it the minority core, not a myth
    ("minecraft:old_growth_birch_forest", "variantNoise"): -0.2,
}
# Gates worth dropping outright -- the biome's climate conditions are already the rare part.
DROP_GATE = {("minecraft:ice_spikes", "variantNoise")}


def pass_rate(field, cond, fams, rng, cache):
    if field not in cache:
        cache[field] = noise_data.family_for_variable(field, fams).sample(rng, 400_000)
    s = cache[field]
    op, v, v2 = cond["op"], cond.get("value"), cond.get("value2")
    if op == "gt":
        return float((s > v).mean())
    if op == "gte":
        return float((s >= v).mean())
    if op == "lt":
        return float((s < v).mean())
    if op == "lte":
        return float((s <= v).mean())
    if op == "between":
        return float(((s >= v) & (s <= v2)).mean())
    return 0.0


def main():
    path = Path(sys.argv[1])
    catalog = json.loads(path.read_text())
    fams = noise_data.load_families(LAB / "data" / "noise_quantiles")
    rng = np.random.default_rng(0)
    cache = {}

    unstacked = dropped = rethresholded = 0
    for entry in catalog:
        key = entry["key"]
        signature = SIGNATURE_FIELD.get(key)
        for rule in entry.get("rules", []):
            gates = rule.get("noiseConditions") or []
            if not gates:
                continue

            for gate in gates:
                pair = (key, gate["variable"])
                if pair in RETHRESHOLD:
                    gate["value"] = RETHRESHOLD[pair]
                    rethresholded += 1

            gates = [g for g in gates if (key, g["variable"]) not in DROP_GATE]
            if len(gates) != len(rule.get("noiseConditions") or []):
                dropped += 1

            if len(gates) > 1:
                keep = next((g for g in gates if g["variable"] == signature), None)
                if keep is None:
                    keep = max(gates, key=lambda g: pass_rate(g["variable"], g, fams, rng, cache))
                gates = [keep]
                unstacked += 1

            if gates:
                rule["noiseConditions"] = gates
            else:
                rule.pop("noiseConditions", None)

    path.write_text(json.dumps(catalog, indent=2) + "\n")
    print(f"un-stacked {unstacked} multi-gate rules, dropped {dropped} gates, "
          f"re-thresholded {rethresholded}")


if __name__ == "__main__":
    main()
