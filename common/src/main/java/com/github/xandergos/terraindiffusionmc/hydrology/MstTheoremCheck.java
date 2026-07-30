package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

import java.util.Arrays;
import java.util.Random;

/**
 * Sanity check for the "bottleneck spanning tree" property runPriorityFloodParallel's
 * pass-through mechanism relies on: for a single-source flood tree with monotonic filled[]
 * values, max(filled(x), filled(y)) should equal the true bottleneck cost between x and y.
 */
public class MstTheoremCheck {
    public static void main(String[] args) throws Exception {
        int height = 100, width = 100;
        FastNoiseLite noise = new FastNoiseLite(1);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(5);
        noise.SetFrequency(1f / 180f);
        float[] elevation = new float[height * width];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                elevation[r * width + c] = 250f + 500f * (noise.GetNoise((float) c, (float) r) * 0.5f + 0.5f);
            }
        }
        Random random = new Random(42);
        for (int i = 0; i < 10; i++) {
            int pr = random.nextInt(height), pc = random.nextInt(width), radius = 2 + random.nextInt(6);
            float depth = 50f + random.nextFloat() * 300f;
            for (int dr = -radius; dr <= radius; dr++) {
                int r = pr + dr;
                if (r < 0 || r >= height) continue;
                for (int dc = -radius; dc <= radius; dc++) {
                    int c = pc + dc;
                    if (c < 0 || c >= width) continue;
                    float dist = (float) Math.hypot(dr, dc);
                    if (dist > radius) continue;
                    elevation[r * width + c] -= depth * (1f - dist / radius);
                }
            }
        }

        java.lang.reflect.Method floodStrip = FluvialRiverNetwork.class.getDeclaredMethod(
                "floodStrip", float[].class, int.class, int.class, int.class, int.class,
                boolean.class, float[].class, float[].class, boolean[].class, float[].class, int[].class, int[].class);
        floodStrip.setAccessible(true);

        // Build the "mst" tree from an arbitrary single seed (row 0, col 0).
        int n = height * width;
        boolean[] mstVisited = new boolean[n];
        float[] mstFilled = new float[n];
        int[] mstDownstream = new int[n];
        float[] singleSeedRow = new float[width];
        Arrays.fill(singleSeedRow, Float.POSITIVE_INFINITY);
        singleSeedRow[0] = elevation[0];
        floodStrip.invoke(null, elevation, height, width, 0, height, false, singleSeedRow, null,
                mstVisited, mstFilled, mstDownstream, null);

        // Pick several (x, y) pairs and compare max(mstFilled(x), mstFilled(y)) against the true
        // bottleneck, computed directly by seeding a fresh flood from x alone and reading y.
        int[][] pairs = {{30, 40, 70, 60}, {10, 10, 90, 90}, {50, 5, 50, 95}, {20, 80, 80, 20}, {0, 50, 99, 50}};
        boolean anyMismatch = false;
        for (int[] pair : pairs) {
            int xr = pair[0], xc = pair[1], yr = pair[2], yc = pair[3];
            int x = xr * width + xc, y = yr * width + yc;

            boolean[] xVisited = new boolean[n];
            float[] xFilled = new float[n];
            int[] xDownstream = new int[n];
            float[] xSeedRow = new float[width];
            Arrays.fill(xSeedRow, Float.POSITIVE_INFINITY);
            // Seed exactly x by placing it on a "row" of a 1-row-tall flood window trick:
            // simplest is to seed via bottomSeedValues/topSeedValues only works on row boundaries,
            // so instead seed manually by prepopulating visited/filled/queue equivalent: run a
            // full-grid flood using isTrueSeed-free seeding at row xr only if xr is 0 or height-1;
            // otherwise, directly seed cell x using a 1-cell "row" trick is impossible generically,
            // so instead we special-case: reuse floodStrip with the single seed placed at row 0
            // by temporarily treating (xr,xc) AS row 0 via topSeedValues when xr==0, else fall back
            // to a manual seed loop replicating floodStrip's core (same algorithm, single seed x).
            manualSingleSeedFlood(elevation, height, width, x, xVisited, xFilled, xDownstream);

            float trueBottleneck = xFilled[y];
            float theorem = Math.max(mstFilled[x], mstFilled[y]);
            boolean match = Math.abs(trueBottleneck - theorem) < 1e-3f;
            if (!match) anyMismatch = true;
            System.out.printf("(%d,%d)-(%d,%d): trueBottleneck=%.5f theorem=max(mstFilled(x)=%.5f, mstFilled(y)=%.5f)=%.5f -> %s%n",
                    xr, xc, yr, yc, trueBottleneck, mstFilled[x], mstFilled[y], theorem, match ? "MATCH" : "MISMATCH");
        }
        System.out.println(anyMismatch ? "THEOREM CHECK: MISMATCH FOUND" : "THEOREM CHECK: ALL MATCH");
    }

    private static void manualSingleSeedFlood(float[] elevation, int height, int width, int seedIdx,
                                               boolean[] visited, float[] filled, int[] downstream) {
        int[] dr = {-1, -1, -1, 0, 0, 1, 1, 1};
        int[] dc = {-1, 0, 1, -1, 1, -1, 0, 1};
        java.util.PriorityQueue<Integer> queue = new java.util.PriorityQueue<>((a, b) -> Float.compare(filled[a], filled[b]));
        visited[seedIdx] = true;
        filled[seedIdx] = elevation[seedIdx];
        queue.add(seedIdx);
        Arrays.fill(downstream, -1);
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int r = idx / width, c = idx - r * width;
            for (int k = 0; k < 8; k++) {
                int nr = r + dr[k], nc = c + dc[k];
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                int ni = nr * width + nc;
                if (visited[ni]) continue;
                visited[ni] = true;
                downstream[ni] = idx;
                filled[ni] = Math.max(elevation[ni], filled[idx]);
                queue.add(ni);
            }
        }
    }
}
