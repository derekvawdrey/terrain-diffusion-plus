# Parallelizing priority-flood — handoff notes

Status as of 2026-07-30. Written so a future session (or a future me) doesn't have to
re-derive the same dead ends. Production code is **unaffected and safe** right now —
read "Current state" first.

## Why this was worth attempting

At the project's real config (`hydrology.tile_size=8192`, `hydrology.analysis_halo=512`,
before it was reduced — see below), a single canonical hydrology tile is a ~9216×9216
(~85M cell) analysis grid, and generating one took **~49 seconds**, of which **~43.8s
(90%)** was `FluvialRiverNetwork.runPriorityFloodSequential`'s single-threaded depression-
filling flood. Everything else in the pipeline (channel rasterization, terrain carving,
biome classification) is already parallelized across `HydrologyParallel`'s worker pool;
this one step wasn't, and dominated. See `HydrologyBenchmark.java` for the harness that
measured this (run it standalone, no Minecraft/ONNX needed — see "How to benchmark" below).

**Immediate, zero-risk mitigation already applied**: `hydrology.tile_size` was reduced
8192→2048 and `hydrology.analysis_halo` 512→128 in
`versions/1.21.1/common/config/terrain-diffusion-mc.properties`. Benchmarked at ~1.9s per
tile at those values — a ~25x win with no code changes. If you're just here to make
generation faster and don't care about true parallelism, that's likely still your best
lever, especially if you want to push tile size back up for larger coherent drainage
basins.

## Current state (2026-07-30)

`FluvialRiverNetwork.build()` calls `runPriorityFloodSequential` — the original,
single-threaded, unmodified reference algorithm. **This is correct and safe to ship.**

`runPriorityFloodParallel` exists alongside it but is **not called from production** and
is **not fully correct**. It's a genuine, partially-working attempt:

- **Phase 1** (per-strip flood using only true seeds — left/right columns of every
  row-strip always qualify since a strip spans the full width) — **validated correct**.
- **Phase 3** (cross-cut boundary graph, solved via an ordinary lazy-deletion Dijkstra
  over `IntMinHeap`) — **validated correct** for the cross-cut-only case. Prove this
  yourself: run `HydrologyFloodValidation` with `-Dtdmc.disablePassThrough` set — every
  test case passes.
- **Phase 2/3's same-strip pass-through mechanism** (letting a cell route through its own
  strip's interior to reach the *other* cut, when that's cheaper than direct true-seed
  access) — **provably wrong**. Two different designs were tried and both failed, for two
  different reasons (see "What was tried and why it failed" below). This is the only
  remaining piece; if you fix just this, phase 4 (the final re-flood) and the override/
  topological-order reconstruction downstream of it already work correctly given a
  correct phase 3.

The unused code is deliberately left in place, well-commented, because phases 1/3/4 are
real, validated, reusable work — only the pass-through case needs to be redone.

## What was tried and why it failed

### Attempt 1: iterative round-based relaxation (Bellman-Ford style)

Each strip re-floods every round using the current best-known boundary values from
adjacent strips; boundary values only ever decrease; repeat until convergence (bounded by
`stripCount` rounds since a correction can propagate at most one strip per round).

**Bug found**: adjacent strips can mutually reinforce each other's boundary estimate
across rounds and converge to a value that's self-consistent but *wrong* (a real 2-cycle
in the resulting drainage graph — confirmed directly, 52–300+ actual cycles depending on
grid size). The round-based structure doesn't prevent two strips from "trusting" a
value that traces back to each other rather than to a genuine true seed. This is a subtle
enough trap that a specific `-Dtdmc.debugFlood` instrumentation was needed to catch it
(counts `downstream[downstream[idx]] == idx` occurrences) — worth re-adding if you go back
down this road.

### Attempt 2: single-source spanning tree + O(1) all-pairs shortcut

Replace the iterative relaxation with something structurally cycle-free: compute one
single-source flood per middle strip (arbitrary seed, e.g. the strip's own top-left
cell), and use the claim "for a bottleneck spanning tree, `max(dist(x), dist(y))` gives
the true bottleneck distance between any two nodes x and y" to get exact same-strip
pairwise costs in O(1) instead of O(width²).

**This claim is false for what was actually built**, and that's the real lesson here:
`floodStrip`'s single-seed output is a **shortest-bottleneck-path tree rooted at that
seed** (optimizing each node's distance *from the root*), not a **minimum bottleneck
spanning tree** (a different, global structure). The all-pairs `max(dist(x),dist(y))`
property is a theorem about the latter, not the former, and they aren't the same tree
in general. Confirmed empirically, not just in theory — see `MstTheoremCheck.java`,
which seeds a flood from an arbitrary corner and shows multiple pairs of cells where
`max(mstFilled(x), mstFilled(y))` reports the shared "ceiling" value from a dominant
terrain feature both paths crossed on the way from the root, when their *true* mutual
bottleneck (computed by flooding from one of them directly) is meaningfully lower.
Run it — it's fast and the failure is obvious in the printed output.

Net effect when this was plugged into the boundary-graph Dijkstra: measurably *worse*
results than attempt 1's "single nearest candidate" approximation (which was itself
provably incomplete but at least didn't have this specific flaw), because most of the
"pass-through" contributions it computed simply never fired (the virtual-node fan-out
never improved anything, since the offered values, being contaminated by the wrong
theorem, were usually higher than what cross-cut alone already found).

## What to actually try next (research pointers)

The goal is: for a middle strip (bounded by cuts on both sides), correctly and cheaply
determine, for every boundary cell, the cost of routing through the strip's own interior
to reach the *other* boundary — without O(width²) all-pairs work, and without the
theorem confusion above.

1. **Read the actual Barnes paper before implementing anything.** This session's mistakes
   came from working off fuzzy memory of "some published parallel priority-flood
   technique" rather than the real thing. Barnes, Lehman, Mulla, *"Parallel Priority-Flood
   depression filling for trillion cell digital elevation models on desktops or
   clusters"* (arXiv:1606.06204) is the specific paper this session kept trying to
   reconstruct from memory. Read it properly first. It likely has the correct
   formulation of the "spillover graph" between tiles/strips that avoids both bugs above.
   The companion original algorithm paper is Barnes, Lehman, Mulla (2014), *"Priority-Flood:
   An Optimal Depression-Filling and Watershed-Labeling Algorithm for Digital Elevation
   Models."*

2. **If building on this session's structure, fix it with a real Minimum Bottleneck
   Spanning Tree, not a shortest-path tree.** A genuine MBST *does* have the all-pairs
   `max(dist(x),dist(y))`-via-tree-path property — construct one with Kruskal's algorithm
   (sort the strip's own grid edges by `max(elevation[a], elevation[b])`, union-find,
   standard MST construction) instead of Prim/flood-fill from an arbitrary seed. Concretely:
   - Build edges for 8-connectivity (4 directions per cell to avoid double-counting: right,
     down, down-right, down-left) — about `4 × stripHeight × width` edges per strip.
   - Sort them (parallelizable via `Arrays.parallelSort` on a packed `long` — pack a
     sortable float-bit-flipped weight in the high bits, endpoint indices in the low bits,
     or split into two parallel arrays sorted together).
   - Union-Find with path compression + union by rank/size to build the MST, recording
     parent pointers and edge weights (not just connectivity) so you can reconstruct paths.
   - **Then you need O(1) or O(log n) LCA queries** to actually reconstruct a valid
     downstream/routing chain for a specific pair (not just the bottleneck *value*, which
     the MST alone gives you for free) — binary lifting or Euler-tour + sparse table are
     the standard techniques. This is real, non-trivial additional machinery; budget for it.
   - Memory note: explicit edge lists at real scale (a single strip at ~5.6M cells has
     ~22M edges) are not free — expect a few hundred MB per strip if materialized. Consider
     whether a bucket/radix sort keyed by quantized elevation can avoid full materialization.

3. **Simpler alternative worth considering before the full MST/LCA machinery**: is the
   same-strip pass-through case actually common enough on *real* diffusion-model terrain
   (smooth, no deliberately-adversarial random pits) to be worth this complexity? This
   session's validation harness (`HydrologyFloodValidation`) uses synthetic terrain with
   randomly injected pits specifically to stress this case — real terrain may see it far
   less often. If so, it might be reasonable to ship the cross-cut-only mechanism (already
   validated correct) as a real, if incomplete, speedup, explicitly documented as "same-
   strip pass-through not modeled — safe (never produces an incorrect/too-low value), just
   occasionally not the global optimum," and measure how often it actually matters on real
   generated terrain before investing further. Every mismatch found this session was
   confirmed *safe* (parallel result ≥ true value, never <) — see the `[N UNSAFE/too-low]`
   field `HydrologyFloodValidation` prints; it was 0 in every test run, in every attempt.

4. Whichever approach you land on, **the disk cache is keyed by `ALGORITHM_VERSION`** in
   `HydrologyProvider.java`. Any change to tie-breaking in flat/lake regions (a likely
   consequence of *any* parallel approach, since tie-breaking order changes with strip
   decomposition) needs a version bump to invalidate stale cached tiles. This was bumped
   and then reverted during this session's work (currently back at its original value);
   bump it again once something actually ships.

## How to validate a new attempt

```
common/src/main/java/.../hydrology/HydrologyFloodValidation.java
```
Runs both `runPriorityFloodSequential` (reference) and `runPriorityFloodParallel` (your
new code) on identical synthetic terrain (rolling hills + injected pits/ridges + an
interior sub-sea-level patch, at several sizes chosen to exercise different strip counts),
and reports: value mismatches (and whether any are unsafe/too-low, which would be a real
regression, not just suboptimality), and structural checks on the downstream graph (no
self-loops, valid topological order, monotonic filled values along every edge).

Useful system properties already wired up in `FluvialRiverNetwork`:
- `-Dtdmc.debugFlood=1` — round-by-round / phase diagnostics (visited counts, two-cycle
  detection, virtual-node finalization state). Verbose; was added for this session's
  debugging and can be trimmed or extended as needed.
- `-Dtdmc.disablePassThrough=1` — disables just the same-strip pass-through contribution,
  useful for confirming the rest of the pipeline (phases 1/3/4, cross-cut only) still
  passes cleanly while you rework the pass-through piece in isolation.

Compile and run (no Minecraft/ONNX needed, but you do need the module's runtime
classpath — the game's dependency jars, not just `common`'s own classes). This project's
Gradle setup doesn't expose a `run`/`test` task for these ad hoc classes, so extract the
resolved classpath once via a throwaway init script:

```bash
./gradlew :common:compileJava

cat > /tmp/print-cp.gradle << 'EOF'
allprojects {
    if (project.path == ':common') {
        tasks.register('printRuntimeClasspath') {
            doLast { println sourceSets.main.runtimeClasspath.asPath }
        }
    }
}
EOF
CP=$(./gradlew --init-script /tmp/print-cp.gradle :common:printRuntimeClasspath -q)

java -Xmx4g -cp "$CP" \
  com.github.xandergos.terraindiffusionmc.hydrology.HydrologyFloodValidation

java -cp "$CP" com.github.xandergos.terraindiffusionmc.hydrology.MstTheoremCheck
```

Run these commands with a working directory of `versions/1.21.1/common` — that's the
module whose classpath you just extracted, and it's also where `config/` lives if you
want `HydrologyBenchmark`'s defaults to pick up the real `hydrology.tile_size`/
`hydrology.analysis_halo` from `terrain-diffusion-mc.properties` automatically.

## How to benchmark

```
common/src/main/java/.../hydrology/HydrologyBenchmark.java
```
Standalone, synthetic-terrain timing harness for `FluvialRiverNetwork.build` +
`DetailedRiverCarver.carve` directly — no Minecraft/ONNX/world needed. Defaults to
whatever `hydrology.tile_size`/`hydrology.analysis_halo` resolve to (real config if run
with a working directory that has a sibling `config/` folder), or pass explicit args:
`tileSize halo pixelSizeM seed`. At the real (pre-reduction) config of 8192/512 this is an
~85M-cell grid; needs a large heap (`-Xmx8g`+) and several GB of memory headroom.

Both classes already emit their own phase-by-phase timing via SLF4J
(`FluvialRiverNetwork`/`DetailedRiverCarver` LOG.info lines), so the breakdown is visible
regardless of whether you're running the benchmark or the validation harness.
