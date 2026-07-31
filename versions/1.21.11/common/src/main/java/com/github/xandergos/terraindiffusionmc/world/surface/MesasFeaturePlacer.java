package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Surface texture pass that carves horizontal terracotta bands into exposed sides of terrain.
 * Targets badlands/mesa biomes at high elevation on flat plateaus, creating the characteristic
 * layered terracotta coloration seen on real mesas.
 *
 * <p>Unlike BoulderFeaturePlacer which stamps new blocks, this recolors the existing crust: for
 * every column in range it repaints from the surface block down {@link #BAND_DEPTH} blocks,
 * cycling terracotta colors by Y so the bands line up across neighbouring columns and read as
 * continuous strata on an exposed flank.</p>
 */
public final class MesasFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4D455341L;
    private static final float SPAWN_CHANCE = 0.3f;
    private static final float MAX_SLOPE_BLOCKS = 0.15f;
    private static final int RADIUS = 12;
    private static final int CELL_SIZE = 60;
    /** How far below the surface the banding is painted, in blocks. */
    private static final int BAND_DEPTH = 12;
    /** Only band terrain that actually stands above the surrounding plain, in blocks. */
    private static final float MIN_ELEVATION_BLOCKS = 6f;

    @Override
    public String id() {
        return "mesa_terracing";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return RADIUS + 2;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        // No site-in-chunk gate: every column below is anchored and clipped individually, so each
        // chunk this site reaches draws its own slice instead of the footprint being cut off at the
        // chunk border.
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        if (TerrainSampling.elevationAt(data, row, col)
                <= SurfaceStamp.blocksToElevation(MIN_ELEVATION_BLOCKS)) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || (!biomeKey.contains("badlands") && !biomeKey.contains("mesa"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        stampTerracing(chunk, site);
    }

    private void stampTerracing(ChunkAccess chunk, SiteGrid.Site site) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            int worldZ = site.worldZ() + dz;
            int localZ = worldZ - minZ;
            if (localZ < 0 || localZ > 15) continue;
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int worldX = site.worldX() + dx;
                int localX = worldX - minX;
                if (localX < 0 || localX > 15) continue;

                float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                if (horizontal > RADIUS) continue;

                int localGroundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
                if (localGroundY <= chunk.getMinY()) continue;

                // Start at the exposed surface block itself and only band the shallow crust below
                // it. Scanning from localGroundY - 1 skips the surface entirely (its neighbour above
                // is solid by definition), and running to the world floor repaints every
                // cave-exposed stone face in the column instead of the mesa flank.
                int lowestY = Math.max(chunk.getMinY(), localGroundY - BAND_DEPTH);
                for (int y = localGroundY; y >= lowestY; y--) {
                    pos.set(worldX, y, worldZ);
                    BlockState state = chunk.getBlockState(pos);
                    if (!isReplaceableBlock(state)) continue;

                    BlockState terracotta = terracottaForY(y);
                    chunk.setBlockState(pos.set(worldX, y, worldZ), terracotta);
                    worldSurface.update(localX, y, localZ, terracotta);
                    motionBlocking.update(localX, y, localZ, terracotta);
                }
            }
        }
    }

    private static BlockState terracottaForY(int y) {
        int band = Math.floorDiv(y, 4) % 4;
        if (band < 0) band += 4;
        switch (band) {
            case 0: return Blocks.RED_TERRACOTTA.defaultBlockState();
            case 1: return Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 2: return Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            default: return Blocks.TERRACOTTA.defaultBlockState();
        }
    }

    /**
     * Blocks a badlands column is actually made of. Stone/dirt alone never matched the surface --
     * the biome's own surface rules put red sand and terracotta there -- so the pass had nothing
     * to recolor.
     */
    private static boolean isReplaceableBlock(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.SAND)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.TERRACOTTA);
    }
}
