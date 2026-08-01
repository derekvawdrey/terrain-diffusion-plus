"""Loads the empirical FastNoiseLite quantile tables produced by java/NoiseProbe.java and
provides inverse-CDF sampling from them.

Why this exists: BiomeClassifier.java builds several named Perlin-FBm noise fields
(TEMP_NOISE, PRECIP_NOISE, BIOME_VARIANT_NOISE, ...). We can't easily re-implement
FastNoiseLite's exact gradient-noise algorithm in Python without risking subtle bugs, and we
don't need to: the *marginal* distribution of an FBm field only depends on (octaves, gain), not
frequency/seed/world position (see java/NoiseProbe.java's docstring for why). So instead we
measure the true marginal distribution once in Java (ground truth, using the mod's actual
FastNoiseLite implementation) and reuse it here via inverse-CDF sampling. This also means the
static "noise ceiling" validator and the Monte Carlo simulator draw from the exact same measured
data -- no risk of the two disagreeing.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "noise_quantiles"

# Maps each named noise field used in BiomeClassifier.java / biome_catalog.json noiseConditions
# to the (octaves, gain) family file that was measured for it. See java/NoiseProbe.java's
# docstring for the full derivation of which named fields share a family.
FIELD_TO_FAMILY = {
    "variantNoise": "oct3_gain055",
    "cherryNoise": "oct3_gain055",
    "paleNoise": "oct3_gain055",
    "clearingNoise": "oct3_gain054",
    "flowerNoise": "oct3_gain054",
    "regionNoise": "oct2_gain050",
    # Same field construction as regionNoise, so same marginal distribution. What the catalog
    # actually sees is that draw minus a threshold set by biome.japan_region_share -- see
    # japan_threshold() in climate.py.
    "japanRegion": "oct2_gain050",
    # Internal-only components (not exposed as JSON condition variables, but needed to
    # reconstruct temp/snow noise exactly as classifyPixel does):
    "tempNoiseCoarse": "oct3_gain050",
    "tempNoiseFine": "oct2_gain050",
    "snowNoiseCoarse": "oct3_gain050",
    "snowNoiseFine": "oct2_gain050",
    "precipNoise": "oct5_gain050",
}


@dataclass
class NoiseFamily:
    name: str
    octaves: int
    gain: float
    samples: int
    min: float
    max: float
    max_abs: float
    mean: float
    stddev: float
    used_by: list
    quantile_pcts: np.ndarray  # percentiles 0..100
    quantile_vals: np.ndarray  # corresponding values, monotonic non-decreasing

    def inverse_cdf(self, u: np.ndarray) -> np.ndarray:
        """u in [0,1) -> value, via linear interpolation of the measured quantile table."""
        pct = np.clip(u, 0.0, 1.0) * 100.0
        return np.interp(pct, self.quantile_pcts, self.quantile_vals)

    def sample(self, rng: np.random.Generator, n: int) -> np.ndarray:
        return self.inverse_cdf(rng.uniform(0.0, 1.0, n))


class NoiseDataError(RuntimeError):
    """Raised when the Java-probe quantile data hasn't been generated yet."""


def load_families(data_dir: Path | None = None) -> dict[str, NoiseFamily]:
    data_dir = data_dir or DEFAULT_DATA_DIR
    if not data_dir.exists():
        raise NoiseDataError(
            f"Noise quantile data not found at {data_dir}.\n"
            "Generate it once with:\n"
            "  cd tools/biome-lab\n"
            "  javac -d out java/FastNoiseLite.java java/NoiseProbe.java\n"
            "  java -cp out NoiseProbe --out data/noise_quantiles --grid 4500 --range 200000\n"
            "(This repo ships a pre-generated copy under tools/biome-lab/data/noise_quantiles/ -- "
            "if it's missing, something removed it or you're running from a stripped checkout.)"
        )
    families: dict[str, NoiseFamily] = {}
    for f in sorted(data_dir.glob("*.json")):
        if f.name == "summary.json":
            continue
        d = json.loads(f.read_text())
        q = np.array(d["quantiles"], dtype=np.float64)
        order = np.argsort(q[:, 0])
        families[d["family"]] = NoiseFamily(
            name=d["family"],
            octaves=d["octaves"],
            gain=d["gain"],
            samples=d["samples"],
            min=d["min"],
            max=d["max"],
            max_abs=d["maxAbs"],
            mean=d["mean"],
            stddev=d["stddev"],
            used_by=d.get("usedBy", []),
            quantile_pcts=q[order, 0],
            quantile_vals=q[order, 1],
        )
    if not families:
        raise NoiseDataError(f"No family *.json files found under {data_dir}")
    return families


def family_for_variable(var_name: str, families: dict[str, NoiseFamily]) -> NoiseFamily | None:
    fam_name = FIELD_TO_FAMILY.get(var_name)
    if fam_name is None:
        return None
    return families.get(fam_name)
