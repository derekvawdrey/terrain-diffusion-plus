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
 * A fumarole vent: a single-anchor feature that places a magma block surrounded by a ring of
 * tuff blocks. Follows the BoulderFeaturePlacer pattern (single-anchor, rarity roll) but adds
 * coherent noise gating for volcanic-area clustering. The magma block produces smoke particles
 * naturally in Minecraft.
 */
public final class FumaroleFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x46554D4CL;
    private static final int CELL_SIZE = 48;
    private static final float SPAWN_CHANCE = 0.15f;
    private static final int MAX_RADIUS = 2;

    private static final float PLACEMENT_NOISE_WAVELENGTH = 250.0f;
    private static final float PLACEMENT_THRESHOLD = 0.5f;

    private static final int MIN_ELEVATION = 50;

    @Override
    public String id() {
        return "fumarole";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return MAX_RADIUS + 2;
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
        if (TerrainSampling.elevationAt(data, row, col) <= MIN_ELEVATION) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("badlands") || biomeKey.contains("mesa")
                || biomeKey.contains("mountain") || biomeKey.contains("peaks"))) return;

        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
        if (groundY <= chunk.getMinBuildHeight()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        BlockState magma = Blocks.MAGMA_BLOCK.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();

        // Place the central magma block at ground level (replaces the top block)
        int worldX = site.worldX();
        int worldZ = site.worldZ();
        int lx = worldX - minX;
        int lz = worldZ - minZ;
        pos.set(worldX, groundY + 1, worldZ);
        if (chunk.getBlockState(pos).isAir()) {
            chunk.setBlockState(pos, magma, false);
            worldSurface.update(lx, groundY + 1, lz, magma);
            motionBlocking.update(lx, groundY + 1, lz, magma);
        }

        // Place a ring of 8 tuff blocks around the magma block at radius 2
        int[] dxDirs = {-1, -1, 0, 1, 1, 1, 0, -1};
        int[] dzDirs = {0, 1, 1, 1, 0, -1, -1, -1};
        for (int i = 0; i < dxDirs.length; i++) {
            int ringWorldX = worldX + dxDirs[i] * MAX_RADIUS;
            int ringWorldZ = worldZ + dzDirs[i] * MAX_RADIUS;
            int ringLx = ringWorldX - minX;
            int ringLz = ringWorldZ - minZ;
            if (ringLx < 0 || ringLx > 15 || ringLz < 0 || ringLz > 15) continue;

            pos.set(ringWorldX, groundY + 1, ringWorldZ);
            if (!chunk.getBlockState(pos).isAir()) continue;
            chunk.setBlockState(pos, tuff, false);
            worldSurface.update(ringLx, groundY + 1, ringLz, tuff);
            motionBlocking.update(ringLx, groundY + 1, ringLz, tuff);
        }
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
