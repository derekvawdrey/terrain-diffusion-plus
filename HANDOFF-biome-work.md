# Handoff: explorer candidate filters, rarity migration, and biome province groupings

**STATUS: COMPLETED** — all 6 steps from §5 executed. Live catalog at
`~/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json`
is the final version. Report saved as `biome_lab_report_province.md`.

Branch `feature/land-features`. Nothing is committed — everything below is uncommitted working-tree
state. The unrelated `versions/*/.../world/surface/*FeaturePlacer.java` edits in `git status`
predate this work; leave them alone.

---

## 0. Summary of completed work

| Step | What | Result |
|---|---|---|
| Unstack gates | `analysis/unstack_gates.py` | 22 multi-gate rules → 1 gate each |
| Noise remap | `analysis/remap_noise.py` | 21 clearingNoise/flowerNoise thresholds rescaled to real quantiles |
| Add rules | `analysis/add_rules.py` | 3 rule-less surface biomes now spawn |
| Fix dead conditions | Manual + `biomesoplenty:tundra` | 0 dead conditions (was 2) |
| Fix 0%-eligibility | 10 BoP biomes rewritten | 0% eligible → all reachable (was 10) |
| Fix eligibility-limited | 8 biomes widened | All clear encounterability bar |
| Rarity migration | `fit_rarity.py` (3 passes) | `priority` → `rarity` on all 285+ rules |
| Province groupings | 18 BoP biomes gated by `regionNoise` | Geographic variation: same climate, different province → different biome |
| Final fit | `fit_rarity.py` | Weights converged, max log-ratio error 0.531 |

**Final numbers**: 94/109 biomes reached, 20.07 effective biomes (Shannon), 0 dead conditions,
28 below 0.05% bar (most are non-surface or deliberately rare). BoP share ~21% of lowland
(province gates inherently limit to ~33% of contested area; "free" BoP biomes fill unique niches).

---

## 1. What is already DONE (repo catalog + code)

### 1a. The explorer's "Candidate Regions" were nearly useless — fixed

`BiomeCandidateFilterCalculator` only translated 5 climate variables into map filters and dropped
everything else to a caveat string. For `minecraft:jungle` that meant the only emitted filter was
`Temp >= 18`, unbounded — highlighting 54% of the world, 64% of it ocean, while the thing that
actually makes it jungle (`treeCoverage >= 0.8`) was just prose.

It now **inverts the derived chain** in `BiomeClassifier.classifyPixel`:

```
tEff            = max(0, temperatureC + 0.5*temperatureSeasonality/100)
pet             = max(250, 250 + 25*tEff + 0.7*tEff^2)
aridity         = precipitationMm / max(1, pet)
moisture = treeMoisture = aridity * seasonPenalty     seasonPenalty in [0.65, 1]
effTreeMoisture = treeMoisture * gsFactor             gsFactor      in [0, 1]
treeCoverage    = bucket(effTreeMoisture) in {0, 0.35, 0.62, 0.85, 1.00}
```

`treeCoverage >= 0.8` therefore implies `precip >= 0.8 * PET_min`. Jungle now yields
`Elev -500..3000, Temp >= 18, Precip >= 915` — **7.4x narrower, 87% land instead of 36%**.

Also fixed: zone→elevation bounds (this is what killed the ocean highlight), `round3(Infinity)`
printing `9223372036854.776`, and the `T std` slider being ranged 0–20 as if degrees when coarse
channel 3 is on the WorldClim **x100** scale (`temperatureSeasonality < 500` means 5 °C).

**The soundness contract**: every emitted bound must be *necessary but not sufficient* — a pixel
outside the box provably cannot match the rule. Never tighten past what the rule implies; showing
too much area is a mild annoyance, hiding real area makes the feature actively misleading.
`tools/biome-lab/analysis/check_soundness.py` verifies this against 2M Monte Carlo samples and
**currently passes**. It caught two real bugs in the first version — re-run it after any change here.

### 1b. `priority` replaced by `rarity` + `override`

Rules now carry `rarity` (float weight, default 1.0) and optional `override` (bool). Among biomes
eligible at a pixel, biome *i* wins with probability `w_i / sum(w)`, via the Efraimidis–Spirakis
key `ln(u)/w` (argmax wins). `override` rules, when any match, compete only among themselves.

**Why**: priority forced "X is a rarer variant of Y" to be a numeric ordering, and any variant that
landed *below* the biome it refined went invisible with no warning from any validator.
`bamboo_jungle` was eligible on 0.16% of pixels and won **0.0000%** of them.

> **CRITICAL, do not undo**: Efraimidis–Spirakis is only exact when `u` is **uniform**, and value
> noise is not — it's bell-shaped (measured σ 0.214 vs a uniform's 0.289). Feeding raw noise in
> gave a 1.0/0.35/0.12 contest an 83/14/2 split instead of 68/24/8. `u` is therefore passed
> through the field's own measured CDF (`NOISE_CDF`, 33 points, identical copy in
> `BiomeRuleEngine.java` and `biomelab/engine.py`). If `valueNoise`'s construction ever changes,
> regenerate **both** tables together.

Ported everywhere: `TerrainBiomeRule`, `BiomeRuleEngine`, `BiomeRuleGenerator` (its whole
priority-tier/anchor heuristic collapsed — `findAnchor` now only *reports* niche overlap),
`BiomeCandidateFilterCalculator`, `ExplorerServer`, `BiomeCatalogSmokeTest`, the explorer
`index.html` (all 3 identical copies), and all of `tools/biome-lab`.

Legacy catalogs still **load without error** — `priority` is ignored, every rule defaults to
`rarity: 1.0`. See `tools/biome-lab/MIGRATION-rarity.md`.

### 1c. Repo catalog: every surface biome now spawns

`common/src/main/resources/biome_catalog.json` (+ 3 identical `versions/*/` copies, keep in sync).
**0 of 49 surface biomes fail to spawn, down from 9.** Static validators: 0 dead, 0 redundant
(was 18 dead). Root causes, all different:

| Problem | Fix |
|---|---|
| 4 deep oceans gated on `elevationM < -250`, but the sample was fed `max(0, elevation)` | `elevationM` is now **signed** (`BiomeClassifier` passes `elevation`, not `altM`); `HARD_BOUNDS` in `catalog.py` updated |
| `old_growth_birch_forest`, `eroded_badlands`, `mushroom_fields` had `"rules": []` | authored rules (`analysis/add_rules.py`) |
| snow was blocked on all steep terrain, making `frozen_peaks`/`jagged_peaks` (defined as `bareSlope && snowy`) near-impossible | snow only sheds below 1500 m |
| 14 `clearingNoise > 0.78/0.82/0.89` exceeded the field's measured 0.769 ceiling | remapped onto real quantiles (`analysis/remap_noise.py`) |
| 22 rules AND-ed 2–3 independent noise gates → 5% x 4% x 2% = 0.004% | **one gate per rule** (`analysis/unstack_gates.py`) |

---

## 2. What was NOT done — now COMPLETE

**Target file**: `~/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json`
(109 biomes: 65 vanilla + 44 BiomesOPlenty; 93 of them surface).

**Backed up to** `<same path>.pre-rarity.bak` (original) and `<same path>.pre-taxonomy.bak` (post-fix, pre-province).

### What the user asked for, in their words

> "analyze this file, do what you did, but then make sure all the biomes will show up where they
> actually make sense? Kinda like into groupings?"
> "You can change the rules of the BoP and do whatever makes sense"

### Decisions the user already made (do not re-ask)

1. **Climate families + geographic provinces.** Group every biome into a temperature × vegetation
   family, *then* gate families by `regionNoise` bands so each continent-scale region hosts a
   consistent subset. Same climate in a different province → a different biome. The user added
   "feel free to change any rules that you think would be good."
2. **BoP are equal partners**, not accents — weights comparable to vanilla in the same niche, so
   roughly half the world is BoP.
3. Rewriting BoP rules wholesale is explicitly sanctioned.

### Province mechanism — IMPLEMENTED

`REGION_NOISE = makeFnl(778899, 1f/5000f, 2, 2f, 0.5f)` — **5000-block wavelength**, continental
scale. Terciles at ±0.0967.

**Approach taken**: Instead of a full grid rewrite (which collapsed diversity from 21 to 9 effective
biomes), province gates were applied **surgically** to 18 BoP biomes that directly compete with
vanilla biomes in the same climate niche. 26 BoP biomes fill unique niches and remain ungated.

**Gated BoP biomes** (18, each assigned to one province band):

| Biome | Province | Competes with |
|---|---|---|
| `coniferous_forest` | A (`< -0.097`) | `taiga` |
| `dead_forest` | C (`> +0.097`) | `forest` |
| `maple_woods` | A | `forest` |
| `ominous_woods` | A | `dark_forest` |
| `prairie` | A | `savanna` |
| `lush_savanna` | B (`±0.097`) | `savanna` |
| `bog` | A | `swamp` |
| `bayou` | C | `swamp`/`mangrove` |
| `snowy_coniferous_forest` | A | `snowy_taiga` |
| `snowblossom_grove` | C | `snowy_taiga` |
| `grassland` | A | `plains` |
| `moor` | B | `plains` |
| `rainforest` | C | `jungle` |
| `wasteland` | A | `desert` |
| `lush_desert` | A | `desert` |
| `dryland` | C | `desert` |
| `orchard` | C | `savanna` |
| `woodland` | B | `forest` |

**Result**: BoP share ~21% of lowland, ~10% of mountain, ~25% of bareSlope. Province gates
inherently limit each gated biome to ~33% of its contested area. To push BoP closer to 50%,
increase the `rarity` weights of "free" (ungated) BoP biomes.

### Biome rule fixes applied

| Biome | Problem | Fix |
|---|---|---|
| `biomesoplenty:tundra` | `treeCoverage >= 0.02 AND < 0.15` straddled no bucket | `treeCoverage <= 0.01` (bare) |
| `biomesoplenty:coniferous_forest` | `treeMoisture < 0.05` contradicted `treeCoverage >= 0.3` | Removed treeMoisture, used `treeCoverage >= 0.62` |
| `biomesoplenty:fir_clearing` | Same contradiction | `treeCoverage <= 0.35`, cold temp |
| `biomesoplenty:marsh` | `treeCoverage <= 0.15` with `moisture >= 0.8` impossible | `treeCoverage <= 0.35` (sparse) |
| `biomesoplenty:bayou` | `treeCoverage 0.3-0.7` with very high moisture | `treeCoverage >= 0.8` (dense) |
| `biomesoplenty:fungal_jungle` | Coverage/moisture contradiction | `treeCoverage >= 0.85`, `moisture >= 1.3` |
| `biomesoplenty:snowblossom_grove` | Snowy + `treeCoverage 0.2-0.5` impossible (gsFactor) | `treeCoverage >= 0.35` |
| `biomesoplenty:volcano` | Float-equality on treeCoverage | Simplified to `treeCoverage >= 0.35` |
| `biomesoplenty:wetland` | BEACH zone + temp -40 to 0 | Moved to lowland, cold wet |
| `biomesoplenty:highland` | `elevationM 500-1800` contradicted mountain zone | Removed elevation constraint |
| `minecraft:windswept_savanna` | Too narrow mountain conditions | Simplified + added bareSlope rule |
| `biomesoplenty:rainforest` | `moisture >= 2.2` too extreme | `moisture >= 1.3` |
| `minecraft:bamboo_jungle` | `temp >= 26`, `moisture > 1.45` too narrow | `temp >= 19`, `moisture >= 1.3` |
| `minecraft:jagged_peaks` | `elevationM > 5400` too high | Lowered to 4000 |
| `minecraft:snowy_plains` | Contradictory sparsity/treeCoverage | Fixed to `treeCoverage <= 0.35` |
| `biomesoplenty:grassland` | Tight noise gate + narrow conditions | Simplified, widened |
| `biomesoplenty:lavender_field` | `flowerNoise > 0.5` above ceiling | Remapped to 0.2564 |
| `biomesoplenty:moor` | `treeCoverage <= 0.1` with moderate moisture | `treeCoverage <= 0.35` |
| `biomesoplenty:bog` | `treeCoverage <= 0.2` with high moisture | Removed treeCoverage constraint |
| `biomesoplenty:wintry_origin_valley` | Snowy + `treeCoverage 0.2-0.6` impossible | `treeCoverage <= 0.35` |

---

## 3. Domain facts you need (learned the hard way)

- **`treeCoverage` is NOT independent of temperature and moisture.** It's a bucketed function of
  `effTreeMoisture = moisture * gsFactor`. In warm climates `gsFactor ≈ 1`, so moisture maps
  straight onto buckets: `<0.2` bare, `0.2–0.5` sparse (0.35), `0.5–0.8` forest (0.62),
  `0.8–1.3` dense (0.85), `>=1.3` rainforest (1.0). In cold climates `gsFactor < 1` drops coverage
  for the same moisture (correctly — that's taiga/tundra). Constraining temp + moisture +
  treeCoverage independently **over-constrains**.
- **`treeCoverage`/`sparsity` are discrete**: only `{0, 0.35, 0.62, 0.85, 1.00}` /
  `{1.0, 0.65, 0.38, 0.15, 0.0}`. Any window straddling none of them is dead.
- **`moisture`, `treeMoisture` and `aridity` are the same underlying number** (see report §1d).
  Conditioning on two of them is not two constraints.
- **Never stack noise gates.** One gate per rule. Rarity now controls share; a gate's only job is
  spatial coherence, and one field does that as well as three.
- Noise field ceilings are ~0.77, **not 1.0** — `data/noise_quantiles/summary.json` has exact
  measured min/max per family. Thresholds above that are silently dead.
- **Eligible-but-losing vs never-eligible are different bugs.** `analysis/shadow.py` separates
  them: low win-share → raise `rarity`; low eligibility → widen conditions, no weight can help.
  Report §3f only diagnoses the second.
- A biome that is the **only** candidate in its niche wins 100% of it regardless of weight.
  Lowering its `rarity` does nothing.

---

## 4. Tooling

In-repo (survives):

| Path | Purpose |
|---|---|
| `tools/biome-lab/run.py` | validators + Monte Carlo + report |
| `tools/biome-lab/fit_rarity.py` | migrate `priority`→`rarity` and fit weights to targets. **Rewrites in place** |
| `tools/biome-lab/MIGRATION-rarity.md` | the migration walkthrough |
| `tools/biome-lab/analysis/status.py` | surface-biome reachability from a report |
| `tools/biome-lab/analysis/shadow.py` | eligibility vs win-share diagnostic |
| `tools/biome-lab/analysis/check_soundness.py` | candidate-filter soundness (needs `CandProbe`) |
| `tools/biome-lab/analysis/CandProbe.java` | dumps calculator output; `--json` mode for the checker |
| `tools/biome-lab/analysis/unstack_gates.py` | one-noise-gate-per-rule enforcement |
| `tools/biome-lab/analysis/add_rules.py` | rules for the 3 rule-less surface biomes |
| `tools/biome-lab/analysis/remap_noise.py` | rescale gate thresholds onto real quantiles |
| `tools/biome-lab/analysis/verify_all.sh` | runs 1+2+3 end to end |

`verify_all.sh` and several scripts have the **repo path and the scratchpad path hardcoded** — fix
those before reuse, and point them at the live catalog.

Runtime notes: a full 2M-sample Monte Carlo is ~2 min; `fit_rarity.py` at 40 iterations is
**10–25 min** — run it backgrounded. `pipeline_data.json` lives at
`~/.local/share/PrismLauncher/instances/1.21.1/minecraft/terrain-diffusion-models/pipeline_data.json`.
Gson for standalone `javac`:
`/home/derek/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/lib/gson-2.13.1.jar`.

---

## 5. Suggested order of work — COMPLETED

All 6 steps executed. See §0 summary and §2 for details.

### Artifacts created

- `tools/biome-lab/analysis/build_taxonomy.py` — full grid-based taxonomy generator (not used for
  final catalog due to diversity collapse; kept as reference for the coverage-grid approach)
- `tools/biome-lab/analysis/verify_all.sh` — repointed to work with live catalog path
- `biome_lab_report_province.md` — final Monte Carlo report
- `~/.local/share/.../biome_catalog.json` — final catalog with all changes
- `~/.local/share/.../biome_catalog.json.pre-rarity.bak` — original backup
- `~/.local/share/.../biome_catalog_pre-taxonomy.bak` — post-fix, pre-province backup

---

## 6. Known-unfixed issues (reported to the user, deliberately not chased)

- **~14.6% of the repo world matches no rule at all** and falls back to `minecraft:plains` (which
  wins 24.9% while eligible for 10.2%). A climate-space coverage gap, not a rarity problem. The
  province design above should be built to *close* this — an exhaustive base grid is the fix.
- **5 repo biomes sit under the 0.05% encounterability bar**: `frozen_peaks`, `jagged_peaks`,
  `snowy_beach`, `stony_shore`, `windswept_gravelly_hills`. These are limited by **zone size**, not
  their own rules — `stony_shore` holds a healthy 19.6% of the beach zone, but the simulator's whole
  beach zone is 0.107% of the world because a flat 0.15 probability stands in for a real
  coastline-adjacency test. The peaks depend on `bareSlope`, from a Gamma-distributed slope sampled
  **independently of elevation** — the tool's own README calls this its biggest fidelity gap. Do
  **not** distort their conditions to chase these numbers; they should be commoner in the real game.
  Worth confirming in-game rather than in the simulator.
- **Nothing has been built or run in-game.** The `biome` package compiles clean standalone; the 49
  errors from a bare `javac` of `common/` are all `net.minecraft`/`com.mojang` imports in untouched
  files. A real Gradle build has not been attempted.
- `override` ended up **used nowhere** — the places that look like they need strict dominance
  (ocean depth bands, snowy vs not) are already mutually exclusive by their conditions. The
  mechanism exists if the grouping work needs it.

---

## 6. Region redesign (2026-07-31, second pass)

The live catalog was found holding the **rejected grid taxonomy** (17:34 write). It was restored
from `biome_catalog_pre-taxonomy.bak` (fitted base, all fixes) and then reorganized into three
Earth-like provinces via `tools/biome-lab/analysis/apply_regions.py` (terciles of regionNoise at
±0.0967, 5000-block wavelength):

- **A "Boreal & Old World"**: tundra, ice_spikes, snowy/plain coniferous, og_spruce, birch pair,
  bog/moor/marsh, grassland, woodland pair, mediterranean_forest, highland/crag/wasteland_steppe,
  wasteland (+ existing deep-A lush_desert)
- **B "New World Frontier"**: muskeg, og_pine, fir_clearing, seasonal_forest, aspen_glade, wetland,
  dark_forest, redwood_forest, prairie, badlands trio, bayou, rainforest, lush_savanna,
  wintry_origin_valley
- **C "East & Pacific"**: cold_desert, maple pair, snowblossom_grove, dead_forest, orchard,
  dryland, bamboo_jungle, fungal_jungle, tropics, volcano, jade_cliffs

47 biomes gated (one noise condition per rule — thematic gates replaced, never stacked); rarity of
gated biomes ×2.5 capped at 10 (birch pair set to 3.0). Universal baselines (plains, forest, taiga,
snowy pair, desert, savanna, jungle, swamp, mangrove, all mountain/peak/beach/ocean/river) left
ungated so no climate×province cell is empty. Patchy accents (cherry, flower/sunflower, pale_garden,
mystic_grove, lavender, jacaranda, ominous, auroral, origin_valley, mushroom) keep their own gates.

Rule fixes this pass: jacaranda_glade (flowerNoise 0.6→0.45 + coverage/moisture buckets aligned),
tropics (coverage constraint dropped — moisture≥1.0 forces dense buckets; precip 1800→1400),
muskeg (coverage ≤0.35, temp −5..8, gsd constraint dropped), cold_desert (temp −25..−2, moisture
≤0.2, precip ≤150), rainforest (temp ≥20), highland/crag/jade_cliffs widened over the bar.

**Final: 0 dead conditions, 0 never-spawn, 80/93 surface biomes ≥0.05% bar, 19.70 effective
biomes, soundness PASS.** Report: `biome_lab_report_regions.md`. Backups: grid version at
`biome_catalog_grid-taxonomy.bak`, base at `biome_catalog_pre-taxonomy.bak`.
