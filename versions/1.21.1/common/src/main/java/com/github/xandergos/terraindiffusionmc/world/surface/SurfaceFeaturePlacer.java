package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * One kind of standalone surface structure -- a boulder, a hoodoo cluster, an arch, or any other
 * "generate an object that responds to the terrain shape" feature idea.
 *
 * <p>Implementations must be pure functions of {@code (site, terrain data, seed)}: the same site
 * has to produce the same blocks every time, because {@link SurfaceFeatureDecorator} calls
 * {@link #place} once per chunk whose bounds a site's footprint can reach -- a structure wider
 * than one chunk gets assembled from several independent {@code place} calls, each contributing
 * whatever part of the structure falls in its own chunk. Concretely:</p>
 * <ul>
 *     <li>Every write MUST go through the {@code chunk} passed in, and MUST be clipped to that
 *     chunk's own local 0..15 X/Z columns -- {@code ChunkAccess} cannot write outside itself.</li>
 *     <li>Don't use {@code java.util.Random} or anything else seeded from wall-clock/chunk-order.
 *     Derive every random decision from {@link SiteGrid.Site#seed()} (or
 *     {@link com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise#hash} reseeded
 *     with a per-purpose salt), so two chunks that both touch the same site agree on its shape.</li>
 *     <li>{@code data} is a shared window covering every registered placer's
 *     {@link #maxReachBlocks()} around the current chunk; index it with
 *     {@code row = worldZ - dataOriginZ}, {@code col = worldX - dataOriginX}. It's the coarse
 *     diffusion raster, not actually-placed blocks -- good for deciding whether/how to place
 *     (biome, slope, "is there a gap here"), not for anchoring exact Y inside the current chunk
 *     (use {@code chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ)} for that).</li>
 * </ul>
 *
 * <p>See {@link BoulderFeaturePlacer} for the minimal single-anchor shape, {@link
 * HoodooClusterFeaturePlacer} for multi-anchor/ridge-following placement gated by coherent
 * placement noise, and {@link ArchFeaturePlacer} for two-anchor gap-spanning placement. Copy
 * whichever is structurally closest to a new feature idea.</p>
 */
public interface SurfaceFeaturePlacer {
    /** Unique id, used for logging only. */
    String id();

    /** Site grid cell size in blocks -- controls the average spacing between candidate sites. */
    int cellSizeBlocks();

    /**
     * Furthest a site's structure can reach from its own origin, in blocks. Sets how much extra
     * heightmap margin {@link SurfaceFeatureDecorator} fetches and how far out it searches for
     * this placer's candidate sites on this placer's behalf.
     */
    int maxReachBlocks();

    /** Per-placer salt so different placers don't share a site grid or roll correlated noise. */
    long salt();

    /**
     * Consider placing at {@code site}. Implementations re-check their own eligibility here
     * (biome, slope, elevation, a rarity/coherent-noise roll) using {@code data} indexed via
     * {@code dataOriginX}/{@code dataOriginZ}, then write only the blocks that land inside
     * {@code chunk}.
     */
    void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
               SiteGrid.Site site, long worldSeed);
}
