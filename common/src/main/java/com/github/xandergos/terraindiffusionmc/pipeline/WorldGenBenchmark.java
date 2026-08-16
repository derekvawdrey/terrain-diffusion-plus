package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;
import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * End-to-end timing harness for one canonical hydrology tile: the exact work a player's machine
 * does when world generation reaches fresh terrain. Unlike
 * {@link com.github.xandergos.terraindiffusionmc.hydrology.HydrologyBenchmark}, which feeds the
 * river code synthetic noise, this runs the real ONNX pipeline (coarse -> latent -> decoder) and
 * then the river/biome passes over its output, so the printed split shows which half to optimise.
 *
 * <p>No Minecraft is needed: it drives {@link LocalTerrainProvider#generateHydrologyTileUncached}
 * with an explicit scale, so {@code WorldScaleManager} is never touched. Run it from a directory
 * with a {@code config/} folder and either a {@code terrain-diffusion-models/} directory or
 * {@code -DterrainDiffusion.devModelDirectory=...}.
 *
 * <p>Usage: {@code WorldGenBenchmark [scale] [tiles] [tileSize] [halo]} (defaults: scale 2, one
 * tile, and the configured hydrology tile size/halo). Each tile is generated at a different
 * origin with cold pipeline caches, so per-tile numbers are comparable.
 */
public final class WorldGenBenchmark {

    private WorldGenBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int scale = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int tiles = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int tileSize = args.length > 2 ? Integer.parseInt(args[2]) : TerrainDiffusionConfig.hydrologyTileSize();
        int halo = args.length > 3 ? Integer.parseInt(args[3]) : TerrainDiffusionConfig.hydrologyAnalysisHalo();
        // "cold": far-apart tiles with cleared caches, i.e. the worst case for one tile.
        // "walk": neighbouring tiles with caches kept, i.e. a player exploring in one direction.
        // "parN": N tiles generated at once, i.e. several chunk workers wanting fresh terrain.
        String mode = args.length > 4 ? args[4].toLowerCase() : "cold";
        boolean walk = mode.equals("walk");
        int parallelTiles = mode.startsWith("par") ? Integer.parseInt(mode.substring(3)) : 1;

        int analysis = tileSize + 2 * halo;
        System.out.printf("World generation benchmark: scale=%d tiles=%d tileSize=%d halo=%d%n",
                scale, tiles, tileSize, halo);
        System.out.printf("  analysis grid %dx%d blocks (%,d cells), native pixels ~%dx%d%n",
                analysis, analysis, (long) analysis * analysis, analysis / scale, analysis / scale);
        System.out.printf("  hydrology workers=%d, decoder batch=%d, offload_models=%s%n%n",
                HydrologyParallel.workerThreads(), TerrainDiffusionConfig.decoderBatchSize(),
                TerrainDiffusionConfig.offloadModels());

        long modelLoadStart = System.nanoTime();
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        System.out.printf("Models ready in %d ms (provider: %s)%n%n",
                millis(modelLoadStart, System.nanoTime()), OnnxModel.getResolvedInferenceProvider());

        if (parallelTiles > 1) {
            runConcurrent(provider, tiles, parallelTiles, tileSize, halo, scale, analysis);
            return;
        }

        List<Long> tileMillis = new ArrayList<>(tiles);
        for (int tile = 0; tile < tiles; tile++) {
            int coreI0 = walk ? 0 : tile * tileSize * 4;
            int coreJ0 = walk ? tile * tileSize : 0;
            if (!walk) {
                // Fresh origin and empty caches: nothing from the previous tile can be reused.
                provider.clearPipelineCaches();
            }
            InferenceStats.reset();

            long start = System.nanoTime();
            HydrologyProvider.HydrologyTile generated = provider.generateHydrologyTileUncached(
                    coreI0, coreJ0, tileSize, halo, scale, false);
            long elapsed = millis(start, System.nanoTime());
            tileMillis.add(elapsed);

            System.out.printf("Tile %d/%d at (%d, %d): %d ms  [checksum %d]%n",
                    tile + 1, tiles, coreJ0, coreI0, elapsed, checksum(generated));
            System.out.print(InferenceStats.format());
            System.out.println();
        }

        System.out.printf("Per-tile totals (ms): %s%n", tileMillis);
        if (tileMillis.size() > 1) {
            long sum = 0L;
            for (int i = 1; i < tileMillis.size(); i++) sum += tileMillis.get(i);
            System.out.printf("Mean excluding first tile: %d ms%n", sum / (tileMillis.size() - 1));
        }
        System.out.printf("Blocks per second: %.0f%n",
                (double) analysis * analysis * 1000.0 / Math.max(1L, tileMillis.get(tileMillis.size() - 1)));
    }

    /**
     * Generates {@code tiles} distant tiles through a pool of {@code workers} threads and reports
     * throughput. The point is overlap: one tile's GPU inference can run while another's river and
     * biome passes use the CPU, so wall time per tile should approach the larger of the two rather
     * than their sum. Tile results are checksummed to confirm concurrency changes nothing.
     */
    private static void runConcurrent(LocalTerrainProvider provider, int tiles, int workers,
                                      int tileSize, int halo, int scale, int analysis) throws Exception {
        System.out.printf("Concurrent mode: %d tiles through %d worker threads%n", tiles, workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<String>> jobs = new ArrayList<>(tiles);
            for (int tile = 0; tile < tiles; tile++) {
                int coreI0 = tile * tileSize * 4;
                jobs.add(() -> {
                    long start = System.nanoTime();
                    HydrologyProvider.HydrologyTile generated = provider.generateHydrologyTileUncached(
                            coreI0, 0, tileSize, halo, scale, false);
                    return String.format("  tile at (0, %d): %d ms  [checksum %d]",
                            coreI0, millis(start, System.nanoTime()), checksum(generated));
                });
            }
            long start = System.nanoTime();
            for (Future<String> result : pool.invokeAll(jobs)) {
                System.out.println(result.get());
            }
            long elapsed = millis(start, System.nanoTime());
            System.out.printf("%d tiles in %d ms -> %d ms per tile, %.0f blocks per second%n",
                    tiles, elapsed, elapsed / tiles,
                    (double) analysis * analysis * tiles * 1000.0 / Math.max(1L, elapsed));
            System.out.print(InferenceStats.format());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Cheap order-sensitive hash of the tile payload. Printing it makes an accidental change in
     * generated terrain visible immediately: an optimisation that keeps the numbers identical
     * keeps this identical too.
     */
    private static long checksum(HydrologyProvider.HydrologyTile tile) {
        long hash = 1125899906842597L;
        for (short value : tile.adjustedElevation) hash = hash * 31 + value;
        for (short value : tile.biomeIndexes) hash = hash * 31 + value;
        for (byte value : tile.waterMask) hash = hash * 31 + value;
        for (short value : tile.waterSurface) hash = hash * 31 + value;
        return hash;
    }

    private static long millis(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000L;
    }
}
