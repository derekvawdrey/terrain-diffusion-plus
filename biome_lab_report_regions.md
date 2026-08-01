# biome-lab report

- Catalog: `/home/derek/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json`
- Biomes in catalog: 124
- Generated: 2026-07-31T19:38:17
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
- `minecraft:forest` (zone=lowland, rarity=1.784): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:flower_forest` (zone=lowland, rarity=2.252): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.
- `minecraft:meadow` (zone=mountain, rarity=1.434): This rule conditions on both 'moisture' and 'treeMoisture', but TerrainClimateSample populates both from the exact same underlying value -- they are not independent constraints, just two names for one number. Not necessarily a bug, but double-check the intent.

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
| `minecraft:beach` | 56.084% |
| `biomesoplenty:dune_beach` | 15.944% |
| `minecraft:stony_shore` | 13.193% |
| `biomesoplenty:gravel_beach` | 8.252% |
| `minecraft:snowy_beach` | 6.527% |

**mountain** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:grove` | 18.308% |
| `minecraft:snowy_taiga` | 17.598% |
| `minecraft:meadow` | 10.566% |
| `minecraft:snowy_slopes` | 7.726% |
| `biomesoplenty:snowy_coniferous_forest` | 6.855% |
| `minecraft:windswept_forest` | 6.334% |
| `minecraft:taiga` | 4.794% |
| `minecraft:windswept_hills` | 3.958% |
| `biomesoplenty:hot_springs` | 3.805% |
| `minecraft:old_growth_spruce_taiga` | 3.602% |
| `minecraft:plains` | 3.588% |
| `minecraft:jagged_peaks` | 3.486% |
| `biomesoplenty:highland` | 2.670% |
| `minecraft:frozen_peaks` | 2.455% |
| `biomesoplenty:auroral_garden` | 1.286% |
| `minecraft:windswept_gravelly_hills` | 1.091% |
| `minecraft:cherry_grove` | 0.960% |
| `minecraft:stony_peaks` | 0.614% |
| `minecraft:forest` | 0.306% |

**lowland** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:forest` | 10.730% |
| `minecraft:savanna` | 9.683% |
| `minecraft:plains` | 7.844% |
| `minecraft:sparse_jungle` | 7.135% |
| `minecraft:taiga` | 5.920% |
| `minecraft:meadow` | 3.864% |
| `minecraft:snowy_taiga` | 2.888% |
| `minecraft:dark_forest` | 2.748% |
| `minecraft:badlands` | 2.736% |
| `biomesoplenty:bog` | 2.110% |
| `minecraft:stony_peaks` | 2.003% |
| `minecraft:birch_forest` | 1.895% |
| `biomesoplenty:mediterranean_forest` | 1.865% |
| `minecraft:swamp` | 1.560% |
| `minecraft:wooded_badlands` | 1.535% |
| `minecraft:old_growth_pine_taiga` | 1.461% |
| `minecraft:jungle` | 1.409% |
| `biomesoplenty:dead_forest` | 1.392% |
| `minecraft:desert` | 1.390% |
| `biomesoplenty:scrubland` | 1.312% |
| `biomesoplenty:maple_woods` | 1.269% |
| `biomesoplenty:volcano` | 1.193% |
| `biomesoplenty:coniferous_forest` | 1.175% |
| `biomesoplenty:snowblossom_grove` | 1.168% |
| `biomesoplenty:bayou` | 1.162% |
| _... 61 more_ | |

**bareSlope** (100.000% of samples land in this zone)

| biome | area fraction (of zone) |
|---|---|
| `minecraft:stony_peaks` | 62.839% |
| `biomesoplenty:wasteland_steppe` | 11.876% |
| `minecraft:eroded_badlands` | 8.906% |
| `minecraft:windswept_savanna` | 5.920% |
| `biomesoplenty:jade_cliffs` | 5.892% |
| `biomesoplenty:crag` | 3.911% |
| `minecraft:frozen_peaks` | 0.514% |
| `minecraft:basalt_deltas` | 0.142% |

### 3b. Diversity metrics (overall)

- Effective number of biomes (exp(Shannon entropy)): **22.00**
- Shannon entropy: 3.091
- HHI concentration: 0.0998 (higher = more concentrated in few biomes)
- Distinct biomes actually reached: 109 / 124

### 3c. Cross-tier collision rates

How often two (or three) genuinely eligible biomes competed for the same pixel (BiomeRuleEngine's competition-noise resolution), by pair, top 20 by count:

| biome A | biome B | pixels where both were eligible |
|---|---|---|
| `minecraft:savanna` | `minecraft:sparse_jungle` | 45,140 |
| `minecraft:desert` | `minecraft:savanna` | 27,614 |
| `minecraft:forest` | `minecraft:dark_forest` | 27,178 |
| `minecraft:forest` | `minecraft:birch_forest` | 20,978 |
| `minecraft:savanna` | `minecraft:badlands` | 19,997 |
| `minecraft:forest` | `minecraft:old_growth_spruce_taiga` | 11,725 |
| `minecraft:swamp` | `minecraft:forest` | 11,443 |
| `minecraft:badlands` | `minecraft:wooded_badlands` | 9,779 |
| `minecraft:forest` | `minecraft:snowy_taiga` | 9,634 |
| `minecraft:savanna` | `minecraft:wooded_badlands` | 9,395 |
| `minecraft:windswept_savanna` | `minecraft:stony_peaks` | 9,251 |
| `minecraft:taiga` | `biomesoplenty:coniferous_forest` | 8,753 |
| `minecraft:savanna` | `biomesoplenty:dryland` | 8,609 |
| `minecraft:taiga` | `biomesoplenty:seasonal_forest` | 8,400 |
| `minecraft:savanna` | `biomesoplenty:scrubland` | 8,028 |
| `minecraft:taiga` | `biomesoplenty:maple_woods` | 7,965 |
| `minecraft:forest` | `minecraft:pale_garden` | 7,835 |
| `minecraft:savanna` | `biomesoplenty:mediterranean_forest` | 7,829 |
| `minecraft:taiga` | `biomesoplenty:bog` | 7,532 |
| `minecraft:forest` | `biomesoplenty:dead_forest` | 6,841 |

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
| `minecraft:basalt_deltas` | 0.002% | variantNoise | ~650 blocks | ~153.2k blocks | **YES** |
| `minecraft:mushroom_fields` | 0.003% | variantNoise | ~650 blocks | ~114.9k blocks | **YES** |
| `minecraft:snowy_beach` | 0.007% | n/a | ~900 blocks | ~107.6k blocks | **YES** |
| `biomesoplenty:origin_valley` | 0.008% | clearingNoise | ~260 blocks | ~28.3k blocks | **YES** |
| `biomesoplenty:gravel_beach` | 0.009% | n/a | ~900 blocks | ~95.7k blocks | **YES** |
| `minecraft:stony_shore` | 0.014% | n/a | ~900 blocks | ~75.7k blocks | **YES** |
| `biomesoplenty:rocky_rainforest` | 0.016% | regionNoise | ~900 blocks | ~70.4k blocks | **YES** |
| `biomesoplenty:dune_beach` | 0.017% | n/a | ~900 blocks | ~68.8k blocks | **YES** |
| `biomesoplenty:jacaranda_glade` | 0.017% | flowerNoise | ~220 blocks | ~16.7k blocks | **YES** |
| `biomesoplenty:muskeg` | 0.020% | regionNoise | ~900 blocks | ~64.0k blocks | **YES** |
| `minecraft:windswept_gravelly_hills` | 0.022% | n/a | ~900 blocks | ~60.5k blocks | **YES** |
| `biomesoplenty:ominous_woods` | 0.023% | variantNoise | ~650 blocks | ~42.6k blocks | **YES** |
| `minecraft:ice_spikes` | 0.025% | regionNoise | ~900 blocks | ~56.6k blocks | **YES** |
| `biomesoplenty:auroral_garden` | 0.026% | paleNoise | ~280 blocks | ~17.3k blocks | **YES** |
| `biomesoplenty:floodplain` | 0.036% | regionNoise | ~900 blocks | ~47.3k blocks | **YES** |
| `minecraft:frozen_ocean` | 0.037% | n/a | ~900 blocks | ~47.1k blocks | **YES** |
| `biomesoplenty:overgrown_greens` | 0.038% | regionNoise | ~900 blocks | ~46.3k blocks | **YES** |
| `biomesoplenty:wintry_origin_valley` | 0.039% | regionNoise | ~900 blocks | ~45.3k blocks | **YES** |
| `biomesoplenty:pumpkin_patch` | 0.047% | clearingNoise | ~260 blocks | ~11.9k blocks | **YES** |
| `biomesoplenty:crag` | 0.049% | regionNoise | ~900 blocks | ~40.5k blocks | **YES** |
| `biomesoplenty:marsh` | 0.052% | regionNoise | ~900 blocks | ~39.6k blocks |  |
| `biomesoplenty:highland` | 0.054% | regionNoise | ~900 blocks | ~38.7k blocks |  |
| `minecraft:frozen_peaks` | 0.056% | n/a | ~900 blocks | ~37.9k blocks |  |
| `biomesoplenty:rainforest` | 0.059% | regionNoise | ~900 blocks | ~37.1k blocks |  |
| `minecraft:beach` | 0.060% | n/a | ~900 blocks | ~36.7k blocks |  |
| `biomesoplenty:snowy_fir_clearing` | 0.060% | regionNoise | ~900 blocks | ~36.6k blocks |  |
| `biomesoplenty:tundra` | 0.067% | regionNoise | ~900 blocks | ~34.7k blocks |  |
| `minecraft:jagged_peaks` | 0.071% | n/a | ~900 blocks | ~33.9k blocks |  |
| `biomesoplenty:jade_cliffs` | 0.074% | regionNoise | ~900 blocks | ~33.0k blocks |  |
| `minecraft:windswept_savanna` | 0.075% | n/a | ~900 blocks | ~32.9k blocks |  |
| `biomesoplenty:cold_desert` | 0.077% | regionNoise | ~900 blocks | ~32.4k blocks |  |
| `biomesoplenty:hot_springs` | 0.077% | regionNoise | ~900 blocks | ~32.4k blocks |  |
| `biomesoplenty:aspen_glade` | 0.077% | regionNoise | ~900 blocks | ~32.4k blocks |  |
| `minecraft:windswept_hills` | 0.080% | n/a | ~900 blocks | ~31.8k blocks |  |
| `biomesoplenty:tropics` | 0.094% | regionNoise | ~900 blocks | ~29.3k blocks |  |
| `biomesoplenty:old_growth_woodland` | 0.101% | regionNoise | ~900 blocks | ~28.3k blocks |  |
| `biomesoplenty:lavender_field` | 0.102% | flowerNoise | ~220 blocks | ~6.9k blocks |  |
| `biomesoplenty:fungal_jungle` | 0.102% | regionNoise | ~900 blocks | ~28.1k blocks |  |
| `biomesoplenty:wetland` | 0.104% | regionNoise | ~900 blocks | ~27.9k blocks |  |
| `minecraft:sunflower_plains` | 0.106% | flowerNoise | ~220 blocks | ~6.7k blocks |  |
| `minecraft:bamboo_jungle` | 0.113% | regionNoise | ~900 blocks | ~26.8k blocks |  |
| `minecraft:eroded_badlands` | 0.113% | regionNoise | ~900 blocks | ~26.8k blocks |  |
| `minecraft:mangrove_swamp` | 0.113% | n/a | ~900 blocks | ~26.8k blocks |  |
| `biomesoplenty:mystic_grove` | 0.126% | clearingNoise | ~260 blocks | ~7.3k blocks |  |
| `biomesoplenty:redwood_forest` | 0.126% | regionNoise | ~900 blocks | ~25.3k blocks |  |
| `minecraft:cherry_grove` | 0.127% | cherryNoise | ~320 blocks | ~9.0k blocks |  |
| `minecraft:windswept_forest` | 0.128% | n/a | ~900 blocks | ~25.1k blocks |  |
| `biomesoplenty:wasteland` | 0.132% | regionNoise | ~900 blocks | ~24.8k blocks |  |
| `minecraft:old_growth_birch_forest` | 0.133% | regionNoise | ~900 blocks | ~24.7k blocks |  |
| `minecraft:flower_forest` | 0.144% | flowerNoise | ~220 blocks | ~5.8k blocks |  |
| `biomesoplenty:pasture` | 0.144% | regionNoise | ~900 blocks | ~23.7k blocks |  |
| `minecraft:savanna_plateau` | 0.146% | n/a | ~900 blocks | ~23.5k blocks |  |
| `biomesoplenty:rocky_shrubland` | 0.149% | regionNoise | ~900 blocks | ~23.3k blocks |  |
| `biomesoplenty:wasteland_steppe` | 0.150% | regionNoise | ~900 blocks | ~23.2k blocks |  |
| `minecraft:snowy_slopes` | 0.157% | n/a | ~900 blocks | ~22.7k blocks |  |
| `biomesoplenty:lush_desert` | 0.165% | regionNoise | ~900 blocks | ~22.2k blocks |  |
| `biomesoplenty:prairie` | 0.169% | regionNoise | ~900 blocks | ~21.9k blocks |  |
| `biomesoplenty:forested_field` | 0.183% | regionNoise | ~900 blocks | ~21.0k blocks |  |
| `biomesoplenty:snowy_maple_woods` | 0.186% | regionNoise | ~900 blocks | ~20.9k blocks |  |
| `biomesoplenty:woodland` | 0.197% | regionNoise | ~900 blocks | ~20.3k blocks |  |
| `biomesoplenty:field` | 0.213% | regionNoise | ~900 blocks | ~19.5k blocks |  |
| `biomesoplenty:old_growth_dead_forest` | 0.241% | regionNoise | ~900 blocks | ~18.3k blocks |  |
| `minecraft:snowy_plains` | 0.258% | n/a | ~900 blocks | ~17.7k blocks |  |
| `biomesoplenty:fir_clearing` | 0.283% | regionNoise | ~900 blocks | ~16.9k blocks |  |
| `biomesoplenty:moor` | 0.303% | regionNoise | ~900 blocks | ~16.4k blocks |  |
| `biomesoplenty:lush_savanna` | 0.310% | regionNoise | ~900 blocks | ~16.2k blocks |  |
| `biomesoplenty:volcanic_plains` | 0.318% | regionNoise | ~900 blocks | ~16.0k blocks |  |
| `biomesoplenty:snowy_coniferous_forest` | 0.325% | regionNoise | ~900 blocks | ~15.8k blocks |  |
| `minecraft:pale_garden` | 0.329% | paleNoise | ~280 blocks | ~4.9k blocks |  |
| `biomesoplenty:orchard` | 0.353% | regionNoise | ~900 blocks | ~15.1k blocks |  |
| `biomesoplenty:grassland` | 0.361% | regionNoise | ~900 blocks | ~15.0k blocks |  |
| `minecraft:grove` | 0.371% | n/a | ~900 blocks | ~14.8k blocks |  |
| `biomesoplenty:dryland` | 0.405% | regionNoise | ~900 blocks | ~14.1k blocks |  |
| `biomesoplenty:shrubland` | 0.442% | regionNoise | ~900 blocks | ~13.5k blocks |  |
| `biomesoplenty:seasonal_forest` | 0.448% | regionNoise | ~900 blocks | ~13.4k blocks |  |
| `biomesoplenty:bayou` | 0.461% | regionNoise | ~900 blocks | ~13.3k blocks |  |
| `biomesoplenty:snowblossom_grove` | 0.463% | regionNoise | ~900 blocks | ~13.2k blocks |  |
| `biomesoplenty:coniferous_forest` | 0.466% | regionNoise | ~900 blocks | ~13.2k blocks |  |
| `biomesoplenty:volcano` | 0.473% | regionNoise | ~900 blocks | ~13.1k blocks |  |
| `minecraft:deep_frozen_ocean` | 0.485% | n/a | ~900 blocks | ~12.9k blocks |  |
| `minecraft:old_growth_spruce_taiga` | 0.487% | regionNoise | ~900 blocks | ~12.9k blocks |  |
| `biomesoplenty:maple_woods` | 0.503% | regionNoise | ~900 blocks | ~12.7k blocks |  |
| `biomesoplenty:scrubland` | 0.520% | regionNoise | ~900 blocks | ~12.5k blocks |  |
| `minecraft:desert` | 0.551% | n/a | ~900 blocks | ~12.1k blocks |  |
| `biomesoplenty:dead_forest` | 0.552% | regionNoise | ~900 blocks | ~12.1k blocks |  |
| `minecraft:jungle` | 0.559% | n/a | ~900 blocks | ~12.0k blocks |  |
| `minecraft:cold_ocean` | 0.576% | n/a | ~900 blocks | ~11.9k blocks |  |
| `minecraft:old_growth_pine_taiga` | 0.579% | regionNoise | ~900 blocks | ~11.8k blocks |  |
| `minecraft:wooded_badlands` | 0.609% | regionNoise | ~900 blocks | ~11.5k blocks |  |
| `minecraft:swamp` | 0.619% | n/a | ~900 blocks | ~11.4k blocks |  |
| `biomesoplenty:mediterranean_forest` | 0.740% | regionNoise | ~900 blocks | ~10.5k blocks |  |
| `minecraft:birch_forest` | 0.752% | regionNoise | ~900 blocks | ~10.4k blocks |  |
| `minecraft:stony_peaks` | 0.807% | n/a | ~900 blocks | ~10.0k blocks |  |
| `biomesoplenty:bog` | 0.837% | regionNoise | ~900 blocks | ~9.8k blocks |  |
| `minecraft:lukewarm_ocean` | 0.861% | n/a | ~900 blocks | ~9.7k blocks |  |
| `minecraft:badlands` | 1.085% | regionNoise | ~900 blocks | ~8.6k blocks |  |
| `minecraft:dark_forest` | 1.090% | regionNoise | ~900 blocks | ~8.6k blocks |  |
| `minecraft:ocean` | 1.305% | n/a | ~900 blocks | ~7.9k blocks |  |
| `minecraft:snowy_taiga` | 1.502% | n/a | ~900 blocks | ~7.3k blocks |  |
| `minecraft:warm_ocean` | 1.509% | n/a | ~900 blocks | ~7.3k blocks |  |
| `minecraft:meadow` | 1.747% | flowerNoise | ~220 blocks | ~1.7k blocks |  |
| `minecraft:taiga` | 2.445% | n/a | ~900 blocks | ~5.8k blocks |  |
| `minecraft:sparse_jungle` | 2.830% | n/a | ~900 blocks | ~5.3k blocks |  |
| `minecraft:savanna` | 3.841% | n/a | ~900 blocks | ~4.6k blocks |  |
| `minecraft:forest` | 4.262% | variantNoise | ~650 blocks | ~3.1k blocks |  |
| `minecraft:deep_cold_ocean` | 7.279% | n/a | ~900 blocks | ~3.3k blocks |  |
| `minecraft:deep_lukewarm_ocean` | 10.768% | n/a | ~900 blocks | ~2.7k blocks |  |
| `minecraft:deep_ocean` | 16.373% | n/a | ~900 blocks | ~2.2k blocks |  |
| `minecraft:plains` | 22.195% | clearingNoise | ~260 blocks | ~552 blocks |  |

### 3e. Reachable climate ranges ("does it make sense where it's placed")

p5 / p50 / p95 of each variable among pixels where this biome actually won, so you can eyeball whether e.g. a biome literally named 'desert' is reachable under desert-like conditions. Biomes with 0 samples never won a single pixel.

| biome | n | tempC (p5/p50/p95) | precipMm (p5/p50/p95) | moisture (p5/p50/p95) | elevM (p5/p50/p95) | snowy % |
|---|---|---|---|---|---|---|
| `minecraft:plains` | 443,894 | 7.8/27.2/31.1 | 25/538/2310 | 0.02/0.26/1.28 | -5612/-4028/459 | 0.0% |
| `minecraft:sunflower_plains` | 2,129 | 6.4/16.2/22.2 | 34/694/1605 | 0.02/0.64/1.21 | 45/394/1535 | 0.0% |
| `minecraft:snowy_plains` | 5,158 | -10.1/-7.4/-1.8 | 164/367/1880 | 0.41/1.09/6.33 | 155/1149/2331 | 100.0% |
| `minecraft:ice_spikes` | 505 | -10.1/-9.0/-2.5 | 168/499/2140 | 0.54/1.54/8.02 | 161/1158/2374 | 100.0% |
| `minecraft:desert` | 11,030 | 20.5/23.8/28.1 | 6/83/168 | 0.00/0.04/0.10 | 219/428/997 | 0.0% |
| `minecraft:swamp` | 12,371 | -1.9/16.6/26.0 | 934/1671/3390 | 0.84/1.53/4.96 | 15/109/191 | 4.8% |
| `minecraft:mangrove_swamp` | 2,262 | 20.4/23.9/25.9 | 1954/2623/5585 | 1.33/1.68/3.92 | 16/115/191 | 0.0% |
| `minecraft:forest` | 85,241 | -3.1/14.7/21.9 | 330/1205/3157 | 0.25/1.38/5.06 | 56/509/1779 | 7.3% |
| `minecraft:flower_forest` | 2,870 | 6.5/16.8/22.5 | 535/1005/1717 | 0.46/0.85/1.27 | 46/448/1598 | 0.0% |
| `minecraft:birch_forest` | 15,035 | 12.5/16.4/19.7 | 289/940/2629 | 0.23/0.75/2.38 | 51/529/1790 | 0.0% |
| `minecraft:dark_forest` | 21,802 | -6.6/8.0/24.5 | 857/1736/4294 | 1.28/2.14/6.59 | 52/497/1814 | 19.4% |
| `minecraft:old_growth_birch_forest` | 2,664 | 12.4/15.7/19.6 | 1436/2151/4851 | 1.35/1.84/4.78 | 58/605/1818 | 0.0% |
| `minecraft:old_growth_pine_taiga` | 11,588 | -1.3/4.1/11.0 | 598/1195/2906 | 1.13/1.73/5.14 | 42/391/1687 | 0.0% |
| `minecraft:old_growth_spruce_taiga` | 9,741 | -1.3/4.1/10.8 | 766/1484/3261 | 1.33/2.23/6.37 | 53/498/3720 | 2.4% |
| `minecraft:taiga` | 48,905 | -1.7/4.2/11.3 | 150/443/1139 | 0.23/0.66/1.67 | 40/402/2352 | 0.4% |
| `minecraft:snowy_taiga` | 30,041 | -10.1/-5.4/-1.2 | 304/736/2589 | 0.72/2.06/8.35 | 134/1174/5292 | 100.0% |
| `minecraft:savanna` | 76,815 | 20.7/24.5/29.2 | 21/291/1089 | 0.01/0.15/0.61 | 29/273/882 | 0.0% |
| `minecraft:savanna_plateau` | 2,926 | 20.1/21.4/24.5 | 331/520/862 | 0.21/0.31/0.49 | 914/1054/1450 | 0.0% |
| `minecraft:windswept_hills` | 1,604 | -4.4/3.4/12.2 | 6/62/175 | 0.01/0.10/0.23 | 2537/3159/4818 | 0.0% |
| `minecraft:windswept_gravelly_hills` | 442 | -4.3/4.7/12.4 | 4/86/260 | 0.01/0.12/0.40 | 2532/3113/4855 | 3.8% |
| `minecraft:windswept_forest` | 2,567 | -10.1/-4.5/11.0 | 167/561/2407 | 0.29/1.29/6.91 | 2554/3743/5622 | 59.1% |
| `minecraft:windswept_savanna` | 1,497 | 20.7/24.7/29.5 | 28/446/1213 | 0.02/0.23/0.66 | 27/284/965 | 0.0% |
| `minecraft:jungle` | 11,175 | 21.2/26.9/30.5 | 1466/1931/3935 | 0.82/1.04/2.59 | 18/194/786 | 0.0% |
| `minecraft:sparse_jungle` | 56,601 | 20.6/24.5/29.0 | 406/1015/1597 | 0.23/0.57/0.80 | 32/294/909 | 0.0% |
| `minecraft:bamboo_jungle` | 2,252 | 19.3/21.8/27.9 | 1853/2577/5667 | 1.33/1.72/4.11 | 54/475/1224 | 0.0% |
| `minecraft:badlands` | 21,703 | 21.3/26.5/30.6 | 20/320/782 | 0.01/0.16/0.38 | 22/205/804 | 0.0% |
| `minecraft:eroded_badlands` | 2,252 | 20.7/24.9/29.8 | 19/299/632 | 0.01/0.16/0.33 | 26/270/964 | 0.0% |
| `minecraft:wooded_badlands` | 12,174 | 20.8/25.1/29.8 | 10/170/393 | 0.01/0.09/0.19 | 27/259/902 | 0.0% |
| `minecraft:meadow` | 34,932 | -5.9/13.8/21.6 | 9/153/1631 | 0.01/0.15/2.02 | 924/1327/3477 | 2.5% |
| `minecraft:cherry_grove` | 2,538 | 8.3/15.3/18.2 | 607/1054/1599 | 0.61/0.91/1.30 | 621/1416/2844 | 0.0% |
| `minecraft:grove` | 7,420 | -10.1/-9.9/-2.0 | 165/414/1364 | 0.46/1.21/4.44 | 2610/4540/5711 | 96.6% |
| `minecraft:snowy_slopes` | 3,131 | -10.1/-10.0/-5.9 | 10/194/1641 | 0.03/0.60/5.66 | 2562/3817/5641 | 55.3% |
| `minecraft:frozen_peaks` | 1,125 | -10.1/-9.9/-4.2 | 13/202/1606 | 0.04/0.59/5.34 | 1757/5313/5785 | 60.1% |
| `minecraft:jagged_peaks` | 1,413 | -10.1/-10.0/-5.9 | 10/183/1655 | 0.03/0.56/5.69 | 4112/4981/5763 | 54.1% |
| `minecraft:stony_peaks` | 16,139 | -2.9/18.3/28.1 | 17/328/1299 | 0.02/0.25/1.31 | 38/380/1681 | 0.0% |
| `minecraft:mushroom_fields` | 64 | 10.8/16.2/23.5 | 942/1465/2622 | 0.88/1.15/2.23 | 23/152/366 | 0.0% |
| `minecraft:pale_garden` | 6,588 | 5.7/11.6/19.0 | 728/1317/2223 | 0.87/1.29/2.19 | 36/306/652 | 0.0% |
| `minecraft:river` | 0 | _never won any pixel_ | | | | |
| `minecraft:frozen_river` | 0 | _never won any pixel_ | | | | |
| `minecraft:beach` | 1,203 | 4.2/23.2/30.1 | 38/571/2371 | 0.03/0.37/2.03 | 1/10/17 | 0.0% |
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
| `biomesoplenty:bayou` | 9,215 | 20.4/23.4/25.7 | 1257/1664/2972 | 0.82/1.04/2.01 | 61/391/1056 | 0.0% |
| `biomesoplenty:bog` | 16,741 | -1.5/4.0/11.2 | 511/1055/2777 | 0.86/1.52/4.99 | 42/391/1662 | 0.0% |
| `biomesoplenty:coniferous_forest` | 9,324 | -0.8/4.4/9.6 | 326/523/906 | 0.53/0.74/1.14 | 41/367/1647 | 0.0% |
| `biomesoplenty:dead_forest` | 11,039 | 4.3/12.9/21.1 | 195/347/481 | 0.21/0.30/0.46 | 48/456/1786 | 0.0% |
| `biomesoplenty:dryland` | 8,091 | 17.1/24.1/28.5 | 188/281/404 | 0.12/0.15/0.19 | 33/315/1215 | 0.0% |
| `biomesoplenty:grassland` | 7,220 | 9.8/19.6/23.7 | 349/564/871 | 0.31/0.40/0.51 | 54/481/1608 | 0.0% |
| `biomesoplenty:highland` | 1,082 | 0.8/7.1/13.1 | 9/158/681 | 0.01/0.19/0.93 | 2522/2839/4128 | 0.2% |
| `biomesoplenty:lavender_field` | 2,047 | 14.8/19.8/23.6 | 447/615/892 | 0.36/0.42/0.53 | 51/507/1563 | 0.0% |
| `biomesoplenty:lush_desert` | 3,295 | 19.2/24.9/29.0 | 259/457/911 | 0.16/0.24/0.47 | 33/271/998 | 0.0% |
| `biomesoplenty:maple_woods` | 10,065 | 2.6/8.5/14.4 | 346/542/847 | 0.47/0.62/0.80 | 40/388/1865 | 0.0% |
| `biomesoplenty:marsh` | 1,032 | 8.7/18.0/23.6 | 962/1669/4055 | 0.84/1.34/3.88 | 47/504/1668 | 0.0% |
| `biomesoplenty:moor` | 6,059 | -0.8/5.6/13.0 | 186/327/587 | 0.31/0.42/0.76 | 36/390/1714 | 0.4% |
| `biomesoplenty:mystic_grove` | 2,519 | 13.0/19.3/23.6 | 856/1284/1827 | 0.72/0.94/1.26 | 56/497/1569 | 0.0% |
| `biomesoplenty:ominous_woods` | 466 | 3.4/8.7/15.4 | 608/961/1580 | 0.87/1.11/1.44 | 44/363/1706 | 0.0% |
| `biomesoplenty:orchard` | 7,065 | 14.0/19.4/23.6 | 419/589/840 | 0.33/0.41/0.49 | 55/508/1597 | 0.0% |
| `biomesoplenty:prairie` | 3,372 | 12.9/19.4/25.6 | 178/269/393 | 0.15/0.18/0.20 | 49/421/1618 | 0.0% |
| `biomesoplenty:rainforest` | 1,175 | 20.5/24.2/29.0 | 1994/2633/5781 | 1.32/1.65/3.95 | 45/349/1045 | 0.0% |
| `biomesoplenty:redwood_forest` | 2,522 | 8.7/15.6/20.5 | 713/1057/1450 | 0.81/0.89/0.99 | 176/564/1426 | 0.0% |
| `biomesoplenty:wasteland` | 2,638 | 24.6/27.1/30.8 | 4/34/101 | 0.00/0.02/0.05 | 15/157/558 | 0.0% |
| `biomesoplenty:woodland` | 3,939 | 8.9/15.5/19.6 | 315/486/699 | 0.31/0.39/0.49 | 43/508/1782 | 0.0% |
| `biomesoplenty:tundra` | 1,346 | -10.1/-6.7/-1.7 | 163/336/1947 | 0.41/0.98/6.24 | 122/1034/2276 | 100.0% |
| `biomesoplenty:cold_desert` | 1,542 | -10.0/-4.9/-2.3 | 3/30/80 | 0.01/0.08/0.19 | 73/758/2094 | 0.0% |
| `biomesoplenty:snowy_coniferous_forest` | 6,508 | -10.1/-6.7/-1.4 | 220/540/2362 | 0.53/1.54/8.04 | 161/1782/5524 | 100.0% |
| `biomesoplenty:snowy_maple_woods` | 3,726 | -10.0/-5.0/-1.3 | 177/372/1180 | 0.41/0.93/3.55 | 105/915/2212 | 100.0% |
| `biomesoplenty:crag` | 989 | -2.6/8.0/15.4 | 174/444/1763 | 0.27/0.47/2.72 | 42/413/1807 | 0.0% |
| `biomesoplenty:jade_cliffs` | 1,490 | 13.3/22.1/28.1 | 415/675/1716 | 0.31/0.42/1.17 | 29/334/1443 | 0.0% |
| `biomesoplenty:wasteland_steppe` | 3,003 | 8.4/22.9/29.0 | 13/214/540 | 0.01/0.14/0.28 | 30/319/1378 | 0.0% |
| `biomesoplenty:tropics` | 1,888 | 26.1/27.3/30.7 | 1768/2246/3107 | 1.02/1.22/1.81 | 15/153/554 | 0.0% |
| `biomesoplenty:jacaranda_glade` | 347 | 18.6/23.4/25.6 | 663/962/1328 | 0.42/0.58/0.76 | 60/384/1223 | 0.0% |
| `biomesoplenty:mediterranean_forest` | 14,796 | 20.3/25.4/29.1 | 361/539/801 | 0.21/0.29/0.40 | 26/252/922 | 0.0% |
| `biomesoplenty:lush_savanna` | 6,193 | 22.4/25.2/28.7 | 579/767/1050 | 0.36/0.42/0.50 | 31/260/773 | 0.0% |
| `biomesoplenty:seasonal_forest` | 8,966 | 2.7/8.2/14.4 | 348/536/836 | 0.48/0.62/0.79 | 35/386/1901 | 0.0% |
| `biomesoplenty:aspen_glade` | 1,544 | 2.9/9.6/14.6 | 308/500/921 | 0.45/0.49/0.99 | 40/414/2064 | 0.0% |
| `biomesoplenty:old_growth_woodland` | 2,019 | 8.7/14.3/19.5 | 447/628/897 | 0.50/0.55/0.60 | 47/494/1828 | 0.0% |
| `biomesoplenty:muskeg` | 395 | -3.0/1.6/7.7 | 258/431/680 | 0.61/0.74/0.88 | 37/390/1406 | 0.0% |
| `biomesoplenty:fir_clearing` | 5,658 | -3.2/3.4/7.6 | 93/196/367 | 0.16/0.30/0.46 | 41/395/1644 | 0.0% |
| `biomesoplenty:snowy_fir_clearing` | 1,209 | -10.0/-5.1/-1.2 | 164/291/1409 | 0.34/0.78/3.93 | 151/974/2186 | 100.0% |
| `biomesoplenty:snowblossom_grove` | 9,266 | -10.0/-4.6/-1.1 | 267/810/2601 | 0.65/2.15/8.13 | 109/887/2163 | 100.0% |
| `biomesoplenty:origin_valley` | 169 | 8.9/15.1/19.5 | 398/549/826 | 0.41/0.45/0.68 | 42/292/806 | 0.0% |
| `biomesoplenty:wintry_origin_valley` | 789 | -10.0/-5.2/-1.8 | 164/301/2008 | 0.38/0.81/6.22 | 72/466/861 | 100.0% |
| `biomesoplenty:auroral_garden` | 521 | -10.2/-10.0/-5.8 | 10/179/1599 | 0.03/0.56/5.36 | 2552/3654/4714 | 54.1% |
| `biomesoplenty:volcano` | 9,466 | 26.1/27.4/31.5 | 438/814/1491 | 0.22/0.40/0.75 | 17/156/549 | 0.0% |
| `minecraft:basalt_deltas` | 36 | 1.2/7.9/12.2 | 9/53/283 | 0.01/0.06/0.29 | 53/417/1312 | 0.0% |
| `biomesoplenty:wetland` | 2,077 | -1.5/3.3/11.1 | 505/1226/2866 | 0.95/1.86/5.62 | 34/395/1662 | 3.0% |
| `biomesoplenty:fungal_jungle` | 2,049 | 22.2/24.3/27.0 | 2075/2695/5843 | 1.33/1.70/3.97 | 53/349/916 | 0.0% |
| `biomesoplenty:pasture` | 2,876 | 8.5/12.7/17.5 | 434/628/984 | 0.42/0.61/0.77 | 45/436/1838 | 0.0% |
| `biomesoplenty:field` | 4,266 | 9.0/16.0/19.6 | 345/516/762 | 0.36/0.41/0.50 | 43/530/1787 | 0.0% |
| `biomesoplenty:forested_field` | 3,663 | 8.8/15.1/17.7 | 409/634/992 | 0.41/0.56/0.77 | 51/440/1860 | 0.0% |
| `biomesoplenty:overgrown_greens` | 757 | 10.3/13.7/17.6 | 561/768/1079 | 0.61/0.69/0.80 | 46/452/1908 | 0.0% |
| `biomesoplenty:shrubland` | 8,842 | 16.6/24.5/27.5 | 333/558/940 | 0.21/0.32/0.48 | 32/301/1213 | 0.0% |
| `biomesoplenty:scrubland` | 10,406 | 19.1/24.7/28.7 | 282/498/812 | 0.16/0.27/0.43 | 31/281/1062 | 0.0% |
| `biomesoplenty:rocky_shrubland` | 2,978 | 16.1/23.6/27.3 | 258/498/866 | 0.16/0.29/0.47 | 36/328/1295 | 0.0% |
| `biomesoplenty:floodplain` | 725 | 13.4/22.5/28.0 | 1058/1720/4519 | 0.83/1.15/3.34 | 19/197/419 | 0.0% |
| `biomesoplenty:pumpkin_patch` | 948 | 5.4/10.1/14.4 | 411/581/866 | 0.51/0.63/0.79 | 39/406/2034 | 0.0% |
| `biomesoplenty:old_growth_dead_forest` | 4,825 | 4.5/15.6/21.5 | 200/444/786 | 0.22/0.37/0.54 | 47/490/1722 | 0.0% |
| `biomesoplenty:rocky_rainforest` | 327 | 18.3/22.5/28.3 | 1865/2555/5114 | 1.34/1.71/3.56 | 45/401/1303 | 0.0% |
| `biomesoplenty:volcanic_plains` | 6,359 | 22.5/25.4/30.2 | 382/591/1011 | 0.21/0.31/0.49 | 27/243/744 | 0.0% |
| `biomesoplenty:hot_springs` | 1,542 | -0.2/4.4/9.5 | 327/656/2506 | 0.49/0.98/4.20 | 2553/3246/4339 | 0.0% |
| `biomesoplenty:dune_beach` | 342 | 19.4/26.4/30.3 | 47/622/2343 | 0.02/0.32/1.41 | 1/10/17 | 0.0% |
| `biomesoplenty:gravel_beach` | 177 | 2.2/6.3/11.2 | 53/598/2456 | 0.08/0.72/3.60 | 1/9/17 | 0.0% |

### 3f. Rule bottleneck diagnostics

Per-rule joint pass rate vs. each individual condition's own pass rate (tightest first) -- the fastest way to spot which single condition is strangling a rule in a compounding-narrow-AND case. Showing the 20 rules with the lowest nonzero joint pass rate.

- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.000%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 1100.01 <= precipitationMm <= 2600 (22.636%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%)
- `minecraft:grove` (zone=mountain, rarity=2.322): joint pass rate 0.000%, tightest condition: `temperatureC < -5` (3.314%)
  - all conditions: temperatureC < -5 (3.314%), 0.55 <= treeCoverage <= 0.7 (14.507%), sparsity <= 0.75 (72.800%), snowy == False (94.533%)
- `biomesoplenty:snowy_coniferous_forest` (zone=mountain, rarity=9.12): joint pass rate 0.000%, tightest condition: `temperatureC < -5` (3.314%)
  - all conditions: temperatureC < -5 (3.314%), treeCoverage >= 0.8 (27.860%), regionNoise < -0.0967 (33.315%), sparsity <= 0.75 (72.800%), snowy == False (94.533%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.000%, tightest condition: `cherryNoise > 0.29` (7.169%)
  - all conditions: cherryNoise > 0.29 (7.169%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), elevationM >= 560 (15.934%), 1100.01 <= precipitationMm <= 2600 (22.636%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%), elevationM <= 900 (89.690%)
- `minecraft:snowy_taiga` (zone=mountain, rarity=2.142): joint pass rate 0.000%, tightest condition: `temperatureC < -5` (3.314%)
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
- `minecraft:flower_forest` (zone=lowland, rarity=2.252): joint pass rate 0.002%, tightest condition: `1.1201 <= moisture <= 1.35` (4.688%)
  - all conditions: 1.1201 <= moisture <= 1.35 (4.688%), flowerNoise > 0.3256 (4.963%), elevationM > 850 (10.940%), treeCoverage >= 0.95 (15.138%), 5 <= temperatureC <= 23 (41.177%), precipitationMm >= 380 (68.140%), slope < 0.38 (76.541%), snowy == False (94.533%), bareSlope == False (96.689%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.003%, tightest condition: `elevationM > 1000` (9.081%)
  - all conditions: elevationM > 1000 (9.081%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), 1100.01 <= precipitationMm <= 2600 (22.636%), cherryNoise > 0.12 (27.041%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.003%, tightest condition: `900 <= elevationM <= 1000` (1.229%)
  - all conditions: 900 <= elevationM <= 1000 (1.229%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.18 (18.226%), 360 <= precipitationMm <= 1100 (43.332%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.004%, tightest condition: `cherryNoise > 0.32` (5.209%)
  - all conditions: cherryNoise > 0.32 (5.209%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), elevationM >= 560 (15.934%), 360 <= precipitationMm <= 1100 (43.332%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope <= 0.52 (87.015%), elevationM <= 900 (89.690%)
- `minecraft:plains` (zone=lowland, rarity=1.0): joint pass rate 0.005%, tightest condition: `clearingNoise > 0.3968` (1.996%)
  - all conditions: clearingNoise > 0.3968 (1.996%), treeCoverage >= 0.95 (15.138%), 0.52 <= moisture <= 1.35 (28.832%), 5 <= temperatureC <= 23 (41.177%), precipitationMm >= 380 (68.140%), slope < 0.38 (76.541%), elevationM <= 850 (89.060%), snowy == False (94.533%), bareSlope == False (96.689%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.006%, tightest condition: `cherryNoise > 0.32` (5.209%)
  - all conditions: cherryNoise > 0.32 (5.209%), 12 <= temperatureC <= 18.5 (14.089%), 200 <= elevationM <= 560 (14.161%), 0.58 <= moisture <= 0.9 (14.246%), 360 <= precipitationMm <= 1100 (43.332%), 0.3 <= treeCoverage <= 0.7 (44.940%), slope >= 0.12 (67.030%), slope <= 0.52 (87.015%)
- `minecraft:cherry_grove` (zone=lowland, rarity=10.0): joint pass rate 0.006%, tightest condition: `elevationM > 1000` (9.081%)
  - all conditions: elevationM > 1000 (9.081%), 12 <= temperatureC <= 18.5 (14.089%), 0.58 <= moisture <= 0.9 (14.246%), 1100.01 <= precipitationMm <= 2600 (22.636%), cherryNoise > 0.12 (27.041%), treeCoverage >= 0.8 (27.860%), slope <= 0.52 (87.015%)

**30 rule(s) had a joint pass rate of exactly 0 in this sample** (either genuinely dead per the static validators above, or just extremely rare -- increase `--min-samples` to tell the two apart):

- `minecraft:plains` (zone=lowland, rarity=1.0), tightest: `12 <= temperatureC <= 19.99`
- `minecraft:flower_forest` (zone=lowland, rarity=2.252), tightest: `flowerNoise > 0.3256`
- `minecraft:flower_forest` (zone=lowland, rarity=2.252), tightest: `flowerNoise > 0.3256`
- `minecraft:flower_forest` (zone=lowland, rarity=2.252), tightest: `1.1201 <= moisture <= 1.35`
- `minecraft:flower_forest` (zone=lowland, rarity=2.252), tightest: `variantNoise > 0.22`
- `minecraft:flower_forest` (zone=lowland, rarity=2.252), tightest: `flowerNoise > 0.3256`
- `minecraft:taiga` (zone=lowland, rarity=1.563), tightest: `treeCoverage >= 0.95`
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
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `paleNoise > 0.34`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `paleNoise > 0.3`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `paleNoise > 0.3`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `paleNoise > 0.26`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `paleNoise > 0.32`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `paleNoise > 0.24`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `0.82 <= moisture <= 1.1`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `0.82 <= moisture <= 1.1`
- `minecraft:pale_garden` (zone=lowland, rarity=1.755), tightest: `0.82 <= moisture <= 1.1`
- `biomesoplenty:prairie` (zone=lowland, rarity=2.763), tightest: `0.2 <= moisture <= 0.4`
- `biomesoplenty:auroral_garden` (zone=lowland, rarity=10.0), tightest: `paleNoise > 0.75`
- `minecraft:basalt_deltas` (zone=bareSlope, rarity=1.0), tightest: `treeCoverage == 0.3499999940395355`

### 3g. Low joint pass rate (individually valid, compounds to near-invisible)

Rules where every condition is individually satisfiable and none trip the structural discreteness/noise-ceiling checks in section 1, but the AND of all of them together is so restrictive the biome is effectively invisible in normal play. Different failure mode from section 1's dead conditions: nothing here is broken, several moderately-narrow conditions are just compounding multiplicatively. Run with `--fix` to widen the tightest 1-2 conditions per rule toward `--fix-target-rate` (default 2%), re-simulated against real Monte Carlo samples rather than an independence assumption -- see section 2 for what was widened, if `--fix` was passed.

**117 rule(s)** below 0.500%:

- `biomesoplenty:aspen_glade` (zone=lowland, rarity=5.79): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.4975% of sampled climate -- below the 0.50% bar. Tightest single condition: '2 <= temperatureC <= 15' (26.374% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 2 <= temperatureC <= 15 (26.374%), 0.45 <= moisture <= 1.1 (28.356%), 0.34 <= treeCoverage <= 0.36 (30.433%), -0.0967 <= regionNoise <= 0.0967 (33.371%), snowy == False (94.533%).
- `biomesoplenty:auroral_garden` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0741% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), paleNoise > 0.15 (22.415%), treeCoverage <= 0.01 (27.200%), elevationM <= 4800 (99.461%).
- `biomesoplenty:auroral_garden` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0861% of sampled climate -- below the 0.50% bar. Tightest single condition: 'temperatureC < -5' (3.314% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: temperatureC < -5 (3.314%), paleNoise > 0.15 (22.415%), treeCoverage <= 0.01 (27.200%), snowy == False (94.533%), elevationM <= 4800 (99.461%).
- `biomesoplenty:cold_desert` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1675% of sampled climate -- below the 0.50% bar. Tightest single condition: '-25 <= temperatureC <= -2' (6.128% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: -25 <= temperatureC <= -2 (6.128%), precipitationMm <= 150 (14.065%), moisture <= 0.2 (24.565%), treeCoverage <= 0.02 (27.200%), regionNoise > 0.0967 (33.314%), snowy == False (94.533%).
- `biomesoplenty:floodplain` (zone=lowland, rarity=2.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3095% of sampled climate -- below the 0.50% bar. Tightest single condition: 'moisture >= 0.8' (32.083% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: moisture >= 0.8 (32.083%), regionNoise > 0.0967 (33.314%), 12 <= temperatureC <= 30 (63.499%), treeCoverage <= 0.7 (72.140%), elevationM <= 450 (80.910%), snowy == False (94.533%).
- `biomesoplenty:jacaranda_glade` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0553% of sampled climate -- below the 0.50% bar. Tightest single condition: 'flowerNoise > 0.45' (0.852% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: flowerNoise > 0.45 (0.852%), 0.4 <= moisture <= 0.8 (22.656%), 18 <= temperatureC <= 26 (28.301%), 0.3 <= treeCoverage <= 0.7 (44.940%), snowy == False (94.533%).
- `biomesoplenty:lavender_field` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.4021% of sampled climate -- below the 0.50% bar. Tightest single condition: 'flowerNoise > 0.2564' (9.964% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: flowerNoise > 0.2564 (9.964%), 0.35 <= moisture <= 0.7 (22.637%), 14 <= temperatureC <= 24 (26.716%), treeCoverage <= 0.35 (57.633%), snowy == False (94.533%).
- `biomesoplenty:marsh` (zone=lowland, rarity=2.5): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3216% of sampled climate -- below the 0.50% bar. Tightest single condition: 'moisture >= 0.8' (32.083% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: moisture >= 0.8 (32.083%), regionNoise < -0.0967 (33.315%), 8 <= temperatureC <= 24 (38.720%), treeCoverage <= 0.35 (57.633%), snowy == False (94.533%).
- `biomesoplenty:muskeg` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0961% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.6 <= moisture <= 0.9' (13.139% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.6 <= moisture <= 0.9 (13.139%), -5 <= temperatureC <= 8 (21.644%), -0.0967 <= regionNoise <= 0.0967 (33.371%), treeCoverage <= 0.35 (57.633%), snowy == False (94.533%).
- `biomesoplenty:mystic_grove` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0365% of sampled climate -- below the 0.50% bar. Tightest single condition: 'clearingNoise > 0.3968' (1.996% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: clearingNoise > 0.3968 (1.996%), 0.8 <= moisture <= 1.3 (13.773%), 14 <= temperatureC <= 22 (19.619%), 0.5 <= treeCoverage <= 0.9 (27.229%), slope < 0.3 (67.306%).
- `biomesoplenty:mystic_grove` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.2812% of sampled climate -- below the 0.50% bar. Tightest single condition: 'regionNoise > 0.35' (6.617% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: regionNoise > 0.35 (6.617%), 0.7 <= moisture <= 1.4 (19.875%), 0.45 <= treeCoverage <= 0.95 (27.229%), 12 <= temperatureC <= 24 (30.521%), slope < 0.35 (73.414%).
- `biomesoplenty:ominous_woods` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0080% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise < -0.5' (0.310% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise < -0.5 (0.310%), 0.9 <= moisture <= 1.4 (11.822%), 4 <= temperatureC <= 14 (20.375%), treeCoverage >= 0.7 (27.860%), snowy == False (94.533%).
- `biomesoplenty:ominous_woods` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0856% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise < -0.4' (1.847% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise < -0.4 (1.847%), 0.85 <= moisture <= 1.5 (15.121%), 3 <= temperatureC <= 16 (26.677%), treeCoverage >= 0.65 (27.860%), snowy == False (94.533%).
- `biomesoplenty:origin_valley` (zone=lowland, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0441% of sampled climate -- below the 0.50% bar. Tightest single condition: 'clearingNoise > 0.3968' (1.996% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: clearingNoise > 0.3968 (1.996%), 0.4 <= moisture <= 0.8 (22.656%), 8 <= temperatureC <= 20 (26.026%), 0.2 <= treeCoverage <= 0.5 (30.433%), elevationM <= 900 (89.690%), snowy == False (94.533%).
- `biomesoplenty:prairie` (zone=lowland, rarity=2.763): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1540% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.2 <= moisture <= 0.45' (24.496% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.2 <= moisture <= 0.45 (24.496%), treeCoverage <= 0.1 (27.200%), 15 <= temperatureC <= 25 (29.246%), -0.0967 <= regionNoise <= 0.0967 (33.371%), snowy == False (94.533%).
- `biomesoplenty:pumpkin_patch` (zone=lowland, rarity=6.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.2500% of sampled climate -- below the 0.50% bar. Tightest single condition: 'clearingNoise > 0.3' (6.570% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: clearingNoise > 0.3 (6.570%), 0.5 <= moisture <= 0.9 (19.126%), 5 <= temperatureC <= 15 (20.299%), 0.3 <= treeCoverage <= 0.7 (44.940%), snowy == False (94.533%).
- `biomesoplenty:redwood_forest` (zone=lowland, rarity=4.643): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.1086% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.55 <= moisture <= 0.95' (17.640% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.55 <= moisture <= 0.95 (17.640%), 10 <= temperatureC <= 20 (21.795%), 200 <= elevationM <= 1400 (24.926%), treeCoverage >= 0.8 (27.860%), -0.0967 <= regionNoise <= 0.0967 (33.371%).
- `biomesoplenty:redwood_forest` (zone=lowland, rarity=4.643): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.2158% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.5 <= moisture <= 1.0' (22.263% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.5 <= moisture <= 1.0 (22.263%), treeCoverage >= 0.75 (27.860%), 8 <= temperatureC <= 21 (28.722%), 150 <= elevationM <= 1600 (28.843%), -0.0967 <= regionNoise <= 0.0967 (33.371%).
- `biomesoplenty:rocky_rainforest` (zone=lowland, rarity=1.5): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3988% of sampled climate -- below the 0.50% bar. Tightest single condition: 'moisture >= 1.3' (18.311% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: moisture >= 1.3 (18.311%), treeCoverage >= 0.8 (27.860%), -0.0967 <= regionNoise <= 0.0967 (33.371%), slope >= 0.25 (40.136%), temperatureC >= 18 (53.959%), snowy == False (94.533%).
- `biomesoplenty:snowy_coniferous_forest` (zone=mountain, rarity=9.12): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0001% of sampled climate -- below the 0.50% bar. Tightest single condition: 'temperatureC < -5' (3.314% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: temperatureC < -5 (3.314%), treeCoverage >= 0.8 (27.860%), regionNoise < -0.0967 (33.315%), sparsity <= 0.75 (72.800%), snowy == False (94.533%).
- `biomesoplenty:snowy_fir_clearing` (zone=lowland, rarity=9.782): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3629% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), 0.34 <= treeCoverage <= 0.36 (30.433%), regionNoise < -0.0967 (33.315%).
- `biomesoplenty:tundra` (zone=lowland, rarity=6.857): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.4927% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), regionNoise < -0.0967 (33.315%), treeCoverage <= 0.35 (57.633%).
- `biomesoplenty:wasteland` (zone=lowland, rarity=4.66): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.2752% of sampled climate -- below the 0.50% bar. Tightest single condition: 'regionNoise < -0.35' (6.609% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: regionNoise < -0.35 (6.609%), precipitationMm <= 120 (11.507%), moisture <= 0.12 (15.568%), treeCoverage <= 0.04 (27.200%), temperatureC >= 24 (36.322%).
- `biomesoplenty:wetland` (zone=lowland, rarity=4.21): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.4159% of sampled climate -- below the 0.50% bar. Tightest single condition: '-2 <= temperatureC <= 12' (27.029% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: -2 <= temperatureC <= 12 (27.029%), moisture >= 0.8 (32.083%), -0.0967 <= regionNoise <= 0.0967 (33.371%), treeCoverage <= 0.35 (57.633%).
- `biomesoplenty:wintry_origin_valley` (zone=lowland, rarity=2.5): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.2026% of sampled climate -- below the 0.50% bar. Tightest single condition: 'snowy == True' (5.467% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: snowy == True (5.467%), -0.0967 <= regionNoise <= 0.0967 (33.371%), treeCoverage <= 0.35 (57.633%), elevationM <= 900 (89.690%).
- `minecraft:basalt_deltas` (zone=bareSlope, rarity=1.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3764% of sampled climate -- below the 0.50% bar. Tightest single condition: 'variantNoise > 0.27000001072883606' (8.689% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: variantNoise > 0.27000001072883606 (8.689%), treeCoverage == 0.0 (27.200%), 0.0 <= temperatureC <= 14.0 (27.877%), 0.0 <= moisture <= 0.30000001192092896 (35.943%), 0.0 <= precipitationMm <= 500.0 (42.416%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0365% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.58 <= moisture <= 0.9' (14.246% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 1100.01 <= precipitationMm <= 2600 (22.636%), 6 <= temperatureC <= 18.5 (26.493%), slope <= 0.52 (87.015%), elevationM <= 1000 (90.919%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0979% of sampled climate -- below the 0.50% bar. Tightest single condition: 'elevationM > 1000' (9.081% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: elevationM > 1000 (9.081%), 0.58 <= moisture <= 0.9 (14.246%), cherryNoise > 0.15 (22.381%), 6 <= temperatureC <= 18.5 (26.493%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.0087% of sampled climate -- below the 0.50% bar. Tightest single condition: 'elevationM > 1000' (9.081% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: elevationM > 1000 (9.081%), 0.58 <= moisture <= 0.9 (14.246%), 1100.01 <= precipitationMm <= 2600 (22.636%), 6 <= temperatureC <= 18.5 (26.493%), cherryNoise > 0.12 (27.041%), slope <= 0.52 (87.015%).
- `minecraft:cherry_grove` (zone=mountain, rarity=10.0): This rule's conditions are all individually satisfiable, but their conjunction only matches 0.3056% of sampled climate -- below the 0.50% bar. Tightest single condition: '0.9001 <= moisture <= 1.35' (10.969% alone), but no single condition is broken; it's the AND of several moderately-narrow conditions compounding multiplicatively. Full condition list: 0.9001 <= moisture <= 1.35 (10.969%), cherryNoise > 0.14 (23.885%), 6 <= temperatureC <= 18.5 (26.493%), 360 <= precipitationMm <= 1100 (43.332%), slope <= 0.52 (87.015%), elevationM <= 1000 (90.919%).
