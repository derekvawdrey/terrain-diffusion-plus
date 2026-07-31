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
 * Rare oasis sinkhole feature. Creates a circular depression filled with water,
 * surrounded by sand/grass and a few palm-like trees around the rim.
 * Targets desert biomes on flat, low-elevation terrain.
 */
public final class OasisSinkholeFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4F415349L;
    private static final float SPAWN_CHANCE = 0.08f;
    private static final float MAX_SLOPE = 1.0f;
    private static final int CELL_SIZE = 80;
    private static final int MAX_RADIUS = 5;
    private static final int MIN_TREE_COUNT = 3;
    private static final int MAX_TREE_COUNT = 5;

    @Override
    public String id() {
        return "oasis_sinkhole";
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
        int localX = site.worldX() - chunk.getPos().getMinBlockX();
        int localZ = site.worldZ() - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return;

        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation <= 0f || elevation >= 60f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !biomeKey.contains("desert")) return;

        if (TerrainSampling.slopeAt(data, row, col, 2) > MAX_SLOPE) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
        if (groundY <= chunk.getMinY()) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        long seed = site.seed();
        int radius = 4 + (int) (SurfaceNoise.unitHash(seed, 0, 0) * 3);
        radius = Math.min(radius, MAX_RADIUS);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int waterRadius = radius - 1;

        for (int dz = -radius; dz <= radius; dz++) {
            int worldZ = site.worldZ() + dz;
            int localZ = worldZ - minZ;
            if (localZ < 0 || localZ > 15) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int worldX = site.worldX() + dx;
                int localX = worldX - minX;
                if (localX < 0 || localX > 15) continue;

                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > radius) continue;

                float normalizedDist = dist / radius;
                int digDepth = Math.max(1, (int) (3f * (1f - normalizedDist)));

                if (dist <= waterRadius) {
                    for (int dy = -digDepth; dy <= 0; dy++) {
                        int worldY = groundY + dy;
                        pos.set(worldX, worldY, worldZ);
                        BlockState current = chunk.getBlockState(pos);
                        if (!current.isAir() && !current.getFluidState().isEmpty()) continue;

                        if (dy == -digDepth || dy == -1) {
                            chunk.setBlockState(pos, Blocks.WATER.defaultBlockState());
                        } else {
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState());
                        }
                    }
                } else {
                    for (int dy = -digDepth; dy <= 0; dy++) {
                        int worldY = groundY + dy;
                        pos.set(worldX, worldY, worldZ);
                        BlockState current = chunk.getBlockState(pos);
                        if (!current.isAir() && !current.getFluidState().isEmpty()) continue;

                        BlockState fillBlock;
                        if (SurfaceNoise.unitHash(seed, worldX, worldZ) < 0.5f) {
                            fillBlock = Blocks.SAND.defaultBlockState();
                        } else {
                            fillBlock = Blocks.GRASS_BLOCK.defaultBlockState();
                        }
                        chunk.setBlockState(pos, fillBlock);
                    }
                }
            }
        }

        int numTrees = MIN_TREE_COUNT + (int) (SurfaceNoise.unitHash(seed, 1, 0) * (MAX_TREE_COUNT - MIN_TREE_COUNT + 1));
        for (int i = 0; i < numTrees; i++) {
            float angle = SurfaceNoise.unitHash(seed, 10 + i, 0) * (float) (Math.PI * 2);
            float treeDist = waterRadius + 0.5f + SurfaceNoise.unitHash(seed, 11 + i, 0) * 1.5f;
            int treeDx = (int) Math.round(Math.cos(angle) * treeDist);
            int treeDz = (int) Math.round(Math.sin(angle) * treeDist);
            int treeWorldX = site.worldX() + treeDx;
            int treeWorldZ = site.worldZ() + treeDz;
            int treeLocalX = treeWorldX - minX;
            int treeLocalZ = treeWorldZ - minZ;
            if (treeLocalX < 0 || treeLocalX > 15 || treeLocalZ < 0 || treeLocalZ > 15) continue;

            int treeGroundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, treeLocalX, treeLocalZ);
            if (treeGroundY <= chunk.getMinY()) continue;

            int treeHeight = 3 + (int) (SurfaceNoise.unitHash(seed, 20 + i, 0) * 3);
            for (int dy = 1; dy <= treeHeight; dy++) {
                int worldY = treeGroundY + dy;
                pos.set(treeWorldX, worldY, treeWorldZ);
                if (!chunk.getBlockState(pos).isAir()) continue;
                chunk.setBlockState(pos, Blocks.OAK_LOG.defaultBlockState());
                worldSurface.update(treeLocalX, worldY, treeLocalZ, Blocks.OAK_LOG.defaultBlockState());
                motionBlocking.update(treeLocalX, worldY, treeLocalZ, Blocks.OAK_LOG.defaultBlockState());
            }
        }
    }
}
