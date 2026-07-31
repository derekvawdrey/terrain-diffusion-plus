# Migrating a `biome_catalog.json` from `priority` to `rarity`

The rule field `priority` (integer, higher wins) has been replaced by `rarity` (float weight,
default `1.0`) plus an optional `override` (boolean, default `false`). If you have a catalog of
your own — e.g. an instance config override with modded biomes in it — this is how to convert it.

## What changed, and why it matters for your catalog

`BiomeRuleEngine` used to walk priority tiers highest-first and let a lower tier steal a pixel only
by beating the leader's noise by a fixed penalty. That made "X is a rarer variant of Y" something
you had to express as `priority(X) > priority(Y)` — and if it wasn't, X became close to invisible
with no error, no warning, and nothing in the validators to catch it. The shipped catalog had
several of these; `minecraft:bamboo_jungle` was eligible on 0.16% of sampled pixels and won
0.0000% of them.

Now, among the biomes eligible at a pixel, biome *i* wins with probability `w_i / sum(w)`. So:

```json
{ "zone": "lowland", "rarity": 0.15, "conditions": [ ... ] }
```

reads as "where this overlaps one ungated `rarity: 1.0` biome, take about 13% of it."

Two things to keep in mind:

- **A weight is a share of *contested* area, not of the world.** A biome that is the only candidate
  in its niche wins all of it regardless of how small its weight is. Lowering `rarity` on such a
  biome changes nothing.
- **A weight can never exceed eligibility.** If a biome's conditions only hold on 0.01% of pixels,
  no weight makes it common. Widen the conditions instead.

`"override": true` makes a rule beat every non-override candidate outright, competing on weight
only against other overrides. It is for structural dominance; the ordinary "rarer variant of"
relationship wants a weight. The bundled catalog needs it nowhere — the places that look like they
need strict dominance (ocean depth bands, snowy vs non-snowy) are already mutually exclusive by
their conditions.

## Backwards compatibility

A catalog that still has `priority` **loads without error**: `priority` is ignored and every rule
defaults to `rarity: 1.0`. Nothing crashes, but every biome then competes on equal terms, so rare
variants will be far more common than you intended. Convert properly rather than relying on this.

## Converting

There is no formula from `priority` to `rarity` — one is an ordering, the other a proportion. Fit
the weights against the Monte Carlo instead:

```bash
cd tools/biome-lab
python3 fit_rarity.py \
  ~/.local/share/PrismLauncher/instances/<instance>/minecraft/config/terrain-diffusion-mc/biome_catalog.json \
  ~/.local/share/PrismLauncher/instances/<instance>/minecraft/terrain-diffusion-models/pipeline_data.json \
  1200000
```

It rewrites the file in place (back it up first), so:

1. drops `priority`, seeds every rule at `1.0`;
2. measures each biome's eligibility and its current share;
3. iterates weights until each biome hits `max(current share, floor)`, capped at what its
   eligibility can actually support;
4. prints a per-biome table, flagging anything still short as **eligibility-limited** — those need
   wider conditions, not a bigger number.

Then re-run the usual validation before trusting it:

```bash
python3 run.py --catalog <your catalog> --pipeline-data <pipeline_data.json> \
  --min-samples 2000000 --force-montecarlo --report report.md
```

## Two failure modes the fitter will surface

**Stacked noise gates.** Three independent `noiseConditions` AND-ed in one rule multiply: 5% x 4%
x 2% is 0.004%, not a rare accent. Each gate is individually reachable so no validator flags it.
Now that `rarity` controls share, a gate's only job is to make patches spatially coherent — one
field does that as well as three. Keep one per rule.

**Coverage gaps.** If the fallback biome (index 1, normally `minecraft:plains`) wins far more area
than its own rules are eligible for, the difference is pixels no rule in the catalog matches at
all. The fitter reports that number explicitly. It is not a rarity problem and no weight will fix
it; it means the catalog's climate conditions leave part of the climate space uncovered.
