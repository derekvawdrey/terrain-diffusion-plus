#!/usr/bin/env python3
"""Add the 16 BoP overworld biomes that ship in BiomesOPlenty-1.21.1 but were never
cataloged. Their main job is to break up minecraft:plains' 12% lowland dominance by
filling the open-land niche in every province, plus a few forest/beach/mountain gaps.

Province identities (see apply_regions.py): A Old World, B New World, C East & Pacific.
All climate windows respect the treeCoverage bucket physics ({0,0.35,0.62,0.85,1.0},
warm gsFactor~1 so moisture maps straight onto buckets).
"""
import json

P = "/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json"

A = {"variable": "regionNoise", "op": "lt", "value": -0.0967}
B = {"variable": "regionNoise", "op": "between", "value": -0.0967, "value2": 0.0967}
C = {"variable": "regionNoise", "op": "gt", "value": 0.0967}

def cond(var, op, v=None, v2=None, b=None):
    c = {"variable": var, "op": op}
    if b is not None: c["bool"] = b
    else:
        c["value"] = v
        if v2 is not None: c["value2"] = v2
    return c

def rule(zone, conds, rarity, noise=None):
    r = {"zone": zone, "conditions": conds, "rarity": rarity}
    if noise: r["noiseConditions"] = [noise]
    return r

T  = lambda lo, hi: cond("temperatureC", "between", lo, hi)
M  = lambda lo, hi: cond("moisture", "between", lo, hi)
TC = lambda lo, hi: cond("treeCoverage", "between", lo, hi)
NOSNOW = cond("snowy", "eq", b=False)

def entry(idx, key, fallback, kind, color, rules):
    return {
        "index": idx, "key": key, "fallbackKey": fallback, "kind": kind,
        "color": color, "hardBoundary": False, "blendable": True,
        "river": False, "frozenRiver": False, "canGenerateOverworld": True,
        "rules": rules,
    }

NEW = [
    # ---- open-land competitors for plains -----------------------------------
    entry(110, "biomesoplenty:pasture", "minecraft:plains", "OVERWORLD", 0xA8C46B, [
        rule("lowland", [T(8, 18), M(0.4, 0.8), cond("treeCoverage", "lte", 0.35), NOSNOW], 2.0, A),
    ]),
    entry(111, "biomesoplenty:field", "minecraft:plains", "OVERWORLD", 0xB5C979, [
        rule("lowland", [T(8, 20), M(0.35, 0.75), cond("treeCoverage", "lte", 0.35), NOSNOW], 2.0, B),
    ]),
    entry(112, "biomesoplenty:forested_field", "minecraft:plains", "OVERWORLD", 0x8FA95B, [
        rule("lowland", [T(8, 18), M(0.4, 0.8), TC(0.3, 0.7), NOSNOW], 1.5, B),
    ]),
    entry(113, "biomesoplenty:overgrown_greens", "minecraft:plains", "OVERWORLD", 0x66C060, [
        rule("lowland", [T(10, 18), M(0.6, 1.0), cond("treeCoverage", "lte", 0.35), NOSNOW], 1.2, A),
    ]),
    entry(114, "biomesoplenty:shrubland", "minecraft:savanna", "OVERWORLD", 0x9CA65A, [
        # Mediterranean maquis, next door to mediterranean_forest
        rule("lowland", [T(15, 28), M(0.2, 0.5), cond("treeCoverage", "lte", 0.35), NOSNOW], 1.5, A),
    ]),
    entry(115, "biomesoplenty:scrubland", "minecraft:savanna", "OVERWORLD", 0xB8A05C, [
        # Australian bush, pairs with dryland
        rule("lowland", [T(18, 30), M(0.15, 0.45), cond("treeCoverage", "lte", 0.35), NOSNOW], 1.5, C),
    ]),
    entry(116, "biomesoplenty:rocky_shrubland", "minecraft:savanna", "OVERWORLD", 0xA39A6E, [
        rule("lowland", [T(15, 28), M(0.15, 0.5), cond("treeCoverage", "lte", 0.35),
                         cond("slope", "gte", 0.3), NOSNOW], 1.5, C),
    ]),
    entry(117, "biomesoplenty:floodplain", "minecraft:swamp", "OVERWORLD", 0x77AB4E, [
        # Asian river lowlands
        rule("lowland", [T(15, 30), cond("moisture", "gte", 0.8), cond("treeCoverage", "lte", 0.7),
                         cond("elevationM", "lte", 300), NOSNOW], 2.0, C),
    ]),
    entry(118, "biomesoplenty:pumpkin_patch", "minecraft:plains", "OVERWORLD", 0xC9843C, [
        # autumn Americana accent -- clearing-noise patch, not province-wide
        rule("lowland", [T(5, 15), M(0.5, 0.9), TC(0.3, 0.7), NOSNOW], 6.0,
             {"variable": "clearingNoise", "op": "gt", "value": 0.3968}),
    ]),
    # ---- forest / mountain gaps ---------------------------------------------
    entry(119, "biomesoplenty:old_growth_dead_forest", "minecraft:taiga", "OVERWORLD", 0x6E6A4E, [
        rule("lowland", [T(3, 22), M(0.15, 0.55), TC(0.3, 0.7), NOSNOW], 1.0, C),
    ]),
    entry(120, "biomesoplenty:rocky_rainforest", "minecraft:jungle", "OVERWORLD", 0x3F7A44, [
        # tepui country, pairs with rainforest
        rule("lowland", [cond("temperatureC", "gte", 20), cond("moisture", "gte", 1.3),
                         cond("treeCoverage", "gte", 0.8), cond("slope", "gte", 0.3), NOSNOW], 1.5, B),
    ]),
    entry(121, "biomesoplenty:volcanic_plains", "minecraft:savanna", "OVERWORLD", 0x5C5650, [
        # pairs with volcano (which requires treeCoverage >= 0.35)
        rule("lowland", [cond("temperatureC", "gte", 22), M(0.2, 0.8),
                         cond("treeCoverage", "lte", 0.35), NOSNOW], 1.2, C),
    ]),
    entry(122, "biomesoplenty:hot_springs", "minecraft:taiga", "MOUNTAIN", 0x6FBFB4, [
        # Yellowstone
        rule("mountain", [T(-5, 10), cond("moisture", "gte", 0.4), NOSNOW], 1.5, B),
    ]),
    # ---- beaches -------------------------------------------------------------
    entry(123, "biomesoplenty:dune_beach", "minecraft:beach", "BEACH", 0xEFE1A6, [
        rule("beach", [cond("temperatureC", "gte", 18), cond("slope", "lte", 0.28), NOSNOW], 0.5),
    ]),
    entry(124, "biomesoplenty:gravel_beach", "minecraft:stony_shore", "BEACH", 0x9B9B93, [
        rule("beach", [T(1, 12), cond("slope", "lte", 0.28), NOSNOW], 0.8),
    ]),
]

cat = json.load(open(P))
have = {b["key"] for b in cat}
used = {b["index"] for b in cat}
added = 0
for e in NEW:
    if e["key"] in have:
        print(f"skip {e['key']} (already present)")
        continue
    assert e["index"] not in used, f"index collision {e['index']}"
    cat.append(e)
    added += 1
    print(f"added #{e['index']:3d} {e['key']}")

json.dump(cat, open(P, "w"), indent=2)
print(f"\n{added} biomes added -> {len(cat)} total")
