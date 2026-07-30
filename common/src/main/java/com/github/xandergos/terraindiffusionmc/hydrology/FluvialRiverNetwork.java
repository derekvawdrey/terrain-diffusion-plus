package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeSettlement;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(FluvialRiverNetwork.class);
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
    private static final int CHANNEL_LOCK_COUNT = 4096;
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

        long t0 = System.nanoTime();
        // TODO: switch to runPriorityFloodParallel once it passes HydrologyFloodValidation --
        // it currently has a confirmed correctness bug (adjacent strips can mutually reinforce
        // a boundary estimate into a 2-cycle). Reverted to the sequential reference pending a fix.
        PriorityFlood flood = runPriorityFloodSequential(elevation, height, width);
        long t1 = System.nanoTime();
        float[] accumulation = accumulateRunoff(elevation, climate, flood.downstream, flood.order,
                flood.orderSize, height, width, pixelSizeM);
        long t2 = System.nanoTime();
        boolean[] visible = selectVisibleNetwork(elevation, accumulation, flood.downstream, flood.order,
                flood.orderSize, height, width, i0, j0, blockSourcesBelowElevation, minimumSourceElevationM);
        long t3 = System.nanoTime();

        float[] profile = new float[n];
        float[] load = new float[n];
        float[] lake = new float[n];
        float[] waterSurface = new float[n];
        Arrays.fill(waterSurface, Float.NaN);

        rasterizeLakes(elevation, flood.filledSurface, accumulation, lake, load, waterSurface);
        long t4 = System.nanoTime();
        rasterizeHermiteChannels(seed, i0, j0, elevation, flood.filledSurface, flood.downstream, accumulation,
                visible, profile, load, waterSurface, height, width, pixelSizeM);
        long t5 = System.nanoTime();

        LOG.info("FluvialRiverNetwork.build ({}, {}) n={} phases (ms): priorityFlood={} accumulateRunoff={} "
                        + "selectVisibleNetwork={} rasterizeLakes={} rasterizeChannels={} total={}",
                j0, i0, n, millis(t0, t1), millis(t1, t2), millis(t2, t3), millis(t3, t4), millis(t4, t5),
                millis(t0, t5));

        return new RiverTopology(profile, load, lake, waterSurface, height, width);
    }

    private static long millis(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000L;
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
        HydrologyParallel.forEachRow(0, maskHeight, maskWidth, r -> {
            int sourceRow = row0 + r - margin;
            if (sourceRow < 0 || sourceRow >= rivers.height()) return;
            for (int c = 0; c < maskWidth; c++) {
                int sourceCol = col0 + c - margin;
                if (sourceCol < 0 || sourceCol >= rivers.width()) continue;
                influence[r * maskWidth + c] = Float.isFinite(
                        rivers.waterSurface()[sourceRow * rivers.width() + sourceCol]);
            }
        });
        for (int pass = 0; pass < margin; pass++) {
            boolean[] source = influence.clone();
            HydrologyParallel.forEachRow(0, maskHeight, maskWidth, r -> {
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
            });
        }

        HydrologyParallel.forEachRow(0, height, width, r -> {
            int targetOffset = r * width;
            int maskOffset = (r + margin) * maskWidth + margin;
            for (int c = 0; c < width; c++) {
                if (!influence[maskOffset + c]) continue;
                int targetIndex = targetOffset + c;
                short current = biomes[targetIndex];
                if (isOceanOrShore(current)) continue;
                float temp = climate != null && climate.length >= n ? climate[targetIndex] : 8.0f;
                biomes[targetIndex] = temp < 0.0f ? TerrainBiomeRegistry.instance().frozenRiverBiomeIndex() : TerrainBiomeRegistry.instance().riverBiomeIndex();
            }
        });
    }

    public static byte encodeWaterMask(float channelProfile, float channelLoad, float lakeDepth) {
        float channel = clamp01(channelProfile) * (0.55f + 0.45f * clamp01(channelLoad));
        float lakeValue = clamp01(lakeDepth / 24.0f);
        float value = Math.max(channel, lakeValue);
        if (value <= 0.0f) return 0;
        return (byte) Math.max(1, Math.min(255, Math.round(value * 255.0f)));
    }

    /**
     * Floor on the region river-density multiplier. Rivers are never fully disabled by the
     * region gate (avoids a hard visible cutoff at the noise field's boundary); at this floor
     * the effective flow threshold is ~8x normal, so only the largest trunk rivers survive.
     */
    private static final float MIN_RIVER_DENSITY = 0.12f;

    /**
     * Large-wavelength multiplier (0..1) on how easily a cell qualifies as a river, sampled
     * from the same region-noise field {@link BiomeClassifier} uses to gate rare biomes. Low
     * values raise the effective {@link #RILL_FLOW} threshold, thinning rivers out smoothly
     * across whole regions instead of per-cell.
     */
    private static float riverDensity(float worldX, float worldZ) {
        float normalized = clamp01((BiomeClassifier.sampleRegionNoise(worldX, worldZ) + 1f) * 0.5f);
        float smooth = normalized * normalized * (3f - 2f * normalized);
        return MIN_RIVER_DENSITY + (1f - MIN_RIVER_DENSITY) * smooth;
    }

    /** Grid spacing (pixels) between direct {@link #riverDensity} samples; see {@link RiverDensityField}. */
    private static final int REGION_DENSITY_STRIDE = 64;

    /**
     * {@link #riverDensity} is driven by {@code BiomeClassifier}'s region-noise field, which only
     * has a ~5000-block wavelength (see {@code BiomeClassifier.REGION_NOISE}). Evaluating that
     * noise once per native pixel is tens of millions of wasted FastNoiseLite calls per tile for a
     * value that barely changes over thousands of pixels. This samples it on a coarse grid instead
     * and bilinearly interpolates, matching the coarse-grid pattern used elsewhere in the pipeline
     * (e.g. {@code LaplacianUtils.localBaselineTemperature}).
     */
    private static final class RiverDensityField {
        private final float[] values;
        private final int coarseWidth;
        private final int coarseHeight;

        private RiverDensityField(float[] values, int coarseWidth, int coarseHeight) {
            this.values = values;
            this.coarseWidth = coarseWidth;
            this.coarseHeight = coarseHeight;
        }

        static RiverDensityField build(int i0, int j0, int height, int width) {
            int coarseWidth = (width - 1) / REGION_DENSITY_STRIDE + 2;
            int coarseHeight = (height - 1) / REGION_DENSITY_STRIDE + 2;
            float[] values = new float[coarseWidth * coarseHeight];
            HydrologyParallel.forEachIndex(0, values.length, idx -> {
                int cr = idx / coarseWidth;
                int cc = idx - cr * coarseWidth;
                float worldX = j0 + cc * (float) REGION_DENSITY_STRIDE;
                float worldZ = i0 + cr * (float) REGION_DENSITY_STRIDE;
                values[idx] = riverDensity(worldX, worldZ);
            });
            return new RiverDensityField(values, coarseWidth, coarseHeight);
        }

        float sample(int r, int c) {
            float gy = r / (float) REGION_DENSITY_STRIDE;
            float gx = c / (float) REGION_DENSITY_STRIDE;
            int y0 = (int) gy;
            int x0 = (int) gx;
            float fy = gy - y0;
            float fx = gx - x0;
            int y1 = Math.min(y0 + 1, coarseHeight - 1);
            int x1 = Math.min(x0 + 1, coarseWidth - 1);
            float v00 = values[y0 * coarseWidth + x0];
            float v01 = values[y0 * coarseWidth + x1];
            float v10 = values[y1 * coarseWidth + x0];
            float v11 = values[y1 * coarseWidth + x1];
            float top = v00 + (v01 - v00) * fx;
            float bottom = v10 + (v11 - v10) * fx;
            return top + (bottom - top) * fy;
        }
    }

    private static boolean[] selectVisibleNetwork(float[] elevation, float[] accumulation, int[] downstream,
                                                   int[] order, int orderSize, int height, int width,
                                                   int i0, int j0,
                                                   boolean blockLowSources, float minimumSourceElevationM) {
        int n = elevation.length;
        boolean[] candidate = new boolean[n];
        boolean[] hasCandidateUpstream = new boolean[n];
        RiverDensityField density = RiverDensityField.build(i0, j0, height, width);
        HydrologyParallel.forEachIndex(0, n, idx -> {
            int r = idx / width;
            int c = idx - r * width;
            float threshold = RILL_FLOW / density.sample(r, c);
            candidate[idx] = elevation[idx] > SEA_LEVEL_METERS && accumulation[idx] >= threshold;
        });
        HydrologyParallel.forEachIndex(0, n, idx -> {
            if (candidate[idx] && downstream[idx] >= 0) {
                // Concurrent writes are idempotent: every writer stores the same true value.
                hasCandidateUpstream[downstream[idx]] = true;
            }
        });
        if (!blockLowSources) return candidate;

        boolean[] visible = new boolean[n];
        HydrologyParallel.forEachIndex(0, n, idx -> {
            if (!candidate[idx]) return;
            int r = idx / width;
            int c = idx - r * width;
            boolean borderContinuation = r <= 1 || c <= 1 || r >= height - 2 || c >= width - 2;
            boolean source = !hasCandidateUpstream[idx];
            if (borderContinuation || (source && elevation[idx] >= minimumSourceElevationM)) {
                visible[idx] = true;
            }
        });
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
        HydrologyParallel.forEachIndex(0, elevation.length, idx -> {
            if (elevation[idx] <= SEA_LEVEL_METERS) return;
            float depth = filledSurface[idx] - elevation[idx];
            if (depth < LAKE_MIN_DEPTH_M) return;
            float flowGate = clamp01(accumulation[idx] / MIN_VISIBLE_FLOW);
            if (flowGate <= 0.05f && depth < 3.0f) return;
            lake[idx] = depth * (0.35f + 0.65f * flowGate);
            load[idx] = Math.max(load[idx], flowGate);
            waterSurface[idx] = filledSurface[idx];
        });
    }

    private static void rasterizeHermiteChannels(long seed, int i0, int j0,
                                                  float[] elevation, float[] surface, int[] downstream,
                                                  float[] accumulation, boolean[] visible,
                                                  float[] profile, float[] load, float[] waterSurface,
                                                  int height, int width, float pixelSizeM) {
        int n = height * width;
        long t0 = System.nanoTime();
        float[] radius = new float[n];
        HydrologyParallel.forEachIndex(0, n, idx -> {
            if (!visible[idx]) return;
            radius[idx] = radiusPixels(accumulation[idx], elevation[idx]);
        });
        long t1 = System.nanoTime();
        CenterlineGeometry geometry = smoothCenterlineGeometry(
                seed, i0, j0, surface, downstream, accumulation, visible,
                height, width, pixelSizeM);
        long t2 = System.nanoTime();

        Object[] channelLocks = null;
        if (HydrologyParallel.isEnabled()) {
            channelLocks = new Object[CHANNEL_LOCK_COUNT];
            for (int index = 0; index < channelLocks.length; index++) {
                channelLocks[index] = new Object();
            }
        }
        Object[] locks = channelLocks;
        HydrologyParallel.forEachIndex(0, n, idx -> rasterizeChannelSegment(
                idx, surface, downstream, accumulation, visible, radius, geometry,
                profile, load, waterSurface, height, width, locks));
        long t3 = System.nanoTime();

        int visibleCount = 0;
        for (int idx = 0; idx < n; idx++) {
            if (visible[idx]) visibleCount++;
        }
        LOG.info("FluvialRiverNetwork.rasterizeHermiteChannels ({}, {}) n={} visible={} phases (ms): "
                        + "radius={} centerlineGeometry={} stampChannels={} total={}",
                j0, i0, n, visibleCount, millis(t0, t1), millis(t1, t2), millis(t2, t3), millis(t0, t3));
    }

    private static void rasterizeChannelSegment(
            int idx, float[] surface, int[] downstream, float[] accumulation, boolean[] visible,
            float[] radius, CenterlineGeometry geometry, float[] profile, float[] load,
            float[] waterSurface, int height, int width, Object[] locks) {
        if (!visible[idx]) return;
        int down = downstream[idx];
        if (down < 0 || !visible[down]) {
            stampChannelPoint(geometry.row()[idx], geometry.col()[idx],
                    surface[idx], accumulation[idx], radius[idx],
                    profile, load, waterSurface, height, width, locks);
            return;
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
                    profile, load, waterSurface, height, width, locks);
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
        HydrologyParallel.forEachIndex(0, n, idx -> {
            if (!visible[idx]) return;
            int row = idx / width;
            rowA[idx] = row + 0.5f;
            colA[idx] = idx - row * width + 0.5f;
        });

        float[] sourceRow = rowA;
        float[] sourceCol = colA;
        float[] targetRow = rowB;
        float[] targetCol = colB;
        for (int pass = 0; pass < 3; pass++) {
            float[] passSourceRow = sourceRow;
            float[] passSourceCol = sourceCol;
            float[] passTargetRow = targetRow;
            float[] passTargetCol = targetCol;
            HydrologyParallel.forEachIndex(0, n, idx -> {
                if (!visible[idx]) return;
                float row = passSourceRow[idx];
                float col = passSourceCol[idx];
                int up = dominantUpstream[idx];
                int down = downstream[idx];
                if (up >= 0 && down >= 0 && visible[down]) {
                    row = passSourceRow[idx] * 0.38f
                            + passSourceRow[up] * 0.31f + passSourceRow[down] * 0.31f;
                    col = passSourceCol[idx] * 0.38f
                            + passSourceCol[up] * 0.31f + passSourceCol[down] * 0.31f;
                }
                setClampedCenter(passTargetRow, passTargetCol, idx, row, col, height, width);
            });
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
        float[] finalSourceRow = sourceRow;
        float[] finalSourceCol = sourceCol;
        float[] tangentRow = targetRow;
        float[] tangentCol = targetCol;
        HydrologyParallel.forEachIndex(0, n, idx -> {
            if (!visible[idx]) return;
            int up = dominantUpstream[idx];
            int down = downstream[idx];
            boolean hasUp = up >= 0 && visible[up];
            boolean hasDown = down >= 0 && visible[down];
            if (hasUp && hasDown) {
                tangentRow[idx] = (finalSourceRow[down] - finalSourceRow[up]) * 0.50f;
                tangentCol[idx] = (finalSourceCol[down] - finalSourceCol[up]) * 0.50f;
            } else if (hasDown) {
                tangentRow[idx] = finalSourceRow[down] - finalSourceRow[idx];
                tangentCol[idx] = finalSourceCol[down] - finalSourceCol[idx];
            } else if (hasUp) {
                tangentRow[idx] = finalSourceRow[idx] - finalSourceRow[up];
                tangentCol[idx] = finalSourceCol[idx] - finalSourceCol[up];
            }
            clampVectorLength(tangentRow, tangentCol, idx, 1.35f);
        });
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
                                          float[] waterSurface, int height, int width, Object[] locks) {
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
                if (locks == null) {
                    updateChannelCell(target, section, normalizedLoad, surface,
                            profile, load, waterSurface);
                } else {
                    synchronized (locks[target & (locks.length - 1)]) {
                        updateChannelCell(target, section, normalizedLoad, surface,
                                profile, load, waterSurface);
                    }
                }
            }
        }
    }

    private static void updateChannelCell(int target, float section, float normalizedLoad, float surface,
                                          float[] profile, float[] load, float[] waterSurface) {
        int sectionOrder = Float.compare(section, profile[target]);
        boolean strongerSection = sectionOrder > 0;
        boolean exactTieWithLowerSurface = sectionOrder == 0
                && (!Float.isFinite(waterSurface[target]) || surface < waterSurface[target]);
        // Load aggregation is commutative, and exact section ties choose the downstream
        // (lower) surface. The result therefore stays identical across thread schedules.
        load[target] = Math.max(load[target], normalizedLoad);
        if (strongerSection || exactTieWithLowerSurface) {
            profile[target] = section;
            waterSurface[target] = surface;
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

    /**
     * Reference, single-threaded implementation. Kept for validation of
     * {@link #runPriorityFloodParallel}; {@link #build} no longer calls this directly.
     */
    static PriorityFlood runPriorityFloodSequential(float[] elevation, int height, int width) {
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

    private static final float RELAX_EPS = 1e-4f;
    private static final int MIN_STRIP_ROWS = 64;

    private static boolean isTrueSeed(int r, int c, int height, int width, float elevationValue) {
        return r == 0 || c == 0 || r == height - 1 || c == width - 1 || elevationValue <= SEA_LEVEL_METERS;
    }

    /**
     * Parallel priority-flood via row-strip domain decomposition, in four phases:
     *
     * <p><b>1.</b> Every strip is flooded independently in parallel using only true seeds (each
     * strip's own left/right columns always qualify -- a row-strip spans the full width -- so
     * this alone is already exact for any cell whose true optimum doesn't need to leave its
     * strip). This gives a safe, non-circular baseline for every cell, including border cells.
     *
     * <p><b>2.</b> For "middle" strips (bounded by a cut on both sides), two more per-strip
     * floods run in parallel: one seeded only by that strip's own top row (raw elevation, true
     * seeds excluded) to get each bottom-row cell's pure-interior cost to cross to the top row
     * (and which column), and the symmetric one for reaching the bottom row from the top.
     *
     * <p><b>3.</b> The boundary cells of every cut, plus the phase-2 same-strip shortcuts, form a
     * small graph (grid edges across each cut, plus one precomputed pass-through edge per
     * boundary cell). This is solved with an ordinary priority-flood -- reusing {@link IntMinHeap}
     * with lazy deletion (a node can be pushed more than once; only its first pop, which is
     * necessarily its true minimum, is acted on) -- seeded from phase 1's baseline. Because a
     * node is finalized exactly once, in strictly increasing cost order, no cyclic reinforcement
     * between adjacent strips is possible (unlike an iterative relaxation, which this replaced
     * after it was found to allow exactly that).
     *
     * <p><b>4.</b> Every strip re-floods in parallel, this time seeding both of its cuts (if any)
     * with phase 3's resolved values alongside its true seeds, giving the final, exact result for
     * every cell. Border cells whose value didn't need a cross-strip correction fall back to their
     * phase-1 downstream pointer.
     *
     * <p>The one behavioral difference from {@link #runPriorityFloodSequential} is in perfectly
     * flat filled regions (lake surfaces): when several neighbors tie for the minimum crossing
     * cost, this may pick a different (still valid) one than the single global heap's tie-breaking
     * would have, so the specific drainage path through a flat surface can differ. Values never
     * differ. See {@code HydrologyProvider.ALGORITHM_VERSION}, bumped alongside this change.
     */
    static PriorityFlood runPriorityFloodParallel(float[] elevation, int height, int width) {
        int n = height * width;
        int workers = Math.max(1, HydrologyParallel.workerThreads());
        int stripCount = Math.max(1, Math.min(workers, height / MIN_STRIP_ROWS));

        int[] stripStart = new int[stripCount + 1];
        for (int i = 0; i <= stripCount; i++) {
            stripStart[i] = (int) ((long) height * i / stripCount);
        }

        // Phase 1: true-seeds-only baseline, every strip independent and parallel.
        boolean[] baseVisited = new boolean[n];
        float[] baseFilled = new float[n];
        int[] baseDownstream = new int[n];
        Arrays.fill(baseDownstream, -1);
        HydrologyParallel.forEachTask(stripCount, s -> floodStrip(
                elevation, height, width, stripStart[s], stripStart[s + 1], true, null, null,
                baseVisited, baseFilled, baseDownstream, null));

        if (stripCount == 1) {
            int[] order = new int[n];
            int orderSize = buildTopologicalOrder(baseDownstream, n, order);
            return new PriorityFlood(baseFilled, baseDownstream, order, orderSize);
        }

        int cuts = stripCount - 1;

        // Phase 2: same-strip pass-through cost, only meaningful for strips with a cut on both
        // sides. Two disjoint scratch sets so both directions for the same strip never race.
        int middleStripCount = Math.max(0, stripCount - 2);
        float[] viaTopFilled = null;
        int[] viaTopOrigin = null;
        float[] viaBottomFilled = null;
        int[] viaBottomOrigin = null;
        if (middleStripCount > 0) {
            viaTopFilled = new float[n];
            int[] viaTopDownstream = new int[n];
            boolean[] viaTopVisited = new boolean[n];
            viaTopOrigin = new int[n];
            float[] fViaTopFilled = viaTopFilled;
            int[] fViaTopOrigin = viaTopOrigin;

            viaBottomFilled = new float[n];
            int[] viaBottomDownstream = new int[n];
            boolean[] viaBottomVisited = new boolean[n];
            viaBottomOrigin = new int[n];
            float[] fViaBottomFilled = viaBottomFilled;
            int[] fViaBottomOrigin = viaBottomOrigin;

            int[] fStripStart = stripStart;
            HydrologyParallel.forEachTask(2 * middleStripCount, t -> {
                int strip = 1 + t / 2;
                int rowStart = fStripStart[strip];
                int rowEnd = fStripStart[strip + 1];
                float[] topRow = new float[width];
                float[] bottomRow = new float[width];
                for (int c = 0; c < width; c++) {
                    topRow[c] = elevation[rowStart * width + c];
                    bottomRow[c] = elevation[(rowEnd - 1) * width + c];
                }
                if (t % 2 == 0) {
                    floodStrip(elevation, height, width, rowStart, rowEnd, false, topRow, null,
                            viaTopVisited, fViaTopFilled, viaTopDownstream, fViaTopOrigin);
                } else {
                    floodStrip(elevation, height, width, rowStart, rowEnd, false, null, bottomRow,
                            viaBottomVisited, fViaBottomFilled, viaBottomDownstream, fViaBottomOrigin);
                }
            });
        }

        // Phase 3: solve the boundary graph exactly via an ordinary (cycle-free) priority-flood.
        int totalBoundaryNodes = cuts * 2 * width;
        int[] boundaryCellOf = new int[totalBoundaryNodes];
        int[] cellToBoundaryNode = new int[n];
        Arrays.fill(cellToBoundaryNode, -1);
        int nodeIdx = 0;
        for (int cut = 0; cut < cuts; cut++) {
            int aboveRow = stripStart[cut + 1] - 1;
            int belowRow = stripStart[cut + 1];
            for (int c = 0; c < width; c++) {
                int cell = aboveRow * width + c;
                boundaryCellOf[nodeIdx] = cell;
                cellToBoundaryNode[cell] = nodeIdx;
                nodeIdx++;
            }
            for (int c = 0; c < width; c++) {
                int cell = belowRow * width + c;
                boundaryCellOf[nodeIdx] = cell;
                cellToBoundaryNode[cell] = nodeIdx;
                nodeIdx++;
            }
        }

        float[] boundaryFilled = new float[totalBoundaryNodes];
        int[] boundaryFromCell = new int[totalBoundaryNodes];
        Arrays.fill(boundaryFromCell, -1);
        boolean[] boundaryFinal = new boolean[totalBoundaryNodes];
        for (int node = 0; node < totalBoundaryNodes; node++) {
            boundaryFilled[node] = baseFilled[boundaryCellOf[node]];
        }
        // Lazy-deletion Dijkstra: a node can be pushed again each time it improves (up to 3
        // cross-cut neighbors + 1 pass-through partner), so the heap sees more pushes than nodes.
        IntMinHeap boundaryQueue = new IntMinHeap(boundaryFilled, totalBoundaryNodes * 6, 1, totalBoundaryNodes);
        for (int node = 0; node < totalBoundaryNodes; node++) {
            boundaryQueue.add(node);
        }

        while (!boundaryQueue.isEmpty()) {
            int node = boundaryQueue.poll();
            if (boundaryFinal[node]) continue;
            boundaryFinal[node] = true;
            int cell = boundaryCellOf[node];
            int r = cell / width;
            int c = cell - r * width;
            int cut = (nodeCutIndex(node, width));
            boolean above = isAboveNode(node, width);
            int otherRow = above ? stripStart[cut + 1] : stripStart[cut + 1] - 1;

            for (int dc = -1; dc <= 1; dc++) {
                int nc = c + dc;
                if (nc < 0 || nc >= width) continue;
                int neighborCell = otherRow * width + nc;
                int neighborNode = cellToBoundaryNode[neighborCell];
                if (neighborNode < 0 || boundaryFinal[neighborNode]) continue;
                float candidate = Math.max(elevation[neighborCell], boundaryFilled[node]);
                if (candidate < boundaryFilled[neighborNode] - RELAX_EPS) {
                    boundaryFilled[neighborNode] = candidate;
                    boundaryFromCell[neighborNode] = cell;
                    boundaryQueue.add(neighborNode);
                }
            }

            int stripOfCell = above ? cut : cut + 1;
            if (stripOfCell >= 1 && stripOfCell <= stripCount - 2) {
                int partnerCell;
                float rawCost;
                if (above) {
                    // "cell" is this middle strip's bottom row; partner is its own top row.
                    rawCost = viaTopFilled[cell];
                    partnerCell = stripStart[stripOfCell] * width + viaTopOrigin[cell];
                } else {
                    // "cell" is this middle strip's top row; partner is its own bottom row.
                    rawCost = viaBottomFilled[cell];
                    partnerCell = (stripStart[stripOfCell + 1] - 1) * width + viaBottomOrigin[cell];
                }
                int partnerNode = cellToBoundaryNode[partnerCell];
                if (partnerNode >= 0 && !boundaryFinal[partnerNode] && Float.isFinite(rawCost)) {
                    float candidate = Math.max(rawCost, boundaryFilled[node]);
                    if (candidate < boundaryFilled[partnerNode] - RELAX_EPS) {
                        boundaryFilled[partnerNode] = candidate;
                        boundaryFromCell[partnerNode] = cell;
                        boundaryQueue.add(partnerNode);
                    }
                }
            }
        }

        // Phase 4: final re-flood per strip using phase 3's resolved boundary values, parallel.
        float[][] finalTopSeed = new float[stripCount][];
        float[][] finalBottomSeed = new float[stripCount][];
        for (int s = 0; s < stripCount; s++) {
            if (s > 0) {
                finalTopSeed[s] = new float[width];
                int belowNodeBase = (s - 1) * 2 * width + width;
                System.arraycopy(boundaryFilled, belowNodeBase, finalTopSeed[s], 0, width);
            }
            if (s < stripCount - 1) {
                finalBottomSeed[s] = new float[width];
                int aboveNodeBase = s * 2 * width;
                System.arraycopy(boundaryFilled, aboveNodeBase, finalBottomSeed[s], 0, width);
            }
        }

        boolean[] visited = new boolean[n];
        float[] filled = new float[n];
        int[] downstream = new int[n];
        Arrays.fill(downstream, -1);
        int[] fStripStart2 = stripStart;
        float[][] fFinalTopSeed = finalTopSeed;
        float[][] fFinalBottomSeed = finalBottomSeed;
        HydrologyParallel.forEachTask(stripCount, s -> floodStrip(
                elevation, height, width, fStripStart2[s], fStripStart2[s + 1], true,
                fFinalTopSeed[s], fFinalBottomSeed[s], visited, filled, downstream, null));

        for (int cut = 0; cut < cuts; cut++) {
            int aboveRow = stripStart[cut + 1] - 1;
            int belowRow = stripStart[cut + 1];
            for (int c = 0; c < width; c++) {
                int aboveCell = aboveRow * width + c;
                if (!isTrueSeed(aboveRow, c, height, width, elevation[aboveCell])) {
                    int node = cellToBoundaryNode[aboveCell];
                    downstream[aboveCell] = boundaryFromCell[node] >= 0 ? boundaryFromCell[node] : baseDownstream[aboveCell];
                }
                int belowCell = belowRow * width + c;
                if (!isTrueSeed(belowRow, c, height, width, elevation[belowCell])) {
                    int node = cellToBoundaryNode[belowCell];
                    downstream[belowCell] = boundaryFromCell[node] >= 0 ? boundaryFromCell[node] : baseDownstream[belowCell];
                }
            }
        }

        int[] order = new int[n];
        int orderSize = buildTopologicalOrder(downstream, n, order);
        return new PriorityFlood(filled, downstream, order, orderSize);
    }

    /** Node layout: per cut, {@code width} "above" nodes followed by {@code width} "below" nodes. */
    private static int nodeCutIndex(int node, int width) {
        return node / (2 * width);
    }

    private static boolean isAboveNode(int node, int width) {
        return (node % (2 * width)) < width;
    }

    /**
     * Floods rows [rowStart, rowEnd) only; never crosses into a neighboring strip.
     *
     * @param includeTrueSeeds whether ordinary border/ocean cells (see {@link #isTrueSeed}) seed
     *                         the flood; {@code false} is used for the same-strip pass-through
     *                         floods, which must only see the provided top/bottom row.
     * @param originTag        if non-null, receives -- for every visited cell -- the column of the
     *                         top/bottom-row seed its flood chain originated from. Only meaningful
     *                         when exactly one of topSeedValues/bottomSeedValues is set.
     */
    private static void floodStrip(float[] elevation, int height, int width, int rowStart, int rowEnd,
                                    boolean includeTrueSeeds, float[] topSeedValues, float[] bottomSeedValues,
                                    boolean[] visited, float[] filled, int[] downstream, int[] originTag) {
        int stripRows = rowEnd - rowStart;
        IntMinHeap queue = new IntMinHeap(filled, stripRows * width, stripRows, width);
        for (int r = rowStart; r < rowEnd; r++) {
            for (int c = 0; c < width; c++) {
                int idx = r * width + c;
                boolean seed = includeTrueSeeds && isTrueSeed(r, c, height, width, elevation[idx]);
                float seedValue = elevation[idx];
                if (!seed && r == rowStart && topSeedValues != null && Float.isFinite(topSeedValues[c])) {
                    seed = true;
                    seedValue = topSeedValues[c];
                }
                if (!seed && r == rowEnd - 1 && bottomSeedValues != null && Float.isFinite(bottomSeedValues[c])) {
                    seed = true;
                    seedValue = bottomSeedValues[c];
                }
                if (!seed) continue;
                visited[idx] = true;
                filled[idx] = seedValue;
                if (originTag != null) originTag[idx] = c;
                queue.add(idx);
            }
        }
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int r = idx / width;
            int c = idx - r * width;
            for (int k = 0; k < 8; k++) {
                int nr = r + DR[k];
                int nc = c + DC[k];
                if (nr < rowStart || nr >= rowEnd || nc < 0 || nc >= width) continue;
                int ni = nr * width + nc;
                if (visited[ni]) continue;
                visited[ni] = true;
                downstream[ni] = idx;
                filled[ni] = Math.max(elevation[ni], filled[idx]);
                if (originTag != null) originTag[ni] = originTag[idx];
                queue.add(ni);
            }
        }
    }

    /**
     * Rebuilds a valid flood order (every cell's downstream target appears earlier than the cell
     * itself) from a complete {@code downstream[]}, via BFS from the roots (downstream == -1).
     * Needed because the parallel flood's cross-strip overrides are applied after all per-strip
     * floods finish, so no single strip's local order is globally valid on its own.
     */
    private static int buildTopologicalOrder(int[] downstream, int n, int[] order) {
        int[] childCount = new int[n];
        for (int idx = 0; idx < n; idx++) {
            int d = downstream[idx];
            if (d >= 0) childCount[d]++;
        }
        int[] childStart = new int[n + 1];
        for (int idx = 0; idx < n; idx++) {
            childStart[idx + 1] = childStart[idx] + childCount[idx];
        }
        int[] childList = new int[childStart[n]];
        int[] fillPos = childStart.clone();
        for (int idx = 0; idx < n; idx++) {
            int d = downstream[idx];
            if (d >= 0) {
                childList[fillPos[d]++] = idx;
            }
        }

        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int idx = 0; idx < n; idx++) {
            if (downstream[idx] < 0) queue[tail++] = idx;
        }
        int orderSize = 0;
        while (head < tail) {
            int idx = queue[head++];
            order[orderSize++] = idx;
            for (int k = childStart[idx]; k < childStart[idx + 1]; k++) {
                queue[tail++] = childList[k];
            }
        }
        if (orderSize < n) {
            // Defensive fallback for a pathological, fully-enclosed region with no path to any
            // seed even through neighboring strips (should not occur for real terrain, since every
            // strip's own left/right columns are always true seeds). Treat leftover cells as
            // additional roots so downstream code never hangs or reads a partially built order.
            LOG.warn("FluvialRiverNetwork parallel flood: {} of {} cells had no path to a seed; "
                    + "treating them as roots", n - orderSize, n);
            boolean[] included = new boolean[n];
            for (int i = 0; i < orderSize; i++) included[order[i]] = true;
            for (int idx = 0; idx < n; idx++) {
                if (!included[idx]) {
                    downstream[idx] = -1;
                    order[orderSize++] = idx;
                }
            }
        }
        return orderSize;
    }

    private static float[] accumulateRunoff(float[] elevation, float[] climate, int[] downstream,
                                             int[] order, int orderSize, int height, int width, float pixelSizeM) {
        int n = height * width;
        float[] accumulation = new float[n];
        float cellAreaKm2 = (pixelSizeM * pixelSizeM) / 1_000_000.0f;
        HydrologyParallel.forEachIndex(0, n, idx -> {
            accumulation[idx] = elevation[idx] <= SEA_LEVEL_METERS ? 0.0f
                    : localRunoff(idx, elevation[idx], climate, n) * cellAreaKm2;
        });
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
        TerrainBiomeSettlement s = TerrainBiomeRegistry.instance().byIndex(biome);
        if (s == null) return false;
        String kind = s.kind();
        return "OCEAN".equals(kind) || "BEACH".equals(kind);
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

    record PriorityFlood(float[] filledSurface, int[] downstream, int[] order, int orderSize) {}

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
