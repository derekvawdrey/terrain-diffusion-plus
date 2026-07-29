package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.Arrays;

/** Carves the hydrology topology directly into the final high-resolution terrain. */
public final class DetailedRiverCarver {
    private static final int SMOOTHING_PASSES = 3;
    private static final float LAKE_MIN_DEPTH_M = 0.75f;
    private static final int[] DR = {-1,-1,-1,0,0,1,1,1};
    private static final int[] DC = {-1,0,1,-1,1,-1,0,1};

    private DetailedRiverCarver() {}

    public static CarvedTerrain carve(float[] detailedElevation, FluvialRiverNetwork.RiverTopology topology,
                                      int height, int width, float metresPerBlock) {
        int n = Math.multiplyExact(height, width);
        if (detailedElevation.length != n || topology.channelProfile().length != n) {
            throw new IllegalArgumentException("Detailed terrain and hydrology topology shapes differ");
        }
        float[] adjusted = detailedElevation.clone();
        float[] bedTarget = new float[n];
        Arrays.fill(bedTarget, Float.NaN);

        for (int idx = 0; idx < n; idx++) {
            float profile = topology.channelProfile()[idx];
            float lakeDepth = topology.lakeDepth()[idx];
            float surface = topology.waterSurface()[idx];
            if (!Float.isFinite(surface)) continue;

            float depthBlocks;
            if (lakeDepth >= LAKE_MIN_DEPTH_M) {
                depthBlocks = Math.min(8.0f, 2.0f + lakeDepth / Math.max(1.0f, metresPerBlock));
            } else if (profile > 0.0f) {
                float load = clamp01(topology.channelLoad()[idx]);
                // One-block shelves at the edge, a load-dependent thalweg up to six blocks.
                float centreDepth = 2.0f + 4.0f * (float) Math.sqrt(load);
                depthBlocks = 0.65f + (centreDepth - 0.65f) * (float) Math.pow(profile, 1.65);
            } else {
                continue;
            }
            bedTarget[idx] = surface - depthBlocks * metresPerBlock;
        }

        for (int pass = 0; pass < SMOOTHING_PASSES; pass++) {
            float[] source = bedTarget.clone();
            for (int r = 1; r < height - 1; r++) {
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
                    // Never lift the thalweg; smoothing only rounds abrupt section changes.
                    bedTarget[idx] = Math.min(source[idx] + metresPerBlock * 0.35f, smoothed);
                }
            }
        }

        for (int idx = 0; idx < n; idx++) {
            if (Float.isFinite(bedTarget[idx])) adjusted[idx] = Math.min(adjusted[idx], bedTarget[idx]);
        }
        return new CarvedTerrain(adjusted, bedTarget);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public record CarvedTerrain(float[] adjustedElevation, float[] bedTarget) {
        public float[] cropAdjustedElevation(int row0, int col0, int cropHeight, int cropWidth, int sourceWidth) {
            float[] out = new float[Math.multiplyExact(cropHeight, cropWidth)];
            for (int row = 0; row < cropHeight; row++) {
                System.arraycopy(adjustedElevation, (row0 + row) * sourceWidth + col0,
                        out, row * cropWidth, cropWidth);
            }
            return out;
        }
    }
}
