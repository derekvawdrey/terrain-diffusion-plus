package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Deterministic fluvial post-process driven directly by the generated pipeline DEM and climate channels.
 *
 * <p>The implementation uses a priority-flood pass to create a hydrological surface. Depressions are not
 * discarded: they become filled basins/lakes whose outflow is routed through the lowest spillway. Flow
 * accumulation is then propagated along that drainage tree, making headwater rills grow into larger rivers
 * downstream and across confluences.</p>
 */
public final class FluvialRiverNetwork {
    private static final float SEA_LEVEL_METERS = 0.0f;
    private static final float MIN_VISIBLE_FLOW = 0.18f;
    private static final float RILL_FLOW = 0.08f;
    private static final float LAKE_MIN_DEPTH_M = 0.75f;
    private static final float MAX_LAKE_CARVE_DEPTH_M = 22.0f;
    private static final float MAX_CHANNEL_RADIUS_PX = 18.0f;
    private static final float EPS = 1e-5f;

    private static final int[] DR = {-1,-1,-1, 0,0, 1,1,1};
    private static final int[] DC = {-1, 0, 1,-1,1,-1,0,1};
    private static final float[] DIST = {
            1.41421356f, 1.0f, 1.41421356f, 1.0f, 1.0f, 1.41421356f, 1.0f, 1.41421356f
    };

    private FluvialRiverNetwork() {}

    /**
     * Padding used by callers that want a central crop without obvious drainage cut-offs at tile borders.
     */
    public static int analysisPaddingPixels(float pixelSizeM) {
        float meters = Math.max(768.0f, pixelSizeM * 10.0f);
        int pad = (int) Math.ceil(meters / Math.max(1.0f, pixelSizeM));
        return Math.max(48, Math.min(192, pad));
    }

    public static RiverResult build(int i0, int j0, float[] elevation, float[] climate, int height, int width, float pixelSizeM) {
        int n = height * width;
        if (elevation.length != n) {
            throw new IllegalArgumentException("elevation length does not match grid shape");
        }
        if (height <= 2 || width <= 2) {
            return RiverResult.empty(elevation, height, width);
        }

        PriorityFlood flood = runPriorityFlood(i0, j0, elevation, height, width);
        float[] accumulation = accumulateRunoff(elevation, climate, flood.downstream, flood.order, flood.orderSize,
                height, width, pixelSizeM);

        float[] adjustedElevation = elevation.clone();
        float[] river = new float[n];
        float[] lake = new float[n];
        float[] waterSurface = new float[n];
        Arrays.fill(waterSurface, Float.NaN);

        carveLakes(elevation, flood.filledSurface, accumulation, adjustedElevation, lake, waterSurface, height, width);
        carveChannels(elevation, flood.filledSurface, flood.downstream, accumulation, adjustedElevation, river,
                waterSurface, height, width, pixelSizeM);
        smoothChannelEdges(adjustedElevation, river, lake, height, width);

        return new RiverResult(adjustedElevation, river, lake, waterSurface, height, width);
    }

    public static void applyRiverBiomes(short[] biomes, float[] climate, RiverResult rivers, int height, int width) {
        int n = height * width;
        if (biomes == null || biomes.length != n || rivers == null) return;
        float[] river = rivers.riverStrength();
        float[] lake = rivers.lakeDepth();
        for (int idx = 0; idx < n; idx++) {
            if (river[idx] < 0.22f && lake[idx] < LAKE_MIN_DEPTH_M) continue;
            short current = biomes[idx];
            if (isOceanOrShore(current)) continue;
            float temp = climate != null && climate.length >= n ? climate[idx] : 8.0f;
            biomes[idx] = temp < -1.0f ? TerrainBiomeCatalog.FROZEN_RIVER : TerrainBiomeCatalog.RIVER;
        }
    }

    private static boolean isOceanOrShore(short biome) {
        return biome == TerrainBiomeCatalog.WARM_OCEAN
                || biome == TerrainBiomeCatalog.LUKEWARM_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_LUKEWARM_OCEAN
                || biome == TerrainBiomeCatalog.OCEAN
                || biome == TerrainBiomeCatalog.DEEP_OCEAN
                || biome == TerrainBiomeCatalog.COLD_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_COLD_OCEAN
                || biome == TerrainBiomeCatalog.FROZEN_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_FROZEN_OCEAN
                || biome == TerrainBiomeCatalog.BEACH
                || biome == TerrainBiomeCatalog.SNOWY_BEACH
                || biome == TerrainBiomeCatalog.STONY_SHORE;
    }

    private static PriorityFlood runPriorityFlood(int i0, int j0, float[] elevation, int height, int width) {
        int n = height * width;
        boolean[] visited = new boolean[n];
        float[] filled = new float[n];
        int[] downstream = new int[n];
        int[] order = new int[n];
        Arrays.fill(downstream, -1);
        PriorityQueue<Node> queue = new PriorityQueue<>();

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int idx = r * width + c;
                boolean edge = r == 0 || c == 0 || r == height - 1 || c == width - 1;
                boolean ocean = elevation[idx] <= SEA_LEVEL_METERS;
                if (!edge && !ocean) continue;
                if (visited[idx]) continue;
                visited[idx] = true;
                filled[idx] = elevation[idx];
                queue.add(new Node(idx, filled[idx], i0 + r, j0 + c));
            }
        }

        int orderSize = 0;
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            int idx = node.index;
            order[orderSize++] = idx;
            int r = idx / width;
            int c = idx - r * width;
            for (int k = 0; k < 8; k++) {
                int nr = r + DR[k];
                int nc = c + DC[k];
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                int ni = nr * width + nc;
                if (visited[ni]) continue;
                visited[ni] = true;
                downstream[ni] = idx;
                filled[ni] = Math.max(elevation[ni], filled[idx]);
                queue.add(new Node(ni, filled[ni], i0 + nr, j0 + nc));
            }
        }

        return new PriorityFlood(filled, downstream, order, orderSize);
    }

    private static float[] accumulateRunoff(float[] elevation, float[] climate, int[] downstream, int[] order, int orderSize,
                                             int height, int width, float pixelSizeM) {
        int n = height * width;
        float[] accumulation = new float[n];
        float cellAreaKm2 = (pixelSizeM * pixelSizeM) / 1_000_000.0f;
        for (int idx = 0; idx < n; idx++) {
            if (elevation[idx] <= SEA_LEVEL_METERS) {
                accumulation[idx] = 0.0f;
                continue;
            }
            accumulation[idx] = localRunoff(idx, elevation[idx], climate, n) * cellAreaKm2;
        }

        for (int oi = orderSize - 1; oi >= 0; oi--) {
            int idx = order[oi];
            int down = downstream[idx];
            if (down >= 0) {
                accumulation[down] += accumulation[idx];
            }
        }
        return accumulation;
    }

    private static float localRunoff(int idx, float elevationM, float[] climate, int n) {
        if (climate == null || climate.length < 4 * n) {
            float altitude = Math.max(0.0f, elevationM);
            return 0.35f + 0.45f * clamp01(altitude / 2500.0f);
        }
        float temp = climate[idx];
        float precipMm = Math.max(0.0f, climate[2 * n + idx]);
        float precipCv = Math.max(0.0f, climate[3 * n + idx]);
        float altitude = Math.max(0.0f, elevationM);

        float wetness = precipMm / 1000.0f;
        float runoffCoefficient = 0.18f + 0.28f * clamp01((precipMm - 350.0f) / 1650.0f);
        runoffCoefficient += 0.10f * clamp01(altitude / 3000.0f);
        runoffCoefficient += temp < 2.0f ? 0.08f : 0.0f;
        runoffCoefficient *= 1.0f + 0.18f * clamp01(precipCv / 120.0f);
        return Math.max(0.035f, wetness * runoffCoefficient);
    }

    private static void carveLakes(float[] elevation, float[] filledSurface, float[] accumulation,
                                   float[] adjusted, float[] lake, float[] waterSurface, int height, int width) {
        int n = height * width;
        for (int idx = 0; idx < n; idx++) {
            if (elevation[idx] <= SEA_LEVEL_METERS) continue;
            float lakeDepth = filledSurface[idx] - elevation[idx];
            if (lakeDepth < LAKE_MIN_DEPTH_M) continue;
            float basinWater = Math.max(lakeDepth, 0.0f);
            float flowGate = clamp01(accumulation[idx] / MIN_VISIBLE_FLOW);
            if (flowGate <= 0.05f && basinWater < 3.0f) continue;
            float visibleDepth = basinWater * (0.35f + 0.65f * flowGate);
            lake[idx] = Math.max(lake[idx], visibleDepth);
            waterSurface[idx] = filledSurface[idx];
            float bedDepth = Math.min(MAX_LAKE_CARVE_DEPTH_M, 1.5f + visibleDepth * 0.65f);
            adjusted[idx] = Math.min(adjusted[idx], filledSurface[idx] - bedDepth);
        }
    }

    private static void carveChannels(float[] elevation, float[] filledSurface, int[] downstream, float[] accumulation,
                                      float[] adjusted, float[] river, float[] waterSurface,
                                      int height, int width, float pixelSizeM) {
        int n = height * width;
        for (int idx = 0; idx < n; idx++) {
            if (elevation[idx] <= SEA_LEVEL_METERS) continue;
            float flow = accumulation[idx];
            if (flow < RILL_FLOW) continue;
            int down = downstream[idx];
            float slope = slopeToDownstream(idx, down, filledSurface, width, pixelSizeM);
            float power = flow * (float) Math.sqrt(Math.max(0.0002f, slope));
            float normalized = clamp01((flow - RILL_FLOW) / (MIN_VISIBLE_FLOW * 18.0f));
            float channelWidthM = 1.2f + 10.5f * (float) Math.pow(Math.max(0.0f, flow), 0.42)
                    + 36.0f * (float) Math.pow(Math.max(0.0f, power), 0.30);
            float radiusPx = Math.max(0.42f, channelWidthM / Math.max(1.0f, pixelSizeM) * 0.5f);
            radiusPx = Math.min(MAX_CHANNEL_RADIUS_PX, radiusPx);
            float depthM = 0.55f + 4.6f * (float) Math.pow(Math.max(0.0f, flow), 0.34)
                    + 3.0f * clamp01(slope * 350.0f);
            depthM = Math.min(38.0f, depthM);
            stampChannel(idx, filledSurface[idx], depthM, radiusPx, normalized, adjusted, river, waterSurface, height, width);
        }
    }

    private static float slopeToDownstream(int idx, int down, float[] filledSurface, int width, float pixelSizeM) {
        if (down < 0) return 0.001f;
        int r = idx / width, c = idx - r * width;
        int dr = Math.abs(down / width - r);
        int dc = Math.abs(down % width - c);
        float dist = (dr + dc == 2 ? 1.41421356f : 1.0f) * Math.max(1.0f, pixelSizeM);
        return Math.max(0.00005f, (filledSurface[idx] - filledSurface[down]) / dist);
    }

    private static void stampChannel(int center, float surface, float depthM, float radiusPx, float normalizedFlow,
                                     float[] adjusted, float[] river, float[] waterSurface, int height, int width) {
        int cr = center / width;
        int cc = center - cr * width;
        int rRadius = Math.max(1, (int) Math.ceil(radiusPx));
        float targetBed = surface - depthM;
        for (int r = Math.max(0, cr - rRadius); r <= Math.min(height - 1, cr + rRadius); r++) {
            for (int c = Math.max(0, cc - rRadius); c <= Math.min(width - 1, cc + rRadius); c++) {
                float d = (float) Math.sqrt((r - cr) * (r - cr) + (c - cc) * (c - cc));
                if (d > radiusPx + 0.5f) continue;
                int idx = r * width + c;
                float x = clamp01(1.0f - d / Math.max(0.5f, radiusPx + 0.35f));
                float crossSection = x * x * (3.0f - 2.0f * x);
                float localBed = adjusted[idx] + (targetBed - adjusted[idx]) * crossSection;
                adjusted[idx] = Math.min(adjusted[idx], localBed);
                river[idx] = Math.max(river[idx], Math.max(0.18f, normalizedFlow) * crossSection);
                if (Float.isNaN(waterSurface[idx]) || surface > waterSurface[idx]) waterSurface[idx] = surface;
            }
        }
    }

    private static void smoothChannelEdges(float[] adjusted, float[] river, float[] lake, int height, int width) {
        float[] src = adjusted.clone();
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                int idx = r * width + c;
                if (river[idx] < 0.08f && lake[idx] < LAKE_MIN_DEPTH_M) continue;
                float sum = 0.0f;
                float weight = 0.0f;
                for (int k = 0; k < 8; k++) {
                    int ni = (r + DR[k]) * width + (c + DC[k]);
                    float w = 1.0f / DIST[k];
                    sum += src[ni] * w;
                    weight += w;
                }
                float avg = sum / Math.max(EPS, weight);
                float blend = lake[idx] >= LAKE_MIN_DEPTH_M ? 0.12f : 0.18f * clamp01(river[idx]);
                adjusted[idx] = adjusted[idx] * (1.0f - blend) + avg * blend;
            }
        }
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private record Node(int index, float priority, int globalI, int globalJ) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            int byPriority = Float.compare(this.priority, other.priority);
            if (byPriority != 0) return byPriority;
            int byI = Integer.compare(this.globalI, other.globalI);
            if (byI != 0) return byI;
            return Integer.compare(this.globalJ, other.globalJ);
        }
    }

    private record PriorityFlood(float[] filledSurface, int[] downstream, int[] order, int orderSize) {}

    public record RiverResult(float[] adjustedElevation, float[] riverStrength, float[] lakeDepth,
                              float[] waterSurface, int height, int width) {
        static RiverResult empty(float[] elevation, int height, int width) {
            int n = height * width;
            float[] water = new float[n];
            Arrays.fill(water, Float.NaN);
            return new RiverResult(elevation.clone(), new float[n], new float[n], water, height, width);
        }

        public RiverResult crop(int row0, int col0, int cropHeight, int cropWidth) {
            return new RiverResult(
                    cropArray(adjustedElevation, width, row0, col0, cropHeight, cropWidth),
                    cropArray(riverStrength, width, row0, col0, cropHeight, cropWidth),
                    cropArray(lakeDepth, width, row0, col0, cropHeight, cropWidth),
                    cropArray(waterSurface, width, row0, col0, cropHeight, cropWidth),
                    cropHeight,
                    cropWidth
            );
        }

        public byte[] waterMaskBytes() {
            int n = height * width;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                float riverValue = clamp01(riverStrength[i]) * 255.0f;
                float lakeValue = clamp01(lakeDepth[i] / 24.0f) * 255.0f;
                out[i] = (byte) Math.max(0, Math.min(255, Math.round(Math.max(riverValue, lakeValue))));
            }
            return out;
        }

        private static float[] cropArray(float[] src, int srcWidth, int row0, int col0, int cropHeight, int cropWidth) {
            float[] out = new float[cropHeight * cropWidth];
            int srcHeight = src.length / srcWidth;
            for (int r = 0; r < cropHeight; r++) {
                int sr = Math.max(0, Math.min(srcHeight - 1, row0 + r));
                for (int c = 0; c < cropWidth; c++) {
                    int sc = Math.max(0, Math.min(srcWidth - 1, col0 + c));
                    out[r * cropWidth + c] = src[sr * srcWidth + sc];
                }
            }
            return out;
        }
    }
}
