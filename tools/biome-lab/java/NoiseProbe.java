import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ground-truth noise statistics generator for the biome-lab pipeline.
 *
 * <p>BiomeClassifier.java (in the main mod source) builds several named FastNoiseLite fields
 * (TEMP_NOISE, PRECIP_NOISE, BIOME_VARIANT_NOISE, ...), each a Perlin-FBm field with some
 * (octaves, gain) pair. The *marginal* distribution of an FBm field's output only depends on
 * (octaves, gain) -- not on frequency or seed -- because frequency just rescales the coordinate
 * space and seed just re-shuffles the gradient lattice; neither changes the shape of the value
 * distribution sampled over a large-enough region. So instead of probing every named field
 * separately, this tool probes each *distinct* (octaves, gain) pair used anywhere in
 * BiomeClassifier's static initializer once, with millions of samples, and writes out an
 * empirical quantile table + summary stats.
 *
 * <p>Distinct families actually used by BiomeClassifier.java as of this writing (see its
 * `static { ... }` block):
 * <pre>
 *   TEMP_NOISE, SNOW_NOISE                          -> octaves=3, gain=0.50   ("oct3_gain050")
 *   TEMP_NOISE_FINE, SNOW_NOISE_FINE, REGION_NOISE   -> octaves=2, gain=0.50   ("oct2_gain050")
 *   PRECIP_NOISE                                     -> octaves=5, gain=0.50   ("oct5_gain050")
 *   BIOME_VARIANT_NOISE, CHERRY_GROVE_NOISE,
 *     PALE_GARDEN_NOISE                              -> octaves=3, gain=0.55   ("oct3_gain055")
 *   FOREST_CLEARING_NOISE, FLOWER_PATCH_NOISE         -> octaves=3, gain=0.54   ("oct3_gain054")
 * </pre>
 *
 * <p>Output: one JSON file per family under the output directory (default {@code ../data/noise_quantiles}
 * relative to this tool's compiled classes, overridable with {@code --out}), each containing the
 * measured min/max/mean/stddev plus a dense quantile table (1000 evenly spaced percentiles, 0..100,
 * step 0.1, plus a fine-grained tail from 99.0 to 100.0 in 0.01 steps) so downstream Python code can
 * do inverse-CDF sampling and can calibrate "top N% pass rate" noise thresholds for auto-fix
 * suggestions. Also writes a combined {@code summary.json} with just the per-family max-abs ceiling,
 * which is all the static ceiling validator needs.
 *
 * <p>Usage:
 * <pre>
 *   javac -d out java/FastNoiseLite.java java/NoiseProbe.java
 *   java -cp out NoiseProbe --out data/noise_quantiles --grid 2500
 * </pre>
 * {@code --grid N} samples an N x N grid (default 2500 -> 6.25M samples per family) spaced across a
 * +-100000 block world-coordinate window, matching the kind of range biome generation actually
 * covers. Frequency is fixed at 1/500 for every family (doesn't affect the marginal distribution --
 * see above) and lacunarity is fixed at 2.0 (the only value BiomeClassifier ever uses).
 */
public final class NoiseProbe {

    record Family(String name, int octaves, float gain, List<String> usedBy) {}

    public static void main(String[] args) throws IOException {
        String outDir = "data/noise_quantiles";
        int grid = 2500;
        float range = 100000f;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outDir = args[++i];
                case "--grid" -> grid = Integer.parseInt(args[++i]);
                case "--range" -> range = Float.parseFloat(args[++i]);
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }

        List<Family> families = List.of(
                new Family("oct3_gain050", 3, 0.50f, List.of("tempNoiseCoarse", "snowNoiseCoarse")),
                new Family("oct2_gain050", 2, 0.50f, List.of("tempNoiseFine", "snowNoiseFine", "regionNoise")),
                new Family("oct5_gain050", 5, 0.50f, List.of("precipNoise")),
                new Family("oct3_gain055", 3, 0.55f, List.of("variantNoise", "cherryNoise", "paleNoise")),
                new Family("oct3_gain054", 3, 0.54f, List.of("clearingNoise", "flowerNoise"))
        );

        Path outPath = Path.of(outDir);
        Files.createDirectories(outPath);

        StringBuilder summary = new StringBuilder();
        summary.append("{\n");
        long grandTotal = (long) grid * grid;
        System.out.printf("Probing %d families, %d x %d = %,d samples each, range +-%.0f blocks%n",
                families.size(), grid, grid, grandTotal, range);

        for (int fi = 0; fi < families.size(); fi++) {
            Family fam = families.get(fi);
            long t0 = System.currentTimeMillis();
            float[] values = sample(fam, grid, range);
            java.util.Arrays.sort(values);
            long t1 = System.currentTimeMillis();

            double sum = 0, sumSq = 0;
            for (float v : values) { sum += v; sumSq += (double) v * v; }
            double mean = sum / values.length;
            double variance = sumSq / values.length - mean * mean;
            double stddev = Math.sqrt(Math.max(0, variance));
            float min = values[0];
            float max = values[values.length - 1];
            float maxAbs = Math.max(Math.abs(min), Math.abs(max));

            List<double[]> quantiles = new ArrayList<>(); // [percentile, value]
            for (int p = 0; p <= 1000; p++) {
                double pct = p / 10.0; // 0.0 .. 100.0 step 0.1
                quantiles.add(new double[]{pct, quantileOf(values, pct)});
            }
            for (int p = 0; p <= 100; p++) {
                double pct = 99.0 + p / 100.0; // 99.00 .. 100.00 step 0.01, fine tail
                quantiles.add(new double[]{pct, quantileOf(values, pct)});
            }

            Path famFile = outPath.resolve(fam.name() + ".json");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(famFile))) {
                w.println("{");
                w.printf("  \"family\": \"%s\",%n", fam.name());
                w.printf("  \"octaves\": %d,%n", fam.octaves());
                w.printf("  \"gain\": %.4f,%n", fam.gain());
                w.printf("  \"lacunarity\": 2.0,%n");
                w.printf("  \"usedBy\": [%s],%n", fam.usedBy().stream().map(s -> "\"" + s + "\"").reduce((a, b) -> a + ", " + b).orElse(""));
                w.printf("  \"samples\": %d,%n", values.length);
                w.printf("  \"min\": %.6f,%n", min);
                w.printf("  \"max\": %.6f,%n", max);
                w.printf("  \"maxAbs\": %.6f,%n", maxAbs);
                w.printf("  \"mean\": %.6f,%n", mean);
                w.printf("  \"stddev\": %.6f,%n", stddev);
                w.println("  \"quantiles\": [");
                for (int i = 0; i < quantiles.size(); i++) {
                    double[] pv = quantiles.get(i);
                    w.printf("    [%.4f, %.6f]%s%n", pv[0], pv[1], i + 1 < quantiles.size() ? "," : "");
                }
                w.println("  ]");
                w.println("}");
            }

            System.out.printf("[%s] octaves=%d gain=%.2f  min=%.5f max=%.5f maxAbs=%.5f mean=%.5f stddev=%.5f  (%.1fs)%n",
                    fam.name(), fam.octaves(), fam.gain(), min, max, maxAbs, mean, stddev, (t1 - t0) / 1000.0);

            summary.append(String.format("  \"%s\": {\"octaves\": %d, \"gain\": %.4f, \"maxAbs\": %.6f, \"min\": %.6f, \"max\": %.6f, \"usedBy\": [%s]}%s%n",
                    fam.name(), fam.octaves(), fam.gain(), maxAbs, min, max,
                    fam.usedBy().stream().map(s -> "\"" + s + "\"").reduce((a, b) -> a + ", " + b).orElse(""),
                    fi + 1 < families.size() ? "," : ""));
        }
        summary.append("}\n");
        Files.writeString(outPath.resolve("summary.json"), summary.toString());
        System.out.println("Wrote " + outPath.resolve("summary.json"));
    }

    private static float[] sample(Family fam, int grid, float range) {
        FastNoiseLite fnl = new FastNoiseLite(1);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(1f / 500f);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(fam.octaves());
        fnl.SetFractalLacunarity(2.0f);
        fnl.SetFractalGain(fam.gain());

        float[] values = new float[grid * grid];
        float step = (2 * range) / grid;
        int k = 0;
        for (int i = 0; i < grid; i++) {
            float y = -range + i * step;
            for (int j = 0; j < grid; j++) {
                float x = -range + j * step;
                values[k++] = fnl.GetNoise(x, y);
            }
        }
        return values;
    }

    /** Linear-interpolated percentile (0..100) over an already-sorted array. */
    private static double quantileOf(float[] sorted, double pct) {
        double p = Math.max(0, Math.min(100, pct)) / 100.0;
        double idx = p * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = idx - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }
}
