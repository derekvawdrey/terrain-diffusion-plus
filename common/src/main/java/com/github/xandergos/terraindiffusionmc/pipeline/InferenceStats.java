package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-model inference counters, split into the two costs that dominate a pipeline stage:
 * the {@code session.run} calls themselves and the GPU-slot session (re)creations that
 * {@code inference.offload_models=true} forces between stages.
 *
 * <p>Recording is a handful of atomic adds per model call, so it stays on in production;
 * {@link WorldGenBenchmark} and any diagnostic command read it through {@link #snapshot()}.
 */
public final class InferenceStats {

    private static final Map<String, Counters> COUNTERS = new ConcurrentHashMap<>();

    private InferenceStats() {
    }

    private static final class Counters {
        final AtomicLong runNanos = new AtomicLong();
        final AtomicLong runCount = new AtomicLong();
        final AtomicLong runElements = new AtomicLong();
        final AtomicLong sessionNanos = new AtomicLong();
        final AtomicLong sessionCount = new AtomicLong();
    }

    private static Counters counters(String model) {
        return COUNTERS.computeIfAbsent(model, key -> new Counters());
    }

    /** Records one {@code session.run} call. {@code batch} is the leading input dimension. */
    public static void recordRun(String model, long nanos, long batch) {
        Counters counters = counters(model);
        counters.runNanos.addAndGet(nanos);
        counters.runCount.incrementAndGet();
        counters.runElements.addAndGet(batch);
    }

    /** Records one GPU session creation (a model swap under {@code offload_models=true}). */
    public static void recordSessionCreate(String model, long nanos) {
        Counters counters = counters(model);
        counters.sessionNanos.addAndGet(nanos);
        counters.sessionCount.incrementAndGet();
    }

    public static void reset() {
        COUNTERS.clear();
    }

    /** Immutable view of one model's counters. */
    public record ModelStats(String model, long runNanos, long runCount, long batchedWindows,
                             long sessionNanos, long sessionCount) {
        public long totalNanos() {
            return runNanos + sessionNanos;
        }
    }

    /** Snapshot of every model seen so far, ordered by descending total time. */
    public static List<ModelStats> snapshot() {
        List<ModelStats> stats = new ArrayList<>(COUNTERS.size());
        COUNTERS.forEach((model, counters) -> stats.add(new ModelStats(
                model,
                counters.runNanos.get(),
                counters.runCount.get(),
                counters.runElements.get(),
                counters.sessionNanos.get(),
                counters.sessionCount.get())));
        stats.sort((left, right) -> Long.compare(right.totalNanos(), left.totalNanos()));
        return stats;
    }

    /** One-line-per-model summary, e.g. for a benchmark or a debug command. */
    public static String format() {
        StringBuilder builder = new StringBuilder();
        long total = 0L;
        for (ModelStats stats : snapshot()) {
            builder.append(String.format(
                    "  %-8s run %6d ms over %3d calls (%d windows)   sessionLoad %6d ms over %2d loads%n",
                    stats.model(),
                    stats.runNanos() / 1_000_000L, stats.runCount(), stats.batchedWindows(),
                    stats.sessionNanos() / 1_000_000L, stats.sessionCount()));
            total += stats.totalNanos();
        }
        builder.append(String.format("  %-8s %6d ms%n", "TOTAL", total / 1_000_000L));
        return builder.toString();
    }
}
