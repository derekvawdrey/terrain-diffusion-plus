package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic tile-seeded Gaussian noise matching Python world_pipeline.gaussian_noise_patch.
 * Uses portable_rng (PCG64 + Marsaglia polar) and _tile_seed; same algorithm as Python.
 */
public final class GaussianNoisePatch {

    /**
     * Recently generated noise tiles, keyed by seed and tile coordinate.
     *
     * <p>Pipeline windows overlap -- decoder windows advance 192 pixels through 256-pixel noise
     * tiles, latent windows 32 through 64 -- so consecutive windows read mostly the same tiles.
     * A tile's values can only be produced by running its RNG stream from the start, so without
     * this each window regenerated up to four full tiles to use a quarter of them. Cached tiles
     * are immutable and keyed by everything that determines their contents, so reuse returns
     * exactly what regeneration would.
     */
    private static final long CACHE_MAX_BYTES = 32L * 1024 * 1024;

    private record TileKey(long baseSeed, int tileH, int tileW, int channels, int ty, int tx) {}

    private static final Map<TileKey, float[]> TILE_CACHE =
            new LinkedHashMap<>(64, 0.75f, true);
    private static long cachedBytes;

    /**
     * Writes a (channels, h, w) patch of standard-normal noise into {@code dest}, channel-major
     * and flat, starting at {@code destOffset}.
     *
     * <p>Flat is the only shape anyone wants: every caller hands the noise straight to a model as
     * one contiguous buffer, and these patches are built between model calls, where CPU time is
     * GPU idle time -- a {@code float[channels][h][w]} in between would cost an allocation and
     * two full copies per window.
     *
     * @param baseSeed   world seed (64-bit, matches Python WorldPipeline.seed)
     * @param y0         top pixel row (can be negative)
     * @param x0         left pixel column (can be negative)
     * @param h          patch height in pixels
     * @param w          patch width in pixels
     * @param channels   number of channels
     * @param tileH      tile height for seeding
     * @param tileW      tile width for seeding
     * @param dest       destination array, at least {@code destOffset + channels * h * w} long
     * @param destOffset index in {@code dest} where the first channel starts
     */
    public static void generateInto(long baseSeed, int y0, int x0, int h, int w,
                                    int channels, int tileH, int tileW,
                                    float[] dest, int destOffset) {
        int plane = h * w;

        int ty0 = Math.floorDiv(y0, tileH);
        int ty1 = Math.floorDiv(y0 + h - 1, tileH);
        int tx0 = Math.floorDiv(x0, tileW);
        int tx1 = Math.floorDiv(x0 + w - 1, tileW);

        for (int ty = ty0; ty <= ty1; ty++) {
            int tileY0 = ty * tileH;
            for (int tx = tx0; tx <= tx1; tx++) {
                int tileX0 = tx * tileW;

                int oy0 = Math.max(y0, tileY0);
                int oy1 = Math.min(y0 + h, tileY0 + tileH);
                int ox0 = Math.max(x0, tileX0);
                int ox1 = Math.min(x0 + w, tileX0 + tileW);

                float[] tileFlat = tile(baseSeed, tileH, tileW, channels, ty, tx);
                int runLength = ox1 - ox0;
                if (runLength <= 0) continue;

                for (int c = 0; c < channels; c++) {
                    int tilePlane = c * (tileH * tileW);
                    int outPlane = destOffset + c * plane;
                    for (int py = oy0; py < oy1; py++) {
                        System.arraycopy(
                                tileFlat, tilePlane + (py - tileY0) * tileW + (ox0 - tileX0),
                                dest, outPlane + (py - y0) * w + (ox0 - x0),
                                runLength);
                    }
                }
            }
        }
    }

    /** One tile's noise, from the cache when it is still there and freshly generated otherwise. */
    private static float[] tile(long baseSeed, int tileH, int tileW, int channels, int ty, int tx) {
        TileKey key = new TileKey(baseSeed, tileH, tileW, channels, ty, tx);
        synchronized (TILE_CACHE) {
            float[] cached = TILE_CACHE.get(key);
            if (cached != null) return cached;
        }

        int tileLen = channels * tileH * tileW;
        float[] tileFlat = new float[tileLen];
        PortableRng.fillStandardNormal(PortableRng.tileSeed(baseSeed, ty, tx), tileFlat, 0, tileLen);

        synchronized (TILE_CACHE) {
            // A concurrent generation of the same tile produced identical values, so either copy
            // is fine to keep; preferring the cached one keeps a single instance shared.
            float[] raced = TILE_CACHE.putIfAbsent(key, tileFlat);
            if (raced != null) return raced;
            cachedBytes += (long) tileLen * Float.BYTES;
            var iterator = TILE_CACHE.entrySet().iterator();
            while (cachedBytes > CACHE_MAX_BYTES && TILE_CACHE.size() > 1 && iterator.hasNext()) {
                Map.Entry<TileKey, float[]> eldest = iterator.next();
                cachedBytes -= (long) eldest.getValue().length * Float.BYTES;
                iterator.remove();
            }
        }
        return tileFlat;
    }

    /** Drops every cached noise tile; used when the world seed changes. */
    public static void clearCache() {
        synchronized (TILE_CACHE) {
            TILE_CACHE.clear();
            cachedBytes = 0L;
        }
    }
}
