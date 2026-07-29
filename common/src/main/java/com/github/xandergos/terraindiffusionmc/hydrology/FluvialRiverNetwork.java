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
    private static final float MIN_CHANNEL_RADIUS_PX = 0.26f;
    private static final float MAX_CHANNEL_RADIUS_PX = 16.0f;
    private static final float MAX_CENTERLINE_DISPLACEMENT_PX = 0.48f;
    private static final float EPS = 1e-5f;
    private static final float CHANNEL_SAMPLE_STEP_PX = 0.20f;
    private static final int RIVER_BIOME_DILATION_BLOCKS = 2;
    private static final int[] DR = {-1,-1,-1, 0,0, 1,1,1};
    private static final int[] DC = {-1, 0, 1,-1,1,-1,0,1};
    private static final float[] DISTANCE_PIXELS = {
            1.41421356f, 1.0f, 1.41421356f, 1.0f,
            1.0f, 1.41421356f, 1.0f, 1.41421356f
    };

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
        rasterizeHermiteChannels(seed, i0, j0, elevation, flood.filledSurface, flood.downstream, accumulation,
                visible, profile, load, waterSurface, height, width, pixelSizeM);

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

    private static void rasterizeHermiteChannels(long seed, int i0, int j0,
                                                  float[] elevation, float[] surface, int[] downstream,
                                                  float[] accumulation, boolean[] visible,
                                                  float[] profile, float[] load, float[] waterSurface,
                                                  int height, int width, float pixelSizeM) {
        int n = height * width;
        float[] radius = new float[n];
        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            radius[idx] = radiusPixels(accumulation[idx], elevation[idx]);
        }
        CenterlineGeometry geometry = smoothCenterlineGeometry(
                seed, i0, j0, surface, downstream, accumulation, visible,
                height, width, pixelSizeM);

        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            int down = downstream[idx];
            if (down < 0 || !visible[down]) {
                stampChannelPoint(geometry.row()[idx], geometry.col()[idx],
                        surface[idx], accumulation[idx], radius[idx],
                        profile, load, waterSurface, height, width);
                continue;
            }

            float flow0 = accumulation[idx];
            float flow1 = accumulation[down];
            float downstreamSurface = Math.min(surface[idx], surface[down]);
            float distance = (float) Math.hypot(
                    geometry.row()[down] - geometry.row()[idx],
                    geometry.col()[down] - geometry.col()[idx]);
            int samples = Math.max(1, (int) Math.ceil(distance / CHANNEL_SAMPLE_STEP_PX));
            for (int sample = 0; sample <= samples; sample++) {
                float t = sample / (float) samples;
                float rr = hermite(geometry.row()[idx], geometry.tangentRow()[idx],
                        geometry.row()[down], geometry.tangentRow()[down], t);
                float cc = hermite(geometry.col()[idx], geometry.tangentCol()[idx],
                        geometry.col()[down], geometry.tangentCol()[down], t);
                float flow = lerp(flow0, flow1, t);
                float level = lerp(surface[idx], downstreamSurface, t);
                float sectionRadius = lerp(radius[idx], radius[down], t);
                stampChannelPoint(rr, cc, level, flow, sectionRadius,
                        profile, load, waterSurface, height, width);
            }
        }
    }

    /**
     * Reproduce archive 29's constrained centre-line smoothing without its dense point sampling.
     * The two work buffers are reused as tangent storage after the third pass to limit allocation.
     */
    private static CenterlineGeometry smoothCenterlineGeometry(
            long seed, int i0, int j0, float[] surface, int[] downstream,
            float[] accumulation, boolean[] visible, int height, int width, float pixelSizeM) {
        int n = height * width;
        int[] dominantUpstream = new int[n];
        Arrays.fill(dominantUpstream, -1);
        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            int down = downstream[idx];
            if (down < 0 || !visible[down]) continue;
            int current = dominantUpstream[down];
            if (current < 0 || accumulation[idx] > accumulation[current]) {
                dominantUpstream[down] = idx;
            }
        }

        float[] rowA = new float[n];
        float[] colA = new float[n];
        float[] rowB = new float[n];
        float[] colB = new float[n];
        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            int row = idx / width;
            rowA[idx] = row + 0.5f;
            colA[idx] = idx - row * width + 0.5f;
        }

        float[] sourceRow = rowA;
        float[] sourceCol = colA;
        float[] targetRow = rowB;
        float[] targetCol = colB;
        for (int pass = 0; pass < 3; pass++) {
            for (int idx = 0; idx < n; idx++) {
                if (!visible[idx]) continue;
                float row = sourceRow[idx];
                float col = sourceCol[idx];
                int up = dominantUpstream[idx];
                int down = downstream[idx];
                if (up >= 0 && down >= 0 && visible[down]) {
                    row = sourceRow[idx] * 0.38f + sourceRow[up] * 0.31f + sourceRow[down] * 0.31f;
                    col = sourceCol[idx] * 0.38f + sourceCol[up] * 0.31f + sourceCol[down] * 0.31f;
                }
                setClampedCenter(targetRow, targetCol, idx, row, col, height, width);
            }
            float[] swap = sourceRow;
            sourceRow = targetRow;
            targetRow = swap;
            swap = sourceCol;
            sourceCol = targetCol;
            targetCol = swap;
        }

        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            int down = downstream[idx];
            if (down < 0 || !visible[down]) continue;
            float dr = sourceRow[down] - sourceRow[idx];
            float dc = sourceCol[down] - sourceCol[idx];
            float inverseLength = invLength(dr, dc);
            if (inverseLength <= 0.0f) continue;
            float slope = Math.max(channelSlope(surface, idx, down, width, pixelSizeM),
                    localTerrainSlope(surface, idx, height, width, pixelSizeM));
            float amplitude = 0.34f * (1.0f - smoothRange(0.0008f, 0.018f, slope));
            amplitude *= 0.45f + 0.55f * (1.0f - clamp01(accumulation[idx] / 8.0f));
            float noise = smoothValueNoise(seed, i0 + sourceRow[idx], j0 + sourceCol[idx], 10.0f);
            float row = sourceRow[idx] - dc * inverseLength * noise * amplitude;
            float col = sourceCol[idx] + dr * inverseLength * noise * amplitude;
            setClampedCenter(sourceRow, sourceCol, idx, row, col, height, width);
        }

        // After three passes targetRow/targetCol are the unused pair, so reuse them as tangents.
        Arrays.fill(targetRow, 0.0f);
        Arrays.fill(targetCol, 0.0f);
        for (int idx = 0; idx < n; idx++) {
            if (!visible[idx]) continue;
            int up = dominantUpstream[idx];
            int down = downstream[idx];
            boolean hasUp = up >= 0 && visible[up];
            boolean hasDown = down >= 0 && visible[down];
            if (hasUp && hasDown) {
                targetRow[idx] = (sourceRow[down] - sourceRow[up]) * 0.50f;
                targetCol[idx] = (sourceCol[down] - sourceCol[up]) * 0.50f;
            } else if (hasDown) {
                targetRow[idx] = sourceRow[down] - sourceRow[idx];
                targetCol[idx] = sourceCol[down] - sourceCol[idx];
            } else if (hasUp) {
                targetRow[idx] = sourceRow[idx] - sourceRow[up];
                targetCol[idx] = sourceCol[idx] - sourceCol[up];
            }
            clampVectorLength(targetRow, targetCol, idx, 1.35f);
        }
        return new CenterlineGeometry(sourceRow, sourceCol, targetRow, targetCol);
    }

    private static void setClampedCenter(float[] rows, float[] cols, int idx,
                                         float row, float col, int height, int width) {
        int originRowIndex = idx / width;
        float originRow = originRowIndex + 0.5f;
        float originCol = idx - originRowIndex * width + 0.5f;
        float dr = row - originRow;
        float dc = col - originCol;
        float inverseLength = invLength(dr, dc);
        if (inverseLength > 0.0f && 1.0f / inverseLength > MAX_CENTERLINE_DISPLACEMENT_PX) {
            float scale = MAX_CENTERLINE_DISPLACEMENT_PX * inverseLength;
            row = originRow + dr * scale;
            col = originCol + dc * scale;
        }
        rows[idx] = Math.max(0.5f, Math.min(height - 0.5f, row));
        cols[idx] = Math.max(0.5f, Math.min(width - 0.5f, col));
    }

    private static void clampVectorLength(float[] rows, float[] cols, int idx, float maximumLength) {
        float inverseLength = invLength(rows[idx], cols[idx]);
        if (inverseLength <= 0.0f || 1.0f / inverseLength <= maximumLength) return;
        float scale = maximumLength * inverseLength;
        rows[idx] *= scale;
        cols[idx] *= scale;
    }

    private static void stampChannelPoint(float centerR, float centerC, float surface, float flow,
                                          float radius, float[] profile, float[] load,
                                          float[] waterSurface, int height, int width) {
        int minR = Math.max(0, (int) Math.floor(centerR - radius - 0.75f));
        int maxR = Math.min(height - 1, (int) Math.ceil(centerR + radius + 0.75f));
        int minC = Math.max(0, (int) Math.floor(centerC - radius - 0.75f));
        int maxC = Math.min(width - 1, (int) Math.ceil(centerC + radius + 0.75f));
        float normalizedLoad = normalizedLoad(flow);
        for (int r = minR; r <= maxR; r++) {
            for (int c = minC; c <= maxC; c++) {
                float distance = (float) Math.hypot(
                        (r + 0.5f) - centerR, (c + 0.5f) - centerC);
                if (distance > radius + 0.50f) continue;
                float x = clamp01(1.0f - distance / Math.max(0.55f, radius + 0.35f));
                if (x <= 0.0f) continue;
                float section = smoothstep(x);
                int target = r * width + c;
                boolean strongerSection = section > profile[target] + EPS;
                boolean sameSectionWithMoreFlow = Math.abs(section - profile[target]) <= EPS
                        && normalizedLoad > load[target];
                if (strongerSection || sameSectionWithMoreFlow) {
                    profile[target] = section;
                    load[target] = Math.max(load[target], normalizedLoad);
                    waterSurface[target] = surface;
                }
            }
        }
    }

    private static float radiusPixels(float flow, float elevationM) {
        float normalizedLoad = normalizedLoad(flow);
        float highAltitude = smoothRange(650.0f, 2600.0f, elevationM);
        float headwater = 1.0f - normalizedLoad;
        float hydraulicRadius = MIN_CHANNEL_RADIUS_PX
                + 0.34f * (float) Math.pow(Math.max(1.0f, flow / RILL_FLOW), 0.34)
                + 0.90f * (float) Math.pow(Math.max(0.0f, flow), 0.28);
        hydraulicRadius *= 1.0f - 0.48f * highAltitude * headwater;
        float minimumRadius = MIN_CHANNEL_RADIUS_PX + 0.38f * (float) Math.sqrt(normalizedLoad);
        float projectedRadius = Math.max(minimumRadius, hydraulicRadius)
                * (1.75f + 0.50f * (float) Math.sqrt(normalizedLoad));
        return Math.min(MAX_CHANNEL_RADIUS_PX, projectedRadius);
    }

    private static float channelSlope(float[] surface, int idx, int down, int width, float pixelSizeM) {
        if (surface == null || idx < 0 || down < 0) return 0.001f;
        int r = idx / width;
        int c = idx - r * width;
        int dr = Math.abs(down / width - r);
        int dc = Math.abs(down % width - c);
        float distanceM = (dr + dc == 2 ? 1.41421356f : 1.0f) * Math.max(1.0f, pixelSizeM);
        return Math.max(0.00005f, (surface[idx] - surface[down]) / distanceM);
    }

    private static float localTerrainSlope(float[] surface, int idx, int height, int width, float pixelSizeM) {
        int r = idx / width;
        int c = idx - r * width;
        float maximum = 0.0f;
        for (int k = 0; k < 8; k++) {
            int nr = r + DR[k];
            int nc = c + DC[k];
            if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
            float distanceM = DISTANCE_PIXELS[k] * Math.max(1.0f, pixelSizeM);
            maximum = Math.max(maximum, Math.abs(surface[idx] - surface[nr * width + nc]) / distanceM);
        }
        return maximum;
    }

    private static float normalizedLoad(float flow) {
        return smoothRange(RILL_FLOW, MIN_VISIBLE_FLOW * 18.0f, flow);
    }

    private static float hermite(float p0, float m0, float p1, float m1, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return (2.0f * t3 - 3.0f * t2 + 1.0f) * p0
                + (t3 - 2.0f * t2 + t) * m0
                + (-2.0f * t3 + 3.0f * t2) * p1
                + (t3 - t2) * m1;
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

    private static float smoothValueNoise(long seed, float row, float col, float spacing) {
        return valueNoise(seed, row / spacing, col / spacing);
    }

    private static float valueNoise(long seed, float x, float y) {
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

    private static float smoothRange(float edge0, float edge1, float value) {
        if (value <= edge0) return 0.0f;
        if (value >= edge1) return 1.0f;
        return smoothstep((value - edge0) / (edge1 - edge0));
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

    private record CenterlineGeometry(float[] row, float[] col,
                                      float[] tangentRow, float[] tangentCol) {}

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
