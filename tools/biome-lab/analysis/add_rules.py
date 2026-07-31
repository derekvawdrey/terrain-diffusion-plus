"""Give the three rule-less overworld biomes a climate niche so they can actually generate.

old_growth_birch_forest, eroded_badlands and mushroom_fields all shipped with `"rules": []`, which
means BiomeRuleEngine could never select them under any climate -- they existed in the catalog as
registry entries and nothing more. Each niche below is carved out of an adjacent biome's existing
range so the new rule wins a genuine sub-band rather than being shadowed, following the same
priority-above-the-parent pattern the catalog already uses (e.g. badlands 70 over wooded_badlands
66).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def cond(variable, op, value=None, value2=None, boolean=None):
    d = {"variable": variable, "op": op}
    if value is not None:
        d["value"] = value
    if value2 is not None:
        d["value2"] = value2
    if boolean is not None:
        d["bool"] = boolean
    return d


NEW_RULES = {
    # The densest core of birch_forest's own temperate band. birch_forest keeps the sparser
    # buckets; this takes only the rainforest-bucket pixels (treeCoverage == 1.0) inside the same
    # variantNoise tail, so old growth reads as the mature heart of a birch patch.
    "minecraft:old_growth_birch_forest": [
        {
            "zone": "lowland",
            "priority": 43,
            "conditions": [
                cond("temperatureC", "between", 12, 19.99),
                cond("treeCoverage", "gte", 0.95),
                cond("snowy", "eq", boolean=False),
            ],
            "noiseConditions": [cond("variantNoise", "lt", -0.35)],
        },
    ],
    # Bare, wind-scoured rock in a hot arid climate -- exactly the bareSlope re-selection zone,
    # which until now only distinguished snowy (frozen_peaks, 80) from not-snowy (stony_peaks, 78).
    # Sitting above both lets the desert end of that zone read as badlands instead of grey stone.
    "minecraft:eroded_badlands": [
        {
            "zone": "bareSlope",
            "priority": 82,
            "conditions": [
                cond("temperatureC", "gte", 20),
                cond("moisture", "lt", 0.35),
                cond("snowy", "eq", boolean=False),
            ],
        },
    ],
    # Deliberately the rarest surface niche in the catalog: a warm, very wet, densely vegetated
    # lowland gated on the thin upper tail of variantNoise, so it shows up as isolated patches the
    # way vanilla's mushroom islands do. Priority is above every competing wet-lowland biome
    # (jungle 78, dark_forest, swamp) because the whole point is that it takes over where it fires.
    "minecraft:mushroom_fields": [
        {
            "zone": "lowland",
            "priority": 88,
            "conditions": [
                cond("temperatureC", "between", 10, 24),
                cond("moisture", "gte", 0.85),
                cond("treeCoverage", "gte", 0.8),
                cond("elevationM", "lte", 400),
                cond("snowy", "eq", boolean=False),
            ],
            "noiseConditions": [cond("variantNoise", "gt", 0.5)],
        },
    ],
}


def main():
    path = Path(sys.argv[1])
    catalog = json.loads(path.read_text())
    by_key = {e["key"]: e for e in catalog}

    for key, rules in NEW_RULES.items():
        entry = by_key.get(key)
        if entry is None:
            print(f"SKIP (not in catalog): {key}")
            continue
        if entry.get("rules"):
            print(f"SKIP (already has {len(entry['rules'])} rules): {key}")
            continue
        entry["rules"] = rules
        print(f"added {len(rules)} rule(s) to {key}")

    path.write_text(json.dumps(catalog, indent=2) + "\n")


if __name__ == "__main__":
    main()
