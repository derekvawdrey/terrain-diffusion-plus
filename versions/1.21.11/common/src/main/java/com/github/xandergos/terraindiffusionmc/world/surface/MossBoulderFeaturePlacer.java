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
 * A cluster of moss-covered boulders scattered across swamp/forest terrain. Each boulder is
 * independently positioned and shaped, creating a natural mossy rock cluster appearance.
 */
public final class MossBoulderFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4D4F5353L;
    private static final int CELL_SIZE = 32;
    private static final float SPAWN_CHANCE = 0.25f;
    private static final float MAX_SLOPE = 1.5f;
    private static final int CLUSTER_RADIUS = 4;
    private static final int MIN_BOULDERS = 2;
    private static final int MAX_BOULDERS = 4;
    private static final int MAX_BOULDER_RADIUS = 2;
    private static final int MAX_BOULDER_HEIGHT = 2;

    private static final BlockState[] MOSSY_BLOCKS = {
            Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
            Blocks.COBBLED_DEEPSLATE.defaultBlockState(),
            Blocks.MOSS_BLOCK.defaultBlockState(),
    };

    @Override
    public String id() {
        return "moss_boulder";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return 7;
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
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("swamp") || biomeKey.contains("forest")
                || biomeKey.contains("old_growth") || biomeKey.contains("grove"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > MAX_SLOPE) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
        if (groundY <= chunk.getMinY()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        long seed = site.seed();
        int boulderCount = MIN_BOULDERS + (int) (SurfaceNoise.unitHash(seed, 0, 0) * (MAX_BOULDERS - MIN_BOULDERS + 1));

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int i = 0; i < boulderCount; i++) {
            long boulderSeed = SurfaceNoise.hash(seed, i, 2000 + i);

            float angle = SurfaceNoise.unitHash(boulderSeed, 1, 0) * (float) (Math.PI * 2);
            float distance = SurfaceNoise.unitHash(boulderSeed, 2, 0) * CLUSTER_RADIUS;
            int boulderWorldX = site.worldX() + Math.round((float) Math.cos(angle) * distance);
            int boulderWorldZ = site.worldZ() + Math.round((float) Math.sin(angle) * distance);

            int localX = boulderWorldX - minX;
            int localZ = boulderWorldZ - minZ;
            if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) continue;

            int boulderRadius = 1 + (int) (SurfaceNoise.unitHash(boulderSeed, 3, 0) * MAX_BOULDER_RADIUS);
            int boulderHeight = 1 + (int) (SurfaceNoise.unitHash(boulderSeed, 4, 0) * MAX_BOULDER_HEIGHT);
            BlockState bodyBlock = MOSSY_BLOCKS[Math.floorMod((int) (SurfaceNoise.unitHash(boulderSeed, 5, 0) * MOSSY_BLOCKS.length), MOSSY_BLOCKS.length)];

            for (int dz = -boulderRadius; dz <= boulderRadius; dz++) {
                int worldZ = boulderWorldZ + dz;
                int lz = worldZ - minZ;
                if (lz < 0 || lz > 15) continue;
                for (int dx = -boulderRadius; dx <= boulderRadius; dx++) {
                    int worldX = boulderWorldX + dx;
                    int lx = worldX - minX;
                    if (lx < 0 || lx > 15) continue;

                    float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                    if (horizontal > boulderRadius + 0.35f) continue;

                    for (int dy = 0; dy <= boulderHeight; dy++) {
                        int worldY = groundY + 1 + dy;
                        pos.set(worldX, worldY, worldZ);
                        if (!chunk.getBlockState(pos).isAir()) continue;

                        BlockState block;
                        if (dy == boulderHeight && SurfaceNoise.unitHash(boulderSeed, dx, dz) < 0.4f) {
                            block = Blocks.MOSS_BLOCK.defaultBlockState();
                        } else {
                            block = bodyBlock;
                        }
                        chunk.setBlockState(pos, block);
                        worldSurface.update(lx, worldY, lz, block);
                        motionBlocking.update(lx, worldY, lz, block);
                    }
                }
            }
        }
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
