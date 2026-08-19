package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

import java.util.Random;

/**
 * Standalone correctness check for {@link FluvialRiverNetwork#runPriorityFloodParallel} against
 * the reference {@link FluvialRiverNetwork#runPriorityFloodSequential}. Runs both on identical
 * synthetic terrain (rolling hills plus deliberately injected pits/ridges/an inland "lake" patch
 * of sub-sea-level cells, to stress depression-filling specifically) at several sizes chosen to
 * exercise different strip counts, and reports whether the fill surfaces match and whether the
 * parallel version's downstream/order output is internally consistent.
 *
 * <p>No Minecraft/ONNX needed. Run directly; exits with a non-zero status if any check fails.
 */
public class HydrologyFloodValidation {

    public static void main(String[] args) {
        int[] sizes = {50, 130, 256, 577, 1024, 2176};
        long[] seeds = {1L, 2L, 3L};
        boolean allPassed = true;
        for (int size : sizes) {
            for (long seed : seeds) {
                allPassed &= validate(size, size, seed);
            }
        }
        System.out.println();
        System.out.println(allPassed ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        if (!allPassed) {
            System.exit(1);
        }
    }

    private static boolean validate(int height, int width, long seed) {
        float[] elevation = syntheticElevation(height, width, seed);

        FluvialRiverNetwork.PriorityFlood sequential =
                FluvialRiverNetwork.runPriorityFloodSequential(elevation, height, width);
        if (!validateFastFlood(sequential, elevation, height, width, seed)) {
            return false;
        }
        FluvialRiverNetwork.PriorityFlood parallel =
                FluvialRiverNetwork.runPriorityFloodParallel(elevation, height, width);

        // Every interior land cell must drain somewhere. Comparing only the filled surface misses
        // the failure that matters: a flood can agree on filled values to the bit while leaving
        // most cells with downstream == -1, which severs accumulation and breaks rivers mid-channel.
        if (!validateNoOrphans("sequential", sequential, elevation, height, width, seed)) return false;
        if (!validateNoOrphans("fast", FluvialRiverNetwork.runPriorityFloodFast(elevation, height, width),
                elevation, height, width, seed)) return false;

        int n = height * width;
        float[] filledSeq = sequential.filledSurface();
        float[] filledPar = parallel.filledSurface();

        int mismatches = 0;
        int unsafeCount = 0;
        float maxDiff = 0f;
        int worstIdx = -1;
        for (int idx = 0; idx < n; idx++) {
            float diff = Math.abs(filledSeq[idx] - filledPar[idx]);
            if (diff > 1e-3f) {
                mismatches++;
                if (filledPar[idx] < filledSeq[idx] - 1e-3f) unsafeCount++;
                if (diff > maxDiff) {
                    maxDiff = diff;
                    worstIdx = idx;
                }
            }
        }

        int[] downstreamPar = parallel.downstream();
        int[] orderPar = parallel.order();
        int orderSizePar = parallel.orderSize();
        boolean orderComplete = orderSizePar == n;

        int[] orderPosition = new int[n];
        java.util.Arrays.fill(orderPosition, -1);
        for (int i = 0; i < orderSizePar; i++) {
            orderPosition[orderPar[i]] = i;
        }

        int badDownstreamCount = 0;
        int badOrderCount = 0;
        int badMonotonicCount = 0;
        for (int idx = 0; idx < n; idx++) {
            int d = downstreamPar[idx];
            if (d == idx) {
                badDownstreamCount++;
                continue;
            }
            if (d >= 0) {
                if (orderPosition[d] < 0 || orderPosition[idx] < 0 || orderPosition[d] >= orderPosition[idx]) {
                    badOrderCount++;
                }
                if (filledPar[d] > filledPar[idx] + 1e-3f) {
                    badMonotonicCount++;
                }
            }
        }

        boolean pass = mismatches == 0 && orderComplete && badDownstreamCount == 0
                && badOrderCount == 0 && badMonotonicCount == 0;
        System.out.printf(
                "size=%dx%d seed=%d: %s  (filled mismatches=%d [%d UNSAFE/too-low] maxDiff=%.5f@%d, "
                        + "orderComplete=%b, selfLoops=%d, badTopoOrder=%d, nonMonotonic=%d)%n",
                height, width, seed, pass ? "PASS" : "FAIL",
                mismatches, unsafeCount, maxDiff, worstIdx, orderComplete, badDownstreamCount, badOrderCount, badMonotonicCount);
        return pass;
    }

    /**
     * {@link FluvialRiverNetwork#runPriorityFloodFast} is a pure data-structure swap, so unlike
     * the parallel flood it must match the reference bit-for-bit: identical filled bits,
     * identical downstream cells, identical processing order.
     */
    private static boolean validateFastFlood(FluvialRiverNetwork.PriorityFlood sequential,
                                             float[] elevation, int height, int width, long seed) {
        FluvialRiverNetwork.PriorityFlood fast =
                FluvialRiverNetwork.runPriorityFloodFast(elevation, height, width);
        int n = height * width;
        int filledMismatches = 0;
        int downstreamMismatches = 0;
        int orderMismatches = 0;
        for (int idx = 0; idx < n; idx++) {
            if (Float.floatToIntBits(sequential.filledSurface()[idx])
                    != Float.floatToIntBits(fast.filledSurface()[idx])) filledMismatches++;
            if (sequential.downstream()[idx] != fast.downstream()[idx]) downstreamMismatches++;
            if (sequential.order()[idx] != fast.order()[idx]) orderMismatches++;
        }
        boolean pass = filledMismatches == 0 && downstreamMismatches == 0 && orderMismatches == 0
                && sequential.orderSize() == fast.orderSize();
        System.out.printf(
                "size=%dx%d seed=%d: fast-vs-sequential %s (filled=%d downstream=%d order=%d mismatches, "
                        + "orderSize %d vs %d)%n",
                height, width, seed, pass ? "EXACT" : "FAIL",
                filledMismatches, downstreamMismatches, orderMismatches,
                sequential.orderSize(), fast.orderSize());
        return pass;
    }

    /**
     * Every interior land cell must have somewhere to drain to. Only border cells and cells at or
     * below sea level may terminate a flow path; anything else is an orphan, and flow that reaches
     * it stops there instead of continuing downstream.
     */
    private static boolean validateNoOrphans(String label, FluvialRiverNetwork.PriorityFlood flood,
                                             float[] elevation, int height, int width, long seed) {
        FluvialRiverNetwork.resolveFlatDrainage(elevation, flood, height, width);
        int orphans = 0;
        int land = 0;
        for (int idx = 0; idx < height * width; idx++) {
            int r = idx / width;
            int c = idx - r * width;
            if (r == 0 || c == 0 || r == height - 1 || c == width - 1) continue;
            if (!(elevation[idx] > 0f)) continue;
            land++;
            if (flood.downstream()[idx] < 0) orphans++;
        }
        boolean pass = orphans == 0;
        System.out.printf("size=%dx%d seed=%d: %s orphaned interior land cells = %d of %d %s%n",
                height, width, seed, label, orphans, land, pass ? "OK" : "FAIL");
        return pass;
    }

    /** Rolling hills plus injected pits, ridges, and a sub-sea-level patch to exercise depression-filling. */
    private static float[] syntheticElevation(int height, int width, long seed) {
        FastNoiseLite noise = new FastNoiseLite((int) seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(5);
        noise.SetFractalLacunarity(2.0f);
        noise.SetFractalGain(0.5f);
        noise.SetFrequency(1f / 180f);

        float[] elevation = new float[height * width];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                float n = noise.GetNoise((float) c, (float) r);
                elevation[r * width + c] = 250f + 500f * (n * 0.5f + 0.5f);
            }
        }

        Random random = new Random(seed * 7919 + 1);
        int pitCount = Math.max(4, (height * width) / 5000);
        for (int i = 0; i < pitCount; i++) {
            int pr = random.nextInt(height);
            int pc = random.nextInt(width);
            int radius = 2 + random.nextInt(6);
            float depth = 50f + random.nextFloat() * 300f;
            for (int dr = -radius; dr <= radius; dr++) {
                int r = pr + dr;
                if (r < 0 || r >= height) continue;
                for (int dc = -radius; dc <= radius; dc++) {
                    int c = pc + dc;
                    if (c < 0 || c >= width) continue;
                    float dist = (float) Math.hypot(dr, dc);
                    if (dist > radius) continue;
                    int idx = r * width + c;
                    elevation[idx] -= depth * (1f - dist / radius);
                }
            }
        }

        // A patch of sub-sea-level cells away from the border, to exercise interior "ocean" seeds.
        if (height > 40 && width > 40) {
            int cy = height / 2;
            int cx = width / 2;
            int lakeRadius = Math.max(3, Math.min(height, width) / 20);
            for (int dr = -lakeRadius; dr <= lakeRadius; dr++) {
                int r = cy + dr;
                if (r < 0 || r >= height) continue;
                for (int dc = -lakeRadius; dc <= lakeRadius; dc++) {
                    int c = cx + dc;
                    if (c < 0 || c >= width) continue;
                    if (Math.hypot(dr, dc) > lakeRadius) continue;
                    elevation[r * width + c] = -5f;
                }
            }
        }

        return elevation;
    }
}
