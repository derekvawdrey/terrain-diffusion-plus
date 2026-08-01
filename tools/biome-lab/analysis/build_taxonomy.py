#!/usr/bin/env python3
"""Build province-gated biome taxonomy and generate rules from it.

This script creates a comprehensive climate-space coverage grid for all 93 surface biomes.
Each climate cell (temp band x treeCoverage bucket x zone) gets:
  - One ungated "base" biome (always eligible, guarantees no holes)
  - Optional province-gated variants that replace the base within their regionNoise band

Province bands (terciles of regionNoise, verified at +/-0.0967):
  A: regionNoise < -0.0967   (low band, ~33%)
  B: |regionNoise| <= 0.0967  (mid band, ~33%)
  C: regionNoise > 0.0967    (high band, ~33%)

Usage:
  python3 build_taxonomy.py <input_catalog.json> <output_catalog.json>

The output catalog replaces all surface-biome rules with taxonomy-generated rules.
Ocean, beach, cave, nether, end, river biomes are left unchanged.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# Province gate thresholds (terciles of regionNoise)
PROV_LOW = -0.0967
PROV_HIGH = 0.0967


def cond(variable, op, value=None, value2=None, boolean=None):
    d = {"variable": variable, "op": op}
    if value is not None:
        d["value"] = value
    if value2 is not None:
        d["value2"] = value2
    if boolean is not None:
        d["bool"] = boolean
    return d


def noise_gate(province):
    """Return regionNoise noiseCondition for a province band, or None for ungated."""
    if province == "A":
        return cond("regionNoise", "lt", PROV_LOW)
    if province == "B":
        return cond("regionNoise", "between", PROV_LOW, PROV_HIGH)
    if province == "C":
        return cond("regionNoise", "gt", PROV_HIGH)
    return None  # ungated


# ============================================================================
# Taxonomy: every surface biome assigned to (zone, temp, veg, province)
# ============================================================================
#
# Temp bands:
#   FROZEN: temp < -2
#   COLD:   -2 <= temp < 5
#   COOL:   5 <= temp < 12
#   TEMP:   12 <= temp < 19
#   WARM:   19 <= temp < 26
#   HOT:    temp >= 26
#
# Veg buckets (treeCoverage):
#   bare:    0.00
#   sparse:  0.35
#   forest:  0.62
#   dense:   0.85
#   rainforest: 1.00
#
# Format per cell:
#   (zone, temp_band, veg): [
#       {"biome": "key", "province": None, "rarity": float},   # base (ungated)
#       {"biome": "key", "province": "A", "rarity": float},     # variant A
#       ...
#   ]
#
# Province None = ungated base (exactly one per cell)
# Province A/B/C = province-gated variant

TAXONOMY = {}

def cell(zone, temp, veg):
    return (zone, temp, veg)

def entry(biome, province=None, rarity=1.0):
    return {"biome": biome, "province": province, "rarity": rarity}

# =========================================================================
# LOWLAND biomes — the bulk of the taxonomy
# =========================================================================

# --- HOT + bare (desert, wasteland, dryland) ---
TAXONOMY[cell("lowland", "HOT", "bare")] = [
    entry("minecraft:desert", None, 1.0),           # base: classic desert
    entry("biomesoplenty:wasteland", "A", 1.0),      # very hot + very dry province
    entry("biomesoplenty:dryland", "C", 1.0),        # hot dry with some structure
]

# --- HOT + sparse (sparse jungle, tropical scrub) ---
TAXONOMY[cell("lowland", "HOT", "sparse")] = [
    entry("minecraft:sparse_jungle", None, 1.0),
    entry("biomesoplenty:lush_savanna", "A", 1.0),   # hot sparse with moisture
    entry("biomesoplenty:tropics", "C", 0.3),         # rare tropical accent
]

# --- HOT + forest (jungle edge, volcano) ---
TAXONOMY[cell("lowland", "HOT", "forest")] = [
    entry("minecraft:jungle", None, 1.0),
    entry("biomesoplenty:volcano", "C", 1.0),
]

# --- HOT + dense/rainforest (jungle, bamboo, fungal, rainforest) ---
TAXONOMY[cell("lowland", "HOT", "dense")] = [
    entry("minecraft:jungle", None, 1.0),
    entry("minecraft:bamboo_jungle", "A", 0.5),
    entry("biomesoplenty:rainforest", "C", 1.0),
]

TAXONOMY[cell("lowland", "HOT", "rainforest")] = [
    entry("minecraft:jungle", None, 1.0),
    entry("biomesoplenty:fungal_jungle", "A", 0.5),
    entry("biomesoplenty:rainforest", "C", 1.0),
]

# --- WARM + bare (savanna, badlands, desert edge) ---
TAXONOMY[cell("lowland", "WARM", "bare")] = [
    entry("minecraft:savanna", None, 1.0),
    entry("minecraft:badlands", "A", 1.0),
    entry("biomesoplenty:lush_desert", "C", 1.0),
]

# --- WARM + sparse (savanna, prairie, orchard) ---
TAXONOMY[cell("lowland", "WARM", "sparse")] = [
    entry("minecraft:savanna", None, 1.0),
    entry("biomesoplenty:prairie", "A", 1.0),
    entry("biomesoplenty:orchard", "C", 1.0),
]

# --- WARM + forest (mediterranean, dead_forest, swamp) ---
TAXONOMY[cell("lowland", "WARM", "forest")] = [
    entry("biomesoplenty:mediterranean_forest", None, 1.0),
    entry("minecraft:swamp", "A", 1.0),
    entry("biomesoplenty:dead_forest", "C", 1.0),
]

# --- WARM + dense (mangrove, bayou, swamp) ---
TAXONOMY[cell("lowland", "WARM", "dense")] = [
    entry("minecraft:swamp", None, 1.0),
    entry("minecraft:mangrove_swamp", "A", 1.0),
    entry("biomesoplenty:bayou", "C", 1.0),
]

# --- WARM + rainforest ---
TAXONOMY[cell("lowland", "WARM", "rainforest")] = [
    entry("minecraft:swamp", None, 1.0),
    entry("biomesoplenty:bog", "A", 1.0),
]

# --- TEMPERATE + bare (plains, meadow) ---
TAXONOMY[cell("lowland", "TEMP", "bare")] = [
    entry("minecraft:plains", None, 1.0),
    entry("minecraft:meadow", "A", 1.0),
    entry("minecraft:sunflower_plains", "C", 1.0),
]

# --- TEMPERATE + sparse (plains, flower_forest, lavender) ---
TAXONOMY[cell("lowland", "TEMP", "sparse")] = [
    entry("minecraft:plains", None, 1.0),
    entry("minecraft:flower_forest", "A", 1.0),
    entry("biomesoplenty:lavender_field", "C", 0.5),
]

# --- TEMPERATE + forest (forest, dark_forest, maple, woodland) ---
TAXONOMY[cell("lowland", "TEMP", "forest")] = [
    entry("minecraft:forest", None, 1.0),
    entry("minecraft:dark_forest", "A", 1.0),
    entry("biomesoplenty:maple_woods", "C", 1.0),
]

# --- TEMPERATE + dense (dark_forest, ominous, birch) ---
TAXONOMY[cell("lowland", "TEMP", "dense")] = [
    entry("minecraft:dark_forest", None, 1.0),
    entry("biomesoplenty:ominous_woods", "A", 0.5),
    entry("minecraft:birch_forest", "C", 1.0),
]

# --- TEMPERATE + rainforest (old_growth_birch, pale_garden) ---
TAXONOMY[cell("lowland", "TEMP", "rainforest")] = [
    entry("minecraft:dark_forest", None, 1.0),
    entry("minecraft:old_growth_birch_forest", "A", 0.3),
    entry("minecraft:pale_garden", "C", 1.0),
]

# --- COOL + bare (plains, moor, grassland) ---
TAXONOMY[cell("lowland", "COOL", "bare")] = [
    entry("minecraft:plains", None, 1.0),
    entry("biomesoplenty:moor", "A", 1.0),
    entry("biomesoplenty:grassland", "C", 1.0),
]

# --- COOL + sparse (taiga, fir_clearing, seasonal) ---
TAXONOMY[cell("lowland", "COOL", "sparse")] = [
    entry("minecraft:taiga", None, 1.0),
    entry("biomesoplenty:fir_clearing", "A", 0.5),
    entry("biomesoplenty:seasonal_forest", "C", 1.0),
]

# --- COOL + forest (taiga, coniferous, redwood, aspen) ---
TAXONOMY[cell("lowland", "COOL", "forest")] = [
    entry("minecraft:taiga", None, 1.0),
    entry("biomesoplenty:coniferous_forest", "A", 1.0),
    entry("biomesoplenty:redwood_forest", "C", 1.0),
]

# --- COOL + dense (old_growth_taiga, bog, wetland) ---
TAXONOMY[cell("lowland", "COOL", "dense")] = [
    entry("minecraft:old_growth_pine_taiga", None, 1.0),
    entry("biomesoplenty:bog", "A", 1.0),
    entry("biomesoplenty:wetland", "C", 1.0),
]

# --- COOL + rainforest ---
TAXONOMY[cell("lowland", "COOL", "rainforest")] = [
    entry("minecraft:old_growth_pine_taiga", None, 1.0),
    entry("biomesoplenty:old_growth_woodland", "C", 1.0),
]

# --- COLD + bare (snowy_plains, tundra, muskeg) ---
TAXONOMY[cell("lowland", "COLD", "bare")] = [
    entry("minecraft:snowy_plains", None, 1.0),
    entry("biomesoplenty:tundra", "A", 1.0),
    entry("biomesoplenty:muskeg", "C", 0.3),
]

# --- COLD + sparse (snowy_taiga, snowy_maple, wintry_origin) ---
TAXONOMY[cell("lowland", "COLD", "sparse")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
    entry("biomesoplenty:snowy_maple_woods", "A", 1.0),
    entry("biomesoplenty:wintry_origin_valley", "C", 0.5),
]

# --- COLD + forest (snowy_taiga, snowy_coniferous, snowblossom) ---
TAXONOMY[cell("lowland", "COLD", "forest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
    entry("biomesoplenty:snowy_coniferous_forest", "A", 1.0),
    entry("biomesoplenty:snowblossom_grove", "C", 1.0),
]

# --- COLD + dense ---
TAXONOMY[cell("lowland", "COLD", "dense")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
    entry("biomesoplenty:snowy_fir_clearing", "A", 1.0),
]

# --- COLD + rainforest ---
TAXONOMY[cell("lowland", "COLD", "rainforest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- FROZEN + bare ---
TAXONOMY[cell("lowland", "FROZEN", "bare")] = [
    entry("minecraft:snowy_plains", None, 1.0),
    entry("minecraft:ice_spikes", "A", 0.3),
    entry("biomesoplenty:cold_desert", "C", 0.5),
]

# --- FROZEN + sparse ---
TAXONOMY[cell("lowland", "FROZEN", "sparse")] = [
    entry("minecraft:snowy_plains", None, 1.0),
    entry("biomesoplenty:origin_valley", "C", 0.5),
]

# --- FROZEN + forest ---
TAXONOMY[cell("lowland", "FROZEN", "forest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- FROZEN + dense ---
TAXONOMY[cell("lowland", "FROZEN", "dense")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- FROZEN + rainforest ---
TAXONOMY[cell("lowland", "FROZEN", "rainforest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# =========================================================================
# MOUNTAIN biomes
# =========================================================================

# --- HOT mountain + bare ---
TAXONOMY[cell("mountain", "HOT", "bare")] = [
    entry("minecraft:windswept_hills", None, 1.0),
    entry("minecraft:windswept_savanna", "A", 1.0),
]

# --- WARM mountain + bare ---
TAXONOMY[cell("mountain", "WARM", "bare")] = [
    entry("minecraft:windswept_hills", None, 1.0),
    entry("minecraft:savanna_plateau", "A", 1.0),
]

# --- WARM mountain + sparse ---
TAXONOMY[cell("mountain", "WARM", "sparse")] = [
    entry("minecraft:windswept_forest", None, 1.0),
    entry("biomesoplenty:highland", "C", 1.0),
]

# --- TEMPERATE mountain + bare ---
TAXONOMY[cell("mountain", "TEMP", "bare")] = [
    entry("minecraft:plains", None, 1.0),
    entry("minecraft:meadow", "A", 1.0),
]

# --- TEMPERATE mountain + sparse ---
TAXONOMY[cell("mountain", "TEMP", "sparse")] = [
    entry("minecraft:windswept_forest", None, 1.0),
    entry("minecraft:cherry_grove", "C", 1.0),
]

# --- TEMPERATE mountain + forest ---
TAXONOMY[cell("mountain", "TEMP", "forest")] = [
    entry("minecraft:forest", None, 1.0),
    entry("minecraft:cherry_grove", "A", 1.0),
]

# --- TEMPERATE mountain + dense ---
TAXONOMY[cell("mountain", "TEMP", "dense")] = [
    entry("minecraft:forest", None, 1.0),
]

# --- TEMPERATE mountain + rainforest ---
TAXONOMY[cell("mountain", "TEMP", "rainforest")] = [
    entry("minecraft:forest", None, 1.0),
]

# --- COOL mountain + bare ---
TAXONOMY[cell("mountain", "COOL", "bare")] = [
    entry("minecraft:windswept_hills", None, 1.0),
    entry("minecraft:windswept_gravelly_hills", "A", 0.5),
]

# --- COOL mountain + sparse ---
TAXONOMY[cell("mountain", "COOL", "sparse")] = [
    entry("minecraft:taiga", None, 1.0),
    entry("minecraft:grove", "A", 1.0),
]

# --- COOL mountain + forest ---
TAXONOMY[cell("mountain", "COOL", "forest")] = [
    entry("minecraft:taiga", None, 1.0),
    entry("biomesoplenty:snowy_coniferous_forest", "C", 1.0),
]

# --- COOL mountain + dense ---
TAXONOMY[cell("mountain", "COOL", "dense")] = [
    entry("minecraft:old_growth_spruce_taiga", None, 1.0),
    entry("biomesoplenty:auroral_garden", "A", 0.5),
]

# --- COOL mountain + rainforest ---
TAXONOMY[cell("mountain", "COOL", "rainforest")] = [
    entry("minecraft:old_growth_spruce_taiga", None, 1.0),
]

# --- COLD mountain + bare ---
TAXONOMY[cell("mountain", "COLD", "bare")] = [
    entry("minecraft:snowy_slopes", None, 1.0),
]

# --- COLD mountain + sparse ---
TAXONOMY[cell("mountain", "COLD", "sparse")] = [
    entry("minecraft:snowy_slopes", None, 1.0),
    entry("minecraft:grove", "A", 1.0),
]

# --- COLD mountain + forest ---
TAXONOMY[cell("mountain", "COLD", "forest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- COLD mountain + dense ---
TAXONOMY[cell("mountain", "COLD", "dense")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- COLD mountain + rainforest ---
TAXONOMY[cell("mountain", "COLD", "rainforest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- FROZEN mountain + bare ---
TAXONOMY[cell("mountain", "FROZEN", "bare")] = [
    entry("minecraft:snowy_slopes", None, 1.0),
    entry("minecraft:jagged_peaks", "A", 0.5),
    entry("minecraft:frozen_peaks", "C", 0.5),
]

# --- FROZEN mountain + sparse ---
TAXONOMY[cell("mountain", "FROZEN", "sparse")] = [
    entry("minecraft:snowy_slopes", None, 1.0),
    entry("minecraft:grove", "A", 0.5),
]

# --- FROZEN mountain + forest ---
TAXONOMY[cell("mountain", "FROZEN", "forest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- FROZEN mountain + dense ---
TAXONOMY[cell("mountain", "FROZEN", "dense")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# --- FROZEN mountain + rainforest ---
TAXONOMY[cell("mountain", "FROZEN", "rainforest")] = [
    entry("minecraft:snowy_taiga", None, 1.0),
]

# =========================================================================
# bareSlope biomes (steep terrain re-selection)
# =========================================================================

TAXONOMY[cell("bareSlope", "HOT", "bare")] = [
    entry("minecraft:eroded_badlands", None, 1.0),
    entry("biomesoplenty:crag", "A", 1.0),
    entry("biomesoplenty:jade_cliffs", "C", 1.0),
]

TAXONOMY[cell("bareSlope", "WARM", "bare")] = [
    entry("minecraft:stony_peaks", None, 1.0),
    entry("biomesoplenty:wasteland_steppe", "A", 1.0),
]

TAXONOMY[cell("bareSlope", "TEMP", "bare")] = [
    entry("minecraft:stony_peaks", None, 1.0),
    entry("biomesoplenty:crag", "C", 1.0),
]

TAXONOMY[cell("bareSlope", "COOL", "bare")] = [
    entry("minecraft:stony_peaks", None, 1.0),
]

TAXONOMY[cell("bareSlope", "COLD", "bare")] = [
    entry("minecraft:frozen_peaks", None, 1.0),
]

TAXONOMY[cell("bareSlope", "FROZEN", "bare")] = [
    entry("minecraft:frozen_peaks", None, 1.0),
]

# =========================================================================
# Special biomes that don't fit the standard grid — standalone cells
# =========================================================================

# Beach biomes (handled separately by zone, not climate)
TAXONOMY[cell("beach", "ANY", "bare")] = [
    entry("minecraft:beach", None, 1.0),
    entry("minecraft:stony_shore", "A", 1.0),
    entry("minecraft:snowy_beach", "C", 0.5),
]

# mushroom_fields — rare, warm, very wet, variantNoise gated
TAXONOMY[cell("lowland", "TEMP", "mushroom")] = [
    entry("minecraft:mushroom_fields", None, 0.1),
]

# wooded_badlands — warm, dry, sparse (between badlands and savanna)
TAXONOMY[cell("lowland", "WARM", "wooded_badlands")] = [
    entry("minecraft:wooded_badlands", None, 1.0),
]

# mystic_grove — temperate, dense, province-gated accent
TAXONOMY[cell("lowland", "TEMP", "mystic")] = [
    entry("biomesoplenty:mystic_grove", "C", 1.0),
]

# aspen_glade — cool, sparse, accent
TAXONOMY[cell("lowland", "COOL", "aspen")] = [
    entry("biomesoplenty:aspen_glade", "A", 1.0),
]

# jacaranda_glade — temperate, very rare accent
TAXONOMY[cell("lowland", "TEMP", "jacaranda")] = [
    entry("biomesoplenty:jacaranda_glade", "C", 0.2),
]

# marsh — warm, wet, treeless (flooded)
TAXONOMY[cell("lowland", "WARM", "marsh")] = [
    entry("biomesoplenty:marsh", "A", 1.0),
]

# woodland — temperate, forest, general accent
TAXONOMY[cell("lowland", "TEMP", "woodland")] = [
    entry("biomesoplenty:woodland", "B", 1.0),
]


# =========================================================================
# Temp band -> condition generator
# =========================================================================

def temp_condition(temp_band):
    """Generate temperatureC condition for a temp band."""
    bounds = {
        "FROZEN": ("lt", -2, None),
        "COLD": ("between", -2, 4.99),
        "COOL": ("between", 5, 11.99),
        "TEMP": ("between", 12, 18.99),
        "WARM": ("between", 19, 25.99),
        "HOT": ("gte", 26, None),
    }
    op, v1, v2 = bounds[temp_band]
    return cond("temperatureC", op, v1, v2)


def tree_coverage_condition(veg):
    """Generate treeCoverage condition for a veg bucket."""
    mapping = {
        "bare": ("lte", 0.01, None),
        "sparse": ("between", 0.34, 0.36),
        "forest": ("between", 0.61, 0.63),
        "dense": ("between", 0.84, 0.86),
        "rainforest": ("gte", 0.95, None),
    }
    op, v1, v2 = mapping[veg]
    return cond("treeCoverage", op, v1, v2)


# Special biome conditions that override the standard grid
SPECIAL_CONDITIONS = {
    "minecraft:mushroom_fields": {
        "zone": "lowland",
        "conditions": [
            cond("temperatureC", "between", 10, 24),
            cond("moisture", "gte", 0.85),
            cond("treeCoverage", "gte", 0.8),
            cond("lowland", "eq", boolean=True),
        ],
        "noiseConditions": [cond("variantNoise", "gt", 0.3)],
    },
    "minecraft:beach": {
        "zone": "beach",
        "conditions": [],
    },
    "minecraft:stony_shore": {
        "zone": "beach",
        "conditions": [],
    },
    "minecraft:snowy_beach": {
        "zone": "beach",
        "conditions": [cond("snowy", "eq", boolean=True)],
    },
    "minecraft:wooded_badlands": {
        "zone": "lowland",
        "conditions": [
            cond("temperatureC", "gte", 19),
            cond("moisture", "lt", 0.3),
            cond("treeCoverage", "lte", 0.35),
        ],
    },
    "biomesoplenty:mystic_grove": {
        "zone": "lowland",
        "conditions": [
            cond("temperatureC", "between", 12, 18.99),
            cond("moisture", "gte", 0.7),
            cond("treeCoverage", "gte", 0.62),
        ],
        "noiseConditions": [cond("regionNoise", "gt", PROV_HIGH)],
    },
    "biomesoplenty:aspen_glade": {
        "zone": "lowland",
        "conditions": [
            cond("temperatureC", "between", 5, 11.99),
            cond("moisture", "between", 0.45, 0.8),
            cond("treeCoverage", "between", 0.34, 0.36),
        ],
    },
    "biomesoplenty:jacaranda_glade": {
        "zone": "lowland",
        "conditions": [
            cond("temperatureC", "between", 12, 18.99),
            cond("moisture", "between", 0.5, 0.9),
            cond("treeCoverage", "between", 0.15, 0.63),
        ],
        "noiseConditions": [cond("flowerNoise", "gt", 0.3967)],
    },
    "biomesoplenty:marsh": {
        "zone": "lowland",
        "conditions": [
            cond("temperatureC", "between", 8, 24),
            cond("moisture", "gte", 0.8),
            cond("treeCoverage", "lte", 0.35),
        ],
    },
    "biomesoplenty:woodland": {
        "zone": "lowland",
        "conditions": [
            cond("temperatureC", "between", 5, 18.99),
            cond("moisture", "between", 0.3, 0.7),
            cond("treeCoverage", "between", 0.34, 0.63),
        ],
        "noiseConditions": [cond("regionNoise", "between", PROV_LOW, PROV_HIGH)],
    },
}


def zone_conditions(zone, temp_band, veg):
    """Generate zone-specific base conditions."""
    if temp_band == "ANY":
        conds = []
    else:
        conds = [temp_condition(temp_band)]

    if veg not in ("bare", "sparse", "forest", "dense", "rainforest"):
        # Special veg key — conditions come from SPECIAL_CONDITIONS
        pass
    else:
        conds.append(tree_coverage_condition(veg))

    if zone == "lowland":
        conds.append(cond("lowland", "eq", boolean=True))
    elif zone == "mountain":
        conds.append(cond("mountain", "eq", boolean=True))
    elif zone == "bareSlope":
        conds.append(cond("bareSlope", "eq", boolean=True))
        conds.append(cond("snowy", "eq", boolean=False))
    elif zone == "beach":
        pass  # beach zone has no extra conditions

    # Snowy conditions for cold bands
    if temp_band in ("COLD", "FROZEN") and zone in ("lowland", "mountain"):
        conds.append(cond("snowy", "eq", boolean=True))

    return conds


def generate_rules():
    """Generate rules from taxonomy. Returns dict of biome_key -> list of rules."""
    rules = {}

    for (zone, temp, veg), entries in TAXONOMY.items():
        base_conds = zone_conditions(zone, temp, veg)

        for e in entries:
            biome = e["biome"]
            province = e["province"]
            rarity = e["rarity"]

            # Check for special conditions override
            if biome in SPECIAL_CONDITIONS:
                spec = SPECIAL_CONDITIONS[biome]
                rule_conds = list(spec.get("conditions", []))
                noise_conds = list(spec.get("noiseConditions", []))
                rule_zone = spec.get("zone", zone)
            else:
                rule_conds = list(base_conds)  # copy
                noise_conds = []
                rule_zone = zone

                if province is not None:
                    gate = noise_gate(province)
                    if gate:
                        noise_conds.append(gate)

            rule = {
                "zone": rule_zone,
                "conditions": rule_conds,
                "rarity": rarity,
            }
            if noise_conds:
                rule["noiseConditions"] = noise_conds

            if biome not in rules:
                rules[biome] = []
            rules[biome].append(rule)

    return rules


def main():
    input_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])

    catalog = json.loads(input_path.read_text())
    new_rules = generate_rules()

    # Track which biomes got rules
    surface_keys = set()
    for e in catalog:
        if e.get("kind") in ("OVERWORLD", "MOUNTAIN", "BEACH"):
            surface_keys.add(e["key"])

    # Apply new rules to surface biomes
    for e in catalog:
        key = e["key"]
        kind = e.get("kind")

        # Only regenerate rules for OVERWORLD/MOUNTAIN/BEACH biomes in our taxonomy
        if kind in ("OVERWORLD", "MOUNTAIN", "BEACH") and key in new_rules:
            old_count = len(e.get("rules", []))
            e["rules"] = new_rules[key]
            new_count = len(e["rules"])
            if old_count != new_count or True:  # always report
                print(f"  {key}: {old_count} -> {new_count} rules")

    # Verify every taxonomy cell has exactly one ungated base
    for (zone, temp, veg), entries in TAXONOMY.items():
        bases = [e for e in entries if e["province"] is None]
        if len(bases) != 1:
            print(f"WARNING: cell ({zone},{temp},{veg}) has {len(bases)} base entries (expected 1)")

    # Count coverage
    all_cells = set(TAXONOMY.keys())
    all_biomes_in_taxonomy = set()
    for entries in TAXONOMY.values():
        for e in entries:
            all_biomes_in_taxonomy.add(e["biome"])

    missing = surface_keys - all_biomes_in_taxonomy
    if missing:
        print(f"\nWARNING: {len(missing)} surface biomes not in taxonomy:")
        for m in sorted(missing):
            print(f"  {m}")

    ocean_biomes = [e["key"] for e in catalog if e.get("kind") == "OCEAN"]
    print(f"\nOcean biomes (unchanged): {len(ocean_biomes)}")
    print(f"Taxonomy cells: {len(TAXONOMY)}")
    print(f"Biomes in taxonomy: {len(all_biomes_in_taxonomy)}")

    output_path.write_text(json.dumps(catalog, indent=2) + "\n")
    print(f"\nWrote {output_path}")


if __name__ == "__main__":
    main()
