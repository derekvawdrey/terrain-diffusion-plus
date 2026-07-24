package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Resolves stable lake levels on a regional coarse grid before the fine fluvial pass.
 *
 * <p>A normal terrain request only sees a few hundred blocks around one generation tile.
 * That is not enough to find the spillway of a lake spanning thousands of blocks. This
 * resolver works on the model's coarse elevation map instead: each fixed macro cell owns
 * a large overlapping analysis window, so neighboring terrain tiles reuse the same basin
 * solution and therefore the same lake surface.</p>
 */
final class RegionalLakeLevelResolver {
    private static final int NATIVE_PIXELS_PER_COARSE_CELL = 256;
    private static final int MACRO_CORE_CELLS = 8;
    private static final int ANALYSIS_HALO_CELLS = 12;
    private static final int ANALYSIS_SIZE_CELLS = MACRO_CORE_CELLS + ANALYSIS_HALO_CELLS * 2;
    private static final int MAX_CACHED_MACROS = 64;
    private static final float MIN_COARSE_LAKE_DEPTH_M = 3.0f;
    private static final float EPSILON_METERS = 0.05f;
    private static final float SEA_LEVEL_METERS = 0.0f;

    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};

    private final WorldPipeline pipeline;
    private final Map<MacroKey, MacroLakeField> cache =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<MacroKey, MacroLakeField> eldest) {
                    return size() > MAX_CACHED_MACROS;
                }
            };

    RegionalLakeLevelResolver(WorldPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Samples regional lake surfaces for a block-space grid.
     *
     * @return one surface elevation in metres per cell, or {@link Float#NaN} outside
     *         a resolved regional basin
     */
    synchronized RegionalHydrologySample sampleBlockGrid(
            int blockI0, int blockJ0, int height, int width, int scale) {
        float[] lakeSurface = new float[height * width];
        byte[] lakeOutflowDirection = new byte[height * width];
        float[] flowAccumulation = new float[height * width];
        int[] coarseCellToken = new int[height * width];
        Arrays.fill(lakeSurface, Float.NaN);
        int safeScale = Math.max(1, scale);
        int nativeI0 = Math.floorDiv(blockI0, safeScale);
        int nativeJ0 = Math.floorDiv(blockJ0, safeScale);
        int nativeI1 = Math.floorDiv(blockI0 + Math.max(0, height - 1), safeScale);
        int nativeJ1 = Math.floorDiv(blockJ0 + Math.max(0, width - 1), safeScale);
        int firstCoarseI = Math.floorDiv(nativeI0, NATIVE_PIXELS_PER_COARSE_CELL);
        int firstCoarseJ = Math.floorDiv(nativeJ0, NATIVE_PIXELS_PER_COARSE_CELL);
        int lastCoarseI = Math.floorDiv(nativeI1, NATIVE_PIXELS_PER_COARSE_CELL);
        int lastCoarseJ = Math.floorDiv(nativeJ1, NATIVE_PIXELS_PER_COARSE_CELL);
        int coarseColumnCount = lastCoarseJ - firstCoarseJ + 1;
        int macroI0 = Math.floorDiv(firstCoarseI, MACRO_CORE_CELLS);
        int macroJ0 = Math.floorDiv(firstCoarseJ, MACRO_CORE_CELLS);
        int macroI1 = Math.floorDiv(lastCoarseI, MACRO_CORE_CELLS);
        int macroJ1 = Math.floorDiv(lastCoarseJ, MACRO_CORE_CELLS);
        Map<MacroKey, MacroLakeField> requestFields = new HashMap<>();
        for (int macroI = macroI0; macroI <= macroI1; macroI++) {
            for (int macroJ = macroJ0; macroJ <= macroJ1; macroJ++) {
                MacroKey key = new MacroKey(macroI, macroJ);
                requestFields.put(key, getOrBuild(key));
            }
        }

        for (int r = 0; r < height; r++) {
            int nativeI = Math.floorDiv(blockI0 + r, safeScale);
            int coarseI = Math.floorDiv(nativeI, NATIVE_PIXELS_PER_COARSE_CELL);
            int macroI = Math.floorDiv(coarseI, MACRO_CORE_CELLS);
            for (int c = 0; c < width; c++) {
                int nativeJ = Math.floorDiv(blockJ0 + c, safeScale);
                int coarseJ = Math.floorDiv(nativeJ, NATIVE_PIXELS_PER_COARSE_CELL);
                int macroJ = Math.floorDiv(coarseJ, MACRO_CORE_CELLS);
                MacroLakeField field = requestFields.get(new MacroKey(macroI, macroJ));
                int idx = r * width + c;
                lakeSurface[idx] = field.surfaceAt(coarseI, coarseJ);
                lakeOutflowDirection[idx] = field.outflowDirectionAt(coarseI, coarseJ);
                flowAccumulation[idx] = field.flowAccumulationAt(coarseI, coarseJ);
                coarseCellToken[idx] =
                        (coarseI - firstCoarseI) * coarseColumnCount + coarseJ - firstCoarseJ;
            }
        }
        return new RegionalHydrologySample(
                lakeSurface,
                lakeOutflowDirection,
                flowAccumulation,
                coarseCellToken,
                NATIVE_PIXELS_PER_COARSE_CELL * safeScale
        );
    }

    synchronized void clear() {
        cache.clear();
    }

    private MacroLakeField getOrBuild(MacroKey key) {
        MacroLakeField cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        MacroLakeField built = buildMacro(key);
        cache.put(key, built);
        return built;
    }

    private MacroLakeField buildMacro(MacroKey key) {
        int coreI0 = key.macroI * MACRO_CORE_CELLS;
        int coreJ0 = key.macroJ * MACRO_CORE_CELLS;
        int analysisI0 = coreI0 - ANALYSIS_HALO_CELLS;
        int analysisJ0 = coreJ0 - ANALYSIS_HALO_CELLS;

        FloatTensor coarse = pipeline.getCoarseSlice(
                analysisI0,
                analysisJ0,
                analysisI0 + ANALYSIS_SIZE_CELLS,
                analysisJ0 + ANALYSIS_SIZE_CELLS
        );
        float[] elevation = decodeCoarseElevation(coarse, ANALYSIS_SIZE_CELLS, ANALYSIS_SIZE_CELLS);
        CoarseFlood flood = priorityFlood(elevation, ANALYSIS_SIZE_CELLS, ANALYSIS_SIZE_CELLS);
        BasinResolution resolved = resolveBasinComponents(
                elevation, flood, ANALYSIS_SIZE_CELLS, ANALYSIS_SIZE_CELLS);
        float[] flowAccumulation = accumulateCoarseFlow(elevation, flood);

        float[] coreSurface = new float[MACRO_CORE_CELLS * MACRO_CORE_CELLS];
        byte[] coreOutflowDirection = new byte[MACRO_CORE_CELLS * MACRO_CORE_CELLS];
        float[] coreFlowAccumulation = new float[MACRO_CORE_CELLS * MACRO_CORE_CELLS];
        Arrays.fill(coreSurface, Float.NaN);
        for (int r = 0; r < MACRO_CORE_CELLS; r++) {
            int sourceRow = r + ANALYSIS_HALO_CELLS;
            System.arraycopy(
                    resolved.surface,
                    sourceRow * ANALYSIS_SIZE_CELLS + ANALYSIS_HALO_CELLS,
                    coreSurface,
                    r * MACRO_CORE_CELLS,
                    MACRO_CORE_CELLS
            );
            System.arraycopy(
                    resolved.outflowDirection,
                    sourceRow * ANALYSIS_SIZE_CELLS + ANALYSIS_HALO_CELLS,
                    coreOutflowDirection,
                    r * MACRO_CORE_CELLS,
                    MACRO_CORE_CELLS
            );
            System.arraycopy(
                    flowAccumulation,
                    sourceRow * ANALYSIS_SIZE_CELLS + ANALYSIS_HALO_CELLS,
                    coreFlowAccumulation,
                    r * MACRO_CORE_CELLS,
                    MACRO_CORE_CELLS
            );
        }
        return new MacroLakeField(
                coreI0, coreJ0, coreSurface, coreOutflowDirection, coreFlowAccumulation);
    }

    private static float[] decodeCoarseElevation(FloatTensor tensor, int height, int width) {
        int plane = height * width;
        float[] elevation = new float[plane];
        for (int idx = 0; idx < plane; idx++) {
            float weight = tensor.data[6 * plane + idx];
            float encoded = weight > 1e-8f ? tensor.data[idx] / weight : 0.0f;
            elevation[idx] = Math.signum(encoded) * encoded * encoded;
        }
        return elevation;
    }

    private static CoarseFlood priorityFlood(float[] elevation, int height, int width) {
        int n = height * width;
        boolean[] visited = new boolean[n];
        float[] filled = new float[n];
        int[] downstream = new int[n];
        int[] order = new int[n];
        int orderSize = 0;
        Arrays.fill(downstream, -1);
        PriorityQueue<Node> queue = new PriorityQueue<>();

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int idx = r * width + c;
                boolean edge = r == 0 || c == 0 || r == height - 1 || c == width - 1;
                boolean ocean = elevation[idx] <= SEA_LEVEL_METERS;
                if ((!edge && !ocean) || visited[idx]) {
                    continue;
                }
                visited[idx] = true;
                filled[idx] = elevation[idx];
                queue.add(new Node(idx, filled[idx]));
            }
        }

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            order[orderSize++] = node.index;
            int r = node.index / width;
            int c = node.index - r * width;
            for (int k = 0; k < 8; k++) {
                int nr = r + DR[k];
                int nc = c + DC[k];
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) {
                    continue;
                }
                int next = nr * width + nc;
                if (visited[next]) {
                    continue;
                }
                visited[next] = true;
                downstream[next] = node.index;
                filled[next] = Math.max(elevation[next], node.priority);
                queue.add(new Node(next, filled[next]));
            }
        }
        return new CoarseFlood(filled, downstream, order, orderSize);
    }

    private static float[] accumulateCoarseFlow(float[] elevation, CoarseFlood flood) {
        float[] accumulation = new float[elevation.length];
        for (int idx = 0; idx < elevation.length; idx++) {
            accumulation[idx] = elevation[idx] > SEA_LEVEL_METERS ? 1.0f : 0.0f;
        }
        for (int oi = flood.orderSize - 1; oi >= 0; oi--) {
            int idx = flood.order[oi];
            int down = flood.downstream[idx];
            if (down >= 0) {
                accumulation[down] += accumulation[idx];
            }
        }
        return accumulation;
    }

    /**
     * Gives every connected coarse depression its highest calculated surface.
     * Components touching the outer analysis edge are deliberately ignored because
     * their spillway may lie outside the available regional context.
     */
    private static BasinResolution resolveBasinComponents(
            float[] elevation, CoarseFlood flood, int height, int width) {
        int n = height * width;
        float[] filled = flood.filled;
        boolean[] candidate = new boolean[n];
        boolean[] visited = new boolean[n];
        int[] queue = new int[n];
        float[] result = new float[n];
        byte[] outflowDirection = new byte[n];
        Arrays.fill(result, Float.NaN);

        for (int idx = 0; idx < n; idx++) {
            candidate[idx] = elevation[idx] > SEA_LEVEL_METERS
                    && filled[idx] - elevation[idx] > EPSILON_METERS;
        }

        for (int start = 0; start < n; start++) {
            if (!candidate[start] || visited[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            boolean touchesAnalysisEdge = false;
            float highestSurface = filled[start];
            float deepestPoint = filled[start] - elevation[start];

            while (head < tail) {
                int idx = queue[head++];
                int r = idx / width;
                int c = idx - r * width;
                touchesAnalysisEdge |= r == 0 || c == 0 || r == height - 1 || c == width - 1;
                highestSurface = Math.max(highestSurface, filled[idx]);
                deepestPoint = Math.max(deepestPoint, filled[idx] - elevation[idx]);

                for (int k = 0; k < 8; k++) {
                    int nr = r + DR[k];
                    int nc = c + DC[k];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) {
                        continue;
                    }
                    int next = nr * width + nc;
                    if (candidate[next] && !visited[next]) {
                        visited[next] = true;
                        queue[tail++] = next;
                    }
                }
            }

            if (!touchesAnalysisEdge && deepestPoint >= MIN_COARSE_LAKE_DEPTH_M) {
                int outlet = -1;
                float outletBarrier = Float.POSITIVE_INFINITY;
                for (int i = 0; i < tail; i++) {
                    int idx = queue[i];
                    result[idx] = highestSurface;
                    int down = flood.downstream[idx];
                    if (down < 0 || candidate[down]) {
                        continue;
                    }
                    float barrier = Math.max(elevation[idx], elevation[down]);
                    if (barrier < outletBarrier) {
                        outlet = idx;
                        outletBarrier = barrier;
                    }
                }
                if (outlet >= 0) {
                    int down = flood.downstream[outlet];
                    int outletR = outlet / width;
                    int outletC = outlet - outletR * width;
                    int downR = down / width;
                    int downC = down - downR * width;
                    outflowDirection[outlet] = encodeDirection(
                            Integer.compare(downR, outletR),
                            Integer.compare(downC, outletC)
                    );
                }
            }
        }
        return new BasinResolution(result, outflowDirection);
    }

    private static byte encodeDirection(int dr, int dc) {
        for (int k = 0; k < DR.length; k++) {
            if (DR[k] == dr && DC[k] == dc) {
                return (byte) (k + 1);
            }
        }
        return 0;
    }

    record RegionalHydrologySample(
            float[] lakeSurface,
            byte[] lakeOutflowDirection,
            float[] flowAccumulation,
            int[] coarseCellToken,
            int coarseCellSpanBlocks) {
    }

    private record MacroKey(int macroI, int macroJ) {
    }

    private record MacroLakeField(
            int coarseI0, int coarseJ0, float[] surface, byte[] outflowDirection,
            float[] flowAccumulation) {
        float surfaceAt(int coarseI, int coarseJ) {
            int localI = coarseI - coarseI0;
            int localJ = coarseJ - coarseJ0;
            if (localI < 0 || localI >= MACRO_CORE_CELLS
                    || localJ < 0 || localJ >= MACRO_CORE_CELLS) {
                return Float.NaN;
            }
            return surface[localI * MACRO_CORE_CELLS + localJ];
        }

        byte outflowDirectionAt(int coarseI, int coarseJ) {
            int localI = coarseI - coarseI0;
            int localJ = coarseJ - coarseJ0;
            if (localI < 0 || localI >= MACRO_CORE_CELLS
                    || localJ < 0 || localJ >= MACRO_CORE_CELLS) {
                return 0;
            }
            return outflowDirection[localI * MACRO_CORE_CELLS + localJ];
        }

        float flowAccumulationAt(int coarseI, int coarseJ) {
            int localI = coarseI - coarseI0;
            int localJ = coarseJ - coarseJ0;
            if (localI < 0 || localI >= MACRO_CORE_CELLS
                    || localJ < 0 || localJ >= MACRO_CORE_CELLS) {
                return 0.0f;
            }
            return flowAccumulation[localI * MACRO_CORE_CELLS + localJ];
        }
    }

    private record CoarseFlood(float[] filled, int[] downstream, int[] order, int orderSize) {
    }

    private record BasinResolution(float[] surface, byte[] outflowDirection) {
    }

    private record Node(int index, float priority) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            return Float.compare(priority, other.priority);
        }
    }
}
