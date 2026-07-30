package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;

/**
 * Sharpens existing terrain relief into cliffs/escarpments in select biomes.
 *
 * <p>The base pipeline (diffusion model decode + bilinear up-sampling, see {@link WorldPipeline}
 * and {@code LaplacianUtils.bilinearResize}) is smooth by construction and never produces a sheer
 * drop on its own. Rather than inventing new landforms, this locally amplifies each pixel's
 * elevation deviation from its own neighborhood mean wherever a coherent placement noise gates it
 * on -- so a scarp always follows whatever ridgeline/valley wall the heightmap already has,
 * instead of looking arbitrary. Placement is deliberately concentrated: only mountain and
 * badlands/mesa terrain is eligible, everything else is left untouched.</p>
 *
 * <p>Runs after biome classification (so badlands/mesa detection can use the classified biome),
 * but only mutates elevation -- it never revisits the biome assignment.</p>
 */
public final class ScarpCarver {
    private ScarpCarver() {}

    /** Box-mean radius (blocks) used to measure each pixel's deviation from local terrain. Also
     *  the minimum required padding margin around the input elevation. */
    public static final int BOX_RADIUS_BLOCKS = 3;

    /** Matches {@code BiomeClassifier}'s own "mountains" zone threshold, so scarps land exactly
     *  where the rest of the pipeline already considers this mountainous. */
    private static final float MOUNTAIN_ELEVATION_M = 2500.0f;

    private static final float PLACEMENT_NOISE_WAVELENGTH = 220.0f;
    private static final long SCARP_NOISE_SEED = 991177L;

    private static final float MOUNTAIN_AMPLIFY = 3.2f;
    private static final float MOUNTAIN_PLACEMENT_THRESHOLD = -0.1f;
    private static final float MOUNTAIN_MAX_DELTA_M = 30.0f;

    private static final float BADLANDS_AMPLIFY = 2.4f;
    private static final float BADLANDS_PLACEMENT_THRESHOLD = -0.1f;
    private static final float BADLANDS_MAX_DELTA_M = 18.0f;

    /**
     * @param elevationPadded core elevation plus a {@link #BOX_RADIUS_BLOCKS}-or-wider margin on
     *                        every side, {@code (coreSize + 2*margin)} square, row-major
     * @param biomes          core-sized classified biome indexes (no margin)
     * @param registry        resolves a biome index to its vanilla key, for badlands/mesa detection
     * @param i0              world Z (row) of the core's top-left corner
     * @param j0              world X (col) of the core's top-left corner
     * @param coreSize        width/height of the (square) core region
     * @param margin          padding blocks present on every side of {@code elevationPadded};
     *                        must be {@code >= BOX_RADIUS_BLOCKS}
     * @return core-sized adjusted elevation
     */
    public static float[] carve(float[] elevationPadded, short[] biomes, TerrainBiomeRegistry registry,
                                 int i0, int j0, int coreSize, int margin) {
        int paddedWidth = coreSize + 2 * margin;
        float[] integral = buildIntegralImage(elevationPadded, paddedWidth);
        float[] out = new float[coreSize * coreSize];

        HydrologyParallel.forEachRow(0, coreSize, coreSize, r -> {
            for (int c = 0; c < coreSize; c++) {
                int idx = r * coreSize + c;
                int pr = r + margin;
                int pc = c + margin;
                float elevation = elevationPadded[pr * paddedWidth + pc];
                out[idx] = elevation;
                if (elevation <= 0f) continue;

                String key = registry.keyForIndex(biomes[idx]);
                boolean isBadlands = key != null && (key.contains("badlands") || key.contains("mesa"));

                float amplify, threshold, maxDelta;
                if (isBadlands) {
                    amplify = BADLANDS_AMPLIFY;
                    threshold = BADLANDS_PLACEMENT_THRESHOLD;
                    maxDelta = BADLANDS_MAX_DELTA_M;
                } else if (elevation > MOUNTAIN_ELEVATION_M) {
                    amplify = MOUNTAIN_AMPLIFY;
                    threshold = MOUNTAIN_PLACEMENT_THRESHOLD;
                    maxDelta = MOUNTAIN_MAX_DELTA_M;
                } else {
                    continue;
                }

                float worldX = j0 + c, worldZ = i0 + r;
                float placement = valueNoise(worldX / PLACEMENT_NOISE_WAVELENGTH, worldZ / PLACEMENT_NOISE_WAVELENGTH);
                if (placement <= threshold) continue;

                float localMean = boxMean(integral, paddedWidth, pr, pc, BOX_RADIUS_BLOCKS);
                float deviation = elevation - localMean;
                if (deviation == 0f) continue;

                float strength = smoothstep(clamp01((placement - threshold) / (1.0f - threshold)));
                float delta = deviation * (amplify - 1.0f) * strength;
                delta = Math.max(-maxDelta, Math.min(maxDelta, delta));
                out[idx] = elevation + delta;
            }
        });
        return out;
    }

    /** Standard summed-area table, one pixel wider/taller than {@code src} on the low side. */
    private static float[] buildIntegralImage(float[] src, int size) {
        int stride = size + 1;
        float[] integral = new float[stride * stride];
        for (int r = 0; r < size; r++) {
            float rowSum = 0f;
            int srcRow = r * size;
            int dstRow = (r + 1) * stride;
            int prevDstRow = r * stride;
            for (int c = 0; c < size; c++) {
                rowSum += src[srcRow + c];
                integral[dstRow + c + 1] = integral[prevDstRow + c + 1] + rowSum;
            }
        }
        return integral;
    }

    private static float boxMean(float[] integral, int size, int pr, int pc, int radius) {
        int stride = size + 1;
        int x0 = pc - radius, x1 = pc + radius;
        int y0 = pr - radius, y1 = pr + radius;
        float sum = integral[(y1 + 1) * stride + (x1 + 1)]
                - integral[y0 * stride + (x1 + 1)]
                - integral[(y1 + 1) * stride + x0]
                + integral[y0 * stride + x0];
        int count = (x1 - x0 + 1) * (y1 - y0 + 1);
        return sum / count;
    }

    private static float valueNoise(float x, float y) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        float fx = smoothstep(x - x0);
        float fy = smoothstep(y - y0);
        float a = hashUnit(x0, y0);
        float b = hashUnit(x0 + 1, y0);
        float c = hashUnit(x0, y0 + 1);
        float d = hashUnit(x0 + 1, y0 + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fy);
    }

    private static float hashUnit(int x, int y) {
        long value = SCARP_NOISE_SEED ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 40) / (float) (1L << 24)) * 2.0f - 1.0f;
    }

    private static int fastFloor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static float smoothstep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
