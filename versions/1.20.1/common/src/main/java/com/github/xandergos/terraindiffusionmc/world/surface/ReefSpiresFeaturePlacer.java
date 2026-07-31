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
 * Reef spires placer -- underwater spire columns rising from the ocean floor.
 * Based on {@link SeaStackFeaturePlacer} (multi-anchor pattern) but placed
 * entirely underwater in ocean biomes, using prismarine blocks.
 *
 * <p>Each spire is independently anchored to the ocean floor at its own
 * jittered offset from the site. Spires taper from a 1-2 block base to
 * a 1 block top, rising toward but not breaking the water surface.</p>
 */
public final class ReefSpiresFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x52454546L;
    private static final int CELL_SIZE = 48;
    private static final float SPAWN_CHANCE = 0.2f;
    private static final int SPIRE_SPREAD = 4;
    private static final int MIN_SPIRES = 2;
    private static final int MAX_SPIRES = 3;
    private static final int MIN_SPIRE_HEIGHT = 3;
    private static final int MAX_SPIRE_HEIGHT = 6;
    private static final int SEA_LEVEL = 63;
    private static final int SURFACE_CLEARANCE = 2;

    private static final BlockState[] REEF_BLOCKS = {
            Blocks.PRISMARINE.defaultBlockState(),
            Blocks.DARK_PRISMARINE.defaultBlockState(),
            Blocks.PRISMARINE_BRICKS.defaultBlockState(),
    };

    @Override
    public String id() {
        return "reef_spires";
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
        if (TerrainSampling.elevationAt(data, row, col) >= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !biomeKey.contains("ocean") || biomeKey.contains("frozen")) return;

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int spireCount = MIN_SPIRES + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * (MAX_SPIRES - MIN_SPIRES + 1));
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        for (int i = 0; i < spireCount; i++) {
            long spireSeed = SurfaceNoise.hash(site.seed(), i, 2000 + i);
            float angle = SurfaceNoise.unitHash(spireSeed, 1, 0) * (float) (Math.PI * 2);
            float distance = SurfaceNoise.unitHash(spireSeed, 2, 0) * SPIRE_SPREAD;
            int spireWorldX = site.worldX() + Math.round((float) Math.cos(angle) * distance);
            int spireWorldZ = site.worldZ() + Math.round((float) Math.sin(angle) * distance);

            int sLocalX = spireWorldX - minX;
            int sLocalZ = spireWorldZ - minZ;
            if (sLocalX < 0 || sLocalX > 15 || sLocalZ < 0 || sLocalZ > 15) continue;

            int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, sLocalX, sLocalZ) - 1;
            if (groundY <= chunk.getMinBuildHeight()) continue;
            if (groundY >= SEA_LEVEL - SURFACE_CLEARANCE) continue;

            int height = MIN_SPIRE_HEIGHT
                    + (int) (SurfaceNoise.unitHash(spireSeed, 3, 0) * (MAX_SPIRE_HEIGHT - MIN_SPIRE_HEIGHT + 1));
            int maxY = SEA_LEVEL - SURFACE_CLEARANCE + (int) (SurfaceNoise.unitHash(spireSeed, 4, 0) * (SURFACE_CLEARANCE - 1));
            height = Math.min(height, maxY - groundY - 1);
            if (height < 1) continue;

            placeSpire(chunk, worldSurface, motionBlocking, spireSeed, spireWorldX, spireWorldZ,
                    sLocalX, sLocalZ, groundY, height);
        }
    }

    private void placeSpire(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long spireSeed,
                             int worldX, int worldZ, int localX, int localZ, int groundY, int height) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseWidth = 1 + (int) (SurfaceNoise.unitHash(spireSeed, 5, 0) * 2);

        for (int dy = 0; dy <= height; dy++) {
            int worldY = groundY + 1 + dy;
            float taper = 1.0f - (dy / (float) height) * 0.5f;
            int r = Math.max(0, Math.round(baseWidth * taper * 0.5f));

            for (int dz = -r; dz <= r; dz++) {
                int lz = localZ + dz;
                if (lz < 0 || lz > 15) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int lx = localX + dx;
                    if (lx < 0 || lx > 15) continue;
                    if (r > 0 && Math.sqrt(dx * dx + dz * dz) > r + 0.3f) continue;

                    int wx = worldX + dx;
                    int wz = worldZ + dz;
                    pos.set(wx, worldY, wz);
                    if (!chunk.getBlockState(pos).isAir() && !chunk.getBlockState(pos).getFluidState().isEmpty()) continue;
                    BlockState block = reefBlockFor(spireSeed, wx, worldY, wz);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(lx, worldY, lz, block);
                    motionBlocking.update(lx, worldY, lz, block);
                }
            }
        }
    }

    private static BlockState reefBlockFor(long seed, int x, int y, int z) {
        int band = Math.floorMod(y + (int) (SurfaceNoise.unitHash(seed, x, z) * 2), REEF_BLOCKS.length);
        return REEF_BLOCKS[band];
    }
}
