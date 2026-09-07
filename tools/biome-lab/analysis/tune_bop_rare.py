#!/usr/bin/env python3
"""Lift the Biomes O' Plenty biomes the Monte Carlo shows below the 0.05% encounterability bar.

Run on the shipped catalog (2026-09-06 baseline: 13 BoP biomes below the bar, most between
0.008% and 0.05%). Each edit widens the biome's own climate window toward what the model actually
produces where it already wins (report section 3e), or raises its rarity against the universal
baseline it competes with; province gates are never changed, so every biome stays grouped with
its neighbours. Usage: tune_bop_rare.py <catalog.json> [output.json]
"""
import json, sys

def set_cond(rule, var, **fields):
    for c in rule["conditions"]:
        if c["variable"] == var:
            c.update(fields)
            return True
    return False

def set_noise(rule, var, **fields):
    for c in rule.get("noiseConditions", []):
        if c["variable"] == var:
            c.update(fields)
            return True
    return False

EDITS = {
    "biomesoplenty:muskeg": lambda r: (set_cond(r, "moisture", value=0.5, value2=1.2),
                                       set_cond(r, "treeCoverage", value=0.62)),
    "biomesoplenty:wintry_origin_valley": lambda r: (r.__setitem__("rarity", 6.0),
                                                     set_cond(r, "treeCoverage", value=0.62),
                                                     set_cond(r, "elevationM", value=1500)),
    "biomesoplenty:overgrown_greens": lambda r: (r.__setitem__("rarity", 4.0),
                                                 set_cond(r, "moisture", value=0.5, value2=1.3)),
    "biomesoplenty:floodplain": lambda r: (r.__setitem__("rarity", 5.0),
                                           set_cond(r, "elevationM", value=900)),
    "biomesoplenty:crag": lambda r: (set_cond(r, "temperatureC", value=-5, value2=20),
                                     set_cond(r, "moisture", value=0.15)),
    "biomesoplenty:rocky_rainforest": lambda r: (r.__setitem__("rarity", 4.0),
                                                 set_cond(r, "moisture", value=1.0),
                                                 set_cond(r, "slope", value=0.18)),
    "biomesoplenty:jacaranda_glade": lambda r: (set_noise(r, "flowerNoise", value=0.3),
                                                set_cond(r, "moisture", value=0.35, value2=0.9)),
    "biomesoplenty:pumpkin_patch": lambda r: (set_noise(r, "clearingNoise", value=0.2),
                                              set_cond(r, "treeCoverage", value=0.2, value2=0.7)),
    "biomesoplenty:origin_valley": lambda r: (set_noise(r, "clearingNoise", value=0.3),
                                              set_cond(r, "treeCoverage", value=0.0, value2=0.62),
                                              set_cond(r, "moisture", value=0.35, value2=0.9)),
    "biomesoplenty:ominous_woods": lambda r: (set_noise(r, "variantNoise", value=-0.3),
                                              set_cond(r, "moisture", value=0.6)),
    "biomesoplenty:dune_beach": lambda r: r.__setitem__("rarity", 1.5),
    "biomesoplenty:gravel_beach": lambda r: r.__setitem__("rarity", 2.0),
}

def auroral(rule):
    if rule.get("zone") == "lowland":
        set_noise(rule, "paleNoise", value=0.3)
        set_cond(rule, "treeCoverage", value=0.0, value2=0.35)
    else:
        set_noise(rule, "paleNoise", value=0.1)

def marsh(rule):
    rule["rarity"] = round(rule.get("rarity", 1.0) * 1.6, 3)

EDITS["biomesoplenty:marsh"] = marsh

EDITS["biomesoplenty:auroral_garden"] = auroral

def main():
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else src
    cat = json.load(open(src))
    touched = 0
    for biome in cat:
        edit = EDITS.get(biome["key"])
        if not edit:
            continue
        for rule in biome.get("rules", []):
            edit(rule)
        touched += 1
        print("tuned", biome["key"])
    json.dump(cat, open(dst, "w"), indent=2)
    print(f"{touched} biomes tuned -> {dst}")

if __name__ == "__main__":
    main()
