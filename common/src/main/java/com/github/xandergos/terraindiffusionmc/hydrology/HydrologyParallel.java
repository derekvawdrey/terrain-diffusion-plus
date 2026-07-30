package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Dedicated bounded worker pool for deterministic hydrology and terrain post-processing passes.
 *
 * <p>Tasks only split ranges whose output cells are independent. Ordered drainage propagation
 * stays on the caller thread because parallelising those dependencies would change the result.</p>
 */
public final class HydrologyParallel {
    private static final int MIN_CELLS_PER_TASK = 32 * 1024;
    private static final int TASKS_PER_WORKER = 8;
    private static final int WORKER_THREADS = TerrainDiffusionConfig.hydrologyWorkerThreads();
    private static final ForkJoinPool POOL = createPool();

    private HydrologyParallel() {}

    public static int workerThreads() {
        return WORKER_THREADS;
    }

    public static boolean isEnabled() {
        return POOL != null;
    }

    public static void forEachIndex(int fromInclusive, int toExclusive, IntConsumer action) {
        invoke(fromInclusive, toExclusive, MIN_CELLS_PER_TASK, action);
    }

    public static void forEachRow(int fromInclusive, int toExclusive, int rowWidth, IntConsumer action) {
        int minimumRows = Math.max(1, MIN_CELLS_PER_TASK / Math.max(1, rowWidth));
        invoke(fromInclusive, toExclusive, minimumRows, action);
    }

    /**
     * Runs {@code taskCount} independent, individually expensive tasks in parallel (e.g. one
     * per grid strip). Unlike {@link #forEachIndex}/{@link #forEachRow}, this always splits down
     * to one task per leaf regardless of {@code taskCount} -- those assume many cheap cells and
     * would otherwise run a small task count (far below {@link #MIN_CELLS_PER_TASK}) sequentially.
     */
    public static void forEachTask(int taskCount, IntConsumer action) {
        invoke(0, taskCount, 1, action);
    }

    private static void invoke(int fromInclusive, int toExclusive, int minimumGrain, IntConsumer action) {
        if (toExclusive <= fromInclusive) return;
        int length = toExclusive - fromInclusive;
        if (POOL == null || length <= minimumGrain) {
            for (int index = fromInclusive; index < toExclusive; index++) action.accept(index);
            return;
        }
        int targetGrain = Math.max(minimumGrain,
                (length + WORKER_THREADS * TASKS_PER_WORKER - 1)
                        / (WORKER_THREADS * TASKS_PER_WORKER));
        POOL.invoke(new RangeAction(fromInclusive, toExclusive, targetGrain, action));
    }

    private static ForkJoinPool createPool() {
        if (WORKER_THREADS <= 1) return null;
        AtomicInteger workerIndex = new AtomicInteger();
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread worker =
                    ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("terrain-diffusion-hydrology-" + workerIndex.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
        return new ForkJoinPool(WORKER_THREADS, factory, null, false);
    }

    private static final class RangeAction extends RecursiveAction {
        private final int fromInclusive;
        private final int toExclusive;
        private final int grain;
        private final IntConsumer action;

        private RangeAction(int fromInclusive, int toExclusive, int grain, IntConsumer action) {
            this.fromInclusive = fromInclusive;
            this.toExclusive = toExclusive;
            this.grain = grain;
            this.action = action;
        }

        @Override
        protected void compute() {
            int length = toExclusive - fromInclusive;
            if (length <= grain) {
                for (int index = fromInclusive; index < toExclusive; index++) action.accept(index);
                return;
            }
            int middle = fromInclusive + (length >>> 1);
            invokeAll(
                    new RangeAction(fromInclusive, middle, grain, action),
                    new RangeAction(middle, toExclusive, grain, action));
        }
    }
}
