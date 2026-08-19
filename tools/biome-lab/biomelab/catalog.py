"""Loads biome_catalog.json into plain Python objects mirroring
common/src/main/java/.../biome/TerrainBiomeSettlement.java, TerrainBiomeRule.java and
TerrainBiomeCondition.java, so this tool never drifts from the real schema -- it reads the
catalog file directly rather than hardcoding a schema copy.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

# The five discrete treeCoverage values classifyPixel can ever produce (rainforest/dense/
# forest/sparse/none), and the matching sparsity values (sparsity = clamp01(1 - treeCoverage)).
# See BiomeClassifier.classifyPixel lines ~343-349. Any `treeCoverage`/`sparsity` condition whose
# range doesn't straddle one of these five values can never match a real pixel.
VALID_TREE_COVERAGE = (0.00, 0.35, 0.62, 0.85, 1.00)
VALID_SPARSITY = (1.00, 0.65, 0.38, 0.15, 0.00)

# Variables whose value is architecturally guaranteed non-negative (or range-bounded) by
# BiomeClassifier.classifyPixel itself, regardless of what the upstream climate model produces.
# These are *hard* invariants (visible directly in the Java source), unlike e.g. temperatureC's
# approximate [-10,40] range which only holds for the synthetic-fallback conditioning path and
# is not guaranteed for the real generated climate field, so it is NOT included here.
#   elevationM is deliberately ABSENT: it carries the signed elevation (negative over ocean, the
#     real seafloor depth), so it has no architectural lower bound. It used to be fed
#     `altM = Math.max(0f, elevation)`, which silently made every `elevationM lt <negative>` gate
#     -- i.e. all four deep_* ocean biomes -- permanently unreachable. See TerrainClimateSample.
#   precipitationMm: `Math.max(0f, ...)` before the noise factor is applied.
#   aridity, moisture, treeMoisture: aridity = precip / max(1, pet) with precip>=0, pet>0.
#   slope: sqrt(dx^2+dy^2)/pixelSizeM, never negative.
#   growingSeasonDays: derived from a closed-form arcsin formula, provably in [0, 365].
HARD_BOUNDS = {
    "precipitationMm": (0.0, None),
    "aridity": (0.0, None),
    "moisture": (0.0, None),
    "treeMoisture": (0.0, None),
    "slope": (0.0, None),
    "growingSeasonDays": (0.0, 365.0),
    "treeCoverage": (0.0, 1.0),
    "sparsity": (0.0, 1.0),
}

NUMERIC_VARIABLES = (
    "elevationM", "temperatureC", "temperatureSeasonality", "precipitationMm",
    "precipitationCv", "moisture", "aridity", "treeMoisture", "treeCoverage", "sparsity",
    "slope", "growingSeasonDays",
)
BOOL_VARIABLES = ("ocean", "snowy", "bareSlope", "mountain", "lowland")
NOISE_VARIABLES = ("variantNoise", "cherryNoise", "paleNoise", "clearingNoise", "flowerNoise", "regionNoise")

ZONES = ("ocean", "beach", "mountain", "lowland", "bareSlope")


@dataclass
class Condition:
    variable: str
    op: str
    value: Optional[float] = None
    value2: Optional[float] = None
    bool_value: Optional[bool] = None
    # Reference to the exact dict this was parsed from, in the catalog's raw (still-loaded) JSON
    # structure. Mutating this dict in place and re-dumping Catalog.raw is how --fix applies
    # suggested patches without needing to re-locate the condition inside the JSON tree.
    raw: Optional[dict] = field(default=None, repr=False, compare=False)

    @staticmethod
    def from_json(d: dict) -> "Condition":
        return Condition(
            variable=d["variable"],
            op=d["op"],
            value=d.get("value"),
            value2=d.get("value2"),
            bool_value=d.get("bool"),
            raw=d,
        )

    def describe(self) -> str:
        if self.bool_value is not None:
            return f"{self.variable} == {self.bool_value}"
        if self.op == "between":
            return f"{self.value} <= {self.variable} <= {self.value2}"
        sym = {"eq": "==", "gt": ">", "gte": ">=", "lt": "<", "lte": "<="}.get(self.op, self.op)
        return f"{self.variable} {sym} {self.value}"


@dataclass
class Rule:
    zone: str
    rarity: float
    override: bool
    conditions: list[Condition] = field(default_factory=list)
    noise_conditions: list[Condition] = field(default_factory=list)

    @staticmethod
    def from_json(d: dict) -> "Rule":
        return Rule(
            zone=d["zone"],
            rarity=float(d.get("rarity", 1.0)),
            override=bool(d.get("override", False)),
            conditions=[Condition.from_json(c) for c in d.get("conditions", [])],
            noise_conditions=[Condition.from_json(c) for c in d.get("noiseConditions", [])],
        )


@dataclass
class Settlement:
    index: int
    key: str
    fallback_key: str
    kind: str
    color: int
    hard_boundary: bool
    blendable: bool
    is_river: bool
    is_frozen_river: bool
    can_generate_overworld: bool
    rules: list[Rule] = field(default_factory=list)
    required_mods: list[str] = field(default_factory=list)

    @staticmethod
    def from_json(d: dict) -> "Settlement":
        return Settlement(
            required_mods=list(d.get("requiredMods", [])),
            index=d["index"],
            key=d["key"],
            fallback_key=d.get("fallbackKey", d["key"]),
            kind=d.get("kind", "OVERWORLD"),
            color=d.get("color", 0),
            hard_boundary=d.get("hardBoundary", False),
            blendable=d.get("blendable", False),
            is_river=d.get("river", False),
            is_frozen_river=d.get("frozenRiver", False),
            can_generate_overworld=d.get("canGenerateOverworld", False),
            rules=[Rule.from_json(r) for r in d.get("rules", [])],
        )


@dataclass
class Catalog:
    path: Path
    settlements: list[Settlement]
    raw: list = field(default_factory=list, repr=False)  # the still-live parsed JSON list

    @property
    def by_index(self) -> dict[int, Settlement]:
        return {s.index: s for s in self.settlements}

    @property
    def by_key(self) -> dict[str, Settlement]:
        return {s.key: s for s in self.settlements}

    def default_index(self) -> int:
        # Matches TerrainBiomeRegistry.defaultBiomeIndex(): always the settlement with index==1
        # (plains in every known catalog), falling back to literal 1 if absent.
        return 1

    def all_rules(self):
        """Yields (settlement, rule) for every rule in the catalog."""
        for s in self.settlements:
            for r in s.rules:
                yield s, r


def load(path: str | Path, mods: set[str] | None = None) -> Catalog:
    """Loads a catalog, optionally as the mod would see it with only `mods` installed.

    `mods=None` keeps every entry (the whole authored file). Passing a set filters out
    settlements and rules whose `requiredMods` aren't all present, exactly as
    TerrainBiomeRegistry.build() and BiomeRuleEngine.init() do -- which is how the same file gets
    validated once per supported mod configuration (vanilla / +BoP).
    """
    path = Path(path)
    data = json.loads(path.read_text())
    if mods is not None:
        data = [d for d in data if set(d.get("requiredMods", [])) <= mods]
        data = [
            {**d, "rules": [r for r in d.get("rules", []) if set(r.get("requiredMods", [])) <= mods]}
            for d in data
        ]
    settlements = [Settlement.from_json(d) for d in data]
    return Catalog(path=path, settlements=settlements, raw=data)
