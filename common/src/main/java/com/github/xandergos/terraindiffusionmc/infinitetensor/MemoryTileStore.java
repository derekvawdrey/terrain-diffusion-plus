package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory factory and LRU cache for {@link InfiniteTensor} window outputs.
 *
 * <p>Each registered tensor owns an access-ordered cache. Cache entries can be
 * temporarily pinned by a {@link WindowLease} while a slice is assembled. LRU
 * eviction never removes pinned entries, so a cache limit smaller than the
 * requested slice can only cause temporary over-commit, never a partial result.
 */
public final class MemoryTileStore {

    /** Cache state per tensor id. Access to each state is synchronized on the state itself. */
    private final Map<String, CacheState> cacheStates = new HashMap<>();

    /** All registered tensor instances, by id. */
    private final Map<String, InfiniteTensor> tensors = new HashMap<>();

    /** Monotonic count of newly computed/cached windows across all tensors. */
    private final AtomicLong totalComputedWindowCount = new AtomicLong(0L);

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a non-batched InfiniteTensor, or returns the existing one if already
     * registered under {@code id}.
     */
    public synchronized InfiniteTensor getOrCreate(
            String id,
            Integer[] shape,
            TensorFunction function,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes) {

        InfiniteTensor existing = tensors.get(id);
        if (existing != null) {
            return existing;
        }

        InfiniteTensor tensor = new InfiniteTensor(
                id, shape, outputWindow, function, null, 0,
                deps, depWindows, this, cacheLimitBytes);
        register(id, tensor);
        return tensor;
    }

    /**
     * Creates a batched InfiniteTensor, or returns the existing one.
     *
     * @param batchSize maximum windows per {@link BatchTensorFunction} call
     */
    public synchronized InfiniteTensor getOrCreateBatched(
            String id,
            Integer[] shape,
            BatchTensorFunction batchFunction,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes,
            int batchSize) {

        InfiniteTensor existing = tensors.get(id);
        if (existing != null) {
            return existing;
        }

        InfiniteTensor tensor = new InfiniteTensor(
                id, shape, outputWindow, null, batchFunction, batchSize,
                deps, depWindows, this, cacheLimitBytes);
        register(id, tensor);
        return tensor;
    }

    private void register(String id, InfiniteTensor tensor) {
        tensors.put(id, tensor);
        cacheStates.put(id, new CacheState());
    }

    // -------------------------------------------------------------------------
    // Cache operations (called from InfiniteTensor)
    // -------------------------------------------------------------------------

    void cacheWindow(String id, int[] windowIndex, FloatTensor output) {
        CacheState state = requireState(id);
        List<Integer> key = toKey(windowIndex);

        synchronized (state) {
            CacheEntry existing = state.windows.get(key);
            if (existing != null) {
                // Access-order promotion. The computed duplicate is discarded.
                return;
            }

            state.windows.put(key, new CacheEntry(output));
            state.sizeBytes += output.byteSize();
            totalComputedWindowCount.incrementAndGet();
        }
    }

    /** Returns how many windows have been newly computed and cached. */
    public long getTotalComputedWindowCount() {
        return totalComputedWindowCount.get();
    }

    /**
     * Pins every requested window and returns a stable view of their tensors.
     * Returns {@code null} if at least one window is not currently cached.
     */
    WindowLease leaseWindows(String id, List<int[]> windowIndices) {
        CacheState state = requireState(id);
        LinkedHashMap<List<Integer>, FloatTensor> leased = new LinkedHashMap<>();
        List<CacheEntry> pinnedEntries = new ArrayList<>(windowIndices.size());

        synchronized (state) {
            for (int[] windowIndex : windowIndices) {
                List<Integer> key = toKey(windowIndex);
                CacheEntry entry = state.windows.get(key);
                if (entry == null) {
                    for (CacheEntry pinnedEntry : pinnedEntries) {
                        pinnedEntry.pinCount--;
                    }
                    return null;
                }
                entry.pinCount++;
                pinnedEntries.add(entry);
                leased.put(key, entry.tensor);
            }
        }

        return new WindowLease(id, state, leased, pinnedEntries);
    }

    void evictIfNeeded(String id, long limitBytes) {
        if (limitBytes == Long.MAX_VALUE) {
            return;
        }

        CacheState state = requireState(id);
        synchronized (state) {
            evictIfNeededLocked(state, limitBytes);
        }
    }

    boolean isWindowCached(String id, int[] windowIndex) {
        CacheState state = requireState(id);
        synchronized (state) {
            return state.windows.containsKey(toKey(windowIndex));
        }
    }

    /** Remove all cached window outputs for every registered tensor. */
    public void clearAllCaches() {
        List<CacheState> states;
        synchronized (this) {
            states = new ArrayList<>(cacheStates.values());
        }

        for (CacheState state : states) {
            synchronized (state) {
                // Active leases retain direct references to their tensors, so clearing the
                // lookup map cannot invalidate an already assembling slice.
                state.windows.clear();
                state.sizeBytes = 0L;
            }
        }
    }

    /** Remove a single tensor and all its cached state. */
    public synchronized void removeTensor(String id) {
        tensors.remove(id);
        CacheState state = cacheStates.remove(id);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.windows.clear();
            state.sizeBytes = 0L;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private synchronized CacheState requireState(String id) {
        CacheState state = cacheStates.get(id);
        if (state == null) {
            throw new IllegalStateException("Unknown tensor cache id: " + id);
        }
        return state;
    }

    private static void evictIfNeededLocked(CacheState state, long limitBytes) {
        if (state.sizeBytes <= limitBytes || state.windows.size() <= 1) {
            return;
        }

        Iterator<Map.Entry<List<Integer>, CacheEntry>> iterator = state.windows.entrySet().iterator();
        while (state.sizeBytes > limitBytes && state.windows.size() > 1 && iterator.hasNext()) {
            CacheEntry entry = iterator.next().getValue();
            if (entry.pinCount != 0) {
                continue;
            }
            state.sizeBytes -= entry.tensor.byteSize();
            iterator.remove();
        }
    }

    private static List<Integer> toKey(int[] windowIndex) {
        List<Integer> key = new ArrayList<>(windowIndex.length);
        for (int value : windowIndex) {
            key.add(value);
        }
        return key;
    }

    private static final class CacheState {
        private final LinkedHashMap<List<Integer>, CacheEntry> windows =
                new LinkedHashMap<>(16, 0.75f, true);
        private long sizeBytes;
    }

    private static final class CacheEntry {
        private final FloatTensor tensor;
        private int pinCount;
        private CacheEntry(FloatTensor tensor) {
            this.tensor = tensor;
        }
    }

    /** Stable set of pinned window values used while one slice is assembled. */
    final class WindowLease implements AutoCloseable {
        private final String tensorId;
        private final CacheState state;
        private final Map<List<Integer>, FloatTensor> windows;
        private final List<CacheEntry> pinnedEntries;
        private boolean closed;

        private WindowLease(
                String tensorId,
                CacheState state,
                Map<List<Integer>, FloatTensor> windows,
                List<CacheEntry> pinnedEntries) {
            this.tensorId = tensorId;
            this.state = state;
            this.windows = windows;
            this.pinnedEntries = pinnedEntries;
        }

        FloatTensor get(int[] windowIndex) {
            FloatTensor tensor = windows.get(toKey(windowIndex));
            if (tensor == null) {
                throw new IllegalStateException(
                        "Window " + toKey(windowIndex) + " is not part of the active lease for tensor '" + tensorId + "'");
            }
            return tensor;
        }

        @Override
        public void close() {
            synchronized (state) {
                if (closed) {
                    return;
                }
                closed = true;

                for (CacheEntry entry : pinnedEntries) {
                    entry.pinCount--;
                }

            }
        }
    }
}
