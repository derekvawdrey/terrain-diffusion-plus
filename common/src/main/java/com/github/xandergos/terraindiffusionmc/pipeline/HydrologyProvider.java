package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical, world-coordinate hydrology tiles shared by the explorer and Minecraft terrain generation.
 *
 * <p>The visible/requested region is never used as a drainage domain. Instead, every coordinate belongs to
 * one fixed hydrology tile. A tile is generated once from a fixed analysis window and then reused for every
 * overlapping request. This makes river beds, lake outlines, river biomes, and water surfaces invariant when
 * the explorer viewport moves or when neighbouring Minecraft chunks are requested in a different order.</p>
 */
public final class HydrologyProvider {
    /** Increment when a change makes cached hydrology semantically incompatible. */
    private static final int ALGORITHM_VERSION = 1;
    /** Aligns the default 1024x1024 explorer view [-512, 512) to one canonical tile. */
    private static final int GRID_ORIGIN = -512;

    @FunctionalInterface
    public interface TileGenerator {
        HydrologyTile generate(int coreI0, int coreJ0, int coreSize, int halo, int scale);
    }

    private record TileKey(long seed, int scale, int tileI, int tileJ, int algorithmVersion) {}

    private final TileGenerator generator;
    private final int tileSize;
    private final int halo;
    private final int maxEntries;
    private final long maxBytes;

    /** Access-ordered map, guarded by this instance. */
    private final LinkedHashMap<TileKey, HydrologyTile> cache =
            new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;

    public HydrologyProvider(TileGenerator generator) {
        this.generator = generator;
        this.tileSize = TerrainDiffusionConfig.hydrologyTileSize();
        this.halo = TerrainDiffusionConfig.hydrologyAnalysisHalo();
        this.maxEntries = TerrainDiffusionConfig.hydrologyCacheMaxEntries();
        this.maxBytes = TerrainDiffusionConfig.hydrologyCacheMaxBytes();
    }

    /**
     * Assemble an arbitrary world-coordinate region from canonical hydrology tiles.
     * Coordinates use i=Z/row and j=X/column and the end coordinates are exclusive.
     */
    public HydrologyRegion getRegion(long seed, int i1, int j1, int i2, int j2, int scale, boolean withBiomes) {
        if (i2 <= i1 || j2 <= j1) {
            throw new IllegalArgumentException("Hydrology region must have positive dimensions");
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("Hydrology scale must be positive");
        }

        int height = i2 - i1;
        int width = j2 - j1;
        int n = Math.multiplyExact(height, width);
        float[] elevation = new float[n];
        float[] riverStrength = new float[n];
        float[] lakeDepth = new float[n];
        float[] waterSurface = new float[n];
        short[] biomeIndexes = withBiomes ? new short[n] : null;

        int firstTileI = tileIndex(i1);
        int lastTileI = tileIndex(i2 - 1);
        int firstTileJ = tileIndex(j1);
        int lastTileJ = tileIndex(j2 - 1);

        for (int tileI = firstTileI; tileI <= lastTileI; tileI++) {
            for (int tileJ = firstTileJ; tileJ <= lastTileJ; tileJ++) {
                HydrologyTile tile = getTile(seed, scale, tileI, tileJ);
                copyIntersection(tile, i1, j1, i2, j2, elevation, riverStrength,
                        lakeDepth, waterSurface, biomeIndexes, width);
            }
        }

        return new HydrologyRegion(elevation, riverStrength, lakeDepth, waterSurface,
                biomeIndexes, height, width);
    }

    public synchronized void clear() {
        cache.clear();
        retainedBytes = 0L;
    }

    public int tileSize() {
        return tileSize;
    }

    public int analysisHalo() {
        return halo;
    }

    private synchronized HydrologyTile getTile(long seed, int scale, int tileI, int tileJ) {
        TileKey key = new TileKey(seed, scale, tileI, tileJ, ALGORITHM_VERSION);
        HydrologyTile cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        int coreI0 = tileOrigin(tileI);
        int coreJ0 = tileOrigin(tileJ);
        HydrologyTile generated = generator.generate(coreI0, coreJ0, tileSize, halo, scale);
        validateTile(generated, coreI0, coreJ0);

        cache.put(key, generated);
        retainedBytes += generated.estimatedBytes();
        evictIfNeeded();
        return generated;
    }

    private void evictIfNeeded() {
        Iterator<Map.Entry<TileKey, HydrologyTile>> iterator = cache.entrySet().iterator();
        while (cache.size() > 1 && (cache.size() > maxEntries || retainedBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<TileKey, HydrologyTile> eldest = iterator.next();
            retainedBytes -= eldest.getValue().estimatedBytes();
            iterator.remove();
        }
    }

    private void validateTile(HydrologyTile tile, int coreI0, int coreJ0) {
        if (tile == null) {
            throw new IllegalStateException("Hydrology tile generator returned null");
        }
        if (tile.originI != coreI0 || tile.originJ != coreJ0 || tile.height != tileSize || tile.width != tileSize) {
            throw new IllegalStateException("Hydrology tile generator returned an unexpected tile shape/origin");
        }
        int n = tileSize * tileSize;
        if (tile.adjustedElevation.length != n || tile.riverStrength.length != n
                || tile.lakeDepth.length != n || tile.waterSurface.length != n
                || tile.biomeIndexes.length != n) {
            throw new IllegalStateException("Hydrology tile arrays do not match the configured tile size");
        }
    }

    private int tileIndex(int coordinate) {
        long shifted = (long) coordinate - GRID_ORIGIN;
        return (int) Math.floorDiv(shifted, (long) tileSize);
    }

    private int tileOrigin(int tileIndex) {
        return Math.toIntExact(GRID_ORIGIN + (long) tileIndex * tileSize);
    }

    private static void copyIntersection(HydrologyTile tile,
                                         int requestI1, int requestJ1, int requestI2, int requestJ2,
                                         float[] elevation, float[] riverStrength, float[] lakeDepth,
                                         float[] waterSurface, short[] biomeIndexes, int requestWidth) {
        int tileI2 = tile.originI + tile.height;
        int tileJ2 = tile.originJ + tile.width;
        int copyI1 = Math.max(requestI1, tile.originI);
        int copyJ1 = Math.max(requestJ1, tile.originJ);
        int copyI2 = Math.min(requestI2, tileI2);
        int copyJ2 = Math.min(requestJ2, tileJ2);
        if (copyI2 <= copyI1 || copyJ2 <= copyJ1) return;

        int copyWidth = copyJ2 - copyJ1;
        int srcCol = copyJ1 - tile.originJ;
        int dstCol = copyJ1 - requestJ1;
        for (int worldI = copyI1; worldI < copyI2; worldI++) {
            int srcRow = worldI - tile.originI;
            int dstRow = worldI - requestI1;
            int srcOffset = srcRow * tile.width + srcCol;
            int dstOffset = dstRow * requestWidth + dstCol;
            System.arraycopy(tile.adjustedElevation, srcOffset, elevation, dstOffset, copyWidth);
            System.arraycopy(tile.riverStrength, srcOffset, riverStrength, dstOffset, copyWidth);
            System.arraycopy(tile.lakeDepth, srcOffset, lakeDepth, dstOffset, copyWidth);
            System.arraycopy(tile.waterSurface, srcOffset, waterSurface, dstOffset, copyWidth);
            if (biomeIndexes != null) {
                System.arraycopy(tile.biomeIndexes, srcOffset, biomeIndexes, dstOffset, copyWidth);
            }
        }
    }

    public static final class HydrologyTile {
        public final int originI;
        public final int originJ;
        public final float[] adjustedElevation;
        public final float[] riverStrength;
        public final float[] lakeDepth;
        public final float[] waterSurface;
        public final short[] biomeIndexes;
        public final int height;
        public final int width;

        public HydrologyTile(int originI, int originJ, float[] adjustedElevation, float[] riverStrength,
                             float[] lakeDepth, float[] waterSurface, short[] biomeIndexes,
                             int height, int width) {
            this.originI = originI;
            this.originJ = originJ;
            this.adjustedElevation = adjustedElevation;
            this.riverStrength = riverStrength;
            this.lakeDepth = lakeDepth;
            this.waterSurface = waterSurface;
            this.biomeIndexes = biomeIndexes;
            this.height = height;
            this.width = width;
        }

        long estimatedBytes() {
            return 96L
                    + (long) adjustedElevation.length * Float.BYTES
                    + (long) riverStrength.length * Float.BYTES
                    + (long) lakeDepth.length * Float.BYTES
                    + (long) waterSurface.length * Float.BYTES
                    + (long) biomeIndexes.length * Short.BYTES;
        }
    }

    public record HydrologyRegion(float[] adjustedElevation, float[] riverStrength, float[] lakeDepth,
                                  float[] waterSurface, short[] biomeIndexes, int height, int width) {
        public byte[] waterMaskBytes() {
            int n = height * width;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                float riverValue = clamp01(riverStrength[i]) * 255.0f;
                float lakeValue = clamp01(lakeDepth[i] / 24.0f) * 255.0f;
                out[i] = (byte) Math.max(0, Math.min(255, Math.round(Math.max(riverValue, lakeValue))));
            }
            return out;
        }

        private static float clamp01(float value) {
            if (value < 0.0f) return 0.0f;
            if (value > 1.0f) return 1.0f;
            return value;
        }
    }
}
