#!/usr/bin/env python3
"""Post-region-gate widenings for biomes the Monte Carlo showed as dead or far below the
0.05% encounterability bar (run after apply_regions.py, before add_missing_bop.py).

Root causes found 2026-07-31: jacaranda/tropics had moisture windows that physically force
denser treeCoverage buckets than their rules demanded (warm gsFactor~1 maps moisture straight
onto buckets); muskeg/cold_desert/rainforest niches were simply too narrow after losing ~2/3
of their area to province gates; highland/crag/jade_cliffs sat a hair under the bar.
"""
import json

P = "/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json"
cat = json.load(open(P))
by = {b["key"]: b for b in cat}

def setc(rule, var, **kw):
    for c in rule["conditions"]:
        if c["variable"] == var:
            c.update(kw)
            for k in ("value", "value2", "bool"):
                if k not in kw and k in c: del c[k]
            return
    rule["conditions"].append({"variable": var, **kw})

def dropc(rule, var):
    rule["conditions"] = [c for c in rule["conditions"] if c["variable"] != var]

# jacaranda_glade: flowerNoise 0.6 was a ~1% tail against the 0.769 field ceiling, and
# coverage [0.15,0.4] contradicted moisture >= 0.5; align both to the real buckets.
for r in by["biomesoplenty:jacaranda_glade"]["rules"]:
    for nc in r.get("noiseConditions", []):
        if nc["variable"] == "flowerNoise": nc["value"] = 0.45
    setc(r, "moisture", op="between", value=0.4, value2=0.8)
    setc(r, "treeCoverage", op="between", value=0.3, value2=0.7)

# tropics: moisture >= 1.0 forces the 0.85/1.0 coverage buckets -- [0.1,0.4] was near-dead.
for r in by["biomesoplenty:tropics"]["rules"]:
    dropc(r, "treeCoverage")
    setc(r, "precipitationMm", op="gte", value=1400)

# muskeg: coverage <= 0.15 only matched the bare bucket; cold-nonsnowy-wet niche is tiny.
for r in by["biomesoplenty:muskeg"]["rules"]:
    setc(r, "treeCoverage", op="lte", value=0.35)
    dropc(r, "growingSeasonDays")
    setc(r, "temperatureC", op="between", value=-5, value2=8)

# cold_desert: widen the Gobi niche slightly.
for r in by["biomesoplenty:cold_desert"]["rules"]:
    setc(r, "temperatureC", op="between", value=-25, value2=-2)
    setc(r, "moisture", op="lte", value=0.2)
    setc(r, "precipitationMm", op="lte", value=150)

# rainforest: temp >= 22 -> >= 20.
for r in by["biomesoplenty:rainforest"]["rules"]:
    setc(r, "temperatureC", op="gte", value=20)

# cliff/highland trio: nudge eligibility over the encounterability bar.
for r in by["biomesoplenty:highland"]["rules"]:
    setc(r, "temperatureC", op="between", value=0, value2=20)
for r in by["biomesoplenty:crag"]["rules"]:
    setc(r, "moisture", op="gte", value=0.25)
    setc(r, "temperatureC", op="between", value=-5, value2=16)
for r in by["biomesoplenty:jade_cliffs"]["rules"]:
    setc(r, "moisture", op="gte", value=0.3)
    setc(r, "temperatureC", op="gte", value=12)

json.dump(cat, open(P, "w"), indent=2)
print("rare-biome fixes applied")
