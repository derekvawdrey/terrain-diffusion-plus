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
 * Scatters loose gravel/stone blocks on steep mountain slopes, simulating talus and scree fields.
 * This is a surface texture pass, not a discrete object: for each position in a 6x6 area around
 * the site, randomly places a single block on top of stone-like ground.
 */
public final class TalusScreeFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x54414C55L;
    private static final float SPAWN_CHANCE = 0.4f;
    private static final float MIN_SLOPE_BLOCKS = 0.6f;
    private static final int CELL_SIZE = 24;
    private static final int SCATTER_RADIUS = 3;
    private static final float PLACE_CHANCE = 0.3f;

    @Override
    public String id() {
        return "talus_scree";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return 8;
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
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || (!biomeKey.contains("mountain") && !biomeKey.contains("peaks")
                && !biomeKey.contains("stony") && !biomeKey.contains("hills"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) < SurfaceStamp.slopeFromBlocks(MIN_SLOPE_BLOCKS)) return;

        scatter(chunk, site);
    }

    private void scatter(ChunkAccess chunk, SiteGrid.Site site) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int dz = -SCATTER_RADIUS; dz <= SCATTER_RADIUS; dz++) {
            int worldZ = site.worldZ() + dz;
            int localZ = worldZ - minZ;
            if (localZ < 0 || localZ > 15) continue;
            for (int dx = -SCATTER_RADIUS; dx <= SCATTER_RADIUS; dx++) {
                int worldX = site.worldX() + dx;
                int localX = worldX - minX;
                if (localX < 0 || localX > 15) continue;

                if (SurfaceNoise.unitHash(site.seed(), dx, dz) >= PLACE_CHANCE) continue;

                int localGroundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
                if (localGroundY <= chunk.getMinY()) continue;

                pos.set(worldX, localGroundY, worldZ);
                BlockState groundBlock = chunk.getBlockState(pos);
                if (!isStoneLike(groundBlock)) continue;

                pos.set(worldX, localGroundY + 1, worldZ);
                if (!chunk.getBlockState(pos).isAir()) continue;

                BlockState block = randomScreeBlock(site.seed(), worldX, worldZ);
                chunk.setBlockState(pos, block);
                worldSurface.update(localX, localGroundY + 1, localZ, block);
                motionBlocking.update(localX, localGroundY + 1, localZ, block);
            }
        }
    }

    private static BlockState randomScreeBlock(long seed, int x, int z) {
        float r = SurfaceNoise.unitHash(seed ^ 0xA1C55C03L, x, z);
        if (r < 0.4f) return Blocks.GRAVEL.defaultBlockState();
        if (r < 0.7f) return Blocks.COBBLESTONE.defaultBlockState();
        if (r < 0.85f) return Blocks.DEEPSLATE.defaultBlockState();
        return Blocks.TUFF.defaultBlockState();
    }

    private static boolean isStoneLike(BlockState state) {
        return state == Blocks.STONE.defaultBlockState()
                || state == Blocks.ANDESITE.defaultBlockState()
                || state == Blocks.DIORITE.defaultBlockState()
                || state == Blocks.GRANITE.defaultBlockState()
                || state == Blocks.DEEPSLATE.defaultBlockState()
                || state == Blocks.TUFF.defaultBlockState();
    }
}
