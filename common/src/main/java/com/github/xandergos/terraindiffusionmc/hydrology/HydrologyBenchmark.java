package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

/**
 * Standalone timing harness for the river-generation hot path. No Minecraft, ONNX/GPU, or
 * world save is needed: it builds synthetic rolling terrain and runs {@link FluvialRiverNetwork#build}
 * + {@link DetailedRiverCarver#carve} directly, the same two calls {@code computeHydrologyTile} in
 * {@code LocalTerrainProvider} makes for every real tile. Both classes already log a per-phase
 * (ms) breakdown via SLF4J; this harness prints its own totals regardless of whether a logging
 * backend is on the classpath, so results are visible either way.
 *
 * <p>Defaults to whatever {@code hydrology.tile_size}/{@code hydrology.analysis_halo} resolve to
 * (real config if run with a working directory that has a sibling {@code config/} folder, code
 * defaults otherwise) so the numbers match what actually happens in-game. Override via args for
 * quick iteration on a smaller grid: {@code tileSize halo pixelSizeM seed}.
 *
 * <p>Memory note: at the default 8192/512 config this is an ~85M-cell (9216x9216) grid, and
 * {@code FluvialRiverNetwork.build} allocates on the order of fifteen arrays that size. Run with
 * a large heap (e.g. {@code -Xmx8g}) or pass smaller args for fast iteration.
 */
public class HydrologyBenchmark {

    public static void main(String[] args) {
        int tileSize = args.length > 0 ? Integer.parseInt(args[0]) : TerrainDiffusionConfig.hydrologyTileSize();
        int halo = args.length > 1 ? Integer.parseInt(args[1]) : TerrainDiffusionConfig.hydrologyAnalysisHalo();
        float pixelSizeM = args.length > 2 ? Float.parseFloat(args[2]) : 4.0f;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 12345L;

        int size = tileSize + 2 * halo;
        long cells = (long) size * size;
        System.out.printf("Hydrology benchmark: tileSize=%d halo=%d pixelSizeM=%.2f -> analysis grid %dx%d "
                        + "(%,d cells), workerThreads=%d%n",
                tileSize, halo, pixelSizeM, size, size, cells, HydrologyParallel.workerThreads());
        System.out.println("(Per-phase breakdowns print below via FluvialRiverNetwork/DetailedRiverCarver's own logging, if a logger backend is configured.)");
        System.out.println();

        System.out.println("Warming up JIT on a small grid...");
        warmup();
        System.out.println();

        float[] elevation = syntheticElevation(size, size, seed);

        long t0 = System.nanoTime();
        FluvialRiverNetwork.RiverTopology topology = FluvialRiverNetwork.build(
                seed, 0, 0, elevation, null, size, size, pixelSizeM, false, 0f);
        long t1 = System.nanoTime();
        DetailedRiverCarver.carve(elevation, topology, size, size, pixelSizeM);
        long t2 = System.nanoTime();

        System.out.println();
        System.out.printf("Totals (ms): FluvialRiverNetwork.build=%d  DetailedRiverCarver.carve=%d  combined=%d%n",
                millis(t0, t1), millis(t1, t2), millis(t0, t2));
    }

    private static void warmup() {
        int warmSize = 512;
        float[] warmElevation = syntheticElevation(warmSize, warmSize, 1L);
        FluvialRiverNetwork.RiverTopology topology = FluvialRiverNetwork.build(
                1L, 0, 0, warmElevation, null, warmSize, warmSize, 1.0f, false, 0f);
        DetailedRiverCarver.carve(warmElevation, topology, warmSize, warmSize, 1.0f);
    }

    /** Layered value noise: enough ridges/depressions to exercise priority-flood and drainage realistically. */
    private static float[] syntheticElevation(int height, int width, long seed) {
        FastNoiseLite noise = new FastNoiseLite((int) seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(6);
        noise.SetFractalLacunarity(2.0f);
        noise.SetFractalGain(0.5f);
        noise.SetFrequency(1f / 900f);

        float[] elevation = new float[height * width];
        HydrologyParallel.forEachRow(0, height, width, r -> {
            for (int c = 0; c < width; c++) {
                float n = noise.GetNoise((float) c, (float) r);
                elevation[r * width + c] = 300f + 900f * (n * 0.5f + 0.5f);
            }
        });
        return elevation;
    }

    private static long millis(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000L;
    }
}
