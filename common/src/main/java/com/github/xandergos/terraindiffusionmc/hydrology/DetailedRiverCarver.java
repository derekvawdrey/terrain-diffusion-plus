package com.github.xandergos.terraindiffusionmc.hydrology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/** Carves the hydrology topology directly into the final high-resolution terrain. */
public final class DetailedRiverCarver {
    private static final Logger LOG = LoggerFactory.getLogger(DetailedRiverCarver.class);
    private static final int BED_SMOOTHING_PASSES = 2;
    private static final int BANK_SOFTENING_PASSES = 2;
    private static final float LAKE_MIN_DEPTH_M = 0.75f;
    private static final int[] DR = {-1,-1,-1,0,0,1,1,1};
    private static final int[] DC = {-1,0,1,-1,1,-1,0,1};
    private static final float[] DIST = {
            1.41421356f, 1.0f, 1.41421356f, 1.0f,
            1.0f, 1.41421356f, 1.0f, 1.41421356f
    };

    private DetailedRiverCarver() {}

    public static CarvedTerrain carve(float[] detailedElevation, FluvialRiverNetwork.RiverTopology topology,
                                      int height, int width, float metresPerBlock) {
        int n = Math.multiplyExact(height, width);
        if (detailedElevation.length != n || topology.channelProfile().length != n) {
            throw new IllegalArgumentException("Detailed terrain and hydrology topology shapes differ");
        }
        long t0 = System.nanoTime();
        float[] adjusted = detailedElevation.clone();
        float[] bedTarget = new float[n];
        Arrays.fill(bedTarget, Float.NaN);

        HydrologyParallel.forEachIndex(0, n, idx -> {
            float profile = topology.channelProfile()[idx];
            float lakeDepth = topology.lakeDepth()[idx];
            float surface = topology.waterSurface()[idx];
            if (!Float.isFinite(surface)) return;

            float depthBlocks;
            if (lakeDepth >= LAKE_MIN_DEPTH_M) {
                depthBlocks = Math.min(8.0f, 2.0f + lakeDepth / Math.max(1.0f, metresPerBlock));
            } else if (profile > 0.0f) {
                float load = clamp01(topology.channelLoad()[idx]);
                float slope = channelSurfaceSlope(topology.waterSurface(), topology.channelProfile(),
                        idx, height, width, metresPerBlock);
                float steepness = smoothUnit(clamp01((slope - 0.035f) / 0.18f));
                // Both width and depth now grow continuously with downstream accumulation.
                float centreDepth = 1.20f + 4.65f * (float) Math.pow(load, 0.58f);
                centreDepth = 1.0f + (centreDepth - 1.0f) * (1.0f - 0.70f * steepness);
                float roundedProfile = smoothUnit(smoothUnit(profile));
                float edgeDepth = 0.28f + 0.12f * (1.0f - steepness);
                depthBlocks = edgeDepth + (centreDepth - edgeDepth) * roundedProfile;

                // A curved footprint may touch a high side-slope. Limit local excavation there
                // instead of cutting a trench merely to force the full nominal width through.
                float maximumCutBlocks = (1.0f + 1.5f * (float) Math.sqrt(load)
                        + 3.5f * roundedProfile) * (1.0f - 0.35f * steepness);
                float target = surface - depthBlocks * metresPerBlock;
                bedTarget[idx] = Math.max(target,
                        detailedElevation[idx] - maximumCutBlocks * metresPerBlock);
                return;
            } else {
                return;
            }
            bedTarget[idx] = surface - depthBlocks * metresPerBlock;
        });
        long t1 = System.nanoTime();

        float[] bedSmoothingScratch = new float[n];
        for (int pass = 0; pass < BED_SMOOTHING_PASSES; pass++) {
            System.arraycopy(bedTarget, 0, bedSmoothingScratch, 0, n);
            float[] source = bedSmoothingScratch;
            HydrologyParallel.forEachRow(1, height - 1, width, r -> {
                for (int c = 1; c < width - 1; c++) {
                    int idx = r * width + c;
                    if (!Float.isFinite(source[idx])) continue;
                    float sum = source[idx] * 5.0f;
                    float weight = 5.0f;
                    for (int k = 0; k < 8; k++) {
                        int ni = (r + DR[k]) * width + c + DC[k];
                        if (!Float.isFinite(source[ni])) continue;
                        sum += source[ni];
                        weight += 1.0f;
                    }
                    float smoothed = sum / weight;
                    // Smoothing may lift an over-cut step, but must never deepen it into a canyon.
                    bedTarget[idx] = Math.max(source[idx],
                            Math.min(source[idx] + metresPerBlock * 0.30f, smoothed));
                }
            });
        }
        long t2 = System.nanoTime();

        HydrologyParallel.forEachIndex(0, n, idx -> {
            if (Float.isFinite(bedTarget[idx])) adjusted[idx] = Math.min(adjusted[idx], bedTarget[idx]);
        });
        long t3 = System.nanoTime();
        softenChannelBanks(adjusted, detailedElevation, topology.channelProfile(), height, width);
        long t4 = System.nanoTime();

        LOG.info("DetailedRiverCarver.carve n={} phases (ms): depthCompute={} bedSmoothing={} applyBed={} "
                        + "softenBanks={} total={}",
                n, millis(t0, t1), millis(t1, t2), millis(t2, t3), millis(t3, t4), millis(t0, t4));

        return new CarvedTerrain(adjusted, bedTarget);
    }

    /**
     * Lift only the over-cut edge of the wet cross-section toward its natural neighbours. This
     * rounds native-grid steps without expanding the water mask or creating another channel.
     */
    private static void softenChannelBanks(float[] adjusted, float[] naturalElevation,
                                           float[] profile, int height, int width) {
        float[] bankSofteningScratch = new float[adjusted.length];
        for (int pass = 0; pass < BANK_SOFTENING_PASSES; pass++) {
            System.arraycopy(adjusted, 0, bankSofteningScratch, 0, adjusted.length);
            float[] source = bankSofteningScratch;
            HydrologyParallel.forEachRow(1, height - 1, width, r -> {
                for (int c = 1; c < width - 1; c++) {
                    int idx = r * width + c;
                    float bankPosition = clamp01(profile[idx] / 0.34f);
                    if (bankPosition <= 0.0f || bankPosition >= 1.0f) continue;

                    float sum = 0.0f;
                    float weight = 0.0f;
                    for (int k = 0; k < 8; k++) {
                        int ni = (r + DR[k]) * width + c + DC[k];
                        float neighbourWeight = 1.0f / DIST[k];
                        sum += source[ni] * neighbourWeight;
                        weight += neighbourWeight;
                    }
                    float average = sum / weight;
                    if (average <= source[idx]) continue;
                    float bankBand = 4.0f * bankPosition * (1.0f - bankPosition);
                    float softened = source[idx] + (average - source[idx]) * (0.13f * bankBand);
                    adjusted[idx] = Math.min(naturalElevation[idx], softened);
                }
            });
        }
    }

    private static float smoothUnit(float value) {
        value = clamp01(value);
        return value * value * (3.0f - 2.0f * value);
    }

    private static float channelSurfaceSlope(float[] waterSurface, float[] profile, int idx,
                                             int height, int width, float metresPerBlock) {
        int r = idx / width;
        int c = idx - r * width;
        float centre = waterSurface[idx];
        float maximum = 0.0f;
        for (int k = 0; k < 8; k++) {
            int nr = r + DR[k];
            int nc = c + DC[k];
            if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
            int neighbour = nr * width + nc;
            if (profile[neighbour] <= 0.0f || !Float.isFinite(waterSurface[neighbour])) continue;
            float distanceM = DIST[k] * Math.max(1.0f, metresPerBlock);
            maximum = Math.max(maximum,
                    Math.abs(centre - waterSurface[neighbour]) / distanceM);
        }
        return maximum;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static long millis(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000L;
    }

    public record CarvedTerrain(float[] adjustedElevation, float[] bedTarget) {
        public float[] cropAdjustedElevation(int row0, int col0, int cropHeight, int cropWidth, int sourceWidth) {
            float[] out = new float[Math.multiplyExact(cropHeight, cropWidth)];
            HydrologyParallel.forEachRow(0, cropHeight, cropWidth, row -> {
                System.arraycopy(adjustedElevation, (row0 + row) * sourceWidth + col0,
                        out, row * cropWidth, cropWidth);
            });
            return out;
        }
    }
}
