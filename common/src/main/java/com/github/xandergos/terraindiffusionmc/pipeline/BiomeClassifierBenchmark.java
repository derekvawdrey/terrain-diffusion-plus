package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone timing harness for {@link BiomeClassifier#classify}, the single most expensive CPU
 * phase of a hydrology tile. It replays the classifier over a full tile-sized grid so rule
 * evaluation can be optimised without paying for GPU inference on every iteration.
 *
 * <p>Input fidelity matters here: how long the rule engine runs depends on which zones and which
 * biomes the pixels land in. So the harness prefers <em>real</em> pipeline output, captured once
 * through {@link LocalTerrainProvider#getRiverTerrainData} (models required, ~10 s) and cached to
 * {@code terrain-diffusion-cache/classify-inputs-<size>.bin}; later runs load the cache in
 * milliseconds. With {@code -Dbenchmark.synthetic=true} it falls back to a synthetic field, which
 * needs no models but exercises far fewer catalog rules.
 *
 * <p>Usage: {@code BiomeClassifierBenchmark [size] [repeats] [seed] [pixelSizeM]} (defaults 2048,
 * 3, 1234, 15). The printed checksum must not change across optimisation work: the classifier's
 * output is world data, so a faster classifier is only correct if it is bit-identical.
 */
public final class BiomeClassifierBenchmark {

    private BiomeClassifierBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 2048;
        int repeats = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 1234L;
        // Matches a scale-2 world: 30 m native model resolution over 2 blocks per pixel.
        float pixelSizeM = args.length > 3 ? Float.parseFloat(args[3]) : 15f;

        System.out.printf("BiomeClassifier benchmark: %dx%d (%,d px), %d repeats, pixelSize %.1f m, %d workers%n",
                size, size, size * size, repeats, pixelSizeM, HydrologyParallel.workerThreads());

        Inputs warm = synthesise(256, seed);
        for (int i = 0; i < 3; i++) {
            BiomeClassifier.classify(warm.elevation, warm.climate, 0, 0, warm.elevationPadded, 256, 256, pixelSizeM);
        }

        Inputs inputs = Boolean.getBoolean("benchmark.synthetic") ? synthesise(size, seed) : realInputs(size);
        long best = Long.MAX_VALUE;
        long checksum = 0L;
        for (int repeat = 0; repeat < repeats; repeat++) {
            long start = System.nanoTime();
            short[] biomes = BiomeClassifier.classify(inputs.elevation, inputs.climate, 0, 0,
                    inputs.elevationPadded, size, size, pixelSizeM);
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            best = Math.min(best, elapsed);
            checksum = checksum(biomes);
            System.out.printf("  run %d: %d ms  [checksum %d, distinct %d]%n",
                    repeat + 1, elapsed, checksum, distinct(biomes));
        }
        System.out.printf("Best: %d ms  checksum %d%n", best, checksum);
    }

    private record Inputs(float[] elevation, float[] elevationPadded, float[] climate) {}

    /**
     * Real pipeline output for a {@code size x size} region, captured once and cached. The capture
     * asks for one extra pixel on every side so the padded elevation the classifier needs for its
     * slope kernel is the genuine neighbouring terrain, exactly as world generation supplies it.
     */
    private static Inputs realInputs(int size) throws Exception {
        Path cache = Path.of("terrain-diffusion-cache", "classify-inputs-" + size + ".bin");
        Inputs cached = readCache(cache, size);
        if (cached != null) {
            System.out.printf("Loaded captured pipeline inputs from %s%n", cache);
            return cached;
        }

        System.out.println("Capturing real pipeline inputs (loads models, generates one hydrology tile)...");
        long start = System.nanoTime();
        LocalTerrainProvider.RiverTerrainData data =
                LocalTerrainProvider.getRiverTerrainData(-1, -1, size + 1, size + 1, false);
        System.out.printf("Captured in %d ms%n", (System.nanoTime() - start) / 1_000_000L);

        int paddedWidth = size + 2;
        float[] elevationPadded = data.elevation;
        float[] elevation = new float[size * size];
        for (int r = 0; r < size; r++) {
            System.arraycopy(elevationPadded, (r + 1) * paddedWidth + 1, elevation, r * size, size);
        }
        int channels = data.climate.length / (paddedWidth * paddedWidth);
        float[] climate = new float[4 * size * size];
        for (int channel = 0; channel < 4 && channel < channels; channel++) {
            for (int r = 0; r < size; r++) {
                System.arraycopy(data.climate, channel * paddedWidth * paddedWidth + (r + 1) * paddedWidth + 1,
                        climate, channel * size * size + r * size, size);
            }
        }
        Inputs inputs = new Inputs(elevation, elevationPadded, climate);
        writeCache(cache, size, inputs);
        return inputs;
    }

    private static Inputs readCache(Path path, int size) {
        if (!Files.isRegularFile(path)) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 20))) {
            if (in.readInt() != size) return null;
            float[] elevation = readFloats(in, size * size);
            float[] elevationPadded = readFloats(in, (size + 2) * (size + 2));
            float[] climate = readFloats(in, 4 * size * size);
            return new Inputs(elevation, elevationPadded, climate);
        } catch (IOException exception) {
            System.out.println("Ignoring unreadable input cache: " + exception.getMessage());
            return null;
        }
    }

    private static void writeCache(Path path, int size, Inputs inputs) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 20))) {
            out.writeInt(size);
            writeFloats(out, inputs.elevation);
            writeFloats(out, inputs.elevationPadded);
            writeFloats(out, inputs.climate);
        }
        System.out.printf("Cached pipeline inputs to %s%n", path.toAbsolutePath());
    }

    private static float[] readFloats(DataInputStream in, int count) throws IOException {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) values[i] = in.readFloat();
        return values;
    }

    private static void writeFloats(DataOutputStream out, float[] values) throws IOException {
        for (float value : values) out.writeFloat(value);
    }

    /** Elevation with oceans, coasts and mountains, plus lapse-rate temperature and a rain gradient. */
    private static Inputs synthesise(int size, long seed) {
        FastNoiseLite terrain = fnl((int) seed, 1f / 900f, 6, 0.5f);
        FastNoiseLite ridges = fnl((int) seed + 7, 1f / 220f, 4, 0.55f);
        FastNoiseLite rain = fnl((int) seed + 13, 1f / 1600f, 3, 0.5f);
        FastNoiseLite season = fnl((int) seed + 29, 1f / 2400f, 2, 0.5f);

        float[] elevation = new float[size * size];
        float[] climate = new float[4 * size * size];
        int plane = size * size;
        HydrologyParallel.forEachRow(0, size, size, r -> {
            for (int c = 0; c < size; c++) {
                int idx = r * size + c;
                float base = terrain.GetNoise(c, r);
                float detail = ridges.GetNoise(c, r);
                // Cubic shaping puts ~30% of the grid below sea level and lifts the rest into a
                // long tail of hills and mountains, like the real elevation histogram.
                float shaped = base * base * base * 2600f + detail * 180f;
                float elev = shaped - 260f;
                elevation[idx] = elev;

                float altitude = Math.max(0f, elev);
                float latitude = (float) r / size;
                climate[idx] = 27f - 26f * latitude - 0.0062f * altitude + 3f * detail;
                climate[plane + idx] = 300f + 900f * Math.abs(season.GetNoise(c, r));
                climate[2 * plane + idx] = Math.max(0f, 1400f + 1300f * rain.GetNoise(c, r) - 0.15f * altitude);
                climate[3 * plane + idx] = 40f + 60f * Math.abs(rain.GetNoise(c * 0.5f, r * 0.5f));
            }
        });

        float[] padded = new float[(size + 2) * (size + 2)];
        HydrologyParallel.forEachRow(0, size + 2, size + 2, r -> {
            int sr = Math.max(0, Math.min(size - 1, r - 1));
            for (int c = 0; c < size + 2; c++) {
                int sc = Math.max(0, Math.min(size - 1, c - 1));
                padded[r * (size + 2) + c] = elevation[sr * size + sc];
            }
        });
        return new Inputs(elevation, padded, climate);
    }

    private static FastNoiseLite fnl(int seed, float frequency, int octaves, float gain) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFrequency(frequency);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(octaves);
        noise.SetFractalLacunarity(2f);
        noise.SetFractalGain(gain);
        return noise;
    }

    private static long checksum(short[] biomes) {
        long hash = 1125899906842597L;
        for (short value : biomes) hash = hash * 31 + value;
        return hash;
    }

    private static int distinct(short[] biomes) {
        boolean[] seen = new boolean[Short.MAX_VALUE];
        int count = 0;
        for (short value : biomes) {
            if (value >= 0 && !seen[value]) {
                seen[value] = true;
                count++;
            }
        }
        return count;
    }
}
