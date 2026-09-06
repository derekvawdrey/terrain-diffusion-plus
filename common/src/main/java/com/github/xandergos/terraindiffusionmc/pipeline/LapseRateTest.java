package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Standalone check for the region-gated lapse-rate behaviour in
 * {@link LaplacianUtils#localBaselineTemperature}: the warm-region ceiling that lets the
 * regression go positive, and the warm-mountain belts that ease the slope handed to the native
 * stage. Uses synthetic T/e data (no ONNX/GPU needed) so it isolates the regression + clamp
 * logic from the rest of the pipeline. Prints PASS/FAIL lines and exits non-zero on failure.
 */
public class LapseRateTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // Scan a coarse grid for world coordinates solidly inside a warm-mountain belt (high
        // positive regionNoise), solidly outside every belt (regionNoise near 0, which also
        // keeps the warm-region ceiling at 0), and at the field's strongest negative value
        // (inside the warm-region ceiling belt but outside the warm-mountain one).
        float beltX = 0, beltZ = 0, beltNoise = -1;
        float normalX = 0, normalZ = 0, normalNoise = 1;
        float negX = 0, negZ = 0, negNoise = 1;
        for (int x = -40000; x <= 40000; x += 500) {
            for (int z = -40000; z <= 40000; z += 500) {
                float n = BiomeClassifier.sampleRegionNoise(x, z);
                if (n > beltNoise) { beltNoise = n; beltX = x; beltZ = z; }
                if (n < negNoise) { negNoise = n; negX = x; negZ = z; }
                if (Math.abs(n) < Math.abs(normalNoise)) { normalNoise = n; normalX = x; normalZ = z; }
            }
        }
        System.out.printf("Warm belt sample:     (%.0f, %.0f) regionNoise=%.3f weight=%.3f%n",
                beltX, beltZ, beltNoise, LaplacianUtils.warmMountainWeight(beltX, beltZ));
        System.out.printf("Normal region sample: (%.0f, %.0f) regionNoise=%.3f weight=%.3f%n",
                normalX, normalZ, normalNoise, LaplacianUtils.warmMountainWeight(normalX, normalZ));
        System.out.printf("Negative-tail sample: (%.0f, %.0f) regionNoise=%.3f weight=%.3f%n",
                negX, negZ, negNoise, LaplacianUtils.warmMountainWeight(negX, negZ));
        float target = LaplacianUtils.warmMountainTargetBeta();
        System.out.printf("Configured warm-mountain target: %.2f C/km%n", target * 1000f);

        int win = 15;

        // Case A (existing ceiling): a "true" +5 C/km correlation baked into T over a hot
        // slope. Inside a special-region belt the ceiling lets the regression keep it; in the
        // normal region the clamp pins it at 0.
        float[][] eA = new float[win][win];
        float[][] tA = new float[win][win];
        for (int r = 0; r < win; r++) {
            for (int c = 0; c < win; c++) {
                float elev = 2500f + (r * win + c) * 10f;
                eA[r][c] = elev;
                tA[r][c] = 15f + 0.005f * (elev - 2500f);
            }
        }
        float betaBeltA = runCase("A ceiling, warm belt", tA, eA, win, beltX, beltZ);
        float betaNormA = runCase("A ceiling, normal", tA, eA, win, normalX, normalZ);
        check("ceiling lets the regression stay positive inside a belt", betaBeltA > 0.004f);
        check("ceiling pins the slope at 0 in a normal region", Math.abs(betaNormA) < 1e-6f);

        // Case B (warm-mountain belts): the ordinary cooling mountain. The model says the window
        // centre, at ~3600 m, sits at -5 C, and temperature falls at the fallback -6.5 C/km.
        float[][] eB = new float[win][win];
        float[][] tB = new float[win][win];
        float centreElev = 2500f + ((win / 2) * win + win / 2) * 10f;
        for (int r = 0; r < win; r++) {
            for (int c = 0; c < win; c++) {
                float elev = 2500f + (r * win + c) * 10f;
                eB[r][c] = elev;
                tB[r][c] = -5f + LaplacianUtils.FALLBACK_LAPSE_BETA * (elev - centreElev);
            }
        }
        float[][][] belt = baseline(tB, eB, win, beltX, beltZ);
        float[][][] norm = baseline(tB, eB, win, normalX, normalZ);
        float[][][] neg = baseline(tB, eB, win, negX, negZ);
        float tSeaBelt = belt[0][0][0], betaBelt = belt[1][0][0];
        float tSeaNorm = norm[0][0][0], betaNorm = norm[1][0][0];
        float tSeaNeg = neg[0][0][0], betaNeg = neg[1][0][0];
        float centreNorm = tSeaNorm + betaNorm * centreElev;
        float centreBelt = tSeaBelt + betaBelt * centreElev;
        float centreNeg = tSeaNeg + betaNeg * centreElev;
        System.out.printf("[B normal]    beta=%.2f C/km  T_sea=%.2f C  T(centre %.0f m)=%.2f C%n",
                betaNorm * 1000f, tSeaNorm, centreElev, centreNorm);
        System.out.printf("[B warm belt] beta=%.2f C/km  T_sea=%.2f C  T(centre %.0f m)=%.2f C%n",
                betaBelt * 1000f, tSeaBelt, centreElev, centreBelt);
        System.out.printf("[B neg tail]  beta=%.2f C/km  T_sea=%.2f C  T(centre %.0f m)=%.2f C%n",
                betaNeg * 1000f, tSeaNeg, centreElev, centreNeg);

        check("normal region reproduces the model temperature at the centre elevation",
                Math.abs(centreNorm - (-5f)) < 0.05f);
        check("normal region keeps the regressed slope", Math.abs(betaNorm - LaplacianUtils.FALLBACK_LAPSE_BETA) < 2e-4f);
        check("negative tail is outside the warm-mountain belt", Math.abs(betaNeg - betaNorm) < 1e-6f);
        check("sea-level baseline is identical inside and outside the belt", Math.abs(tSeaBelt - tSeaNorm) < 1e-4f);
        if (Float.isNaN(target)) {
            check("feature disabled: belt slope equals the regressed slope", Math.abs(betaBelt - betaNorm) < 1e-6f);
        } else {
            float weight = LaplacianUtils.warmMountainWeight(beltX, beltZ);
            float expectedBeta = betaNorm + weight * (target - betaNorm);
            check("belt slope is the regressed slope eased towards the target by the belt weight",
                    Math.abs(betaBelt - expectedBeta) < 1e-6f);
            check("belt warms the centre by (beta difference x elevation)",
                    Math.abs((centreBelt - centreNorm) - (betaBelt - betaNorm) * centreElev) < 0.05f);
            check("a 3.6 km cooling range inside a full belt is temperate", weight < 0.999f || centreBelt > 10f);
        }

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) System.exit(1);
    }

    private static float[][][] baseline(float[][] T, float[][] e, int win, float worldX, float worldZ) {
        // coarseStride=1 so originCoarseCol/Row map 1:1 to world blocks for this synthetic test;
        // the window centre (pad, pad) then lands exactly on the scanned coordinate.
        int pad = (win - 1) / 2;
        int originCol = Math.round(worldX) - pad;
        int originRow = Math.round(worldZ) - pad;
        return LaplacianUtils.localBaselineTemperature(T, e, win, 0.02f, originRow, originCol, 1);
    }

    private static float runCase(String label, float[][] T, float[][] e, int win, float worldX, float worldZ) {
        float[][][] result = baseline(T, e, win, worldX, worldZ);
        float beta = result[1][0][0];
        float tSea = result[0][0][0];
        float elevLow = e[0][0];
        float elevHigh = e[win - 1][win - 1];
        float tempLow = tSea + beta * elevLow;
        float tempHigh = tSea + beta * elevHigh;
        System.out.printf("[%s] beta=%.6f (%.2f C/km)  temp at elev=%.0fm: %.2fC  ->  temp at elev=%.0fm: %.2fC (delta %+.2fC)%n",
                label, beta, beta * 1000f, elevLow, tempLow, elevHigh, tempHigh, tempHigh - tempLow);
        return beta;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }
}
