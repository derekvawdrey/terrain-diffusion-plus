# Handoff: explorer candidate filters, rarity migration, and the pending "biome groupings" job

Written mid-task because the session ran out of budget. Branch `feature/land-features`. Nothing is
committed — everything below is uncommitted working-tree state. The unrelated `versions/*/.../world/surface/*FeaturePlacer.java` edits in `git status` predate this work; leave them alone.

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

## 2. What is NOT done — the actual remaining job

**Target file**: `~/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json`
(109 biomes: 65 vanilla + 44 BiomesOPlenty; 93 of them surface).

**Backed up to** `<same path>.pre-rarity.bak`. **No changes have been written to it yet.**

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

### Analysis already gathered on the live catalog

- Static validators: only **2 dead conditions** — `biomesoplenty:tundra`'s
  `treeCoverage >= 0.02 AND treeCoverage < 0.15` straddles no discrete bucket. Not auto-fixable;
  pick a bucket (it's a snowy near-bare biome, so `treeCoverage <= 0.01` or the 0.35 bucket).
- **18 rule-less biomes**, of which **3 are surface** and cannot spawn:
  `old_growth_birch_forest`, `eroded_badlands`, `mushroom_fields`. (Same three as the repo — you
  can lift the rules from `analysis/add_rules.py`.) The other 15 are RIVER/CAVE/NETHER/END/VOID and
  are correctly rule-less — they don't go through `BiomeRuleEngine`.
- **22 rules stack 2–3 noise gates** (146 have none, 117 have one, 15 have two, 7 have three).
- **49 distinct priority levels** → guaranteed inversions.
- BoP's climate windows are mostly *semantically sensible already* (rainforest hot+wet,
  cold_desert cold+dry, wasteland hot+very-dry). The intent is good; reachability is what's broken.
- 5 BoP biomes already use `regionNoise` as a province gate (`bayou`, `lush_desert`,
  `mystic_grove`, `rainforest`, `wasteland`) — precedent for the grouping mechanism.

### The province mechanism (designed, not yet implemented)

`REGION_NOISE = makeFnl(778899, 1f/5000f, 2, 2f, 0.5f)` — **5000-block wavelength**, continental
scale. Every other noise field is 220–650 blocks (patch scale), so `regionNoise` is the *only*
field suitable for provinces. Measured range `[-0.803, +0.808]`; **terciles at ±0.0967** (33.3%
each, verified).

**Coverage rule — the important one.** Each climate cell must have one **ungated base biome**
(always eligible). Additional members of that cell get a province gate, so they only replace the
base within their band. Without an ungated base per cell you get holes that fall back to the
default biome (index 1, `minecraft:plains`).

Suggested shape:

```
climate cell (temp band x treeCoverage bucket)
  base biome        ungated                rarity ~1.0
  variant A         regionNoise < -0.0967  rarity ~1.0   (equal partners)
  variant B         |regionNoise| <= 0.0967
  variant C         regionNoise > 0.0967
```

A draft base grid (6 temp bands × 5 `treeCoverage` buckets = 30 cells) was sketched but **not
written down** — redo it. Bands used: FROZEN `<-2`, COLD `-2..5`, COOL `5..12`, TEMPERATE `12..19`,
WARM `19..26`, HOT `>=26`.

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

## 5. Suggested order of work

1. Migrate the live catalog: `analysis/unstack_gates.py`, fix `bop:tundra`'s dead pair, add rules
   for the 3 rule-less surface biomes, then `fit_rarity.py` for a working baseline.
2. Run `verify_all.sh` (repointed) to get the "before groupings" numbers.
3. Build the taxonomy: assign all 93 surface biomes to (zone, temp band, veg bucket, province,
   weight). **Write it as a data file, not inline edits** — it needs to be auditable and re-runnable.
4. Generate rules from the taxonomy, guaranteeing an ungated base per cell.
5. Re-fit weights (equal partners), re-verify, iterate on whatever `status.py` still flags.
6. Re-run `check_soundness.py` — rule changes move the candidate-filter boxes.

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
