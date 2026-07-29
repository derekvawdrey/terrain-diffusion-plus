package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;

import java.util.Arrays;

/**
 * Deterministic drainage topology extracted from the generated DEM.
 *
 * <p>This class deliberately does not carve terrain. It computes drainage, downstream load,
 * a stable water level, and a smooth rasterised channel footprint. The footprint is produced
 * from cubic Hermite segments with deterministic low-frequency meandering. Detailed terrain
 * carving is handled by {@link DetailedRiverCarver}.</p>
 */
public final class FluvialRiverNetwork {
    private static final float SEA_LEVEL_METERS = 0.0f;
    private static final float MIN_VISIBLE_FLOW = 0.18f;
    private static final float RILL_FLOW = 0.08f;
    private static final float LAKE_MIN_DEPTH_M = 0.75f;
    private static final float MAX_CHANNEL_RADIUS_PX = 18.0f;
    private static final float EPS = 1e-5f;
    private static final int DIRECTION_SMOOTHING_PASSES = 3;
    private static final int RIVER_BIOME_DILATION_BLOCKS = 2;
    private static final int[] DR = {-1,-1,-1, 0,0, 1,1,1};
    private static final int[] DC = {-1, 0, 1,-1,1,-1,0,1};

    private FluvialRiverNetwork() {}

    public static int analysisPaddingPixels(float pixelSizeM) {
        float meters = Math.max(768.0f, pixelSizeM * 10.0f);
        int pad = (int) Math.ceil(meters / Math.max(1.0f, pixelSizeM));
        return Math.max(48, Math.min(192, pad));
    }

    public static RiverTopology build(long seed, int i0, int j0, float[] elevation, float[] climate,
                                      int height, int width, float pixelSizeM,
                                      boolean blockSourcesBelowElevation, float minimumSourceElevationM) {
        int n = Math.multiplyExact(height, width);
        if (elevation.length != n) {
            throw new IllegalArgumentException("elevation length does not match grid shape");
        }
        if (height <= 2 || width <= 2) {
            return RiverTopology.empty(height, width);
        }

        PriorityFlood flood = runPriorityFlood(elevation, height, width);
        float[] accumulation = accumulateRunoff(elevation, climate, flood.downstream, flood.order,
                flood.orderSize, height, width, pixelSizeM);
        boolean[] visible = selectVisibleNetwork(elevation, accumulation, flood.downstream, flood.order,
                flood.orderSize, height, width, blockSourcesBelowElevation, minimumSourceElevationM);

        float[] profile = new float[n];
        float[] load = new float[n];
        float[] lake = new float[n];
        float[] waterSurface = new float[n];
        Arrays.fill(waterSurface, Float.NaN);

        rasterizeLakes(elevation, flood.filledSurface, accumulation, lake, load, waterSurface);
        rasterizeHermiteChannels(seed, i0, j0, flood.filledSurface, flood.downstream, accumulation,
                visible, profile, load, waterSurface, height, width, pixelSizeM);
        smoothTopology(profile, load, waterSurface, height, width, 2);

        return new RiverTopology(profile, load, lake, waterSurface, height, width);
    }

    public static void applyRiverBiomesFromWindow(short[] biomes, float[] climate, RiverTopology rivers,
                                                   int row0, int col0, int height, int width) {
        int n = Math.multiplyExact(height, width);
        if (biomes == null || biomes.length != n || rivers == null) return;
        if (row0 < 0 || col0 < 0 || row0 + height > rivers.height() || col0 + width > rivers.width()) {
            throw new IllegalArgumentException("River biome crop is outside the source window");
        }

        // Biomes are sampled on Minecraft's quart grid. Dilating the exact water footprint by two
        // blocks ensures narrow sources still select river/frozen_river at nearby quart samples.
        int margin = RIVER_BIOME_DILATION_BLOCKS;
        int maskHeight = height + margin * 2;
        int maskWidth = width + margin * 2;
        boolean[] influence = new boolean[Math.multiplyExact(maskHeight, maskWidth)];
        for (int r = 0; r < maskHeight; r++) {
            int sourceRow = row0 + r - margin;
            if (sourceRow < 0 || sourceRow >= rivers.height()) continue;
            for (int c = 0; c < maskWidth; c++) {
                int sourceCol = col0 + c - margin;
                if (sourceCol < 0 || sourceCol >= rivers.width()) continue;
                influence[r * maskWidth + c] = Float.isFinite(
                        rivers.waterSurface()[sourceRow * rivers.width() + sourceCol]);
            }
        }
        for (int pass = 0; pass < margin; pass++) {
            boolean[] source = influence.clone();
            for (int r = 0; r < maskHeight; r++) {
                for (int c = 0; c < maskWidth; c++) {
                    int index = r * maskWidth + c;
                    if (source[index]) continue;
                    for (int k = 0; k < 8; k++) {
                        int nr = r + DR[k];
                        int nc = c + DC[k];
                        if (nr >= 0 && nr < maskHeight && nc >= 0 && nc < maskWidth
                                && source[nr * maskWidth + nc]) {
                            influence[index] = true;
                            break;
                        }
                    }
                }
            }
        }

        for (int r = 0; r < height; r++) {
            int targetOffset = r * width;
            int maskOffset = (r + margin) * maskWidth + margin;
            for (int c = 0; c < width; c++) {
                if (!influence[maskOffset + c]) continue;
                int targetIndex = targetOffset + c;
                short current = biomes[targetIndex];
                if (isOceanOrShore(current)) continue;
                float temp = climate != null && climate.length >= n ? climate[targetIndex] : 8.0f;
                biomes[targetIndex] = temp < 0.0f ? TerrainBiomeCatalog.FROZEN_RIVER : TerrainBiomeCatalog.RIVER;
            }
        }
    }

    public static byte encodeWaterMask(float channelProfile, float channelLoad, float lakeDepth) {
        float channel = clamp01(channelProfile) * (0.55f + 0.45f * clamp01(channelLoad));
        float lakeValue = clamp01(lakeDepth / 24.0f);
        float value = Math.max(channel, lakeValue);
        if (value <= 0.0f) return 0;
        return (byte) Math.max(1, Math.min(255, Math.round(value * 255.0f)));
    }

    private static boolean[] selectVisibleNetwork(float[] elevation, float[] accumulation, int[] downstream,
                                                   int[] order, int orderSize, int height, int width,
                                                   boolean blockLowSources, float minimumSourceElevationM) {
        int n = elevation.length;
        boolean[] candidate = new boolean[n];
        boolean[] hasCandidateUpstream = new boolean[n];
        for (int idx = 0; idx < n; idx++) {
            candidate[idx] = elevation[idx] > SEA_LEVEL_METERS && accumulation[idx] >= RILL_FLOW;
            if (candidate[idx] && downstream[idx] >= 0) {
                hasCandidateUpstream[downstream[idx]] = true;
            }
        }
        if (!blockLowSources) return candidate;

        boolean[] visible = new boolean[n];
        for (int idx = 0; idx < n; idx++) {
            if (!candidate[idx]) continue;
            int r = idx / width;
            int c = idx - r * width;
            boolean borderContinuation = r <= 1 || c <= 1 || r >= height - 2 || c >= width - 2;
            boolean source = !hasCandidateUpstream[idx];
            if (borderContinuation || (source && elevation[idx] >= minimumSourceElevationM)) {
                visible[idx] = true;
            }
        }
        // Priority-flood order reversed runs from headwaters toward outlets.
        for (int oi = orderSize - 1; oi >= 0; oi--) {
            int idx = order[oi];
            if (!visible[idx]) continue;
            int down = downstream[idx];
            if (down >= 0 && candidate[down]) visible[down] = true;
        }
        return visible;
    }

    private static void rasterizeLakes(float[] elevation, float[] filledSurface, float[] accumulation,
                                       float[] lake, float[] load, float[] waterSurface) {
        for (int idx = 0; idx < elevation.length; idx++) {
            if (elevation[idx] <= SEA_LEVEL_METERS) continue;
            float depth = filledSurface[idx] - elevation[idx];
            if (depth < LAKE_MIN_DEPTH_M) continue;
            float flowGate = clamp01(accumulation[idx] / MIN_VISIBLE_FLOW);
            if (flowGate <= 0.05f && depth < 3.0f) continue;
            lake[idx] = depth * (0.35f + 0.65f * flowGate);
            load[idx] = Math.max(load[idx], flowGate);
            waterSurface[idx] = filledSurface[idx];
        }
    }

    private static void rasterizeHermiteChannels(long seed, int i0, int j0, float[] surface, int[] downstream,
                                                  float[] accumulation, boolean[] visible,
                                                  float[] profile, float[] load, float[] waterSurface,
                                                  int height, int width, float pixelSizeM) {
        int n = height * width;
        float[] directionR = new float[n];
        float[] directionC = new float[n];
        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            int down = downstream[idx];
            if (down < 0 || !visible[down]) continue;
            int r = idx / width;
            int c = idx - r * width;
            int dr = down / width - r;
            int dc = down % width - c;
            float inv = invLength(dr, dc);
            directionR[idx] = dr * inv;
            directionC[idx] = dc * inv;
        }
        smoothDirections(directionR, directionC, visible, height, width);

        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            int down = downstream[idx];
            if (down < 0 || !visible[down]) {
                stampChannelPoint(seed, i0, j0, idx / width + 0.5f, idx % width + 0.5f,
                        surface[idx], accumulation[idx], 1.0f, profile, load, waterSurface,
                        height, width, pixelSizeM);
                continue;
            }

            int r0 = idx / width;
            int c0 = idx - r0 * width;
            int r1 = down / width;
            int c1 = down - r1 * width;
            float flow0 = accumulation[idx];
            float flow1 = accumulation[down];
            float normalized = clamp01((flow0 - RILL_FLOW) / (MIN_VISIBLE_FLOW * 18.0f));
            float meanderAmplitude = 0.10f + 0.95f * (1.0f - normalized);
            meanderAmplitude = Math.min(meanderAmplitude, 0.45f + radiusPixels(flow0, surface, idx, down,
                    width, pixelSizeM) * 0.22f);

            float off0 = coherentMeander(seed, i0 + r0, j0 + c0) * meanderAmplitude;
            float off1 = coherentMeander(seed, i0 + r1, j0 + c1) * meanderAmplitude;
            float p0r = r0 + 0.5f - directionC[idx] * off0;
            float p0c = c0 + 0.5f + directionR[idx] * off0;
            float p1r = r1 + 0.5f - directionC[down] * off1;
            float p1c = c1 + 0.5f + directionR[down] * off1;

            float segmentLength = (float) Math.hypot(p1r - p0r, p1c - p0c);
            float tangentLength = Math.max(0.55f, segmentLength * 0.85f);
            float m0r = directionR[idx] * tangentLength;
            float m0c = directionC[idx] * tangentLength;
            float m1r = directionR[down] * tangentLength;
            float m1c = directionC[down] * tangentLength;
            int samples = Math.max(5, (int) Math.ceil(segmentLength * 7.0f));
            for (int sample = 0; sample <= samples; sample++) {
                float t = sample / (float) samples;
                float t2 = t * t;
                float t3 = t2 * t;
                float h00 = 2 * t3 - 3 * t2 + 1;
                float h10 = t3 - 2 * t2 + t;
                float h01 = -2 * t3 + 3 * t2;
                float h11 = t3 - t2;
                float rr = h00 * p0r + h10 * m0r + h01 * p1r + h11 * m1r;
                float cc = h00 * p0c + h10 * m0c + h01 * p1c + h11 * m1c;
                float flow = lerp(flow0, flow1, t);
                float level = Math.min(surface[idx], lerp(surface[idx], surface[down], t));
                stampChannelPoint(seed, i0, j0, rr, cc, level, flow, 1.0f,
                        profile, load, waterSurface, height, width, pixelSizeM);
            }
        }
    }

    private static void stampChannelPoint(long seed, int i0, int j0, float centerR, float centerC,
                                          float surface, float flow, float strength,
                                          float[] profile, float[] load, float[] waterSurface,
                                          int height, int width, float pixelSizeM) {
        float normalizedLoad = clamp01((flow - RILL_FLOW) / (MIN_VISIBLE_FLOW * 24.0f));
        float radius = radiusPixels(flow, null, -1, -1, width, pixelSizeM);
        int minR = Math.max(0, (int) Math.floor(centerR - radius - 1));
        int maxR = Math.min(height - 1, (int) Math.ceil(centerR + radius + 1));
        int minC = Math.max(0, (int) Math.floor(centerC - radius - 1));
        int maxC = Math.min(width - 1, (int) Math.ceil(centerC + radius + 1));
        for (int r = minR; r <= maxR; r++) {
            for (int c = minC; c <= maxC; c++) {
                float distance = (float) Math.hypot((r + 0.5f) - centerR, (c + 0.5f) - centerC);
                if (distance > radius + 0.55f) continue;
                float x = clamp01(1.0f - distance / Math.max(0.55f, radius + 0.35f));
                float section = x * x * (3.0f - 2.0f * x) * strength;
                int target = r * width + c;
                if (section > profile[target]) {
                    profile[target] = section;
                    load[target] = Math.max(load[target], normalizedLoad);
                    waterSurface[target] = Float.isFinite(waterSurface[target])
                            ? Math.min(waterSurface[target], surface) : surface;
                }
            }
        }
    }

    private static float radiusPixels(float flow, float[] surface, int idx, int down, int width, float pixelSizeM) {
        float slope = 0.001f;
        if (surface != null && idx >= 0 && down >= 0) {
            int r = idx / width;
            int c = idx - r * width;
            int dr = Math.abs(down / width - r);
            int dc = Math.abs(down % width - c);
            float distanceM = (dr + dc == 2 ? 1.41421356f : 1.0f) * Math.max(1.0f, pixelSizeM);
            slope = Math.max(0.00005f, (surface[idx] - surface[down]) / distanceM);
        }
        float power = flow * (float) Math.sqrt(Math.max(0.0002f, slope));
        float widthM = 1.0f + 9.5f * (float) Math.pow(Math.max(0.0f, flow), 0.42)
                + 28.0f * (float) Math.pow(Math.max(0.0f, power), 0.30);
        float radius = widthM / Math.max(1.0f, pixelSizeM) * 0.5f;
        return Math.max(0.38f, Math.min(MAX_CHANNEL_RADIUS_PX, radius));
    }

    private static void smoothDirections(float[] directionR, float[] directionC, boolean[] visible,
                                         int height, int width) {
        for (int pass = 0; pass < DIRECTION_SMOOTHING_PASSES; pass++) {
            float[] srcR = directionR.clone();
            float[] srcC = directionC.clone();
            for (int r = 1; r < height - 1; r++) {
                for (int c = 1; c < width - 1; c++) {
                    int idx = r * width + c;
                    if (!visible[idx]) continue;
                    float sumR = srcR[idx] * 3.0f;
                    float sumC = srcC[idx] * 3.0f;
                    float weight = 3.0f;
                    for (int k = 0; k < 8; k++) {
                        int ni = (r + DR[k]) * width + c + DC[k];
                        if (!visible[ni]) continue;
                        sumR += srcR[ni];
                        sumC += srcC[ni];
                        weight += 1.0f;
                    }
                    float rr = sumR / weight;
                    float cc = sumC / weight;
                    float inv = invLength(rr, cc);
                    directionR[idx] = rr * inv;
                    directionC[idx] = cc * inv;
                }
            }
        }
    }

    private static void smoothTopology(float[] profile, float[] load, float[] waterSurface,
                                       int height, int width, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            float[] srcProfile = profile.clone();
            float[] srcLoad = load.clone();
            for (int r = 1; r < height - 1; r++) {
                for (int c = 1; c < width - 1; c++) {
                    int idx = r * width + c;
                    if (srcProfile[idx] <= 0.0f) continue;
                    float p = srcProfile[idx] * 4.0f;
                    float l = srcLoad[idx] * 4.0f;
                    float weight = 4.0f;
                    float minSurface = waterSurface[idx];
                    for (int k = 0; k < 8; k++) {
                        int ni = (r + DR[k]) * width + c + DC[k];
                        if (srcProfile[ni] <= 0.0f) continue;
                        p += srcProfile[ni];
                        l += srcLoad[ni];
                        weight += 1.0f;
                        if (Float.isFinite(waterSurface[ni])) {
                            minSurface = Float.isFinite(minSurface) ? Math.min(minSurface, waterSurface[ni]) : waterSurface[ni];
                        }
                    }
                    profile[idx] = Math.max(srcProfile[idx] * 0.82f, p / weight);
                    load[idx] = Math.max(srcLoad[idx], l / weight);
                    waterSurface[idx] = minSurface;
                }
            }
        }
    }

    private static PriorityFlood runPriorityFlood(float[] elevation, int height, int width) {
        int n = height * width;
        boolean[] visited = new boolean[n];
        float[] filled = new float[n];
        int[] downstream = new int[n];
        int[] order = new int[n];
        Arrays.fill(downstream, -1);
        IntMinHeap queue = new IntMinHeap(filled, n, height, width);
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int idx = r * width + c;
                boolean edge = r == 0 || c == 0 || r == height - 1 || c == width - 1;
                boolean ocean = elevation[idx] <= SEA_LEVEL_METERS;
                if ((!edge && !ocean) || visited[idx]) continue;
                visited[idx] = true;
                filled[idx] = elevation[idx];
                queue.add(idx);
            }
        }
        int orderSize = 0;
        while (!queue.isEmpty()) {
            int idx = queue.poll();
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
                queue.add(ni);
            }
        }
        return new PriorityFlood(filled, downstream, order, orderSize);
    }

    private static float[] accumulateRunoff(float[] elevation, float[] climate, int[] downstream,
                                             int[] order, int orderSize, int height, int width, float pixelSizeM) {
        int n = height * width;
        float[] accumulation = new float[n];
        float cellAreaKm2 = (pixelSizeM * pixelSizeM) / 1_000_000.0f;
        for (int idx = 0; idx < n; idx++) {
            accumulation[idx] = elevation[idx] <= SEA_LEVEL_METERS ? 0.0f
                    : localRunoff(idx, elevation[idx], climate, n) * cellAreaKm2;
        }
        for (int oi = orderSize - 1; oi >= 0; oi--) {
            int idx = order[oi];
            int down = downstream[idx];
            if (down >= 0) accumulation[down] += accumulation[idx];
        }
        return accumulation;
    }

    private static float localRunoff(int idx, float elevationM, float[] climate, int n) {
        if (climate == null || climate.length < 4 * n) {
            return 0.35f + 0.45f * clamp01(Math.max(0.0f, elevationM) / 2500.0f);
        }
        float temp = climate[idx];
        float precipMm = Math.max(0.0f, climate[2 * n + idx]);
        float precipCv = Math.max(0.0f, climate[3 * n + idx]);
        float altitude = Math.max(0.0f, elevationM);
        float runoffCoefficient = 0.18f + 0.28f * clamp01((precipMm - 350.0f) / 1650.0f);
        runoffCoefficient += 0.10f * clamp01(altitude / 3000.0f);
        runoffCoefficient += temp < 2.0f ? 0.08f : 0.0f;
        runoffCoefficient *= 1.0f + 0.18f * clamp01(precipCv / 120.0f);
        return Math.max(0.035f, precipMm / 1000.0f * runoffCoefficient);
    }

    private static boolean isOceanOrShore(short biome) {
        return biome == TerrainBiomeCatalog.WARM_OCEAN || biome == TerrainBiomeCatalog.LUKEWARM_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_LUKEWARM_OCEAN || biome == TerrainBiomeCatalog.OCEAN
                || biome == TerrainBiomeCatalog.DEEP_OCEAN || biome == TerrainBiomeCatalog.COLD_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_COLD_OCEAN || biome == TerrainBiomeCatalog.FROZEN_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_FROZEN_OCEAN || biome == TerrainBiomeCatalog.BEACH
                || biome == TerrainBiomeCatalog.SNOWY_BEACH || biome == TerrainBiomeCatalog.STONY_SHORE;
    }

    private static float coherentMeander(long seed, int i, int j) {
        float x = i * 0.035f;
        float y = j * 0.035f;
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        float fx = smoothstep(x - x0);
        float fy = smoothstep(y - y0);
        float a = hashUnit(seed, x0, y0);
        float b = hashUnit(seed, x0 + 1, y0);
        float c = hashUnit(seed, x0, y0 + 1);
        float d = hashUnit(seed, x0 + 1, y0 + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fy);
    }

    private static float hashUnit(long seed, int x, int y) {
        long value = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 40) / (float) (1L << 24)) * 2.0f - 1.0f;
    }

    private static int fastFloor(float value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static float smoothstep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float invLength(float r, float c) {
        float length = (float) Math.hypot(r, c);
        return length > EPS ? 1.0f / length : 0.0f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static final class IntMinHeap {
        private final float[] priorities;
        private final int maximumSize;
        private int[] heap;
        private int size;

        IntMinHeap(float[] priorities, int maximumSize, int height, int width) {
            this.priorities = priorities;
            this.maximumSize = maximumSize;
            this.heap = new int[Math.min(maximumSize, Math.max(1024, 4 * (height + width)))];
        }
        boolean isEmpty() { return size == 0; }
        void add(int index) {
            ensureCapacity(size + 1);
            int position = size++;
            while (position > 0) {
                int parent = (position - 1) >>> 1;
                int parentIndex = heap[parent];
                if (compare(parentIndex, index) <= 0) break;
                heap[position] = parentIndex;
                position = parent;
            }
            heap[position] = index;
        }
        int poll() {
            if (size == 0) throw new IllegalStateException("Cannot poll an empty heap");
            int result = heap[0];
            int replacement = heap[--size];
            if (size == 0) return result;
            int position = 0;
            int half = size >>> 1;
            while (position < half) {
                int left = (position << 1) + 1;
                int right = left + 1;
                int child = right < size && compare(heap[right], heap[left]) < 0 ? right : left;
                if (compare(replacement, heap[child]) <= 0) break;
                heap[position] = heap[child];
                position = child;
            }
            heap[position] = replacement;
            return result;
        }
        private int compare(int first, int second) {
            int byPriority = Float.compare(priorities[first], priorities[second]);
            return byPriority != 0 ? byPriority : Integer.compare(first, second);
        }
        private void ensureCapacity(int required) {
            if (required <= heap.length) return;
            if (required > maximumSize) throw new IllegalStateException("Hydrology heap exceeded grid size");
            heap = Arrays.copyOf(heap, Math.min(maximumSize,
                    Math.max(required, heap.length + Math.max(1024, heap.length >>> 1))));
        }
    }

    private record PriorityFlood(float[] filledSurface, int[] downstream, int[] order, int orderSize) {}

    public record RiverTopology(float[] channelProfile, float[] channelLoad, float[] lakeDepth,
                                float[] waterSurface, int height, int width) {
        static RiverTopology empty(int height, int width) {
            int n = height * width;
            float[] surface = new float[n];
            Arrays.fill(surface, Float.NaN);
            return new RiverTopology(new float[n], new float[n], new float[n], surface, height, width);
        }
    }
}
