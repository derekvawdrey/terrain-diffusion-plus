#!/usr/bin/env python3
"""Build the shipped 1.21.1 catalog: the region-design base, plus mod-gated entries for the
optional integrations (Biomes O' Plenty, Sengoku Jidai).

Two things happen here.

1. Every `biomesoplenty:` entry gets `requiredMods: ["biomesoplenty"]`. Previously BoP support
   meant shipping a whole second catalog file that nothing ever loaded; with the gate, one file
   covers every install and TerrainBiomeRegistry.build() drops what isn't there.

2. Sengoku Jidai's 15 biomes are added, gated on `requiredMods: ["sengoku"]`.

   Sengoku *redefines* the 64 vanilla biomes in place rather than adding alongside them, so the
   Japanese look already arrives on the existing catalog with no entries at all -- every
   minecraft:forest simply is a Japanese forest once the mod is installed. What needs cataloging
   is only its 15 genuinely-new biomes, which is what this adds.

   Of those 15:
     * 12 are surface biomes and get climate rules here.
     * caverns / suisho_caves are cave biomes. Cave placement doesn't run through the rule
       engine at all (see TerrainDiffusionBiomeSource.selectUndergroundBiome, and note that
       lush_caves/dripstone_caves/deep_dark carry zero rules), so they are added rule-less and
       selected in Java.
     * autumnal_river is left out. Rivers come from the hydrology network via
       TerrainBiomeRegistry.riverBiomeIndex(), not from rules, so a second river biome needs
       hydrology work rather than a catalog entry -- and minecraft:river is already Sengoku's
       own river once the mod is installed.

   Every Sengoku rule carries exactly one noise condition, `japanRegion gte 0`, keeping the
   catalog's "no rule stacks noise gates" invariant. japanRegion is a margin around the region
   border (BiomeClassifier.sampleJapanRegion), so that single condition keeps working as
   biome.japan_region_share is retuned -- at the shipped 1.0 it is satisfied everywhere.

Run:  python3 analysis/add_sengoku.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "versions/1.21.1/common/src/main/resources/biome_catalog_BoP.json"
OUT = ROOT / "versions/1.21.1/common/src/main/resources/biome_catalog.json"

BOP = "biomesoplenty"
SENGOKU = "sengoku"

# Inside the Japan region. One condition, so Sengoku rules never stack noise gates; a no-op at
# the shipped biome.japan_region_share=1.0 and a real border below it.
JAPAN = {"variable": "japanRegion", "op": "gte", "value": 0}


def cond(var, op, v=None, v2=None, b=None):
    c = {"variable": var, "op": op}
    if b is not None:
        c["bool"] = b
    else:
        c["value"] = v
        if v2 is not None:
            c["value2"] = v2
    return c


def rule(zone, conds, rarity):
    return {"zone": zone, "conditions": conds, "rarity": rarity, "noiseConditions": [JAPAN]}


T = lambda lo, hi: cond("temperatureC", "between", lo, hi)
M = lambda lo, hi: cond("moisture", "between", lo, hi)
SNOW = cond("snowy", "eq", b=True)
NOSNOW = cond("snowy", "eq", b=False)
# treeCoverage only ever takes {0, 0.35, 0.62, 0.85, 1.0}, so thresholds sit on bucket edges.
TREED = cond("treeCoverage", "gte", 0.62)
SOMETREES = cond("treeCoverage", "gte", 0.35)
OPEN = cond("treeCoverage", "lte", 0.35)
GROWS = cond("growingSeasonDays", "gte", 60)


def entry(idx, key, fallback, color, rules, blendable=True):
    return {
        "index": idx, "key": key, "fallbackKey": fallback, "kind": "OVERWORLD",
        "color": color, "hardBoundary": False, "blendable": blendable,
        "river": False, "frozenRiver": False, "canGenerateOverworld": True,
        "requiredMods": [SENGOKU],
        "rules": rules,
    }


# Climate windows are read off each biome's own definition in the mod jar (temperature /
# downfall) and then placed in the niche the existing catalog leaves for that combination.
NEW = [
    # ---- cold forests (mod temperature -0.5) --------------------------------------------
    entry(125, "sengoku:snowy_forest", "minecraft:snowy_taiga", 0x6A8A78, [
        rule("lowland", [T(-8, 2), SOMETREES, M(0.45, 1.0), SNOW, GROWS], 1.1),
        rule("mountain", [T(-8, 1), SOMETREES, M(0.45, 1.0), SNOW], 0.8),
    ]),
    entry(126, "sengoku:snowy_dark_forest", "minecraft:dark_forest", 0x4A5C4E, [
        # The denser, colder counterpart to snowy_forest. Its window deliberately does NOT
        # follow the mod's low downfall (0.4) into a moisture cap: treeCoverage is derived from
        # moisture, so pairing "treeCoverage >= 0.62" with "moisture <= 0.62" is very nearly a
        # contradiction and measured 0.001% of area. Overlapping snowy_forest is fine -- the
        # engine's weighted competition is what separates them.
        rule("lowland", [T(-10, 0), TREED, M(0.5, 1.0), SNOW], 0.55),
    ]),

    # ---- temperate forests (mod temperature 0.7, downfall 0.9) --------------------------
    entry(127, "sengoku:ginkgo_forest", "minecraft:forest", 0xC8B24A, [
        rule("lowland", [T(7, 17), TREED, M(0.45, 1.0), NOSNOW, GROWS], 0.7),
        rule("mountain", [T(7, 15), TREED, M(0.45, 1.0), NOSNOW], 0.45),
    ]),
    entry(128, "sengoku:autumnal_bamboo_forest", "minecraft:bamboo_jungle", 0xB5762E, [
        rule("lowland", [T(11, 21), TREED, M(0.6, 1.0), NOSNOW, GROWS], 0.6),
    ]),

    # ---- hot springs (downfall 0.0; volcanic, upland) -----------------------------------
    # Mountain-zone like biomesoplenty:hot_springs, which shares the niche -- both stay rare
    # enough to read as landmarks rather than terrain.
    entry(129, "sengoku:hotsprings", "minecraft:taiga", 0x8FBFC4, [
        rule("mountain", [T(-4, 11), M(0.4, 1.0), NOSNOW], 0.9),
    ]),
    entry(130, "sengoku:hotsprings_autumnal", "minecraft:taiga", 0xC07A3A, [
        rule("mountain", [T(4, 14), M(0.4, 1.0), NOSNOW], 0.7),
    ]),
    entry(131, "sengoku:hotsprings_snowy", "minecraft:snowy_taiga", 0xBFD8DE, [
        rule("mountain", [T(-12, 0), M(0.35, 1.0), SNOW], 0.8),
    ]),

    # ---- flower fields (mod temperature 0.95/0.0, open ground) --------------------------
    # Open lowland accents. They deliberately overlap each other's windows and lean on the
    # engine's weighted competition for patchiness, the way the vanilla flower biomes do.
    entry(132, "sengoku:kerria_field", "minecraft:meadow", 0xE8C33A, [
        rule("lowland", [T(12, 24), OPEN, M(0.35, 0.85), NOSNOW, GROWS], 0.5),
    ]),
    entry(133, "sengoku:lily_field", "minecraft:meadow", 0xE0E4C8, [
        rule("lowland", [T(12, 24), OPEN, M(0.4, 0.9), NOSNOW, GROWS], 0.5),
    ]),
    entry(134, "sengoku:rose_field", "minecraft:meadow", 0xC2445A, [
        rule("lowland", [T(10, 22), OPEN, M(0.35, 0.85), NOSNOW, GROWS], 0.45),
    ]),
    entry(135, "sengoku:spider_lily_field", "minecraft:meadow", 0xB03A2E, [
        # Mod temperature 0.0 and downfall 0.5: the cool, drier field of the four.
        rule("lowland", [T(2, 12), OPEN, M(0.3, 0.7), NOSNOW, GROWS], 0.45),
    ]),

    # ---- coast -------------------------------------------------------------------------
    entry(136, "sengoku:autumnal_beach", "minecraft:beach", 0xD9C27A, [
        rule("beach", [T(8, 22), NOSNOW], 0.6),
    ]),

    # ---- cave biomes -------------------------------------------------------------------
    # No rules on purpose: selectUndergroundBiome picks these in Java, exactly as it does for
    # lush_caves / dripstone_caves / deep_dark, which are likewise rule-less here.
    entry(137, "sengoku:caverns", "minecraft:dripstone_caves", 0x6E6A5E, [], blendable=False),
    entry(138, "sengoku:suisho_caves", "minecraft:lush_caves", 0x9FD6E8, [], blendable=False),
]


def main():
    catalog = json.loads(BASE.read_text())

    tagged = 0
    for settlement in catalog:
        if settlement["key"].startswith(BOP + ":") and "requiredMods" not in settlement:
            settlement["requiredMods"] = [BOP]
            tagged += 1

    existing = {s["key"] for s in catalog}
    used_indices = {s["index"] for s in catalog}
    added = 0
    for settlement in NEW:
        if settlement["key"] in existing:
            raise SystemExit(f"{settlement['key']} is already in the catalog")
        if settlement["index"] in used_indices:
            raise SystemExit(f"index {settlement['index']} is already taken")
        used_indices.add(settlement["index"])
        catalog.append(settlement)
        added += 1

    OUT.write_text(json.dumps(catalog, indent=2) + "\n")
    print(f"tagged {tagged} Biomes O' Plenty entries with requiredMods")
    print(f"added {added} Sengoku Jidai entries")
    print(f"wrote {len(catalog)} settlements to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
