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
 * Single-anchor mushroom-shaped rock formation for badlands/mesa biomes.
 * Narrow stem (radius 1) rising 4-7 blocks, wide cap (radius 2-3) 2 blocks thick on top.
 * Uses BADLANDS_BANDS terracotta banding for the stem and cap.
 */
public final class MushroomRockFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4D569601L;
    private static final float SPAWN_CHANCE = 0.25f;
    private static final float MAX_SLOPE = 1.5f;
    private static final int CELL_SIZE = 36;
    private static final int STEM_RADIUS = 1;
    private static final int STEM_MIN_HEIGHT = 4;
    private static final int STEM_MAX_HEIGHT = 7;
    private static final int CAP_THICKNESS = 2;

    private static final BlockState[] BADLANDS_BANDS = {
            Blocks.RED_SAND.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
            Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(),
    };

    @Override
    public String id() {
        return "mushroom_rock";
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
        if (TerrainSampling.slopeAt(data, row, col, 2) > MAX_SLOPE) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
        if (groundY <= chunk.getMinBuildHeight()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        long seed = site.seed();
        int stemHeight = STEM_MIN_HEIGHT + (int) (SurfaceNoise.unitHash(seed, 0, 0) * (STEM_MAX_HEIGHT - STEM_MIN_HEIGHT + 1));
        int capRadius = 2 + (int) (SurfaceNoise.unitHash(seed, 1, 0) * 2);

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int dy = 0; dy < stemHeight; dy++) {
            int worldY = groundY + 1 + dy;
            for (int dz = -STEM_RADIUS; dz <= STEM_RADIUS; dz++) {
                int worldZ = site.worldZ() + dz;
                int localZ = worldZ - minZ;
                if (localZ < 0 || localZ > 15) continue;
                for (int dx = -STEM_RADIUS; dx <= STEM_RADIUS; dx++) {
                    int worldX = site.worldX() + dx;
                    int localX = worldX - minX;
                    if (localX < 0 || localX > 15) continue;

                    float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                    if (horizontal > STEM_RADIUS + 0.3f) continue;

                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    BlockState block = bandFor(seed, worldX, worldY, worldZ);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(localX, worldY, localZ, block);
                    motionBlocking.update(localX, worldY, localZ, block);
                }
            }
        }

        int capBaseY = groundY + 1 + stemHeight;
        for (int cy = 0; cy < CAP_THICKNESS; cy++) {
            int worldY = capBaseY + cy;
            for (int dz = -capRadius; dz <= capRadius; dz++) {
                int worldZ = site.worldZ() + dz;
                int localZ = worldZ - minZ;
                if (localZ < 0 || localZ > 15) continue;
                for (int dx = -capRadius; dx <= capRadius; dx++) {
                    int worldX = site.worldX() + dx;
                    int localX = worldX - minX;
                    if (localX < 0 || localX > 15) continue;

                    float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                    if (horizontal > capRadius + 0.3f) continue;

                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    BlockState block = bandFor(seed, worldX, worldY, worldZ);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(localX, worldY, localZ, block);
                    motionBlocking.update(localX, worldY, localZ, block);
                }
            }
        }
    }

    private static BlockState bandFor(long columnSeed, int x, int y, int z) {
        int band = Math.floorMod(y + (int) (SurfaceNoise.unitHash(columnSeed, x, z) * 2), BADLANDS_BANDS.length);
        return BADLANDS_BANDS[band];
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
