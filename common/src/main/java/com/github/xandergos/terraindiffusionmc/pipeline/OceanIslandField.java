package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;

/**
 * Stamps oceanic islands into the synthetic elevation conditioning map.
 *
 * <p>That channel is a quantile-matched fBm of Earth's real hypsometry, so it inherits the
 * bimodal split between continents and abyssal plain: open ocean sits at a near-uniform 3-5 km
 * depth and essentially never breaks the surface. Islands therefore have to be added as their
 * own sparse field of seamounts, which the elevation channel is raised to.
 *
 * <p>Four independent jittered lattices ("layers") each stamp one archetype -- reef-sized
 * specks, ordinary islands, large landmasses, and rare volcanic spires. A stretch of ocean can
 * draw any mix of them, and layers that overlap read as archipelagos.
 *
 * <p>All coordinates here are coarse-map pixels. One coarse pixel is 256 native pixels, which is
 * 512 blocks at the default world scale of 2.
 */
final class OceanIslandField {

    /** Base elevation lookup in raw metres at fractional coarse-map coordinates. */
    interface ElevationProbe {
        float elevationAt(float x, float y);
    }

    /**
     * One island archetype.
     *
     * @param cellSize  lattice spacing in coarse pixels; one candidate island per cell
     * @param presence  probability that a cell holds an island at all, before the depth gate
     * @param minRadius shoreline radius range in coarse pixels (log-uniform)
     * @param minPeak   summit height range in metres above sea level (log-uniform)
     * @param minShape  profile exponent range: below 1 gives a sharp peak with a long skirt,
     *                  above 1 a flat top with steep sides
     * @param lobe      how far the shoreline is allowed to deviate from a circle, as a fraction
     * @param salt      per-layer hash salt, so layers are statistically independent
     */
    private record Layer(int cellSize, float presence,
                         float minRadius, float maxRadius,
                         float minPeak, float maxPeak,
                         float minShape, float maxShape,
                         float lobe, int salt) {
    }

    /**
     * The lattices are searched three cells wide, so an island must never reach further than one
     * cell from its own. {@link #validateLayers()} checks that at class-init time.
     */
    private static final Layer[] LAYERS = {
            //        cell  presence   radius        peak          shape       lobe  salt
            new Layer(  11,   0.40f,  0.30f, 0.75f,   15f,   90f,  1.4f, 2.6f, 0.35f, 0x51ee),
            new Layer(  18,   0.45f,  0.75f, 2.00f,   80f,  600f,  1.0f, 2.2f, 0.32f, 0x9a13),
            new Layer(  50,   0.45f,  2.00f, 4.50f,  250f, 1300f,  0.9f, 1.8f, 0.26f, 0x2c77),
            new Layer(  46,   0.32f,  1.00f, 2.60f, 1800f, 3800f,  1.4f, 2.4f, 0.22f, 0xd405),
    };

    /**
     * How far the submerged flank runs past the shoreline, as a multiple of shoreline radius.
     *
     * <p>The flank exists so the conditioning map hands the model a coherent seamount instead of
     * a lone raised pixel in the middle of a 5 km abyssal plain. It is not meant to be scenery:
     * Minecraft's depth curve squeezes -2000 m and -4500 m into about 24 blocks of each other, so
     * a wide flank repaves a quarter of the sea floor for something no player can see.
     */
    private static final float MIN_OUTER = 1.25f;
    private static final float MAX_OUTER = 1.75f;
    /** Depth in metres the flank falls to at its outer edge, before the sea floor takes over. */
    private static final float MIN_FLANK_DEPTH = 700f;
    private static final float MAX_FLANK_DEPTH = 2000f;

    /**
     * Islands are only seeded where the sea floor is already this deep, which keeps them off the
     * continental shelf. Earth's hypsometry drops from shore to abyssal plain within about one
     * coarse pixel, so this costs almost no ocean area -- it just stops islands from being born
     * fused to a coastline.
     */
    private static final float MIN_SEED_DEPTH = -2000f;

    /** Subsamples per axis. An island narrower than a coarse pixel has to survive as a partial. */
    private static final int SUBSAMPLES = 4;

    private final boolean enabled;
    private final float density;
    private final float relief;
    private final long seed;

    static {
        validateLayers();
    }

    OceanIslandField(long seed) {
        this.seed = seed;
        this.enabled = TerrainDiffusionConfig.oceanIslandsEnabled();
        this.density = TerrainDiffusionConfig.oceanIslandDensity();
        this.relief = TerrainDiffusionConfig.oceanIslandRelief();
    }

    boolean isActive() {
        return enabled && density > 0f && relief > 0f;
    }

    private static void validateLayers() {
        for (Layer layer : LAYERS) {
            float reach = layer.maxRadius * (1f + layer.lobe) * MAX_OUTER;
            if (reach > layer.cellSize) {
                throw new IllegalStateException("Island layer salt " + Integer.toHexString(layer.salt)
                        + " reaches " + reach + " coarse px, past its " + layer.cellSize + " px cell");
            }
        }
    }

    /**
     * Raises {@code elevation} wherever an island stands over it.
     *
     * @param elevation row-major raw elevation in metres; entry {@code r * width + c} is the
     *                  value sampled at coarse coordinate {@code (x1 + c, y1 + r)}
     * @param probe     base elevation lookup, used to reject island seeds over shallow water
     */
    void apply(float[] elevation, int x1, int y1, int width, int height, ElevationProbe probe) {
        if (!isActive()) return;

        SiteGrid[] grids = new SiteGrid[LAYERS.length];
        boolean anySites = false;
        for (int li = 0; li < LAYERS.length; li++) {
            grids[li] = buildSites(LAYERS[li], x1, y1, width, height, probe);
            anySites |= grids[li].siteCount > 0;
        }
        if (!anySites) return;

        // Half a pixel of diagonal: the widest a subsample can sit from its pixel centre.
        final float pixelReach = 0.7072f;
        final float step = 1f / SUBSAMPLES;
        final float first = -0.5f + step * 0.5f;

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                float px = x1 + c;
                float py = y1 + r;
                if (!touches(grids, px, py, pixelReach)) continue;

                float base = elevation[r * width + c];
                float sum = 0f;
                boolean stood = false;
                for (int sr = 0; sr < SUBSAMPLES; sr++) {
                    float sy = py + first + sr * step;
                    for (int sc = 0; sc < SUBSAMPLES; sc++) {
                        float sx = px + first + sc * step;
                        float h = heightAt(grids, sx, sy);
                        if (h > base) {
                            stood = true;
                            sum += h;
                        } else {
                            sum += base;
                        }
                    }
                }
                // A pixel the flank only grazed keeps its sampled value bit-for-bit: summing
                // `base` sixteen times and dividing is not exact in float, and the pipeline's
                // other consumers are compared bit-for-bit against unmodified builds.
                if (!stood) continue;
                // The average of values that are each at least `base` cannot be below it either,
                // but the same rounding can land a ULP under; clamp so islands only ever raise.
                elevation[r * width + c] = Math.max(base, sum / (SUBSAMPLES * SUBSAMPLES));
            }
        }
    }

    /** Whether any island's outer flank comes within {@code slack} of this point. */
    private boolean touches(SiteGrid[] grids, float x, float y, float slack) {
        for (SiteGrid grid : grids) {
            if (grid.siteCount == 0) continue;
            int cx0 = Math.floorDiv((int) Math.floor(x), grid.cellSize);
            int cy0 = Math.floorDiv((int) Math.floor(y), grid.cellSize);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int idx = grid.index(cx0 + dx, cy0 + dy);
                    if (idx < 0 || grid.radius[idx] <= 0f) continue;
                    float ddx = x - grid.centreX[idx];
                    float ddy = y - grid.centreY[idx];
                    float reach = grid.radius[idx] * (1f + grid.lobe) * grid.outer[idx] + slack;
                    if (ddx * ddx + ddy * ddy <= reach * reach) return true;
                }
            }
        }
        return false;
    }

    /** Tallest island surface over this point, or {@link Float#NEGATIVE_INFINITY} if none. */
    private float heightAt(SiteGrid[] grids, float x, float y) {
        float best = Float.NEGATIVE_INFINITY;
        for (SiteGrid grid : grids) {
            if (grid.siteCount == 0) continue;
            int cx0 = Math.floorDiv((int) Math.floor(x), grid.cellSize);
            int cy0 = Math.floorDiv((int) Math.floor(y), grid.cellSize);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int idx = grid.index(cx0 + dx, cy0 + dy);
                    if (idx < 0 || grid.radius[idx] <= 0f) continue;
                    float h = grid.surfaceAt(idx, x, y);
                    if (h > best) best = h;
                }
            }
        }
        return best;
    }

    /** Per-layer island parameters for the cells overlapping one request, indexed by cell. */
    private static final class SiteGrid {
        final int cellSize;
        final float lobe;
        final int cx0, cy0, cols, rows;
        final float[] centreX, centreY, radius, peak, shape, outer, flank;
        final float[] lobeCoeff;  // four harmonic coefficients per site
        int siteCount;

        SiteGrid(Layer layer, int cx0, int cy0, int cols, int rows) {
            this.cellSize = layer.cellSize;
            this.lobe = layer.lobe;
            this.cx0 = cx0;
            this.cy0 = cy0;
            this.cols = cols;
            this.rows = rows;
            int n = cols * rows;
            this.centreX = new float[n];
            this.centreY = new float[n];
            this.radius = new float[n];
            this.peak = new float[n];
            this.shape = new float[n];
            this.outer = new float[n];
            this.flank = new float[n];
            this.lobeCoeff = new float[n * 4];
        }

        int index(int cx, int cy) {
            int ix = cx - cx0;
            int iy = cy - cy0;
            if (ix < 0 || iy < 0 || ix >= cols || iy >= rows) return -1;
            return iy * cols + ix;
        }

        /**
         * Island surface elevation in metres at a point: the summit profile inside the
         * shoreline, a submerged flank outside it, and nothing past the flank.
         */
        float surfaceAt(int idx, float x, float y) {
            float dx = x - centreX[idx];
            float dy = y - centreY[idx];
            float d2 = dx * dx + dy * dy;
            float rMax = radius[idx] * (1f + lobe) * outer[idx];
            if (d2 >= rMax * rMax) return Float.NEGATIVE_INFINITY;

            float d = (float) Math.sqrt(d2);
            float t = d < 1e-4f ? 0f : d / (radius[idx] * lobeFactor(idx, dx / d, dy / d));
            if (t <= 1f) {
                return peak[idx] * (1f - (float) Math.pow(t, shape[idx]));
            }
            float u = (t - 1f) / (outer[idx] - 1f);
            if (u >= 1f) return Float.NEGATIVE_INFINITY;
            return -flank[idx] * u * u;
        }

        /**
         * Shoreline radius multiplier by bearing. Third and fifth harmonics of the direction
         * cosines pull the outline into a few broad lobes; they are expanded as polynomials in
         * {@code (cos, sin)} to keep this free of trigonometry.
         */
        private float lobeFactor(int idx, float c, float s) {
            float c3 = c * (4f * c * c - 3f);
            float s3 = s * (3f - 4f * s * s);
            float c2 = c * c;
            float s2 = s * s;
            float c5 = c * (16f * c2 * c2 - 20f * c2 + 5f);
            float s5 = s * (16f * s2 * s2 - 20f * s2 + 5f);
            int b = idx * 4;
            float wobble = lobeCoeff[b] * c3 + lobeCoeff[b + 1] * s3
                    + lobeCoeff[b + 2] * c5 + lobeCoeff[b + 3] * s5;
            return 1f + lobe * wobble;
        }
    }

    private SiteGrid buildSites(Layer layer, int x1, int y1, int width, int height, ElevationProbe probe) {
        int cx0 = Math.floorDiv(x1, layer.cellSize) - 1;
        int cy0 = Math.floorDiv(y1, layer.cellSize) - 1;
        int cx1 = Math.floorDiv(x1 + width - 1, layer.cellSize) + 1;
        int cy1 = Math.floorDiv(y1 + height - 1, layer.cellSize) + 1;
        SiteGrid grid = new SiteGrid(layer, cx0, cy0, cx1 - cx0 + 1, cy1 - cy0 + 1);

        float presence = Math.min(1f, layer.presence * density);
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                long h = mix(seed, layer.salt, cx, cy);
                if (unit(h = next(h)) >= presence) continue;

                // Jitter inside the middle half of the cell, so neighbours cannot collide.
                float centreX = (cx + 0.25f + 0.5f * unit(h = next(h))) * layer.cellSize;
                float centreY = (cy + 0.25f + 0.5f * unit(h = next(h))) * layer.cellSize;
                if (probe.elevationAt(centreX, centreY) > MIN_SEED_DEPTH) continue;

                int idx = grid.index(cx, cy);
                grid.centreX[idx] = centreX;
                grid.centreY[idx] = centreY;
                grid.radius[idx] = logUniform(unit(h = next(h)), layer.minRadius, layer.maxRadius);
                grid.peak[idx] = relief * logUniform(unit(h = next(h)), layer.minPeak, layer.maxPeak);
                grid.shape[idx] = lerp(unit(h = next(h)), layer.minShape, layer.maxShape);
                grid.outer[idx] = lerp(unit(h = next(h)), MIN_OUTER, MAX_OUTER);
                grid.flank[idx] = lerp(unit(h = next(h)), MIN_FLANK_DEPTH, MAX_FLANK_DEPTH);
                // Normalised so the harmonics can shift the shoreline by at most one full lobe
                // width either way, whatever mix of them this island drew.
                float weight = 0f;
                for (int k = 0; k < 4; k++) {
                    float coeff = 2f * unit(h = next(h)) - 1f;
                    grid.lobeCoeff[idx * 4 + k] = coeff;
                    weight += Math.abs(coeff);
                }
                for (int k = 0; k < 4; k++) {
                    grid.lobeCoeff[idx * 4 + k] /= Math.max(1e-3f, weight);
                }
                grid.siteCount++;
            }
        }
        return grid;
    }

    private static float lerp(float t, float lo, float hi) {
        return lo + t * (hi - lo);
    }

    /** Log-uniform draw, so each layer spends as much of its range on small islands as on large. */
    private static float logUniform(float t, float lo, float hi) {
        return (float) (lo * Math.pow(hi / lo, t));
    }

    private static long mix(long seed, int salt, int cx, int cy) {
        long h = seed ^ (salt * 0x9E3779B97F4A7C15L);
        h ^= cx * 0xD1B54A32D192ED03L;
        h = Long.rotateLeft(h, 27);
        h ^= cy * 0xA0761D6478BD642FL;
        return next(h);
    }

    /** splitmix64 finalizer, reused as the per-site random stream. */
    private static long next(long h) {
        h += 0x9E3779B97F4A7C15L;
        long z = h;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static float unit(long h) {
        return (h >>> 40) * 0x1.0p-24f;
    }
}
