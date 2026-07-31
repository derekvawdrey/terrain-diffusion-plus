"""Soundness check for BiomeCandidateFilterCalculator.

The calculator's contract is that its emitted channel ranges are NECESSARY: any pixel where one of
biome B's zone-Z rules MATCHES must fall inside B/Z's emitted box. This replays the biome-lab
Monte Carlo sampler and tests that directly against the rules, rather than against which biome
actually won -- competition noise and the default-biome fallback both make "won" a much noisier
signal than "was eligible", and it is eligibility the filter is supposed to bound.

Channel-to-sample mapping (see ExplorerServer.CHANNEL_NAMES / BiomeClassifier.classifyPixel):
  0 -> elevation. The COARSE map channel; the sampler's `raw_elevation` (signed, pre-clamp) is the
       right analogue, since a rule's elevationM has already been through max(0, .).
  2 -> temperatureC. NOTE the sampler's value is post-lapse-correction and post-noise, while the
       real coarse channel is the un-lapsed base. The calculator discloses that divergence as a
       caveat rather than widening the filter into uselessness, so temperature is reported
       separately and does not count toward the verdict.
  3 -> temperatureSeasonality (raw x100 scale, same units both sides)
  4 -> precipitationMm (post precip-noise; the calculator divides its bound by the max factor)
  5 -> precipitationCv
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import numpy as np

LAB = Path("/home/derek/IdeaProjects/terrain-diffusion-plus/tools/biome-lab")
sys.path.insert(0, str(LAB))

from biomelab import catalog as catalog_mod  # noqa: E402
from biomelab import climate, montecarlo, noise_data  # noqa: E402
from biomelab.engine import _eval_rule  # noqa: E402

CHANNEL_FIELD = {
    "0": "raw_elevation",
    "2": "temperatureC",
    "3": "temperatureSeasonality",
    "4": "precipitationMm",
    "5": "precipitationCv",
}
CHANNEL_NAME = {"0": "Elev", "2": "Temp", "3": "T std", "4": "Precip", "5": "P CV"}


def main():
    catalog_path, pipeline_path, n, classpath = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]

    filters = json.loads(subprocess.run(
        ["java", "-cp", classpath, "CandProbe", catalog_path, "--json"],
        capture_output=True, text=True, check=True).stdout)

    cat = catalog_mod.load(catalog_path)
    pdata = climate.load_pipeline_data(pipeline_path)
    families = noise_data.load_families(LAB / "data" / "noise_quantiles")
    samples = climate.simulate(pdata, families, n, seed=1)
    zone = montecarlo.assign_zones(samples)

    # Which samples each zone's rules are even evaluated against, mirroring classifyPixel's
    # dispatch (bareSlope is a re-selection pass over non-ocean, non-mountain pixels).
    zone_mask = {
        "ocean": zone == "ocean",
        "beach": zone == "beach",
        "mountain": zone == "mountain",
        "lowland": zone == "lowland",
        "bareSlope": samples.bareSlope & (~samples.ocean) & (~samples.mountain),
    }

    def get(var):
        return getattr(samples, var)

    print(f"{'biome':42s} {'zone':9s} {'eligible':>9s} {'outside':>8s}  detail")
    print("-" * 100)
    bad = []
    for s in cat.settlements:
        if s.key not in filters:
            continue
        for zname, chans in filters[s.key].items():
            base = zone_mask.get(zname)
            if base is None:
                continue
            eligible = np.zeros(samples.n, dtype=bool)
            for rule in s.rules:
                if rule.zone != zname:
                    continue
                eligible |= _eval_rule(rule.conditions, rule.noise_conditions, get)
            eligible &= base
            total = int(eligible.sum())
            if total == 0:
                continue

            per_channel = {}
            inside = np.ones(total, dtype=bool)
            for ch, rng in chans.items():
                vals = getattr(samples, CHANNEL_FIELD[ch])[eligible]
                ok = np.ones(total, dtype=bool)
                if rng[0] is not None:
                    ok &= vals >= rng[0]
                if rng[1] is not None:
                    ok &= vals <= rng[1]
                per_channel[ch] = total - int(ok.sum())
                inside &= ok
            outside = total - int(inside.sum())
            non_temp = {c: v for c, v in per_channel.items() if c != "2" and v > 0}
            detail = ", ".join(f"{CHANNEL_NAME[c]} -{v}" for c, v in sorted(per_channel.items()) if v)
            flag = "  <-- UNSOUND" if non_temp else ""
            if non_temp:
                bad.append((s.key, zname, non_temp, total))
            print(f"{s.key:42s} {zname:9s} {total:9d} {outside:8d}  {detail}{flag}")

    print()
    if bad:
        print(f"SOUNDNESS FAILURES (non-temperature channels): {len(bad)}")
        for key, zname, chans, total in bad:
            detail = ", ".join(f"{CHANNEL_NAME[c]} misses {v}/{total} ({100*v/total:.2f}%)"
                               for c, v in chans.items())
            print(f"  {key} [{zname}]: {detail}")
        sys.exit(1)
    print("PASS: every emitted range is sound on non-temperature channels "
          "(temperature divergence is the disclosed un-lapsed-map caveat).")


if __name__ == "__main__":
    main()
