# biome-lab report

- Catalog: `/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json`
- Biomes in catalog: 109
- Generated: 2026-07-31T18:26:30
- Monte Carlo samples: 2,000,000 (seed=0)

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
- `minecraft:forest` (zone=lowland, rarity=1.71): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:flower_forest` (zone=lowland, rarity=2.059): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:meadow` (zone=mountain, rarity=1.373): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.

## 3. Monte Carlo evaluation

**Fidelity gaps (read before trusting any number below):**

- **Slope** has no real spatial gradient signal available to an i.i.d. sampler; it's drawn from a calibrated Gamma(1.2, 0.22) distribution tuned to roughly match the classifier's own medium/steep thresholds. This is the single biggest fidelity gap in the whole pipeline -- treat every `mountain`/`bareSlope` zone number and every biome that heavily gates on `slope` with proportional skepticism.
- **Beach/coastline** is a thin spatial boundary (isCoastlineCandidate's real neighbour-count test) that an i.i.d. sampler can't reproduce; approximated as a flat calibrated probability applied to a non-spatial elevation/slope prefilter. Treat `beach` zone area fractions as order-of-magnitude estimates only.
- **Elevation-temperature lapse rate** uses the model's own *fallback* beta (no spatial window to regress against locally); this gets the aggregate warm-region skew right but under-represents pixel-to-pixel lapse variability.

### 3a. Area fractions by zone

**ocean** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:plains` | 32.663% |
| `minecraft:deep_ocean` | 28.131% |
| `minecraft:deep_lukewarm_ocean` | 18.501% |
| `minecraft:deep_cold_ocean` | 12.507% |
| `minecraft:warm_ocean` | 2.593% |
| `minecraft:ocean` | 2.241% |
| `minecraft:lukewarm_ocean` | 1.479% |
| `minecraft:cold_ocean` | 0.990% |
| `minecraft:deep_frozen_ocean` | 0.833% |
| `minecraft:frozen_ocean` | 0.063% |

**beach** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:beach` | 80.280% |
| `minecraft:stony_shore` | 13.193% |
| `minecraft:snowy_beach` | 6.527% |

**mountain** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:snowy_taiga` | 21.706% |
| `minecraft:grove` | 18.249% |
| `minecraft:old_growth_spruce_taiga` | 8.572% |
| `minecraft:meadow` | 8.434% |
| `minecraft:snowy_slopes` | 7.701% |
| `biomesoplenty:highland` | 6.450% |
| `minecraft:windswept_forest` | 5.895% |
| `minecraft:plains` | 4.982% |
| `minecraft:windswept_hills` | 3.916% |
| `minecraft:jagged_peaks` | 3.477% |
| `minecraft:taiga` | 2.778% |
| `minecraft:frozen_peaks` | 2.440% |
| `biomesoplenty:snowy_coniferous_forest` | 1.596% |
| `biomesoplenty:auroral_garden` | 1.226% |
| `minecraft:cherry_grove` | 0.957% |
| `minecraft:windswept_gravelly_hills` | 0.869% |
| `minecraft:stony_peaks` | 0.447% |
| `minecraft:forest` | 0.306% |

**lowland** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:plains` | 11.083% |
| `minecraft:forest` | 10.490% |
| `minecraft:savanna` | 8.145% |
| `minecraft:taiga` | 7.469% |
| `minecraft:sparse_jungle` | 6.823% |
| `minecraft:dark_forest` | 4.348% |
| `biomesoplenty:mediterranean_forest` | 3.413% |
| `minecraft:badlands` | 3.205% |
| `minecraft:snowy_taiga` | 3.147% |
| `minecraft:old_growth_pine_taiga` | 2.768% |
| `minecraft:meadow` | 2.761% |
| `biomesoplenty:volcano` | 2.352% |
| `biomesoplenty:mystic_grove` | 2.279% |
| `minecraft:old_growth_spruce_taiga` | 1.638% |
| `biomesoplenty:lush_desert` | 1.601% |
| `biomesoplenty:fir_clearing` | 1.498% |
| `minecraft:jungle` | 1.425% |
| `minecraft:swamp` | 1.369% |
| `minecraft:stony_peaks` | 1.313% |
| `biomesoplenty:bog` | 1.124% |
| `minecraft:desert` | 1.101% |
| `biomesoplenty:dead_forest` | 1.030% |
| `biomesoplenty:grassland` | 1.011% |
| `minecraft:pale_garden` | 0.784% |
| `biomesoplenty:bayou` | 0.760% |
| _... 49 more_ | |

**bareSlope** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:stony_peaks` | 41.183% |
| `biomesoplenty:wasteland_steppe` | 17.380% |
| `minecraft:windswept_savanna` | 14.292% |
| `minecraft:eroded_badlands` | 10.100% |
| `biomesoplenty:crag` | 9.598% |
| `biomesoplenty:jade_cliffs` | 6.822% |
| `minecraft:frozen_peaks` | 0.514% |
| `minecraft:basalt_deltas` | 0.111% |

### 3b. Diversity metrics (overall)

- Effective number of biomes (exp(Shannon entropy)): **20.07**
- Shannon entropy: 2.999
- HHI concentration: 0.1058 (higher = more concentrated in few biomes)
- Distinct biomes actually reached: 94 / 109

### 3c. Cross-tier collision rates

How often two (or three) genuinely eligible biomes competed for the same pixel (BiomeRuleEngine's competition-noise resolution), by pair, top 20 by count:

| biome A | biome B | pixels where both were eligible |
|---|---|---|
| `minecraft:plains` | `minecraft:savanna` | 74,578 |
| `minecraft:forest` | `minecraft:dark_forest` | 49,794 |
| `minecraft:savanna` | `minecraft:sparse_jungle` | 47,463 |
| `minecraft:savanna` | `minecraft:badlands` | 16,656 |
| `minecraft:plains` | `minecraft:meadow` | 15,893 |
| `minecraft:savanna` | `biomesoplenty:mediterranean_forest` | 15,800 |
| `minecraft:forest` | `minecraft:old_growth_pine_taiga` | 13,258 |
| `minecraft:desert` | `minecraft:savanna` | 11,781 |
| `minecraft:badlands` | `biomesoplenty:mediterranean_forest` | 11,426 |
| `minecraft:swamp` | `minecraft:forest` | 10,955 |
| `minecraft:plains` | `minecraft:desert` | 10,403 |
| `minecraft:forest` | `minecraft:old_growth_spruce_taiga` | 9,803 |
| `minecraft:plains` | `minecraft:badlands` | 9,669 |
| `minecraft:badlands` | `biomesoplenty:volcano` | 9,567 |
| `minecraft:taiga` | `biomesoplenty:fir_clearing` | 8,801 |
| `minecraft:sparse_jungle` | `biomesoplenty:mediterranean_forest` | 8,668 |
| `minecraft:sparse_jungle` | `biomesoplenty:volcano` | 8,629 |
| `minecraft:savanna` | `biomesoplenty:lush_desert` | 8,358 |
| `minecraft:forest` | `minecraft:snowy_taiga` | 8,180 |
| `minecraft:dark_forest` | `minecraft:old_growth_pine_taiga` | 8,111 |

### 3d. Encounterability

Rough "expected exploration distance" estimate = gating-noise-field-wavelength / sqrt(area_fraction). Flagging biomes below 0.050% area fraction as the configured minimum bar.

| biome | area fraction | gating field | wavelength | expected exploration distance | below bar? |
|---|---|---|---|---|---|
| `minecraft:river` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:frozen_river` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:lush_caves` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:dripstone_caves` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:deep_dark` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:nether_wastes` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:crimson_forest` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:warped_forest` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:soul_sand_valley` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:the_end` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:end_highlands` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:end_midlands` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:small_end_islands` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:end_barrens` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `minecraft:the_void` | 0.000% | n/a | ~900 blocks | n/a | **YES** |
| `biomesoplenty:jacaranda_glade` | 0.000% | flowerNoise | ~220 blocks | ~311.1k blocks | **YES** |
| `minecraft:basalt_deltas` | 0.001% | variantNoise | ~650 blocks | ~173.7k blocks | **YES** |
| `minecraft:mushroom_fields` | 0.003% | variantNoise | ~650 blocks | ~112.3k blocks | **YES** |
| `biomesoplenty:muskeg` | 0.004% | n/a | ~900 blocks | ~143.2k blocks | **YES** |
| `minecraft:snowy_beach` | 0.007% | n/a | ~900 blocks | ~107.6k blocks | **YES** |
| `biomesoplenty:ominous_woods` | 0.007% | variantNoise | ~650 blocks | ~75.8k blocks | **YES** |
| `biomesoplenty:origin_valley` | 0.012% | clearingNoise | ~260 blocks | ~23.7k blocks | **YES** |
| `minecraft:stony_shore` | 0.014% | n/a | ~900 blocks | ~75.7k blocks | **YES** |
| `minecraft:windswept_gravelly_hills` | 0.018% | n/a | ~900 blocks | ~67.8k blocks | **YES** |
| `biomesoplenty:auroral_garden` | 0.025% | paleNoise | ~280 blocks | ~17.8k blocks | **YES** |
| `minecraft:old_growth_birch_forest` | 0.025% | variantNoise | ~650 blocks | ~40.7k blocks | **YES** |
| `biomesoplenty:tropics` | 0.028% | n/a | ~900 blocks | ~54.0k blocks | **YES** |
| `minecraft:frozen_ocean` | 0.037% | n/a | ~900 blocks | ~47.1k blocks | **YES** |
| `minecraft:frozen_peaks` | 0.056% | n/a | ~900 blocks | ~38.0k blocks |  |
| `minecraft:ice_spikes` | 0.059% | n/a | ~900 blocks | ~37.1k blocks |  |
| `minecraft:jagged_peaks` | 0.070% | n/a | ~900 blocks | ~33.9k blocks |  |
| `biomesoplenty:snowy_coniferous_forest` | 0.073% | variantNoise | ~650 blocks | ~24.0k blocks |  |
| `minecraft:windswept_hills` | 0.079% | n/a | ~900 blocks | ~31.9k blocks |  |
| `minecraft:beach` | 0.086% | n/a | ~900 blocks | ~30.7k blocks |  |
| `biomesoplenty:jade_cliffs` | 0.086% | n/a | ~900 blocks | ~30.6k blocks |  |
| `minecraft:mangrove_swamp` | 0.100% | n/a | ~900 blocks | ~28.5k blocks |  |
| `biomesoplenty:cold_desert` | 0.109% | n/a | ~900 blocks | ~27.3k blocks |  |
| `biomesoplenty:wintry_origin_valley` | 0.112% | n/a | ~900 blocks | ~26.9k blocks |  |
| `biomesoplenty:snowy_maple_woods` | 0.112% | variantNoise | ~650 blocks | ~19.4k blocks |  |
| `minecraft:sunflower_plains` | 0.117% | flowerNoise | ~220 blocks | ~6.4k blocks |  |
| `minecraft:windswept_forest` | 0.119% | n/a | ~900 blocks | ~26.0k blocks |  |
| `biomesoplenty:crag` | 0.121% | n/a | ~900 blocks | ~25.8k blocks |  |
| `biomesoplenty:lavender_field` | 0.124% | flowerNoise | ~220 blocks | ~6.2k blocks |  |
| `minecraft:eroded_badlands` | 0.128% | n/a | ~900 blocks | ~25.2k blocks |  |
| `biomesoplenty:highland` | 0.131% | n/a | ~900 blocks | ~24.9k blocks |  |
| `biomesoplenty:rainforest` | 0.133% | regionNoise | ~900 blocks | ~24.7k blocks |  |
| `minecraft:birch_forest` | 0.135% | variantNoise | ~650 blocks | ~17.7k blocks |  |
| `minecraft:cherry_grove` | 0.136% | cherryNoise | ~320 blocks | ~8.7k blocks |  |
| `biomesoplenty:fungal_jungle` | 0.147% | n/a | ~900 blocks | ~23.5k blocks |  |
| `biomesoplenty:snowy_fir_clearing` | 0.147% | n/a | ~900 blocks | ~23.5k blocks |  |
| `biomesoplenty:marsh` | 0.147% | n/a | ~900 blocks | ~23.4k blocks |  |
| `minecraft:flower_forest` | 0.147% | flowerNoise | ~220 blocks | ~5.7k blocks |  |
| `minecraft:savanna_plateau` | 0.148% | n/a | ~900 blocks | ~23.4k blocks |  |
| `biomesoplenty:wasteland` | 0.149% | regionNoise | ~900 blocks | ~23.3k blocks |  |
| `biomesoplenty:prairie` | 0.149% | variantNoise | ~650 blocks | ~16.8k blocks |  |
| `biomesoplenty:lush_savanna` | 0.156% | regionNoise | ~900 blocks | ~22.8k blocks |  |
| `minecraft:snowy_slopes` | 0.156% | n/a | ~900 blocks | ~22.8k blocks |  |
| `biomesoplenty:tundra` | 0.167% | n/a | ~900 blocks | ~22.0k blocks |  |
| `biomesoplenty:aspen_glade` | 0.167% | n/a | ~900 blocks | ~22.0k blocks |  |
| `minecraft:snowy_plains` | 0.167% | n/a | ~900 blocks | ~22.0k blocks |  |
| `biomesoplenty:seasonal_forest` | 0.174% | variantNoise | ~650 blocks | ~15.6k blocks |  |
| `biomesoplenty:wetland` | 0.176% | n/a | ~900 blocks | ~21.5k blocks |  |
| `minecraft:windswept_savanna` | 0.181% | n/a | ~900 blocks | ~21.2k blocks |  |
| `biomesoplenty:moor` | 0.181% | regionNoise | ~900 blocks | ~21.1k blocks |  |
| `biomesoplenty:redwood_forest` | 0.206% | n/a | ~900 blocks | ~19.8k blocks |  |
| `biomesoplenty:dryland` | 0.208% | regionNoise | ~900 blocks | ~19.7k blocks |  |
| `biomesoplenty:maple_woods` | 0.212% | variantNoise | ~650 blocks | ~14.1k blocks |  |
| `biomesoplenty:wasteland_steppe` | 0.220% | n/a | ~900 blocks | ~19.2k blocks |  |
| `minecraft:wooded_badlands` | 0.242% | variantNoise | ~650 blocks | ~13.2k blocks |  |
| `minecraft:bamboo_jungle` | 0.262% | n/a | ~900 blocks | ~17.6k blocks |  |
| `biomesoplenty:orchard` | 0.276% | regionNoise | ~900 blocks | ~17.1k blocks |  |
| `biomesoplenty:coniferous_forest` | 0.282% | regionNoise | ~900 blocks | ~16.9k blocks |  |
| `biomesoplenty:woodland` | 0.285% | regionNoise | ~900 blocks | ~16.9k blocks |  |
| `biomesoplenty:snowblossom_grove` | 0.291% | regionNoise | ~900 blocks | ~16.7k blocks |  |
| `biomesoplenty:old_growth_woodland` | 0.300% | n/a | ~900 blocks | ~16.4k blocks |  |
| `biomesoplenty:bayou` | 0.302% | regionNoise | ~900 blocks | ~16.4k blocks |  |
| `minecraft:pale_garden` | 0.311% | paleNoise | ~280 blocks | ~5.0k blocks |  |
| `minecraft:grove` | 0.370% | n/a | ~900 blocks | ~14.8k blocks |  |
| `biomesoplenty:grassland` | 0.401% | regionNoise | ~900 blocks | ~14.2k blocks |  |
| `biomesoplenty:dead_forest` | 0.409% | regionNoise | ~900 blocks | ~14.1k blocks |  |
| `minecraft:desert` | 0.437% | n/a | ~900 blocks | ~13.6k blocks |  |
| `biomesoplenty:bog` | 0.446% | regionNoise | ~900 blocks | ~13.5k blocks |  |
| `minecraft:deep_frozen_ocean` | 0.485% | n/a | ~900 blocks | ~12.9k blocks |  |
| `minecraft:stony_peaks` | 0.530% | n/a | ~900 blocks | ~12.4k blocks |  |
| `minecraft:swamp` | 0.543% | n/a | ~900 blocks | ~12.2k blocks |  |
| `minecraft:jungle` | 0.565% | n/a | ~900 blocks | ~12.0k blocks |  |
| `minecraft:cold_ocean` | 0.576% | n/a | ~900 blocks | ~11.9k blocks |  |
| `biomesoplenty:fir_clearing` | 0.594% | n/a | ~900 blocks | ~11.7k blocks |  |
| `biomesoplenty:lush_desert` | 0.635% | regionNoise | ~900 blocks | ~11.3k blocks |  |
| `minecraft:old_growth_spruce_taiga` | 0.824% | n/a | ~900 blocks | ~9.9k blocks |  |
| `minecraft:lukewarm_ocean` | 0.861% | n/a | ~900 blocks | ~9.7k blocks |  |
| `biomesoplenty:mystic_grove` | 0.904% | clearingNoise | ~260 blocks | ~2.7k blocks |  |
| `biomesoplenty:volcano` | 0.933% | n/a | ~900 blocks | ~9.3k blocks |  |
| `minecraft:old_growth_pine_taiga` | 1.098% | n/a | ~900 blocks | ~8.6k blocks |  |
| `minecraft:meadow` | 1.266% | flowerNoise | ~220 blocks | ~2.0k blocks |  |
| `minecraft:badlands` | 1.271% | variantNoise | ~650 blocks | ~5.8k blocks |  |
| `minecraft:ocean` | 1.305% | n/a | ~900 blocks | ~7.9k blocks |  |
| `biomesoplenty:mediterranean_forest` | 1.354% | n/a | ~900 blocks | ~7.7k blocks |  |
| `minecraft:warm_ocean` | 1.509% | n/a | ~900 blocks | ~7.3k blocks |  |
| `minecraft:snowy_taiga` | 1.688% | n/a | ~900 blocks | ~6.9k blocks |  |
| `minecraft:dark_forest` | 1.725% | variantNoise | ~650 blocks | ~4.9k blocks |  |
| `minecraft:sparse_jungle` | 2.706% | n/a | ~900 blocks | ~5.5k blocks |  |
| `minecraft:taiga` | 3.019% | n/a | ~900 blocks | ~5.2k blocks |  |
| `minecraft:savanna` | 3.230% | n/a | ~900 blocks | ~5.0k blocks |  |
| `minecraft:forest` | 4.167% | variantNoise | ~650 blocks | ~3.2k blocks |  |
| `minecraft:deep_cold_ocean` | 7.279% | n/a | ~900 blocks | ~3.3k blocks |  |
| `minecraft:deep_lukewarm_ocean` | 10.768% | n/a | ~900 blocks | ~2.7k blocks |  |
| `minecraft:deep_ocean` | 16.373% | n/a | ~900 blocks | ~2.2k blocks |  |
| `minecraft:plains` | 23.508% | clearingNoise | ~260 blocks | ~536 blocks |  |

### 3e. Reachable climate ranges ("does it make sense where it's placed")

p5 / p50 / p95 of each variable among pixels where this biome actually won, so you can eyeball whether e.g. a biome literally named 'desert' is reachable under desert-like conditions. Biomes with 0 samples never won a single pixel.

| biome | n | tempC (p5/p50/p95) | precipMm (p5/p50/p95) | moisture (p5/p50/p95) | elevM (p5/p50/p95) | snowy % |
|---|---|---|---|---|---|---|
| `minecraft:plains` | 470,150 | 8.9/27.1/31.0 | 22/478/2268 | 0.01/0.23/1.24 | -5595/-3920/620 | 0.0% |
| `minecraft:sunflower_plains` | 2,330 | 6.3/16.1/22.2 | 39/692/1595 | 0.03/0.65/1.21 | 45/387/1491 | 0.0% |
| `minecraft:snowy_plains` | 3,344 | -10.1/-6.8/-1.5 | 163/334/1769 | 0.38/0.96/5.81 | 163/1155/2343 | 100.0% |
| `minecraft:ice_spikes` | 1,179 | -10.1/-9.3/-2.8 | 169/506/2259 | 0.51/1.57/8.04 | 216/1307/2375 | 100.0% |
| `minecraft:desert` | 8,731 | 20.5/23.7/28.0 | 5/81/168 | 0.00/0.04/0.10 | 221/436/1034 | 0.0% |
| `minecraft:swamp` | 10,861 | -2.1/18.6/25.9 | 960/1677/3279 | 0.84/1.47/4.91 | 14/109/191 | 5.3% |
| `minecraft:mangrove_swamp` | 2,000 | 20.3/23.9/25.8 | 1955/2623/5467 | 1.33/1.69/3.88 | 15/114/192 | 0.0% |
| `minecraft:forest` | 83,334 | -3.1/15.3/19.9 | 302/964/2940 | 0.23/0.84/4.66 | 52/511/1793 | 7.2% |
| `minecraft:flower_forest` | 2,948 | 6.2/15.6/22.2 | 514/910/1669 | 0.43/0.80/1.26 | 46/449/1656 | 0.0% |
| `minecraft:birch_forest` | 2,709 | 12.5/16.5/19.7 | 293/620/1820 | 0.23/0.49/1.64 | 43/504/1776 | 0.0% |
| `minecraft:dark_forest` | 34,493 | -6.4/7.9/23.4 | 861/1784/4309 | 1.34/2.18/6.54 | 58/515/1817 | 18.2% |
| `minecraft:old_growth_birch_forest` | 509 | 12.4/15.8/19.5 | 1447/2112/5145 | 1.34/1.82/4.79 | 54/548/1769 | 0.0% |
| `minecraft:old_growth_pine_taiga` | 21,955 | -1.4/3.8/11.0 | 582/1111/2856 | 1.13/1.58/5.07 | 43/392/1671 | 0.0% |
| `minecraft:old_growth_spruce_taiga` | 16,471 | -1.8/3.9/10.8 | 729/1446/3142 | 1.30/2.17/6.10 | 57/576/4031 | 5.1% |
| `minecraft:taiga` | 60,373 | -1.4/5.0/11.3 | 173/469/994 | 0.24/0.66/1.23 | 38/383/1943 | 0.3% |
| `minecraft:snowy_taiga` | 33,762 | -10.1/-5.5/-1.2 | 332/732/2580 | 0.78/2.07/8.40 | 134/1200/5355 | 100.0% |
| `minecraft:savanna` | 64,608 | 20.6/24.3/28.5 | 25/378/1122 | 0.01/0.20/0.62 | 34/302/925 | 0.0% |
| `minecraft:savanna_plateau` | 2,970 | 20.1/21.4/24.5 | 330/528/867 | 0.21/0.32/0.49 | 914/1054/1463 | 0.0% |
| `minecraft:windswept_hills` | 1,587 | -4.4/1.8/11.8 | 6/63/214 | 0.01/0.10/0.32 | 2540/3328/4828 | 2.7% |
| `minecraft:windswept_gravelly_hills` | 352 | -4.4/2.0/11.2 | 5/80/265 | 0.01/0.12/0.43 | 2538/3320/4916 | 5.1% |
| `minecraft:windswept_forest` | 2,389 | -10.1/-6.6/9.9 | 165/570/2355 | 0.32/1.40/7.14 | 2559/3963/5638 | 66.9% |
| `minecraft:windswept_savanna` | 3,614 | 20.7/24.8/29.3 | 29/429/1048 | 0.02/0.23/0.57 | 26/278/951 | 0.0% |
| `minecraft:jungle` | 11,301 | 21.5/27.0/30.6 | 1467/1911/3012 | 0.82/1.03/2.08 | 17/176/723 | 0.0% |
| `minecraft:sparse_jungle` | 54,123 | 20.6/24.4/28.5 | 399/943/1548 | 0.23/0.53/0.79 | 36/304/927 | 0.0% |
| `minecraft:bamboo_jungle` | 5,231 | 19.3/22.5/28.4 | 1890/2588/5655 | 1.33/1.68/4.01 | 50/428/1200 | 0.0% |
| `minecraft:badlands` | 25,423 | 21.8/26.9/31.3 | 20/305/827 | 0.01/0.15/0.40 | 19/179/714 | 0.0% |
| `minecraft:eroded_badlands` | 2,554 | 20.6/24.8/29.7 | 19/324/647 | 0.01/0.17/0.33 | 24/275/963 | 0.0% |
| `minecraft:wooded_badlands` | 4,834 | 20.7/24.8/29.7 | 12/181/394 | 0.01/0.09/0.19 | 27/265/913 | 0.0% |
| `minecraft:meadow` | 25,317 | -3.6/13.9/21.7 | 12/237/1653 | 0.01/0.19/2.17 | 921/1326/3645 | 3.0% |
| `minecraft:cherry_grove` | 2,713 | 8.4/15.3/18.2 | 605/1038/1590 | 0.61/0.90/1.30 | 603/1410/2795 | 0.0% |
| `minecraft:grove` | 7,396 | -10.1/-9.9/-1.9 | 165/412/1333 | 0.45/1.20/4.38 | 2610/4536/5710 | 96.6% |
| `minecraft:snowy_slopes` | 3,121 | -10.1/-10.0/-6.0 | 10/190/1628 | 0.03/0.59/5.62 | 2562/3811/5643 | 55.0% |
| `minecraft:frozen_peaks` | 1,119 | -10.1/-9.9/-4.3 | 13/202/1607 | 0.04/0.59/5.38 | 1755/5316/5785 | 60.1% |
| `minecraft:jagged_peaks` | 1,409 | -10.1/-10.0/-6.2 | 10/181/1639 | 0.03/0.56/5.65 | 4115/4992/5763 | 53.8% |
| `minecraft:stony_peaks` | 10,595 | -5.0/17.5/27.8 | 14/290/1089 | 0.01/0.23/1.03 | 37/392/1727 | 0.0% |
| `minecraft:mushroom_fields` | 67 | 10.8/16.3/23.5 | 943/1482/2746 | 0.88/1.14/2.38 | 23/168/366 | 0.0% |
| `minecraft:pale_garden` | 6,218 | 5.7/12.1/19.1 | 715/1317/2284 | 0.87/1.27/2.18 | 33/302/656 | 0.0% |
| `minecraft:river` | 0 | _never won any pixel_ | | | | |
| `minecraft:frozen_river` | 0 | _never won any pixel_ | | | | |
| `minecraft:beach` | 1,722 | 3.8/23.5/30.0 | 41/582/2388 | 0.03/0.40/2.06 | 1/10/17 | 0.0% |
| `minecraft:warm_ocean` | 30,182 | 26.2/27.5/31.2 | 42/586/2416 | 0.02/0.28/1.30 | -216/-58/-4 | 0.0% |
| `minecraft:lukewarm_ocean` | 17,217 | 20.4/24.0/25.9 | 42/579/2434 | 0.02/0.33/1.55 | -216/-58/-4 | 0.0% |
| `minecraft:deep_lukewarm_ocean` | 215,359 | 20.5/24.0/25.9 | 38/587/2424 | 0.02/0.34/1.55 | -5665/-4272/-1500 | 0.0% |
| `minecraft:ocean` | 26,091 | 5.8/12.4/19.3 | 39/583/2424 | 0.04/0.56/2.70 | -216/-57/-4 | 0.0% |
| `minecraft:deep_ocean` | 327,460 | 5.8/12.4/19.2 | 39/588/2428 | 0.04/0.56/2.69 | -5666/-4272/-1495 | 0.0% |
| `minecraft:cold_ocean` | 11,526 | -3.1/1.5/4.7 | 38/589/2448 | 0.07/1.05/5.04 | -215/-57/-4 | 11.5% |
| `minecraft:deep_cold_ocean` | 145,585 | -3.1/1.5/4.7 | 39/589/2438 | 0.07/1.05/4.93 | -5670/-4270/-1509 | 11.2% |
| `minecraft:frozen_ocean` | 731 | -7.4/-6.2/-5.1 | 52/585/2171 | 0.16/1.74/7.14 | -214/-60/-4 | 83.0% |
| `minecraft:deep_frozen_ocean` | 9,698 | -7.4/-6.2/-5.1 | 37/587/2409 | 0.10/1.71/7.81 | -5656/-4272/-1509 | 81.7% |
| `minecraft:stony_shore` | 283 | 0.6/23.3/30.0 | 44/544/2640 | 0.03/0.37/2.79 | 1/9/17 | 1.8% |
| `minecraft:snowy_beach` | 140 | -6.1/-1.5/0.8 | 51/560/2928 | 0.10/1.22/6.89 | 1/9/17 | 32.9% |
| `minecraft:lush_caves` | 0 | _never won any pixel_ | | | | |
| `minecraft:dripstone_caves` | 0 | _never won any pixel_ | | | | |
| `minecraft:deep_dark` | 0 | _never won any pixel_ | | | | |
| `minecraft:nether_wastes` | 0 | _never won any pixel_ | | | | |
| `minecraft:crimson_forest` | 0 | _never won any pixel_ | | | | |
| `minecraft:warped_forest` | 0 | _never won any pixel_ | | | | |
| `minecraft:soul_sand_valley` | 0 | _never won any pixel_ | | | | |
| `minecraft:the_end` | 0 | _never won any pixel_ | | | | |
| `minecraft:end_highlands` | 0 | _never won any pixel_ | | | | |
| `minecraft:end_midlands` | 0 | _never won any pixel_ | | | | |
| `minecraft:small_end_islands` | 0 | _never won any pixel_ | | | | |
| `minecraft:end_barrens` | 0 | _never won any pixel_ | | | | |
| `minecraft:the_void` | 0 | _never won any pixel_ | | | | |
| `biomesoplenty:bayou` | 6,031 | 20.5/23.7/25.7 | 1266/1645/2554 | 0.82/1.01/1.70 | 70/390/1051 | 0.0% |
| `biomesoplenty:bog` | 8,914 | -1.6/3.9/11.0 | 483/951/2619 | 0.85/1.34/4.68 | 41/389/1652 | 0.0% |
| `biomesoplenty:coniferous_forest` | 5,648 | -0.9/4.0/9.6 | 326/532/918 | 0.53/0.78/1.14 | 38/365/1642 | 0.0% |
| `biomesoplenty:dead_forest` | 8,170 | 4.6/13.2/20.9 | 202/352/481 | 0.21/0.30/0.46 | 48/465/1802 | 0.0% |
| `biomesoplenty:dryland` | 4,167 | 16.9/23.5/28.2 | 185/281/404 | 0.12/0.16/0.20 | 34/325/1203 | 0.0% |
| `biomesoplenty:grassland` | 8,016 | 9.2/16.9/23.4 | 321/519/811 | 0.31/0.39/0.50 | 46/490/1728 | 0.0% |
| `biomesoplenty:highland` | 2,614 | 3.6/8.3/13.4 | 21/274/903 | 0.02/0.31/1.08 | 2521/2743/3777 | 0.0% |
| `biomesoplenty:lavender_field` | 2,490 | 14.8/19.8/23.6 | 446/615/892 | 0.36/0.42/0.53 | 48/502/1548 | 0.0% |
| `biomesoplenty:lush_desert` | 12,697 | 18.7/24.4/28.6 | 289/527/906 | 0.17/0.29/0.48 | 32/299/1123 | 0.0% |
| `biomesoplenty:maple_woods` | 4,246 | 2.7/8.6/14.3 | 342/543/846 | 0.47/0.62/0.79 | 42/389/1953 | 0.0% |
| `biomesoplenty:marsh` | 2,946 | 8.7/17.0/23.3 | 917/1576/3595 | 0.83/1.27/3.54 | 53/495/1702 | 0.0% |
| `biomesoplenty:moor` | 3,622 | -0.7/7.3/13.4 | 193/348/604 | 0.31/0.41/0.75 | 41/393/1814 | 0.4% |
| `biomesoplenty:mystic_grove` | 18,080 | 13.1/20.7/23.7 | 877/1341/1887 | 0.72/0.94/1.25 | 57/481/1449 | 0.0% |
| `biomesoplenty:ominous_woods` | 147 | 3.5/8.0/14.4 | 607/910/1397 | 0.87/1.08/1.42 | 51/436/1704 | 0.0% |
| `biomesoplenty:orchard` | 5,514 | 13.9/19.0/23.6 | 415/586/841 | 0.33/0.41/0.49 | 55/505/1616 | 0.0% |
| `biomesoplenty:prairie` | 2,984 | 13.2/20.5/25.6 | 180/275/400 | 0.15/0.18/0.20 | 48/391/1476 | 0.0% |
| `biomesoplenty:rainforest` | 2,657 | 22.3/24.9/29.0 | 2095/2721/5907 | 1.33/1.67/3.89 | 34/289/840 | 0.0% |
| `biomesoplenty:redwood_forest` | 4,118 | 8.7/15.8/20.7 | 698/1067/1471 | 0.81/0.89/0.99 | 179/563/1411 | 0.0% |
| `biomesoplenty:wasteland` | 2,983 | 24.2/26.0/30.0 | 4/49/114 | 0.00/0.02/0.06 | 22/191/613 | 0.0% |
| `biomesoplenty:woodland` | 5,703 | 9.1/15.8/19.6 | 322/490/697 | 0.31/0.39/0.49 | 43/514/1805 | 0.0% |
| `biomesoplenty:tundra` | 3,332 | -10.1/-6.8/-1.5 | 163/328/1803 | 0.39/0.96/5.94 | 150/1167/2319 | 100.0% |
| `biomesoplenty:cold_desert` | 2,179 | -10.1/-6.6/-4.2 | 2/24/54 | 0.01/0.07/0.14 | 131/937/2244 | 0.0% |
| `biomesoplenty:snowy_coniferous_forest` | 1,464 | -10.1/-6.3/-1.3 | 222/540/2391 | 0.52/1.54/7.83 | 179/1898/5531 | 100.0% |
| `biomesoplenty:snowy_maple_woods` | 2,234 | -10.0/-5.1/-1.2 | 190/394/1076 | 0.46/0.99/3.33 | 104/979/2214 | 100.0% |
| `biomesoplenty:crag` | 2,427 | -2.7/7.1/14.3 | 197/459/1991 | 0.32/0.51/3.22 | 42/397/1787 | 0.0% |
| `biomesoplenty:jade_cliffs` | 1,725 | 15.9/22.2/28.5 | 551/890/2298 | 0.41/0.52/1.63 | 40/367/1379 | 0.0% |
| `biomesoplenty:wasteland_steppe` | 4,395 | 7.4/20.1/28.7 | 13/199/507 | 0.01/0.14/0.28 | 37/351/1517 | 0.0% |
| `biomesoplenty:tropics` | 555 | 26.1/27.2/30.8 | 1890/2367/3169 | 1.03/1.30/1.85 | 18/152/556 | 0.0% |
| `biomesoplenty:jacaranda_glade` | 1 | 25.4/25.4/25.4 | 1499/1499/1499 | 0.75/0.75/0.75 | 478/478/478 | 0.0% |
| `biomesoplenty:mediterranean_forest` | 27,071 | 19.8/24.7/28.7 | 358/528/779 | 0.21/0.29/0.40 | 30/287/992 | 0.0% |
| `biomesoplenty:lush_savanna` | 3,113 | 22.4/25.2/28.7 | 587/769/1052 | 0.36/0.43/0.50 | 31/255/757 | 0.0% |
| `biomesoplenty:seasonal_forest` | 3,490 | 2.7/8.3/14.4 | 351/540/843 | 0.48/0.63/0.79 | 39/384/1784 | 0.0% |
| `biomesoplenty:aspen_glade` | 3,338 | 2.7/9.2/14.6 | 307/505/910 | 0.45/0.49/0.99 | 41/391/1915 | 0.0% |
| `biomesoplenty:old_growth_woodland` | 6,002 | 8.7/14.8/19.5 | 442/628/883 | 0.50/0.55/0.59 | 49/456/1806 | 0.0% |
| `biomesoplenty:muskeg` | 79 | -1.8/1.6/4.1 | 220/295/396 | 0.63/0.77/0.86 | 69/363/1434 | 0.0% |
| `biomesoplenty:fir_clearing` | 11,883 | -3.2/3.2/7.6 | 92/191/373 | 0.16/0.29/0.47 | 41/389/1643 | 0.0% |
| `biomesoplenty:snowy_fir_clearing` | 2,945 | -10.1/-5.5/-1.2 | 164/297/1591 | 0.37/0.80/4.44 | 137/1112/2285 | 100.0% |
| `biomesoplenty:snowblossom_grove` | 5,822 | -10.0/-4.6/-1.1 | 310/674/2423 | 0.70/1.80/7.49 | 112/900/2163 | 100.0% |
| `biomesoplenty:origin_valley` | 240 | 8.9/14.9/19.5 | 401/550/817 | 0.41/0.46/0.68 | 41/278/793 | 0.0% |
| `biomesoplenty:wintry_origin_valley` | 2,231 | -10.0/-5.0/-1.7 | 164/297/1629 | 0.38/0.80/5.03 | 64/446/861 | 100.0% |
| `biomesoplenty:auroral_garden` | 497 | -10.2/-10.0/-6.3 | 10/173/1588 | 0.03/0.55/5.38 | 2553/3673/4715 | 53.1% |
| `biomesoplenty:volcano` | 18,655 | 26.1/27.4/31.7 | 438/859/1510 | 0.22/0.43/0.75 | 16/153/544 | 0.0% |
| `minecraft:basalt_deltas` | 28 | 1.1/6.6/11.5 | 7/53/221 | 0.01/0.06/0.27 | 43/376/1231 | 0.0% |
| `biomesoplenty:wetland` | 3,520 | -1.4/3.2/10.4 | 483/1130/2818 | 0.88/1.78/5.53 | 34/375/1627 | 0.8% |
| `biomesoplenty:fungal_jungle` | 2,943 | 22.3/24.5/27.3 | 2079/2683/5710 | 1.33/1.67/3.88 | 46/343/839 | 0.0% |

### 3f. Rule bottleneck diagnostics

Per-rule joint pass rate vs. each individual condition's own pass rate (tightest first) -- the fastest way to spot which single condition is strangling a rule in a compounding-narrow-AND case. Showing the 20 rules with the lowest nonzero joint pass rate.

- `biomesoplenty:snowy_coniferous_forest` (zone=mountain, rarity=10.0): joint pass rate 0.000%, tightest condition: `temperatureC < -5` (3.314%)
  - all conditions: temperatureC < -5 (3.314%), clearingNoise > 0.15 (22.518%), treeCoverage >= 0.8 (27.860%), regionNoise < -0.0967 (33.315%), sparsity <= 0.75 (72.800%), snowy == False (94.533%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.000%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 1100.01 <= precipitationMm <= 2600 (22.636%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%)
- `minecraft:grove` (zone=mountain, rarity=3.703): joint pass rate 0.000%, tightest condition: `temperatureC < -5` (3.314%)
  - all conditions: temperatureC < -5 (3.314%), 0.55 <= treeCoverage <= 0.7 (14.507%), sparsity <= 0.75 (72.800%), snowy == False (94.533%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.000%, tightest condition: `cherryNoise > 0.29` (7.169%)
  - all conditions: cherryNoise > 0.29 (7.169%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), elevationM >= 560 (15.934%), 1100.01 <= precipitationMm <= 2600 (22.636%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%), elevationM <= 900 (89.690%)
- `biomesoplenty:jacaranda_glade` (zone=lowland, rarity=10.0): joint pass rate 0.000%, tightest condition: `flowerNoise > 0.6` (0.027%)
  - all conditions: flowerNoise > 0.6 (0.027%), 0.5 <= moisture <= 0.9 (19.126%), 18 <= temperatureC <= 26 (28.301%), 0.15 <= treeCoverage <= 0.4 (30.433%), snowy == False (94.533%)
- `minecraft:snowy_taiga` (zone=mountain, rarity=2.241): joint pass rate 0.000%, tightest condition: `temperatureC < -5` (3.314%)
  - all conditions: temperatureC < -5 (3.314%), treeCoverage >= 0.8 (27.860%), sparsity <= 0.75 (72.800%), snowy == False (94.533%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.000%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 0.9001 <= moisture <= 1.35 (10.969%), 12 <= temperatureC <= 18.5 (14.089%), treeCoverage >= 0.95 (15.138%), 1100.01 <= precipitationMm <= 2600 (22.636%), cherryNoise > 0.11 (28.688%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.001%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 1100.01 <= precipitationMm <= 2600 (22.636%), treeCoverage >= 0.8 (27.860%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.001%, tightest condition: `cherryNoise > 0.29` (7.169%)
  - all conditions: cherryNoise > 0.29 (7.169%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), elevationM >= 560 (15.934%), 1100.01 <= precipitationMm <= 2600 (22.636%), treeCoverage >= 0.8 (27.860%), slope <= 0.52 (87.015%), elevationM <= 900 (89.690%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.001%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.18 (18.226%), treeCoverage >= 0.8 (27.860%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.001%, tightest condition: `cherryNoise > 0.28` (7.910%)
  - all conditions: cherryNoise > 0.28 (7.910%), 0.9001 <= moisture <= 1.35 (10.969%), 12 <= temperatureC <= 18.5 (14.089%), elevationM >= 560 (15.934%), treeCoverage >= 0.8 (27.860%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%), elevationM <= 900 (89.690%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.001%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 0.9001 <= moisture <= 1.35 (10.969%), 12 <= temperatureC <= 18.5 (14.089%), cherryNoise > 0.14 (23.885%), treeCoverage >= 0.8 (27.860%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.001%, tightest condition: `cherryNoise > 0.32` (5.209%)
  - all conditions: cherryNoise > 0.32 (5.209%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), elevationM >= 560 (15.934%), treeCoverage >= 0.8 (27.860%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%), elevationM <= 900 (89.690%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.001%, tightest condition: `cherryNoise > 0.32` (5.209%)
  - all conditions: cherryNoise > 0.32 (5.209%), 12 <= temperatureC <= 18.5 (14.089%), 200 <= elevationM <= 560 (14.161%), 0.58 <= moisture <= 0.9 (14.246%), treeCoverage >= 0.8 (27.860%), 360 <= precipitationMm <= 1100 (43.332%), slope >= 0.12 (67.030%), slope <= 0.52 (87.015%)
- `minecraft:flower_forest` (zone=lowland, rarity=2.059): joint pass rate 0.002%, tightest condition: `1.1201 <= moisture <= 1.35` (4.688%)
  - all conditions: 1.1201 <= moisture <= 1.35 (4.688%), flowerNoise > 0.3256 (4.963%), elevationM > 850 (10.940%), treeCoverage >= 0.95 (15.138%), 5 <= temperatureC <= 23 (41.177%), precipitationMm >= 380 (68.140%), slope < 0.38 (76.541%), snowy == False (94.533%), bareSlope == False (96.689%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.003%, tightest condition: `elevationM > 1000` (9.081%)
  - all conditions: elevationM > 1000 (9.081%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), 1100.01 <= precipitationMm <= 2600 (22.636%), cherryNoise > 0.12 (27.041%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%)
- `biomesoplenty:ominous_woods` (zone=lowland, rarity=10.0): joint pass rate 0.003%, tightest condition: `variantNoise < -0.5` (0.310%)
  - all conditions: variantNoise < -0.5 (0.310%), 0.9 <= moisture <= 1.4 (11.822%), 4 <= temperatureC <= 14 (20.375%), treeCoverage >= 0.7 (27.860%), regionNoise < -0.0967 (33.315%), snowy == False (94.533%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.003%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.18 (18.226%), 360 <= precipitationMm <= 1100 (43.332%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.004%, tightest condition: `cherryNoise > 0.32` (5.209%)
  - all conditions: cherryNoise > 0.32 (5.209%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), elevationM >= 560 (15.934%), 360 <= precipitationMm <= 1100 (43.332%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%), elevationM <= 900 (89.690%)
- `minecraft:plains` (zone=lowland, rarity=1.0): joint pass rate 0.005%, tightest condition: `clearingNoise > 0.3968` (1.996%)
  - all conditions: clearingNoise > 0.3968 (1.996%), treeCoverage >= 0.95 (15.138%), 0.52 <= moisture <= 1.35 (28.832%), 5 <= temperatureC <= 23 (41.177%), precipitationMm >= 380 (68.140%), slope < 0.38 (76.541%), elevationM <= 850 (89.060%), snowy == False (94.533%), bareSlope == False (96.689%)

**30 rule(s) had a joint pass rate of exactly 0 in this sample** (either genuinely dead per the static validators above, or just extremely rare -- increase `--min-samples` to tell the two apart):

- `minecraft:plains` (zone=lowland, rarity=1.0), tightest: `12 <= temperatureC <= 19.99`
- `minecraft:flower_forest` (zone=lowland, rarity=2.059), tightest: `flowerNoise > 0.3256`
- `minecraft:flower_forest` (zone=lowland, rarity=2.059), tightest: `flowerNoise > 0.3256`
- `minecraft:flower_forest` (zone=lowland, rarity=2.059), tightest: `1.1201 <= moisture <= 1.35`
- `minecraft:flower_forest` (zone=lowland, rarity=2.059), tightest: `variantNoise > 0.22`
- `minecraft:flower_forest` (zone=lowland, rarity=2.059), tightest: `flowerNoise > 0.3256`
- `minecraft:taiga` (zone=lowland, rarity=1.393), tightest: `treeCoverage >= 0.95`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `900 <= elevationM <= 1000`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `900 <= elevationM <= 1000`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `elevationM > 1000`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `elevationM > 1000`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `cherryNoise > 0.28`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `cherryNoise > 0.25`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `900 <= elevationM <= 1000`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `900 <= elevationM <= 1000`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `900 <= elevationM <= 1000`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `cherryNoise > 0.32`
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0), tightest: `cherryNoise > 0.28`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `paleNoise > 0.34`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `paleNoise > 0.3`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `paleNoise > 0.3`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `paleNoise > 0.26`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `paleNoise > 0.32`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `paleNoise > 0.24`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `0.82 <= moisture <= 1.1`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `0.82 <= moisture <= 1.1`
- `minecraft:pale_garden` (zone=lowland, rarity=1.644), tightest: `0.82 <= moisture <= 1.1`
- `biomesoplenty:prairie` (zone=lowland, rarity=1.926), tightest: `0.2 <= moisture <= 0.4`
- `biomesoplenty:auroral_garden` (zone=lowland, rarity=10.0), tightest: `paleNoise > 0.75`
- `minecraft:basalt_deltas` (zone=bareSlope, rarity=1.0), tightest: `treeCoverage == 0.3499999940395355`

### 3g. Low joint pass rate (individually valid, compounds to near-invisible)

Rules where every condition is individually satisfiable and none trip the structural discreteness/noise-ceiling checks in section 1, but the AND of all of them together is so restrictive the biome is effectively invisible in normal play. Different failure mode from section 1's dead conditions: nothing here is broken, several moderately-narrow conditions are just compounding multiplicatively. Run with `--fix` to widen the tightest 1-2 conditions per rule toward `--fix-target-rate` (default 2%), re-simulated against real Monte Carlo samples rather than an independence assumption -- see section 2 for what was widened, if `--fix` was passed.

**115 rule(s)** below 0.500%:

- `biomesoplenty:auroral_garden` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0741% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), paleNoise > 0.15 (22.415%), treeCoverage <= 0.01 (27.200%), elevationM <= 4800 (99.461%).
- `biomesoplenty:auroral_garden` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0861% of sampled climate -- below the 0.50% bar. Tightest single condition: 'temperatureC < -5' (3.314% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: temperatureC < -5 (3.314%), paleNoise > 0.15 (22.415%), treeCoverage <= 0.01 (27.200%), snowy == False (94.533%), elevationM <= 4800 (99.461%).
- `biomesoplenty:cold_desert` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.2503% of sampled climate -- below the 0.50% bar. Tightest single condition: '-20 <= temperatureC <= -4' (4.038% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: -20 <= temperatureC <= -4 (4.038%), precipitationMm <= 120 (11.507%), moisture <= 0.15 (18.879%), treeCoverage <= 0.02 (27.200%), snowy == False (94.533%).
- `biomesoplenty:jacaranda_glade` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0002% of sampled climate -- below the 0.50% bar. Tightest single condition: 'flowerNoise > 0.6' (0.027% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: flowerNoise > 0.6 (0.027%), 0.5 <= moisture <= 0.9 (19.126%), 18 <= temperatureC <= 26 (28.301%), 0.15 <= treeCoverage <= 0.4 (30.433%), snowy == False (94.533%).
- `biomesoplenty:lavender_field` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.4021% of sampled climate -- below the 0.50% bar. Tightest single condition: 'flowerNoise > 0.2564' (9.964% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: flowerNoise > 0.2564 (9.964%), 0.35 <= moisture <= 0.7 (22.637%), 14 <= temperatureC <= 24 (26.716%), treeCoverage <= 0.35 (57.633%), snowy == False (94.533%).
- `biomesoplenty:muskeg` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0123% of sampled climate -- below the 0.50% bar. Tightest single condition: 'growingSeasonDays < 90' (1.178% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: growingSeasonDays < 90 (1.178%), 0.6 <= moisture <= 0.9 (13.139%), -5 <= temperatureC <= 5 (15.381%), treeCoverage <= 0.15 (27.200%), snowy == False (94.533%).
- `biomesoplenty:mystic_grove` (zone=lowland, rarity=1.651): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0365% of sampled climate -- below the 0.50% bar. Tightest single condition: 'clearingNoise > 0.3968' (1.996% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: clearingNoise > 0.3968 (1.996%), 0.8 <= moisture <= 1.3 (13.773%), 14 <= temperatureC <= 22 (19.619%), 0.5 <= treeCoverage <= 0.9 (27.229%), slope < 0.3 (67.306%).
- `biomesoplenty:ominous_woods` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0032% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise < -0.5' (0.310% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise < -0.5 (0.310%), 0.9 <= moisture <= 1.4 (11.822%), 4 <= temperatureC <= 14 (20.375%), treeCoverage >= 0.7 (27.860%), regionNoise < -0.0967 (33.315%), snowy == False (94.533%).
- `biomesoplenty:ominous_woods` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0295% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise < -0.4' (1.847% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise < -0.4 (1.847%), 0.85 <= moisture <= 1.5 (15.121%), 3 <= temperatureC <= 16 (26.677%), treeCoverage >= 0.65 (27.860%), regionNoise < -0.0967 (33.315%), snowy == False (94.533%).
- `biomesoplenty:origin_valley` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0441% of sampled climate -- below the 0.50% bar. Tightest single condition: 'clearingNoise > 0.3968' (1.996% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: clearingNoise > 0.3968 (1.996%), 0.4 <= moisture <= 0.8 (22.656%), 8 <= temperatureC <= 20 (26.026%), 0.2 <= treeCoverage <= 0.5 (30.433%), elevationM <= 900 (89.690%), snowy == False (94.533%).
- `biomesoplenty:prairie` (zone=lowland, rarity=1.926): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1500% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.2 <= moisture <= 0.45' (24.496% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.2 <= moisture <= 0.45 (24.496%), treeCoverage <= 0.1 (27.200%), 15 <= temperatureC <= 25 (29.246%), regionNoise < -0.0967 (33.315%), variantNoise <= 0.3 (93.531%), snowy == False (94.533%).
- `biomesoplenty:redwood_forest` (zone=lowland, rarity=1.632): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3243% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.55 <= moisture <= 0.95' (17.640% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.55 <= moisture <= 0.95 (17.640%), 10 <= temperatureC <= 20 (21.795%), 200 <= elevationM <= 1400 (24.926%), treeCoverage >= 0.8 (27.860%).
- `biomesoplenty:snowy_coniferous_forest` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1432% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), variantNoise > 0.15 (22.353%), regionNoise < -0.0967 (33.315%), 0.3 <= treeCoverage <= 0.75 (44.940%).
- `biomesoplenty:snowy_coniferous_forest` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.2367% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), clearingNoise > 0.15 (22.518%), treeCoverage >= 0.8 (27.860%), regionNoise < -0.0967 (33.315%), sparsity <= 0.75 (72.800%).
- `biomesoplenty:snowy_coniferous_forest` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0000% of sampled climate -- below the 0.50% bar. Tightest single condition: 'temperatureC < -5' (3.314% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: temperatureC < -5 (3.314%), clearingNoise > 0.15 (22.518%), treeCoverage >= 0.8 (27.860%), regionNoise < -0.0967 (33.315%), sparsity <= 0.75 (72.800%), snowy == False (94.533%).
- `biomesoplenty:snowy_maple_woods` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3945% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), -0.4 <= variantNoise <= -0.15 (20.540%), 0.3 <= treeCoverage <= 0.65 (44.940%).
- `biomesoplenty:tropics` (zone=lowland, rarity=6.202): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1600% of sampled climate -- below the 0.50% bar. Tightest single condition: 'precipitationMm >= 1800' (10.608% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: precipitationMm >= 1800 (10.608%), 1.0 <= moisture <= 2.2 (16.857%), temperatureC >= 26 (25.658%), 0.1 <= treeCoverage <= 0.4 (30.433%).
- `biomesoplenty:woodland` (zone=lowland, rarity=1.649): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1051% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise < -0.3' (6.458% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise < -0.3 (6.458%), 0.3 <= moisture <= 0.6 (22.535%), 8 <= temperatureC <= 20 (26.026%), 0.3 <= treeCoverage <= 0.45 (30.433%), -0.0967 <= regionNoise <= 0.0967 (33.371%), snowy == False (94.533%).
- `minecraft:basalt_deltas` (zone=bareSlope, rarity=1.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3764% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise > 0.27000001072883606' (8.689% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise > 0.27000001072883606 (8.689%), treeCoverage == 0.0 (27.200%), 0.0 <= temperatureC <= 14.0 (27.877%), 0.0 <= moisture <= 0.30000001192092896 (35.943%), 0.0 <= precipitationMm <= 500.0 (42.416%).
- `minecraft:birch_forest` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3153% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise < -0.35' (3.654% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise < -0.35 (3.654%), 12 <= temperatureC <= 19.99 (17.802%), 0.3 <= treeCoverage <= 0.7 (44.940%).
- `minecraft:birch_forest` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0917% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise < -0.4' (1.847% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise < -0.4 (1.847%), 12 <= temperatureC <= 19.99 (17.802%), treeCoverage >= 0.8 (27.860%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0365% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.58 <= moisture <= 0.9' (14.246% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 1100.01 <= precipitationMm <= 2600 (22.636%), 6 <= temperatureC <= 18.5 (26.493%), slope <= 0.52 (87.015%), elevationM <= 1000 (90.919%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0979% of sampled climate -- below the 0.50% bar. Tightest single condition: 'elevationM > 1000' (9.081% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: elevationM > 1000 (9.081%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 6 <= temperatureC <= 18.5 (26.493%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0087% of sampled climate -- below the 0.50% bar. Tightest single condition: 'elevationM > 1000' (9.081% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: elevationM > 1000 (9.081%), 0.58 <= moisture <= 0.9 (14.246%), 1100.01 <= precipitationMm <= 2600 (22.636%), 6 <= temperatureC <= 18.5 (26.493%), cherryNoise > 0.12 (27.041%), slope <= 0.52 (87.015%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3056% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.9001 <= moisture <= 1.35' (10.969% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.9001 <= moisture <= 1.35 (10.969%), cherryNoise > 0.14 (23.885%), 6 <= temperatureC <= 18.5 (26.493%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%), elevationM <= 1000 (90.919%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3741% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.9001 <= moisture <= 1.35' (10.969% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.9001 <= moisture <= 1.35 (10.969%), 1100.01 <= precipitationMm <= 2600 (22.636%), 6 <= temperatureC <= 18.5 (26.493%), cherryNoise > 0.11 (28.688%), slope <= 0.52 (87.015%), elevationM <= 1000 (90.919%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0461% of sampled climate -- below the 0.50% bar. Tightest single condition: 'elevationM > 1000' (9.081% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: elevationM > 1000 (9.081%), 0.9001 <= moisture <= 1.35 (10.969%), 6 <= temperatureC <= 18.5 (26.493%), cherryNoise > 0.11 (28.688%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0723% of sampled climate -- below the 0.50% bar. Tightest single condition: 'elevationM > 1000' (9.081% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: elevationM > 1000 (9.081%), 0.9001 <= moisture <= 1.35 (10.969%), 1100.01 <= precipitationMm <= 2600 (22.636%), 6 <= temperatureC <= 18.5 (26.493%), cherryNoise > 0.08 (33.942%), slope <= 0.52 (87.015%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1242% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.58 <= moisture <= 0.9' (14.246% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.18 (18.226%), 6 <= temperatureC <= 18.5 (26.493%), treeCoverage >= 0.8 (27.860%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%), elevationM <= 1000 (90.919%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0249% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.58 <= moisture <= 0.9' (14.246% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 1100.01 <= precipitationMm <= 2600 (22.636%), 6 <= temperatureC <= 18.5 (26.493%), treeCoverage >= 0.8 (27.860%), slope <= 0.52 (87.015%), elevationM <= 1000 (90.919%).
