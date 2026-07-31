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
 * Blowhole placer -- a narrow vertical shaft carved through a coastal cliff face.
 * Based on {@link BoulderFeaturePlacer} (single-anchor pattern) but digs DOWN through
 * terrain to create a sea-blown hole appearance.
 *
 * <p>Pattern: gate on coastal biome + cliff slope + rarity roll, anchor to heightmap,
 * then carve a 1-block-wide vertical shaft downward with a water pool at the bottom.</p>
 */
public final class BlowholeFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x424C5748L;
    private static final float SPAWN_CHANCE = 0.15f;
    private static final int CELL_SIZE = 56;
    private static final float MIN_SLOPE_BLOCKS = 0.6f;
    private static final float MIN_ELEVATION_BLOCKS = 0f;
    private static final float MAX_ELEVATION_BLOCKS = 6f;
    private static final int MIN_SHAFT_DEPTH = 3;
    private static final int MAX_SHAFT_DEPTH = 5;

    private static final BlockState[] CARVABLE_BLOCKS = {
            Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.SAND.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
    };

    @Override
    public String id() {
        return "blowhole";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return MAX_SHAFT_DEPTH + 2;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        int localX = site.worldX() - chunk.getPos().getMinBlockX();
        int localZ = site.worldZ() - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return;

        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation < SurfaceStamp.blocksToElevation(MIN_ELEVATION_BLOCKS) || elevation > SurfaceStamp.blocksToElevation(MAX_ELEVATION_BLOCKS)) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !isCoastalBiome(biomeKey)) return;

        if (TerrainSampling.slopeAt(data, row, col, 2) < SurfaceStamp.slopeFromBlocks(MIN_SLOPE_BLOCKS)) return;

        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinBuildHeight()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isCarvable(chunk.getBlockState(pos))) return;

        int shaftDepth = MIN_SHAFT_DEPTH + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * (MAX_SHAFT_DEPTH - MIN_SHAFT_DEPTH));
        carveShaft(chunk, site, groundY, shaftDepth);
    }

    private boolean isCoastalBiome(String biomeKey) {
        return biomeKey.contains("beach") || biomeKey.contains("ocean") || biomeKey.contains("stony");
    }

    private void carveShaft(ChunkAccess chunk, SiteGrid.Site site, int groundY, int depth) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int localX = site.worldX() - minX;
        int localZ = site.worldZ() - minZ;

        for (int dy = 0; dy < depth; dy++) {
            int worldY = groundY - dy;
            if (worldY <= chunk.getMinBuildHeight()) break;

            pos.set(site.worldX(), worldY, site.worldZ());
            BlockState current = chunk.getBlockState(pos);

            if (dy == depth - 1) {
                BlockState water = Blocks.WATER.defaultBlockState();
                chunk.setBlockState(pos, water, false);
                worldSurface.update(localX, worldY, localZ, water);
                motionBlocking.update(localX, worldY, localZ, water);
            } else if (isCarvable(current) || current.isAir()) {
                BlockState air = Blocks.AIR.defaultBlockState();
                chunk.setBlockState(pos, air, false);
            }
        }
    }

    private static boolean isCarvable(BlockState state) {
        for (BlockState carvable : CARVABLE_BLOCKS) {
            if (state.getBlock() == carvable.getBlock()) return true;
        }
        return false;
    }
}
