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
 *     (use {@code SurfaceStamp.surfaceY(chunk, localX, localZ)} for that).</li>
 *     <li>Raster elevations and slopes are in <b>model metres</b>, and one block is
 *     {@code nativeResolution / worldScale} metres -- 15 by default, and it moves with the
 *     configured world scale. A gate written as {@code elevation > 150} means "ten blocks above
 *     sea level", not "Y=150". Express thresholds in blocks and convert them with
 *     {@link SurfaceStamp#blocksToElevation} / {@link SurfaceStamp#slopeFromBlocks}.</li>
 *     <li>{@link SurfaceStamp#surfaceY} returns the Y of the topmost non-air block -- the block a
 *     feature rests <em>on</em> -- so the first writable Y is one above it. It counts fluids, so
 *     over open water it is the water surface; anything meant to sit on the seabed wants
 *     {@link SurfaceStamp#seabedY}. Write through the {@code SurfaceStamp} helpers rather than
 *     calling {@code chunk.setBlockState} directly: they clip to the chunk (a raw write one block
 *     past the edge silently wraps to the far side of the same chunk) and keep the heightmaps
 *     honest in both directions (a bare {@code setBlockState(AIR)} cannot lower them).</li>
 * </ul>
 *
 * <p>See {@link BoulderFeaturePlacer} for the minimal single-anchor shape, {@link
 * HoodooClusterFeaturePlacer} for multi-anchor/ridge-following placement gated by coherent
 * placement noise, and {@link ArchFeaturePlacer} for two-anchor gap-spanning placement. Copy
 * whichever is structurally closest to a new feature idea.</p>
 *
 * <p><b>Known limitation.</b> A placer that returns early unless the site itself lies in the
 * chunk being decorated gets its footprint clipped at chunk borders: the neighbouring chunk is
 * handed the same site but bails out before drawing its half, so those blocks are lost. Placers
 * whose every column is independently anchored -- the cluster template, {@link
 * MesasFeaturePlacer}, {@link MossBoulderFeaturePlacer}, {@link TidePoolFeaturePlacer} and
 * friends -- simply omit that gate and are correct across borders. The remaining single-anchor
 * placers still carry it and stay visually intact only because their footprints are a few blocks
 * wide. Before giving one of them a wide footprint, move its geometry onto the raster the way
 * {@link ArchFeaturePlacer} does, so every chunk that touches the site computes the same shape.</p>
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
