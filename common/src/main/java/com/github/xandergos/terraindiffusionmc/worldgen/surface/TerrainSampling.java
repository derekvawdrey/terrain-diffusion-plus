package com.github.xandergos.terraindiffusionmc.worldgen.surface;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;

/**
 * Read helpers over a fetched {@link HeightmapData} window, for gating where a surface feature
 * is allowed to consider placing. {@code row}/{@code col} follow {@link HeightmapData}'s own
 * convention: {@code row} is offset from the window's world Z origin, {@code col} from its world
 * X origin (i.e. {@code row = worldZ - dataOriginZ}, {@code col = worldX - dataOriginX}).
 *
 * <p>This only tells a placer whether a candidate site is worth considering. The diffusion raster
 * is an approximation of the terrain shape, not the actually-generated block surface -- once a
 * placer commits to writing blocks inside the current chunk, anchor to the chunk's own generated
 * heightmap ({@code chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ)}), not this
 * data.</p>
 */
public final class TerrainSampling {
    private TerrainSampling() {}

    public static boolean inBounds(HeightmapData data, int row, int col) {
        return row >= 0 && row < data.height && col >= 0 && col < data.width;
    }

    public static float elevationAt(HeightmapData data, int row, int col) {
        return data.heightmap[row][col];
    }

    public static short biomeIndexAt(HeightmapData data, int row, int col) {
        return data.biomeIndexes != null ? data.biomeIndexes[row][col] : 0;
    }

    /**
     * Approximate slope, in meters of rise per block, via central differences {@code step} blocks
     * out in each axis. Clamps the sample window to the data bounds rather than rejecting near the
     * edge, so callers don't need their own edge-of-window special case.
     */
    public static float slopeAt(HeightmapData data, int row, int col, int step) {
        int r0 = Math.max(0, row - step), r1 = Math.min(data.height - 1, row + step);
        int c0 = Math.max(0, col - step), c1 = Math.min(data.width - 1, col + step);
        float dRow = (data.heightmap[r1][col] - data.heightmap[r0][col]) / (float) Math.max(1, r1 - r0);
        float dCol = (data.heightmap[row][c1] - data.heightmap[row][c0]) / (float) Math.max(1, c1 - c0);
        return (float) Math.sqrt(dRow * dRow + dCol * dCol);
    }

    /** Mean elevation over a small box, for measuring a point's deviation from its local terrain
     *  (e.g. "is this the top of a ridge" or "is this a saddle dipping below its surroundings"). */
    public static float localMeanElevation(HeightmapData data, int row, int col, int radius) {
        int r0 = Math.max(0, row - radius), r1 = Math.min(data.height - 1, row + radius);
        int c0 = Math.max(0, col - radius), c1 = Math.min(data.width - 1, col + radius);
        float sum = 0f;
        int count = 0;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                sum += data.heightmap[r][c];
                count++;
            }
        }
        return count > 0 ? sum / count : elevationAt(data, row, col);
    }
}
