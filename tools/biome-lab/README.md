# biome-lab

A permanent testing/analysis pipeline for `biome_catalog.json`, the data-driven rule file that
controls which vanilla/modded biome gets selected at each pixel of a generated world (see
`common/src/main/java/com/github/xandergos/terraindiffusionmc/biome/BiomeRuleEngine.java` and
`.../pipeline/BiomeClassifier.java` for the real Java implementation this tool ports).

It exists to catch two kinds of problems in a `biome_catalog.json` edit, fast:

1. **Dead conditions** -- a rule condition that can *literally never be true* no matter what the
   world generates, because it doesn't understand a hidden discreteness/bound in the real
   classifier (e.g. `treeCoverage between 0.15 0.3` when treeCoverage only ever takes 5 exact
   values, or `elevationM lt -250` when `elevationM` is clamped to never go negative). These make
   a biome partially or entirely unreachable in-game with no error, warning, or crash -- silent
   data loss.
2. **Vanishingly rare biomes** -- a rule that's technically reachable but so narrow (compounding
   AND conditions, or narrow noise thresholds) that a player would have to explore enormous
   distances to ever see it.

It is written once, on purpose, to be run repeatedly -- by a human, or by a small local LLM in a
tight edit-test loop.

## Prerequisites

- **Java 17+** and `javac` on PATH (no gradle needed -- this tool is deliberately standalone, see
  `java/`).
- **Python 3.10+** with **numpy** (`pip install numpy` if you don't have it). This is the *first*
  Python code in this repo; there is no other Python tooling to conflict with.
- Nothing else. No GPU, no ONNX runtime, no gradle build required.

## Quick start

```bash
cd tools/biome-lab

# 1. Fast static validators only -- no real climate data needed, runs in well under a second.
#    Use this in a tight edit-test loop after every JSON change.
python3 run.py --catalog ../../common/src/main/resources/biome_catalog.json --validate-only

# 2. Full run: validators + Monte Carlo simulation + one Markdown report.
#    --pipeline-data points at the REAL WorldClim/ETOPO quantile tables (not shipped in this repo
#    -- it lives alongside your actual game install's terrain-diffusion-models, see below).
python3 run.py \
  --catalog ../../common/src/main/resources/biome_catalog.json \
  --pipeline-data ~/.local/share/PrismLauncher/instances/1.21.1/minecraft/terrain-diffusion-models/pipeline_data.json \
  --min-samples 1000000 \
  --report biome_lab_report.md

# 3. Get concrete auto-fix suggestions for the two mechanically-fixable bug classes, written to a
#    NEW file (never touches your input catalog):
python3 run.py --catalog <path-to-catalog> --fix --fix-output ./biome_catalog.fixed.json
```

`--catalog` accepts **any** `biome_catalog.json` path -- point it at the repo's own default
(`common/src/main/resources/biome_catalog.json`, 65 biomes) or at a live instance's config
override (e.g. `.../config/terrain-diffusion-mc/biome_catalog.json`, which can have more entries
if mods like BiomesOPlenty added their own). The tool never hardcodes either path.

`run.py` exits with code `1` if any static validator found a dead condition, `0` otherwise --
useful for scripting/CI-style loops.

## What each report section means

The report is a single Markdown file with these sections, in order:

**1. Static validators.** Three checks, all driven by numbers this tool *measured itself*
(never hardcoded guesses):

- **1a. treeCoverage/sparsity discreteness.** `BiomeClassifier.classifyPixel` only ever produces
  one of exactly 5 `treeCoverage` values (0.00, 0.35, 0.62, 0.85, 1.00) and the matching 5
  `sparsity` values. A condition whose range falls entirely between two of those values can never
  match a real pixel. This check also catches the sneakier case where a *single rule* has two
  separate conditions on the same variable (e.g. `treeCoverage gte 0.02` AND
  `treeCoverage lt 0.15`) that are each individually satisfiable but jointly impossible -- found
  live in `biomesoplenty:tundra`.
- **1b. Noise ceiling.** Every `noiseConditions` threshold (`variantNoise`, `cherryNoise`,
  `paleNoise`, `clearingNoise`, `flowerNoise`, `regionNoise`) is checked against the *measured*
  min/max of that noise field's underlying FastNoiseLite family (see "Noise quantile data" below).
  A threshold beyond the measured range (e.g. `clearingNoise > 0.85` when that field never
  measured above ~0.77) can never be satisfied.
- **1c. Hard climate-variable bounds.** Some condition variables are architecturally guaranteed to
  stay within a range by `BiomeClassifier.classifyPixel` itself, regardless of what the world
  generation model produces -- see `biomelab/catalog.py`'s `HARD_BOUNDS` for the full derivation
  of each. The big one found in *both* shipped catalogs: `elevationM` is fed
  `Math.max(0f, elevation)`, so it is **never negative, even for ocean pixels**. Every
  `deep_*_ocean` biome (`deep_ocean`, `deep_lukewarm_ocean`, `deep_cold_ocean`,
  `deep_frozen_ocean`) gates its only rule on `elevationM lt -250`, which can thus never match --
  those four biomes are currently 100% unreachable. This check also flags *redundant* conditions
  (always trivially true, e.g. `elevationM gte -250`, which does nothing since elevationM can't be
  negative anyway) as a softer, non-blocking finding.
- **1d. moisture/treeMoisture aliasing.** Informational only: `moisture` and `treeMoisture`
  conditions read the *literal same* underlying value in `TerrainClimateSample` -- not independent
  axes. Not flagged as a bug, just worth knowing when reading a rule that uses both.

**2. Suggested fixes** (only shown with `--fix`). Concrete before/after patches for the
discreteness and noise-ceiling dead-condition classes:
- Discreteness fixes snap the condition to a tight `between` window around the nearest real
  discrete value (not a float `eq`, which would be brittle against JSON-vs-Java float rounding).
- Noise-ceiling fixes replace the threshold with the measured 98th (or 2nd) percentile of that
  noise field's real distribution, calibrated toward a ~2% "rare accent" pass rate rather than a
  made-up number.
- Ambiguous multi-condition dead groups (like `tundra`'s two-sided treeCoverage bug) and hard-bound
  violations (the elevationM/deep-ocean bug) are **not** auto-fixed -- there's no single
  mechanical snap that's obviously the intended fix, so those need a human/agent decision.

**3. Monte Carlo evaluation** (only when `--pipeline-data` is given and either validators passed
or `--force-montecarlo` was set):
- **3a. Area fractions by zone** -- what fraction of `ocean`/`beach`/`mountain`/`lowland`/
  `bareSlope`-zone pixels each biome wins.
- **3b. Diversity metrics** -- effective number of biomes (`exp(Shannon entropy)`, a "how many
  biomes does this feel like, accounting for how evenly spread they are" number) and HHI
  concentration (higher = a few biomes dominate).
- **3c. Cross-tier collision rates** -- how often two or three genuinely-eligible biomes competed
  for the same pixel (`BiomeRuleEngine`'s competition-noise resolution), by pair.
- **3d. Encounterability** -- turns an area fraction into a rough "expected blocks of exploration"
  estimate using the gating noise field's real spatial wavelength (650/320/280/260/220 blocks for
  variant/cherry/pale/clearing/flower noise, 900 for the fallback competition-noise wavelength).
  Flags biomes below `--min-area-fraction` (default 0.05%).
- **3e. Reachable climate ranges** -- p5/p50/p95 of temperature/precip/moisture/elevation and the
  snowy-pixel fraction among pixels where each biome actually won. Use this to eyeball "does a
  biome literally named 'desert' actually generate under desert-like conditions?"
- **3f. Rule bottleneck diagnostics** -- for every rule, its joint pass rate plus **each individual
  condition's own pass rate, sorted tightest-first**. This was the single most useful diagnostic
  from this project's throwaway prototype: it immediately shows which one condition in a
  compounding-AND rule is strangling it, instead of just "this rule barely ever fires."

## The slope approximation -- read this before trusting mountain/bareSlope numbers

**There is no real spatial elevation-gradient field anywhere in this Monte Carlo simulator.** The
real game computes `slope` from a literal Sobel gradient over neighbouring elevation pixels (see
`BiomeClassifier.computeSlopeRatio`); an i.i.d. (independent, identically distributed) per-pixel
sampler has no neighbours to take a gradient over. `biomelab/climate.py` instead draws `slope`
from a calibrated `Gamma(shape=1.2, scale=0.22)` distribution, tuned so roughly 8.5% of samples
land >= 0.62 ("medium" slope) and ~4.2% land > 0.78 ("steep"), matching the classifier's own
thresholds reasonably well in aggregate. **This is the single biggest fidelity gap in the whole
pipeline.** Every `mountain`-zone and `bareSlope`-zone number, and every biome anywhere that
heavily gates on `slope`, should be read with real skepticism about the exact percentages --
trust the *relative* ranking of biomes more than the *absolute* area fractions.

The beach/coastline zone has the same fundamental problem (a real spatial adjacency test,
approximated with a flat calibrated probability) and is flagged the same way, every time, right at
the top of the Monte Carlo report section -- not just here.

## Regenerating the noise quantile data

`data/noise_quantiles/*.json` are pre-generated and committed, so you normally never need to
regenerate them. They exist because `BiomeClassifier.java` builds several named FastNoiseLite
fields (`TEMP_NOISE`, `PRECIP_NOISE`, `BIOME_VARIANT_NOISE`, ...), and this tool needs to know each
field's *true* value distribution (to sample from realistically, and to know what thresholds are
even reachable) without re-implementing FastNoiseLite's gradient-noise algorithm from scratch in
Python. Instead, `java/NoiseProbe.java` instantiates the mod's actual (copied verbatim)
`FastNoiseLite.java` and measures millions of real samples per distinct `(octaves, gain)` family
(frequency/seed don't affect the marginal distribution, so one probe per family covers every named
field that shares it -- see the file's docstring for the full family-to-field mapping).

To regenerate (only needed if you change the noise parameters in `BiomeClassifier.java`, or want
more samples):

```bash
cd tools/biome-lab
javac -d out java/FastNoiseLite.java java/NoiseProbe.java
java -cp out NoiseProbe --out data/noise_quantiles --grid 4500 --range 200000
```

`--grid 4500` samples a 4500x4500 grid (~20.25M samples) per family, over a +-200,000 block
world-coordinate window; takes about 15 seconds total on a normal laptop. If `data/noise_quantiles`
is ever missing (e.g. a stripped checkout), `run.py` fails fast with the exact command above
printed to stderr -- it will not silently fall back to a fabricated distribution.

## Where the real climate numbers come from

`--pipeline-data` points at `pipeline_data.json` -- **not shipped in this repo**, it lives
alongside your actual game install, e.g.:

```
~/.local/share/PrismLauncher/instances/1.21.1/minecraft/terrain-diffusion-models/pipeline_data.json
```

It contains `data_quantile_tables`, 5 channels `[elev, temp, temp_std, precip, precip_std]`, each a
64-point quantile table built from real quantile-matched WorldClim/ETOPO data. **Channel 0 is real
elevation in meters already** -- median around -2000m (most of a quantile-matched Earth is ocean),
p90 a few hundred meters, tail out to several thousand meters. It is only sqrt-transformed as the
*last* step inside `SyntheticMapFactory`'s own encoding for feeding the diffusion model -- the raw
table itself is real, un-squared meters. `climate.py` sanity-checks this on load (median must be
negative and under 20,000m in magnitude) specifically to catch anyone re-introducing the
sqrt-squaring mistake made earlier in this project's history.

Sampling is inverse-CDF: draw `u ~ Uniform(0,1)`, linearly interpolate the quantile table at
percentile `u * 100`. See `biomelab/climate.py`'s module docstring for the full derivation chain
(lapse-rate correction, temp/precip-std correlation formulas ported from
`SyntheticMapFactory.sample()`, and where it deliberately swaps out that class's simple synthetic
fallback lapse-rate formula for `LaplacianUtils`'s real warm-region-gated one).

## Layout

```
tools/biome-lab/
  run.py                    CLI entry point
  README.md                 this file
  biomelab/
    catalog.py               loads biome_catalog.json (source of truth: the real JSON schema)
    noise_data.py             loads java/NoiseProbe.java's output, inverse-CDF sampling
    climate.py                Monte Carlo climate/terrain sampler (ports classifyPixel)
    engine.py                 vectorized BiomeRuleEngine port (tiered selection + competition noise)
    validators.py             the 3 fast static checks
    montecarlo.py              area fractions / diversity / collisions / encounterability / ranges
    fixes.py                   auto-fix suggestion + application logic
    report.py                  Markdown report renderer
  java/
    FastNoiseLite.java         verbatim copy of the mod's real noise implementation
    NoiseProbe.java             measures each noise family's true distribution
  data/
    noise_quantiles/*.json      pre-generated, committed output of NoiseProbe
```

## For a local LLM driving this in a loop

1. Run `python3 run.py --catalog <path> --validate-only` after every edit. It's fast (well under a
   second) and exits non-zero if anything is dead -- treat that as "keep iterating."
2. Read the printed `DEAD`/`REDUNDANT` lines directly from stdout; you don't need to open the
   report file for the fast path.
3. Once validators pass, run the full command with `--pipeline-data` and a real `--report` path
   before declaring the catalog edit done, and actually read section 3e (reachable climate ranges)
   to sanity-check that biomes generate under conditions matching their name/intent.
4. Use `--fix --fix-output <somewhere new>` to get a starting patch for the mechanical bug classes,
   then diff it against the original before deciding whether to apply it for real -- it is a
   suggestion, not a guarantee of correctness (it doesn't know your creative intent, only the
   nearest mechanically-valid value).
