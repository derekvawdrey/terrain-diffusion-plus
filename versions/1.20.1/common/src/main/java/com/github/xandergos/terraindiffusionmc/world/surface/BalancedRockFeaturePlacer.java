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
 * Single-anchor balanced rock formation for badlands/mesa biomes.
 * Narrow pillar (radius 1) rising 3-5 blocks, then a single wider capstone (radius 2)
 * offset horizontally for a precarious look. Uses stone variants for the pillar and
 * a random stone variant for the capstone.
 */
public final class BalancedRockFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x424C414CL;
    private static final float SPAWN_CHANCE = 0.15f;
    private static final float MAX_SLOPE_BLOCKS = 0.2f;
    private static final int CELL_SIZE = 50;
    private static final int PILLAR_RADIUS = 1;
    private static final int PILLAR_MIN_HEIGHT = 3;
    private static final int PILLAR_MAX_HEIGHT = 5;
    private static final int CAPSTONE_RADIUS = 2;

    private static final BlockState[] PILLAR_BLOCKS = {
            Blocks.STONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
    };

    private static final BlockState[] CAPSTONE_BLOCKS = {
            Blocks.STONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.COBBLESTONE.defaultBlockState(),
    };

    @Override
    public String id() {
        return "balanced_rock";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return 6;
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
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || (!biomeKey.contains("badlands") && !biomeKey.contains("mesa"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinBuildHeight()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        long seed = site.seed();
        int pillarHeight = PILLAR_MIN_HEIGHT + (int) (SurfaceNoise.unitHash(seed, 0, 0) * (PILLAR_MAX_HEIGHT - PILLAR_MIN_HEIGHT + 1));
        BlockState pillarBlock = PILLAR_BLOCKS[Math.floorMod((int) (SurfaceNoise.unitHash(seed, 0, 1) * PILLAR_BLOCKS.length), PILLAR_BLOCKS.length)];
        BlockState capstoneBlock = CAPSTONE_BLOCKS[Math.floorMod((int) (SurfaceNoise.unitHash(seed, 0, 2) * CAPSTONE_BLOCKS.length), CAPSTONE_BLOCKS.length)];

        int offsetDir = (int) (SurfaceNoise.unitHash(seed, 0, 3) * 4);
        int offsetX = 0;
        int offsetZ = 0;
        switch (offsetDir) {
            case 0: offsetX = 1; break;
            case 1: offsetZ = 1; break;
            case 2: offsetX = -1; break;
            default: offsetZ = -1; break;
        }

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int dy = 0; dy < pillarHeight; dy++) {
            int worldY = groundY + 1 + dy;
            for (int dz = -PILLAR_RADIUS; dz <= PILLAR_RADIUS; dz++) {
                int worldZ = site.worldZ() + dz;
                int localZ = worldZ - minZ;
                if (localZ < 0 || localZ > 15) continue;
                for (int dx = -PILLAR_RADIUS; dx <= PILLAR_RADIUS; dx++) {
                    int worldX = site.worldX() + dx;
                    int localX = worldX - minX;
                    if (localX < 0 || localX > 15) continue;

                    float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                    if (horizontal > PILLAR_RADIUS + 0.3f) continue;

                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, pillarBlock, false);
                    worldSurface.update(localX, worldY, localZ, pillarBlock);
                    motionBlocking.update(localX, worldY, localZ, pillarBlock);
                }
            }
        }

        int capstoneY = groundY + 1 + pillarHeight;
        int capCenterX = site.worldX() + offsetX;
        int capCenterZ = site.worldZ() + offsetZ;

        for (int dz = -CAPSTONE_RADIUS; dz <= CAPSTONE_RADIUS; dz++) {
            int worldZ = capCenterZ + dz;
            int localZ = worldZ - minZ;
            if (localZ < 0 || localZ > 15) continue;
            for (int dx = -CAPSTONE_RADIUS; dx <= CAPSTONE_RADIUS; dx++) {
                int worldX = capCenterX + dx;
                int localX = worldX - minX;
                if (localX < 0 || localX > 15) continue;

                float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                if (horizontal > CAPSTONE_RADIUS + 0.3f) continue;

                pos.set(worldX, capstoneY, worldZ);
                if (!chunk.getBlockState(pos).isAir()) continue;
                chunk.setBlockState(pos, capstoneBlock, false);
                worldSurface.update(localX, capstoneY, localZ, capstoneBlock);
                motionBlocking.update(localX, capstoneY, localZ, capstoneBlock);
            }
        }
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
