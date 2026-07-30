package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Standalone check for the region-gated warm-mountain lapse rate in
 * {@link LaplacianUtils#localBaselineTemperature}. Uses synthetic T/e data (no ONNX/GPU
 * needed) so it isolates the regression + clamp logic from the rest of the pipeline.
 */
public class LapseRateTest {

    public static void main(String[] args) {
        // Scan for a world coordinate solidly inside a "hot region" (|regionNoise| high)
        // and one solidly in a "normal region" (|regionNoise| near 0), on a coarse grid.
        float hotX = 0, hotZ = 0, hotNoise = 0;
        float normalX = 0, normalZ = 0, normalNoise = 1;
        for (int x = -40000; x <= 40000; x += 500) {
            for (int z = -40000; z <= 40000; z += 500) {
                float n = BiomeClassifier.sampleRegionNoise(x, z);
                if (Math.abs(n) > hotNoise) {
                    hotNoise = Math.abs(n);
                    hotX = x;
                    hotZ = z;
                }
                if (Math.abs(n) < normalNoise) {
                    normalNoise = Math.abs(n);
                    normalX = x;
                    normalZ = z;
                }
            }
        }
        System.out.printf("Hot region sample:    (%.0f, %.0f) regionNoise=%.3f%n", hotX, hotZ, hotNoise);
        System.out.printf("Normal region sample: (%.0f, %.0f) regionNoise=%.3f%n", normalX, normalZ, normalNoise);

        // Synthetic 15x15 window: elevation rises 2500m -> 4600m across the window (all land),
        // with a "true" underlying model correlation of +5 C/km baked into T -- representing
        // what raw model output might look like over a hot volcanic slope. betaMin/betaMax
        // clamp behavior is what we're actually testing here.
        int win = 15;
        float[][] e = new float[win][win];
        float[][] T = new float[win][win];
        for (int r = 0; r < win; r++) {
            for (int c = 0; c < win; c++) {
                float elev = 2500f + (r * win + c) * 10f;
                e[r][c] = elev;
                T[r][c] = 15f + 0.005f * (elev - 2500f);
            }
        }

        runCase("HOT region", T, e, win, hotX, hotZ);
        runCase("NORMAL region", T, e, win, normalX, normalZ);
    }

    private static void runCase(String label, float[][] T, float[][] e, int win, float worldX, float worldZ) {
        // coarseStride=1 so originCoarseCol/Row map 1:1 to world blocks for this synthetic test.
        int originCol = Math.round(worldX);
        int originRow = Math.round(worldZ);
        float[][][] result = LaplacianUtils.localBaselineTemperature(T, e, win, 0.02f, originRow, originCol, 1);
        float beta = result[1][0][0];
        float tSea = result[0][0][0];
        float elevLow = e[0][0];
        float elevHigh = e[win - 1][win - 1];
        float tempLow = tSea + beta * elevLow;
        float tempHigh = tSea + beta * elevHigh;
        System.out.printf("[%s] beta=%.6f (%.2f C/km)  temp at elev=%.0fm: %.2fC  ->  temp at elev=%.0fm: %.2fC (delta %+.2fC)%n",
                label, beta, beta * 1000f, elevLow, tempLow, elevHigh, tempHigh, tempHigh - tempLow);
    }
}
