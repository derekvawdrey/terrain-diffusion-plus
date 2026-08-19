package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * A lazy, sliding-window "infinite" tensor backed by a {@link MemoryTileStore}.
 *
 * <p>Only the <em>direct</em> cache strategy is implemented: each computed window
 * output is stored in an LRU cache keyed by window index. Overlapping windows
 * are summed to produce the final slice.
 *
 * <p>Create instances exclusively through {@link MemoryTileStore#getOrCreate}.
 */
public final class InfiniteTensor {

    private static final int CACHE_LEASE_RETRY_COUNT = 3;

    final String id;

    /** Shape in each dimension; null = unbounded. */
    final Integer[] shape;

    /** Defines position and size of each output window. */
    final TensorWindow outputWindow;

    /** Non-batched compute function (null if batched). */
    final TensorFunction function;

    /** Batched compute function (null if non-batched). */
    final BatchTensorFunction batchFunction;

    /** Maximum number of windows per batch call (0 = non-batched). */
    final int batchSize;

    /** Upstream dependency tensors. */
    final InfiniteTensor[] deps;

    /** How to slice each dependency for a given window index. */
    final TensorWindow[] depWindows;

    /** Owning store, used for cache reads/writes and dependency resolution. */
    final MemoryTileStore store;

    /**
     * Soft limit on cached window bytes. {@code Long.MAX_VALUE} = unlimited.
     * Eviction occurs only after a complete slice has been assembled.
     */
    final long cacheLimitBytes;

    /** Serializes cache-miss discovery and computation for this tensor. */
    private final ReentrantLock computeLock = new ReentrantLock();

    InfiniteTensor(
            String id,
            Integer[] shape,
            TensorWindow outputWindow,
            TensorFunction function,
            BatchTensorFunction batchFunction,
            int batchSize,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            MemoryTileStore store,
            long cacheLimitBytes) {
        this.id = id;
        this.shape = shape;
        this.outputWindow = outputWindow;
        this.function = function;
        this.batchFunction = batchFunction;
        this.batchSize = batchSize;
        this.deps = deps;
        this.depWindows = depWindows;
        this.store = store;
        this.cacheLimitBytes = cacheLimitBytes;
    }

    /**
     * Retrieve a contiguous slice of this tensor.
     *
     * @param start inclusive pixel start per dimension
     * @param end   exclusive pixel end per dimension
     * @return the accumulated FloatTensor
     */
    public FloatTensor getSlice(int[] start, int[] end) {
        validateSliceBounds(start, end);
        int[][] pixelRange = buildRange(start, end);
        List<int[]> requiredWindows = collectIntersectingWindows(pixelRange);

        // Fast path: if every window is already cached there is nothing to compute, so the
        // compute lock is not taken at all. That matters because a slice of an upstream stage
        // is often requested by a thread that only wants to read it (the elevation and climate
        // reads at the end of a pipeline run), while another thread holds this tensor's compute
        // lock for the whole of its own multi-second inference pass. Waiting there would idle
        // the GPU: the reader has river and biome work it could be doing meanwhile.
        MemoryTileStore.WindowLease lease = store.leaseWindows(id, requiredWindows);
        if (lease == null) {
            computeLock.lock();
            try {
                for (int attempt = 0; attempt < CACHE_LEASE_RETRY_COUNT && lease == null; attempt++) {
                    ensureComputedRangesLocked(Collections.singletonList(pixelRange));
                    lease = store.leaseWindows(id, requiredWindows);
                }
            } finally {
                computeLock.unlock();
            }
        }

        if (lease == null) {
            throw new IllegalStateException(
                    "Tensor '" + id + "' lost required cache windows while preparing a slice");
        }

        try (MemoryTileStore.WindowLease activeLease = lease) {
            int n = shape.length;
            int[] outShape = new int[n];
            for (int d = 0; d < n; d++) {
                outShape[d] = end[d] - start[d];
            }
            FloatTensor output = new FloatTensor(outShape);

            for (int[] windowIndex : requiredWindows) {
                FloatTensor cached = activeLease.get(windowIndex);
                int[][] windowBounds = outputWindow.getBounds(windowIndex);

                int[][] intersection = new int[n][2];
                boolean overlaps = true;
                for (int d = 0; d < n; d++) {
                    intersection[d][0] = Math.max(pixelRange[d][0], windowBounds[d][0]);
                    intersection[d][1] = Math.min(pixelRange[d][1], windowBounds[d][1]);
                    if (intersection[d][0] >= intersection[d][1]) {
                        overlaps = false;
                        break;
                    }
                }
                if (!overlaps) {
                    continue;
                }

                int[][] sourceRegion = new int[n][2];
                int[][] destinationRegion = new int[n][2];
                for (int d = 0; d < n; d++) {
                    sourceRegion[d][0] = intersection[d][0] - windowBounds[d][0];
                    sourceRegion[d][1] = intersection[d][1] - windowBounds[d][0];
                    destinationRegion[d][0] = intersection[d][0] - pixelRange[d][0];
                    destinationRegion[d][1] = intersection[d][1] - pixelRange[d][0];
                }

                output.addFrom(cached, destinationRegion, sourceRegion);
            }
            return output;
        } finally {
            store.evictIfNeeded(id, cacheLimitBytes);
        }
    }

    /** Ensures every window intersecting {@code pixelRange} is present in the cache. */
    void ensureComputed(int[][] pixelRange) {
        ensureComputedRanges(Collections.singletonList(pixelRange));
    }

    /**
     * Ensures every window that intersects any supplied pixel range is present.
     * Each range is expanded independently, then deduplicated.
     */
    void ensureComputedRanges(List<int[][]> pixelRanges) {
        computeLock.lock();
        try {
            ensureComputedRangesLocked(pixelRanges);
        } finally {
            computeLock.unlock();
        }
    }

    private void ensureComputedRangesLocked(List<int[][]> pixelRanges) {
        Set<List<Integer>> pendingSet = new LinkedHashSet<>();
        for (int[][] range : pixelRanges) {
            int[] lowest = outputWindow.getLowestIntersection(range);
            int[] highest = outputWindow.getHighestIntersection(range);
            iterateWindows(lowest, highest, windowIndex -> {
                if (!store.isWindowCached(id, windowIndex)) {
                    List<Integer> key = new ArrayList<>(windowIndex.length);
                    for (int value : windowIndex) {
                        key.add(value);
                    }
                    pendingSet.add(key);
                }
            });
        }

        List<int[]> pending = pendingSet.stream()
                .map(key -> key.stream().mapToInt(Integer::intValue).toArray())
                .collect(Collectors.toList());
        if (pending.isEmpty()) {
            return;
        }

        // Dependencies receive the exact list of ranges, not a bounding-box union.
        for (int dependencyIndex = 0; dependencyIndex < deps.length; dependencyIndex++) {
            List<int[][]> dependencyRanges = new ArrayList<>(pending.size());
            for (int[] windowIndex : pending) {
                dependencyRanges.add(depWindows[dependencyIndex].getBounds(windowIndex));
            }
            deps[dependencyIndex].ensureComputedRanges(dependencyRanges);
        }

        if (batchSize > 0 && batchFunction != null) {
            computeBatched(pending);
        } else {
            for (int[] windowIndex : pending) {
                computeSingle(windowIndex);
            }
        }
    }

    private void computeSingle(int[] windowIndex) {
        List<FloatTensor> arguments = new ArrayList<>(deps.length);
        for (int dependencyIndex = 0; dependencyIndex < deps.length; dependencyIndex++) {
            int[][] bounds = depWindows[dependencyIndex].getBounds(windowIndex);
            arguments.add(deps[dependencyIndex].getSlice(starts(bounds), ends(bounds)));
        }

        FloatTensor result = function.apply(windowIndex, arguments);
        validateOutputShape(result);
        store.cacheWindow(id, windowIndex, result);
    }

    private void computeBatched(List<int[]> windowIndices) {
        List<List<int[]>> batches = new ArrayList<>();
        List<Integer> realCounts = new ArrayList<>();
        int from = 0;
        while (from < windowIndices.size()) {
            int to = Math.min(from + batchSize, windowIndices.size());
            List<int[]> batch = new ArrayList<>(windowIndices.subList(from, to));
            int realCount = batch.size();
            // Pad a partial batch up to the fixed batch size by repeating the last window,
            // then discard the padded outputs. Execution providers (e.g. CUDA) produce
            // slightly different floats per batch size, so a variable-size final batch
            // would make window outputs depend on request order and break determinism.
            while (batch.size() < batchSize) {
                batch.add(batch.get(realCount - 1));
            }
            batches.add(batch);
            realCounts.add(realCount);
            from = to;
        }

        // Strictly one batch at a time. Overlapping them does keep the GPU busier, but two
        // concurrent runs of one ONNX session return slightly different floats from run to run
        // (the execution provider picks kernels against whatever workspace is free at the time),
        // and terrain that changes between generations would seam against already-saved chunks.
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            runBatch(batches.get(batchIndex), realCounts.get(batchIndex));
        }
    }

    private void runBatch(List<int[]> batch, int realCount) {
        // arguments.get(depIdx) -> one tensor per window in the batch.
        List<List<FloatTensor>> arguments = new ArrayList<>(deps.length);
        for (int dependencyIndex = 0; dependencyIndex < deps.length; dependencyIndex++) {
            List<FloatTensor> dependencyArguments = new ArrayList<>(batch.size());
            for (int[] windowIndex : batch) {
                int[][] bounds = depWindows[dependencyIndex].getBounds(windowIndex);
                dependencyArguments.add(deps[dependencyIndex].getSlice(starts(bounds), ends(bounds)));
            }
            arguments.add(dependencyArguments);
        }

        List<FloatTensor> outputs = batchFunction.apply(batch, arguments);
        if (outputs == null || outputs.size() != batch.size()) {
            throw new IllegalStateException(
                    "Batch function for tensor '" + id + "' returned "
                            + (outputs == null ? "null" : outputs.size() + " outputs")
                            + ", expected " + batch.size());
        }

        for (int batchIndex = 0; batchIndex < realCount; batchIndex++) {
            FloatTensor result = outputs.get(batchIndex);
            int[] windowIndex = batch.get(batchIndex);
            validateOutputShape(result);
            store.cacheWindow(id, windowIndex, result);
        }
    }

    private void validateOutputShape(FloatTensor result) {
        if (result == null) {
            throw new IllegalStateException("Function for tensor '" + id + "' returned null");
        }

        int dimensions = outputWindow.size.length;
        if (result.shape.length != dimensions) {
            throw new IllegalStateException(
                    "Function for tensor '" + id + "' returned shape with "
                            + result.shape.length + " dims, expected " + dimensions);
        }
        for (int dimension = 0; dimension < dimensions; dimension++) {
            if (result.shape[dimension] != outputWindow.size[dimension]) {
                throw new IllegalStateException(
                        "Function for tensor '" + id + "' returned shape[" + dimension + "]="
                                + result.shape[dimension] + ", expected " + outputWindow.size[dimension]);
            }
        }
    }

    private void validateSliceBounds(int[] start, int[] end) {
        if (start == null || end == null || start.length != shape.length || end.length != shape.length) {
            throw new IllegalArgumentException(
                    "Tensor '" + id + "' requires " + shape.length + " start/end dimensions");
        }
        for (int dimension = 0; dimension < shape.length; dimension++) {
            if (end[dimension] <= start[dimension]) {
                throw new IllegalArgumentException(
                        "Tensor '" + id + "' has an empty or inverted slice in dimension " + dimension);
            }
            Integer dimensionSize = shape[dimension];
            if (dimensionSize != null && (start[dimension] < 0 || end[dimension] > dimensionSize)) {
                throw new IndexOutOfBoundsException(
                        "Tensor '" + id + "' slice exceeds bounded dimension " + dimension);
            }
        }
    }

    private List<int[]> collectIntersectingWindows(int[][] pixelRange) {
        List<int[]> windows = new ArrayList<>();
        int[] lowest = outputWindow.getLowestIntersection(pixelRange);
        int[] highest = outputWindow.getHighestIntersection(pixelRange);
        iterateWindows(lowest, highest, windows::add);
        return windows;
    }

    private static int[] starts(int[][] bounds) {
        int[] starts = new int[bounds.length];
        for (int dimension = 0; dimension < bounds.length; dimension++) {
            starts[dimension] = bounds[dimension][0];
        }
        return starts;
    }

    private static int[] ends(int[][] bounds) {
        int[] ends = new int[bounds.length];
        for (int dimension = 0; dimension < bounds.length; dimension++) {
            ends[dimension] = bounds[dimension][1];
        }
        return ends;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static int[][] buildRange(int[] start, int[] end) {
        int dimensions = start.length;
        int[][] range = new int[dimensions][2];
        for (int dimension = 0; dimension < dimensions; dimension++) {
            range[dimension][0] = start[dimension];
            range[dimension][1] = end[dimension];
        }
        return range;
    }

    /** Iterate over all window index combinations in the inclusive range [lo, hi]. */
    static void iterateWindows(int[] lo, int[] hi, WindowConsumer action) {
        int dimensions = lo.length;
        for (int dimension = 0; dimension < dimensions; dimension++) {
            if (lo[dimension] > hi[dimension]) {
                return;
            }
        }

        int[] current = lo.clone();
        outer:
        while (true) {
            action.accept(current.clone());

            // Increment like a mixed-radix counter, last dimension first.
            for (int dimension = dimensions - 1; dimension >= 0; dimension--) {
                current[dimension]++;
                if (current[dimension] <= hi[dimension]) {
                    break;
                }
                current[dimension] = lo[dimension];
                if (dimension == 0) {
                    break outer;
                }
            }
        }
    }

    @FunctionalInterface
    interface WindowConsumer {
        void accept(int[] windowIndex);
    }
}
