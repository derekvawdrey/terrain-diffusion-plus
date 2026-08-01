# biome-lab report

- Catalog: `/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json`
- Biomes in catalog: 109
- Generated: 2026-07-31T17:57:14

## 1. Static validators (fast, no sampling)

**Result: PASS** -- 0 dead condition(s) found across 0 biome(s), 0 redundant (always-true) condition(s).

### 1a. treeCoverage / sparsity discreteness

No issues found.

### 1b. Noise-ceiling (unreachable noiseConditions thresholds)

No issues found.

### 1c. Hard climate-variable bounds

No issues found.

### 1d. moisture / treeMoisture aliasing (informational)

- `minecraft:plains` (zone=mountain, rarity=1.0): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:sunflower_plains` (zone=lowland, rarity=10.0): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:forest` (zone=lowland, rarity=1.784): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:flower_forest` (zone=lowland, rarity=2.252): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:meadow` (zone=mountain, rarity=1.434): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.

## 3. Monte Carlo evaluation

_Not run this pass (validators-only, or Monte Carlo was skipped because validators failed -- pass `--force-montecarlo` to run it anyway)._
