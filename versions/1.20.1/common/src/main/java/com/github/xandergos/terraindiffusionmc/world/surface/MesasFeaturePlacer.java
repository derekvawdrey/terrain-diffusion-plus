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
 * <p>Unlike BoulderFeaturePlacer which stamps new blocks, this modifies existing blocks on
 * exposed faces where the block above is air. It cycles through terracotta colors based on
 * Y coordinate to create horizontal bands.</p>
 */
public final class MesasFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4D455341L;
    private static final float SPAWN_CHANCE = 0.3f;
    private static final float MAX_SLOPE = 0.5f;
    private static final int RADIUS = 12;
    private static final int CELL_SIZE = 60;

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
        int localX = site.worldX() - chunk.getPos().getMinBlockX();
        int localZ = site.worldZ() - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return;

        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        if (TerrainSampling.elevationAt(data, row, col) <= 20f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || (!biomeKey.contains("badlands") && !biomeKey.contains("mesa"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > MAX_SLOPE) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
        if (groundY <= chunk.getMinBuildHeight()) return;

        stampTerracing(chunk, site, groundY);
    }

    private void stampTerracing(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
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

                int localGroundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
                if (localGroundY <= chunk.getMinBuildHeight()) continue;

                for (int y = localGroundY - 1; y >= chunk.getMinBuildHeight(); y--) {
                    pos.set(worldX, y, worldZ);
                    BlockState state = chunk.getBlockState(pos);
                    if (!isReplaceableBlock(state)) continue;

                    BlockState above = chunk.getBlockState(pos.set(worldX, y + 1, worldZ));
                    if (!above.isAir()) continue;

                    BlockState terracotta = terracottaForY(y, site.seed());
                    chunk.setBlockState(pos.set(worldX, y, worldZ), terracotta, false);
                    worldSurface.update(localX, y, localZ, terracotta);
                    motionBlocking.update(localX, y, localZ, terracotta);
                }
            }
        }
    }

    private static BlockState terracottaForY(int y, long seed) {
        int band = Math.floorDiv(y, 4) % 4;
        if (band < 0) band += 4;
        switch (band) {
            case 0: return Blocks.RED_TERRACOTTA.defaultBlockState();
            case 1: return Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 2: return Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            default: return Blocks.TERRACOTTA.defaultBlockState();
        }
    }

    private static boolean isReplaceableBlock(BlockState state) {
        return state == Blocks.STONE.defaultBlockState()
                || state == Blocks.ANDESITE.defaultBlockState()
                || state == Blocks.DIRT.defaultBlockState()
                || state == Blocks.COARSE_DIRT.defaultBlockState();
    }
}
