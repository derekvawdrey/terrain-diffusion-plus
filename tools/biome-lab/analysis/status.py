"""Surface-biome reachability status, extracted from a biome-lab report.

Only the four zones BiomeClassifier can actually select from count: OVERWORLD, MOUNTAIN, BEACH and
OCEAN. RIVER biomes are placed by FluvialRiverNetwork, CAVE biomes by the cave biome system, and
NETHER/END/VOID belong to other dimensions -- none of those go through BiomeRuleEngine, so a 0%
area fraction for them is correct, not a bug.
"""
from __future__ import annotations

import json
import re
import sys

SURFACE_KINDS = {"OVERWORLD", "MOUNTAIN", "BEACH", "OCEAN"}
BAR = 0.05


def main():
    catalog = json.load(open(sys.argv[1]))
    report = open(sys.argv[2]).read()
    surface = {e["key"] for e in catalog if e["kind"] in SURFACE_KINDS}

    section = report.split("### 3d. Encounterability")[1].split("###")[0]
    frac = {m.group(1): float(m.group(2))
            for m in re.finditer(r"\| `([^`]+)` \| ([\d.]+)% \|", section)}

    zero = sorted(k for k in surface if frac.get(k, 0.0) == 0.0)
    low = sorted(((frac[k], k) for k in surface if 0 < frac[k] < BAR))
    ok = [k for k in surface if frac.get(k, 0.0) >= BAR]

    print(f"surface biomes: {len(surface)}   at/above {BAR}% bar: {len(ok)}   "
          f"below bar: {len(low)}   never spawn: {len(zero)}")
    if zero:
        print("\nNEVER SPAWN:")
        for k in zero:
            print(f"    0.000%  {k}")
    if low:
        print("\nBELOW BAR:")
        for v, k in low:
            print(f"  {v:7.3f}%  {k}")
    return 1 if (zero or low) else 0


if __name__ == "__main__":
    sys.exit(main())
