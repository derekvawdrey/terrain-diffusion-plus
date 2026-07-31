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
    private static final float MAX_SLOPE_BLOCKS = 0.3f;
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
        if (biomeKey == null || !(biomeKey.contains("swamp") || biomeKey.contains("forest")
                || biomeKey.contains("old_growth") || biomeKey.contains("grove"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        stamp(chunk, site);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site) {
        long seed = site.seed();
        int boulderCount = SurfaceStamp.randRange(seed, 0, 0, MIN_BOULDERS, MAX_BOULDERS);

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
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

            // Each boulder rests on its own column. Reusing the site's ground height left boulders
            // several blocks away floating or half-buried wherever the ground was not level.
            int boulderGroundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
            if (boulderGroundY <= chunk.getMinY()) continue;
            if (!SurfaceStamp.isSolidGround(
                    SurfaceStamp.stateAt(chunk, boulderWorldX, boulderGroundY, boulderWorldZ))) continue;

            int boulderRadius = SurfaceStamp.randRange(boulderSeed, 3, 0, 1, MAX_BOULDER_RADIUS);
            int boulderHeight = SurfaceStamp.randRange(boulderSeed, 4, 0, 1, MAX_BOULDER_HEIGHT);
            BlockState bodyBlock = MOSSY_BLOCKS[Math.floorMod((int) (SurfaceNoise.unitHash(boulderSeed, 5, 0) * MOSSY_BLOCKS.length), MOSSY_BLOCKS.length)];

            for (int dz = -boulderRadius; dz <= boulderRadius; dz++) {
                int worldZ = boulderWorldZ + dz;
                for (int dx = -boulderRadius; dx <= boulderRadius; dx++) {
                    int worldX = boulderWorldX + dx;

                    float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                    if (horizontal > boulderRadius + 0.35f) continue;

                    for (int dy = 0; dy <= boulderHeight; dy++) {
                        int worldY = boulderGroundY + 1 + dy;
                        BlockState block;
                        if (dy == boulderHeight && SurfaceNoise.unitHash(boulderSeed, dx, dz) < 0.4f) {
                            block = Blocks.MOSS_BLOCK.defaultBlockState();
                        } else {
                            block = bodyBlock;
                        }
                        SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ, block);
                    }
                }
            }
        }
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
