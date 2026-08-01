#!/usr/bin/env python3
"""Cap minecraft:plains' uncapped lowland catch-all rules (they made plains eligible on ALL
bare land -- hot deserts, high plateaus) and align the add_missing_bop.py newcomers' windows
with treeCoverage bucket physics. Run order: apply_regions.py -> fix_rare_biomes.py ->
add_missing_bop.py -> cap_plains.py. Result: plains lowland share 12.1% -> 7.8%."""
import json
P = "/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json"
cat = json.load(open(P)); by = {b["key"]: b for b in cat}

def setc(rule, var, **kw):
    for c in rule["conditions"]:
        if c["variable"] == var:
            c.update(kw)
            for k in ("value","value2","bool"):
                if k not in kw and k in c: del c[k]
            return
    rule["conditions"].append({"variable": var, **kw})

for r in by["minecraft:plains"]["rules"]:
    conds = {c["variable"] for c in r["conditions"]}
    if r["zone"] == "lowland" and "temperatureC" not in conds:
        setc(r, "temperatureC", op="between", value=-5, value2=19.99)
    if r["zone"] == "lowland" and "elevationM" not in conds:
        setc(r, "elevationM", op="lte", value=900)

for r in by["biomesoplenty:pasture"]["rules"]: setc(r, "treeCoverage", op="lte", value=0.7)
for r in by["biomesoplenty:overgrown_greens"]["rules"]: setc(r, "treeCoverage", op="lte", value=0.7)
for r in by["biomesoplenty:floodplain"]["rules"]:
    setc(r, "temperatureC", op="between", value=12, value2=30)
    setc(r, "elevationM", op="lte", value=450)
for r in by["biomesoplenty:pumpkin_patch"]["rules"]:
    for nc in r.get("noiseConditions", []):
        if nc["variable"] == "clearingNoise": nc["value"] = 0.3
for r in by["biomesoplenty:rocky_rainforest"]["rules"]:
    setc(r, "slope", op="gte", value=0.25)
    setc(r, "temperatureC", op="gte", value=18)

json.dump(cat, open(P, "w"), indent=2)
print("plains capped + newcomer widenings applied")
