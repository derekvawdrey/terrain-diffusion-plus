package com.github.xandergos.terraindiffusionmc.explorer;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRule;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeSettlement;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier;

/**
 * Per-coarse-cell "can this biome actually spawn here?" statuses for the explorer's exact-spawn
 * overlay -- the reliability upgrade over {@code BiomeCandidateFilterCalculator}'s interval
 * envelopes, which cannot see noise gates (most damagingly the ~5000-block {@code regionNoise}
 * provinces, which silently kill a gated biome across two-thirds of its climate envelope) or the
 * weighted competition between eligible biomes.
 *
 * <p>For each coarse cell this samples a handful of real world-block positions inside it, feeds
 * the cell's climate through {@link BiomeClassifier#probeCoarsePixel} (identical derivation +
 * rule engine as worldgen, with every noise field evaluated at its true world coordinate), and
 * reports the best outcome over the samples:</p>
 * <ul>
 *   <li>{@link #WINS} -- the target biome is the actual selection winner at some sampled point;
 *   visiting this cell should find it (up to sub-cell climate detail).</li>
 *   <li>{@link #ELIGIBLE} -- its rules match somewhere in the cell but a competitor wins every
 *   sampled point; it can still appear nearby since competition noise shifts at ~900-block
 *   wavelength.</li>
 *   <li>{@link #NONE} -- no rule matches any sampled point; searching here is a waste of time.</li>
 * </ul>
 *
 * <p>Each position is probed at two hypothetical slopes -- flat, and (only where the cell has
 * real internal relief) a bare-rock steep slope -- because a 256-pixel coarse cell always
 * contains both microsites in rough terrain and slope cannot be resolved from coarse data.
 * Known blind spots, disclosed in the UI: beach-zone rules (coastline is a spatial test) and
 * sub-cell elevation extremes (a cold peak inside a warm-average cell).</p>
 */
public final class BiomeSpawnOverlay {

    public static final byte NONE = 0;
    public static final byte ELIGIBLE = 1;
    public static final byte WINS = 2;

    /** Native-pixel offsets (of 256 per cell) probed along each axis: a centered 2x2. */
    private static final int[] SUBSAMPLE_OFFSETS = {64, 192};

    private static final float FLAT_SLOPE = 0.05f;
    /** Past every possible bare-slope threshold (max 1.19), so bareSlope-gated biomes resolve. */
    private static final float STEEP_SLOPE = 1.25f;
    /** Steep probes only run where mean-minus-p5 elevation shows the cell really has relief. */
    private static final float STEEP_RELIEF_THRESHOLD_M = 120f;

    private BiomeSpawnOverlay() {
    }

    /**
     * Computes the status grid for coarse cells {@code [ci0, ci0+H) x [cj0, cj0+W)}. All channel
     * arrays are row-major {@code H x W} in real units (elevation and p5 already de-sqrt-ed).
     * {@code scale} is the active world scale (blocks per native pixel).
     */
    public static byte[] compute(TerrainBiomeSettlement settlement,
                                  float[] elev, float[] p5, float[] temp, float[] tstd,
                                  float[] precip, float[] pcv,
                                  int ci0, int cj0, int H, int W, int scale) {
        byte[] out = new byte[H * W];
        short target = settlement.index();
        HydrologyParallel.forEachRow(0, H, W, r -> {
            int ci = ci0 + r;
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                int cj = cj0 + c;
                boolean hasRelief = elev[idx] - p5[idx] > STEEP_RELIEF_THRESHOLD_M;

                byte status = NONE;
                sampling:
                for (int oi : SUBSAMPLE_OFFSETS) {
                    for (int oj : SUBSAMPLE_OFFSETS) {
                        float blockX = (cj * 256 + oj) * (float) scale;
                        float blockZ = (ci * 256 + oi) * (float) scale;
                        status = best(status, probe(settlement, target, elev[idx], temp[idx],
                                tstd[idx], precip[idx], pcv[idx], FLAT_SLOPE, blockX, blockZ));
                        if (status == WINS) break sampling;
                        if (hasRelief) {
                            status = best(status, probe(settlement, target, elev[idx], temp[idx],
                                    tstd[idx], precip[idx], pcv[idx], STEEP_SLOPE, blockX, blockZ));
                            if (status == WINS) break sampling;
                        }
                    }
                }
                out[idx] = status;
            }
        });
        return out;
    }

    private static byte best(byte a, byte b) {
        return a >= b ? a : b;
    }

    private static byte probe(TerrainBiomeSettlement settlement, short target,
                               float elevation, float temp, float tstd, float precip, float pcv,
                               float slope, float blockX, float blockZ) {
        BiomeClassifier.CoarseProbe probe =
                BiomeClassifier.probeCoarsePixel(elevation, temp, tstd, precip, pcv, slope, blockX, blockZ);
        if (probe.winner() == target) return WINS;
        return anyRuleMatches(settlement, probe) ? ELIGIBLE : NONE;
    }

    /**
     * Whether any of the settlement's rules matches this probe in a zone the classifier would
     * actually consult for it -- the dispatch zone, plus {@code bareSlope} when that second pass
     * runs. Weightless rules are skipped, mirroring {@code BiomeRuleEngine}.
     */
    private static boolean anyRuleMatches(TerrainBiomeSettlement settlement,
                                           BiomeClassifier.CoarseProbe probe) {
        String zone = BiomeClassifier.zoneFor(probe.sample(), false);
        boolean barePass = probe.sample().bareSlope() && !probe.sample().ocean()
                && !probe.sample().mountain();
        for (TerrainBiomeRule rule : settlement.rules()) {
            if (rule.rarity() <= 0f) continue;
            String ruleZone = rule.zone();
            if (!ruleZone.equals(zone) && !(barePass && ruleZone.equals("bareSlope"))) continue;
            if (rule.matches(probe.sample()) && rule.matchesNoise(probe.noise())) return true;
        }
        return false;
    }
}
