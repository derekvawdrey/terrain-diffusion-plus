#!/usr/bin/env python3
"""Apply Earth-like geographic provinces to the live biome catalog.

regionNoise = makeFnl(778899, 1/5000, 2, 2.0, 0.5) -- 5000-block wavelength,
continental scale. Terciles at +/-0.0967 split the world into three provinces
of ~33% land each:

  A (< -0.0967)          "Boreal & Old World"  -- Europe / Nordic / Mediterranean / Sahara
  B (-0.0967 .. 0.0967)  "New World Frontier"  -- Americas: prairie, badlands, bayou, redwoods
  C (> +0.0967)          "East & Pacific"      -- Asia / Oceania: maples, bamboo, tropics

Policy (learned from the failed grid taxonomy -- see HANDOFF-biome-work.md):
  * Universal baseline biomes stay ungated so every climate x province cell keeps
    >= 2 candidates (no diversity collapse, no fallback holes).
  * A gated rule carries exactly ONE noise condition. Rules that already have a
    thematic gate are either left alone (skip) or have that gate REPLACED by the
    province band (replace_gates=True) -- never stacked.
  * Newly gated biomes lose ~2/3 of their eligible area, so their rarity is
    multiplied by RARITY_BOOST (capped at 10) to keep global share and make them
    the signature biome *within* their province. Explicit overrides win.
"""
import json, sys, copy

CATALOG = "/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json"

BAND = {
    "A": {"variable": "regionNoise", "op": "lt", "value": -0.0967},
    "B": {"variable": "regionNoise", "op": "between", "value": -0.0967, "value2": 0.0967},
    "C": {"variable": "regionNoise", "op": "gt", "value": 0.0967},
}
RARITY_BOOST = 2.5
RARITY_CAP = 10.0

# biome -> (province, replace_gates, rarity_override_or_None)
ASSIGN = {
    # ---- Province A: Boreal & Old World -------------------------------------
    "biomesoplenty:tundra":                 ("A", False, None),   # Siberian tundra
    "minecraft:ice_spikes":                 ("A", False, None),   # glacial Siberia
    "biomesoplenty:snowy_coniferous_forest":("A", True,  None),   # Nordic snow forest
    "biomesoplenty:snowy_fir_clearing":     ("A", False, None),
    "minecraft:old_growth_spruce_taiga":    ("A", False, None),   # European spruce
    "biomesoplenty:coniferous_forest":      ("A", False, None),
    "biomesoplenty:bog":                    ("A", False, None),   # British Isles wetland trio
    "biomesoplenty:moor":                   ("A", False, None),
    "biomesoplenty:marsh":                  ("A", False, None),
    "minecraft:birch_forest":               ("A", True,  3.0),    # Scandinavian birch
    "minecraft:old_growth_birch_forest":    ("A", True,  3.0),
    "biomesoplenty:grassland":              ("A", False, None),   # European lowland
    "biomesoplenty:woodland":               ("A", True,  None),   # English woodland
    "biomesoplenty:old_growth_woodland":    ("A", False, None),
    "biomesoplenty:mediterranean_forest":   ("A", False, None),   # Mediterranean belt
    "biomesoplenty:highland":               ("A", False, None),   # Scottish Highlands
    "biomesoplenty:crag":                   ("A", False, None),
    "biomesoplenty:wasteland_steppe":       ("A", False, None),
    "biomesoplenty:wasteland":              ("A", False, None),   # rule 2 keeps its deeper -0.35 gate
    # lush_desert already carries regionNoise < -0.3 (deep A oasis) -- untouched.

    # ---- Province B: New World Frontier -------------------------------------
    "biomesoplenty:muskeg":                 ("B", False, None),   # Canadian muskeg
    "biomesoplenty:wintry_origin_valley":   ("B", False, None),
    "minecraft:old_growth_pine_taiga":      ("B", False, None),   # Pacific Northwest
    "biomesoplenty:fir_clearing":           ("B", False, None),
    "biomesoplenty:seasonal_forest":        ("B", True,  None),   # New England fall
    "biomesoplenty:aspen_glade":            ("B", False, None),   # Rocky Mountain aspen
    "biomesoplenty:wetland":                ("B", False, None),
    "minecraft:dark_forest":                ("B", True,  None),   # deep frontier woods
    "biomesoplenty:redwood_forest":         ("B", False, None),   # California redwoods
    "biomesoplenty:prairie":                ("B", True,  None),   # Great Plains
    "minecraft:badlands":                   ("B", True,  None),   # US Southwest trio
    "minecraft:wooded_badlands":            ("B", True,  None),
    "minecraft:eroded_badlands":            ("B", False, None),
    "biomesoplenty:bayou":                  ("B", False, None),   # Louisiana
    "biomesoplenty:rainforest":             ("B", False, None),   # Amazon
    "biomesoplenty:lush_savanna":           ("B", False, None),   # cerrado

    # ---- Province C: East & Pacific -----------------------------------------
    "biomesoplenty:cold_desert":            ("C", False, None),   # Gobi
    "biomesoplenty:snowy_maple_woods":      ("C", True,  None),   # Hokkaido
    "biomesoplenty:snowblossom_grove":      ("C", False, None),
    "biomesoplenty:maple_woods":            ("C", True,  None),   # momiji maples
    "biomesoplenty:dead_forest":            ("C", False, None),
    "biomesoplenty:orchard":                ("C", False, None),
    "biomesoplenty:dryland":                ("C", False, None),   # Australian outback
    "minecraft:bamboo_jungle":              ("C", False, None),   # China
    "biomesoplenty:fungal_jungle":          ("C", False, None),
    "biomesoplenty:tropics":                ("C", False, None),   # Pacific islands
    "biomesoplenty:volcano":                ("C", False, None),   # Ring of Fire
    "biomesoplenty:jade_cliffs":            ("C", False, None),   # karst cliffs
}

def main():
    with open(CATALOG) as f:
        cat = json.load(f)

    touched = 0
    for biome in cat:
        spec = ASSIGN.get(biome["key"])
        if not spec:
            continue
        prov, replace_gates, override = spec
        band = BAND[prov]
        changed = False
        for rule in biome.get("rules", []):
            nc = rule.get("noiseConditions")
            if nc and not replace_gates:
                # existing thematic gate stays; never stack a second one
                continue
            if nc and nc[0].get("variable") == "regionNoise" and not replace_gates:
                continue
            # keep hand-authored deeper regionNoise gates (e.g. wasteland -0.35)
            if nc and nc[0].get("variable") == "regionNoise" and abs(nc[0].get("value", 0)) > 0.097:
                continue
            rule["noiseConditions"] = [copy.deepcopy(band)]
            changed = True
        if changed:
            for rule in biome.get("rules", []):
                r = rule.get("rarity", 1.0)
                rule["rarity"] = override if override is not None else round(min(r * RARITY_BOOST, RARITY_CAP), 3)
            touched += 1
            print(f"{biome['key']:45s} -> province {prov}"
                  + (" (gates replaced)" if replace_gates else "")
                  + (f" rarity={override}" if override is not None else ""))

    with open(CATALOG, "w") as f:
        json.dump(cat, f, indent=2)
    print(f"\n{touched} biomes gated across 3 provinces. Written to {CATALOG}")

if __name__ == "__main__":
    main()
