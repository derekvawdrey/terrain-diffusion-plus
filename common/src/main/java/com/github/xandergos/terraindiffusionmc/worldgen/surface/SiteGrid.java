package com.github.xandergos.terraindiffusionmc.worldgen.surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, seed-stable candidate-site sampler: one jittered point per grid cell, the same
 * scheme vanilla uses for structure spacing.
 *
 * <p>Placers need this because a structure's footprint (a boulder's radius, an arch's span) can
 * reach beyond the chunk its site falls in, so more than one chunk decoration pass may query the
 * same site. A site's world position -- and anything derived from {@link Site#seed()} -- must
 * come out identical no matter which chunk asks, which is exactly what hashing the owning grid
 * cell guarantees.</p>
 */
public final class SiteGrid {
    private SiteGrid() {}

    public record Site(int worldX, int worldZ, long seed) {}

    /**
     * All candidate sites whose owning cell overlaps {@code [minX,maxX] x [minZ,maxZ]}.
     *
     * @param cellSize     grid cell side length in blocks; controls average site spacing
     * @param jitterMargin keeps a site at least this far from its cell edges. Callers with a large
     *                     footprint should pass something close to {@code cellSize / 2} so a
     *                     site's structure can't reach into a neighboring cell and visually
     *                     collide with that cell's own site.
     */
    public static List<Site> sitesOverlapping(long worldSeed, long salt, int cellSize, int jitterMargin,
                                               int minX, int minZ, int maxX, int maxZ) {
        List<Site> sites = new ArrayList<>();
        int span = Math.max(1, cellSize - 2 * jitterMargin);
        int cellMinX = Math.floorDiv(minX, cellSize);
        int cellMaxX = Math.floorDiv(maxX, cellSize);
        int cellMinZ = Math.floorDiv(minZ, cellSize);
        int cellMaxZ = Math.floorDiv(maxZ, cellSize);
        long cellSeed = worldSeed ^ salt;

        for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
            for (int cx = cellMinX; cx <= cellMaxX; cx++) {
                long h = SurfaceNoise.hash(cellSeed, cx, cz);
                int jitterX = (int) (((h >>> 16) & 0xFFFF) % span);
                int jitterZ = (int) (((h >>> 32) & 0xFFFF) % span);
                int siteWorldX = cx * cellSize + jitterMargin + jitterX;
                int siteWorldZ = cz * cellSize + jitterMargin + jitterZ;
                sites.add(new Site(siteWorldX, siteWorldZ, h));
            }
        }
        return sites;
    }
}
