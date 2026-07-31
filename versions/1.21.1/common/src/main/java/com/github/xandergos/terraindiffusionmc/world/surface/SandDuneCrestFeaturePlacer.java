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
 * Stamps exposed sandstone "bones" on dune crests. Targets desert and badlands biomes
 * on terrain with noticeable slope. Places 2-4 small sandstone protrusions in a line
 * following the slope direction.
 */
public final class SandDuneCrestFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x53444352L;
    private static final float SPAWN_CHANCE = 0.3f;
    private static final int CELL_SIZE = 32;
    private static final int MIN_PROTRUSIONS = 2;
    private static final int MAX_PROTRUSIONS = 4;

    @Override
    public String id() {
        return "sand_dune_crest";
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

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || (!biomeKey.contains("desert") && !biomeKey.contains("badlands"))) return;

        float slope = TerrainSampling.slopeAt(data, row, col, 2);
        if (slope < 0.5f) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
        if (groundY <= chunk.getMinBuildHeight()) return;

        float dRow = (data.heightmap[Math.min(data.height - 1, row + 2)][col] - data.heightmap[Math.max(0, row - 2)][col]) / (float) Math.max(1, Math.min(data.height - 1, row + 2) - Math.max(0, row - 2));
        float dCol = (data.heightmap[row][Math.min(data.width - 1, col + 2)] - data.heightmap[row][Math.max(0, col - 2)]) / (float) Math.max(1, Math.min(data.width - 1, col + 2) - Math.max(0, col - 2));

        stamp(chunk, site, groundY, dRow, dCol);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY, float slopeDRow, float dCol) {
        long seed = site.seed();
        int numProtrusions = MIN_PROTRUSIONS + (int) (SurfaceNoise.unitHash(seed, 0, 0) * (MAX_PROTRUSIONS - MIN_PROTRUSIONS + 1));

        float slopeMag = (float) Math.sqrt(slopeDRow * slopeDRow + dCol * dCol);
        int dirX = 0;
        int dirZ = 0;
        if (slopeMag > 0.01f) {
            dirX = (int) Math.round(dCol / slopeMag);
            dirZ = (int) Math.round(slopeDRow / slopeMag);
        }
        if (dirX == 0 && dirZ == 0) dirX = 1;

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int halfSpan = numProtrusions / 2;
        for (int i = 0; i < numProtrusions; i++) {
            int t = i - halfSpan;
            int offsetX = dirX * t * 2;
            int offsetZ = dirZ * t * 2;
            int worldX = site.worldX() + offsetX;
            int worldZ = site.worldZ() + offsetZ;
            int localX = worldX - minX;
            int localZ = worldZ - minZ;
            if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) continue;

            int localGroundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
            if (localGroundY <= chunk.getMinBuildHeight()) continue;

            int width = 1 + (int) (SurfaceNoise.unitHash(seed, 1 + i, 0) * 2);
            int height = 1 + (int) (SurfaceNoise.unitHash(seed, 2 + i, 0) * 2);

            for (int dy = 0; dy < height; dy++) {
                for (int wx = -width / 2; wx <= width / 2; wx++) {
                    for (int wz = -width / 2; wz <= width / 2; wz++) {
                        int px = worldX + wx;
                        int pz = worldZ + wz;
                        int plx = px - minX;
                        int plz = pz - minZ;
                        if (plx < 0 || plx > 15 || plz < 0 || plz > 15) continue;

                        int py = localGroundY + 1 + dy;
                        pos.set(px, py, pz);
                        if (!chunk.getBlockState(pos).isAir()) continue;

                        BlockState block;
                        if (SurfaceNoise.unitHash(seed, px, pz) < 0.5f) {
                            block = Blocks.SANDSTONE.defaultBlockState();
                        } else {
                            block = Blocks.CUT_SANDSTONE.defaultBlockState();
                        }
                        chunk.setBlockState(pos, block, false);
                        worldSurface.update(plx, py, plz, block);
                        motionBlocking.update(plx, py, plz, block);
                    }
                }
            }
        }
    }
}
