# biome-lab report

- Catalog: `/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json`
- Biomes in catalog: 109
- Generated: 2026-07-30T22:26:43

## 1. Static validators (fast, no sampling)

**Result: FAIL** -- 6 dead condition(s) found across 5 biome(s), 32 redundant (always-true) condition(s).

### 1a. treeCoverage / sparsity discreteness

**2 dead (unreachable) condition(s):**

- `biomesoplenty:tundra` (zone=lowland, priority=75): `treeCoverage >= 0.02` -- treeCoverage only ever takes values (0.0, 0.35, 0.62, 0.85, 1.0). This rule's combined conditions on treeCoverage ('treeCoverage >= 0.02 AND treeCoverage < 0.15') straddle none of them and can never jointly match, even though 'treeCoverage >= 0.02' looks satisfiable in isolation. Not auto-fixed (ambiguous which discrete bucket was intended) -- needs a human/agent decision.
- `biomesoplenty:tundra` (zone=lowland, priority=75): `treeCoverage < 0.15` -- treeCoverage only ever takes values (0.0, 0.35, 0.62, 0.85, 1.0). This rule's combined conditions on treeCoverage ('treeCoverage >= 0.02 AND treeCoverage < 0.15') straddle none of them and can never jointly match, even though 'treeCoverage < 0.15' looks satisfiable in isolation. Not auto-fixed (ambiguous which discrete bucket was intended) -- needs a human/agent decision.

### 1b. Noise-ceiling (unreachable noiseConditions thresholds)

No issues found.

### 1c. Hard climate-variable bounds

**4 dead (unreachable) condition(s):**

- `minecraft:deep_lukewarm_ocean` (zone=ocean, priority=91): `elevationM < -250` -- elevationM is architecturally bounded to [0.0, +inf] by BiomeClassifier.classifyPixel (see catalog.HARD_BOUNDS). 'elevationM < -250' falls entirely outside that and can never match.
- `minecraft:deep_ocean` (zone=ocean, priority=91): `elevationM < -250` -- elevationM is architecturally bounded to [0.0, +inf] by BiomeClassifier.classifyPixel (see catalog.HARD_BOUNDS). 'elevationM < -250' falls entirely outside that and can never match.
- `minecraft:deep_cold_ocean` (zone=ocean, priority=91): `elevationM < -250` -- elevationM is architecturally bounded to [0.0, +inf] by BiomeClassifier.classifyPixel (see catalog.HARD_BOUNDS). 'elevationM < -250' falls entirely outside that and can never match.
- `minecraft:deep_frozen_ocean` (zone=ocean, priority=91): `elevationM < -250` -- elevationM is architecturally bounded to [0.0, +inf] by BiomeClassifier.classifyPixel (see catalog.HARD_BOUNDS). 'elevationM < -250' falls entirely outside that and can never match.

**32 redundant (always-true, no-op) condition(s):**

- `minecraft:ice_spikes` (zone=lowland, priority=61): `treeCoverage <= 1.0` -- treeCoverage is architecturally bounded to [0.0, 1.0] -- 'treeCoverage <= 1.0' is always true and does nothing.
- `minecraft:windswept_gravelly_hills` (zone=mountain, priority=55): `growingSeasonDays < 365.0` -- growingSeasonDays is architecturally bounded to [0.0, 365.0] -- 'growingSeasonDays < 365.0' is always true and does nothing.
- `minecraft:bamboo_jungle` (zone=lowland, priority=79): `moisture > 0.0` -- moisture is architecturally bounded to [0.0, +inf] -- 'moisture > 0.0' is always true and does nothing.
- `minecraft:bamboo_jungle` (zone=lowland, priority=79): `moisture > 0.0` -- moisture is architecturally bounded to [0.0, +inf] -- 'moisture > 0.0' is always true and does nothing.
- `minecraft:bamboo_jungle` (zone=lowland, priority=79): `moisture > 0.0` -- moisture is architecturally bounded to [0.0, +inf] -- 'moisture > 0.0' is always true and does nothing.
- `minecraft:meadow` (zone=lowland, priority=68): `growingSeasonDays < 365.0` -- growingSeasonDays is architecturally bounded to [0.0, 365.0] -- 'growingSeasonDays < 365.0' is always true and does nothing.
- `minecraft:meadow` (zone=lowland, priority=68): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=62): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=62): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=62): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=62): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=69): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=69): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=69): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=mountain, priority=69): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=lowland, priority=66): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=lowland, priority=66): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=lowland, priority=66): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=lowland, priority=66): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=lowland, priority=66): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:cherry_grove` (zone=lowland, priority=66): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:jagged_peaks` (zone=mountain, priority=75): `elevationM > 0.0` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM > 0.0' is always true and does nothing.
- `minecraft:pale_garden` (zone=lowland, priority=66): `treeCoverage >= 0.0` -- treeCoverage is architecturally bounded to [0.0, 1.0] -- 'treeCoverage >= 0.0' is always true and does nothing.
- `minecraft:warm_ocean` (zone=ocean, priority=90): `elevationM >= -250` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM >= -250' is always true and does nothing.
- `minecraft:lukewarm_ocean` (zone=ocean, priority=90): `elevationM >= -250` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM >= -250' is always true and does nothing.
- `minecraft:ocean` (zone=ocean, priority=90): `elevationM >= -250` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM >= -250' is always true and does nothing.
- `minecraft:cold_ocean` (zone=ocean, priority=90): `elevationM >= -250` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM >= -250' is always true and does nothing.
- `minecraft:frozen_ocean` (zone=ocean, priority=90): `elevationM >= -250` -- elevationM is architecturally bounded to [0.0, +inf] -- 'elevationM >= -250' is always true and does nothing.
- `biomesoplenty:rainforest` (zone=lowland, priority=79): `precipitationMm >= 0.0` -- precipitationMm is architecturally bounded to [0.0, +inf] -- 'precipitationMm >= 0.0' is always true and does nothing.
- `biomesoplenty:rainforest` (zone=lowland, priority=79): `precipitationMm >= 0.0` -- precipitationMm is architecturally bounded to [0.0, +inf] -- 'precipitationMm >= 0.0' is always true and does nothing.
- `biomesoplenty:tropics` (zone=lowland, priority=82): `precipitationMm >= 0.0` -- precipitationMm is architecturally bounded to [0.0, +inf] -- 'precipitationMm >= 0.0' is always true and does nothing.
- `biomesoplenty:muskeg` (zone=lowland, priority=72): `growingSeasonDays < 365.0` -- growingSeasonDays is architecturally bounded to [0.0, 365.0] -- 'growingSeasonDays < 365.0' is always true and does nothing.

### 1d. moisture / treeMoisture aliasing (informational)

- `minecraft:plains` (zone=mountain, priority=40): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:sunflower_plains` (zone=lowland, priority=35): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:forest` (zone=lowland, priority=65): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:flower_forest` (zone=lowland, priority=66): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:meadow` (zone=mountain, priority=68): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `biomesoplenty:coniferous_forest` (zone=lowland, priority=67): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `biomesoplenty:coniferous_forest` (zone=lowland, priority=67): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `biomesoplenty:coniferous_forest` (zone=lowland, priority=67): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `biomesoplenty:fir_clearing` (zone=lowland, priority=67): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.

## 3. Monte Carlo evaluation

_Not run this pass (validators-only, or Monte Carlo was skipped because validators failed -- pass `--force-montecarlo` to run it anyway)._
