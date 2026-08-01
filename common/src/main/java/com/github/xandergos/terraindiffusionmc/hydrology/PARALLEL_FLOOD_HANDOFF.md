# Parallelizing priority-flood — RESOLVED (2026-07-31)

`FluvialRiverNetwork.build()` now calls `runPriorityFloodParallel` in production. The
previous handoff (2026-07-30) documented two dead ends; both were root-caused and fixed.
`HydrologyFloodValidation` passes every size/seed with **exact** (maxDiff = 0) agreement
between the parallel and sequential filled surfaces. This file records the two findings so
they aren't re-derived; the algorithm itself is documented on the
`runPriorityFloodParallel` javadoc.

## Fix 1: the pass-through math (per-strip boundary dendrograms)

The old approach approximated through-strip transitions with
`max(mstFilled(x), mstFilled(y))` from a *single-source* flood tree. That structure lacks
the all-pairs bottleneck property (only a *minimum spanning tree* has it — `MstTheoremCheck`
disproved the shortcut empirically), and edge strips got no pass-through at all.

The fix is exact: each strip builds a **single-linkage merge tree (dendrogram)** over its
cut-row cells — a union-find sweep in increasing `(elevation, index)` order, recording a
merge node whenever two components that each contain a cut-row cell unite. By the MST
minimax-path property, the LCA elevation of two cut-row cells in this tree equals the true
bottleneck between them through the strip interior. The dendrogram nodes are spliced into
the phase-3 boundary Dijkstra as ordinary graph nodes (entering a node costs
`max(current, nodeElevation)`). This is the same mathematical object as the watershed
spillover graph in Barnes (2016), *Parallel Priority-Flood depression filling for
trillion cell digital elevation models* (arXiv:1606.06204), in pairwise form.

## Fix 2: the Dijkstra heap (immutable keys)

The boundary Dijkstra used `IntMinHeap`, which reads priorities **live** from the
`boundaryFilled` array at compare time. When a relaxation lowers a node's value after it
was inserted, the stale entry's priority changes underneath the heap and silently breaks
the heap invariant — later sifts compare against the moved value and can pop *other*
nodes out of order, finalizing them too high. This was observed directly (a leaf finalized
at 473.87 before its parent merge node popped at 473.79). Lazy-deletion Dijkstra is only
correct with **snapshot keys**: the fix packs `(sortableFloatBits(value) << 32) | node`
into a `LongMinHeap` entry at push time; stale entries keep their old keys and are skipped
on pop via `boundaryFinal`.

`IntMinHeap` remains safe where priorities are set before insertion and never change
(`runPriorityFloodSequential`). Do not reuse it for anything that relaxes priorities.

## Determinism notes

- Strip count is a **fixed** function of grid height (`MAX_FLOOD_STRIPS` cap), never the
  worker count, so output is identical across machines with different CPU counts.
- Filled values are exactly equal to the sequential reference (max/min compositions of
  elevations — no arithmetic). Drainage tie-breaking in perfectly flat filled lakes can
  differ from the sequential pop order (valid either way, machine-independent);
  `HydrologyProvider.ALGORITHM_VERSION` was bumped alongside.

## Why this matters

At `hydrology.tile_size=2048` the flood is a modest win (~1.5 s → ~0.45 s per tile), but
the flood is the only O(n log n) serial step: at the original 8192/512 config it was
**43.8 s of a 49 s tile**. With it parallelized and exact, pushing tile size back up for
larger coherent drainage basins is viable again.

## How to benchmark / validate

See `HydrologyBenchmark` (timings, no Minecraft/ONNX needed) and
`HydrologyFloodValidation` (parallel-vs-sequential exactness + fast-heap exactness;
must print ALL CHECKS PASSED).
