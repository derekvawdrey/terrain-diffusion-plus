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
 * TEMPLATE A -- minimal single-anchor point feature. Copy this shape for anything that just
 * needs "a small object resting on flat-ish ground in the right biome": boulders, crystal
 * clusters, driftwood piles, mushroom rings, etc.
 *
 * <p>Pattern: gate on biome + slope + a per-site rarity roll, anchor to the chunk's own
 * already-generated surface heightmap (accurate -- unlike the diffusion raster), then stamp a
 * small procedural block blob. Everything here happens inside the current chunk, so there's no
 * cross-chunk bookkeeping to worry about; {@link ArchFeaturePlacer} is the template for when a
 * feature can't avoid that.</p>
 */
public final class BoulderFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0xB0F1DE12L;
    private static final float SPAWN_CHANCE = 0.35f;
    private static final float MAX_SLOPE_BLOCKS = 0.25f;
    private static final int MAX_RADIUS = 2;
    private static final int CELL_SIZE = 28;

    @Override
    public String id() {
        return "boulder";
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
        // 1. Cheapest checks first: is the site even inside the chunk we're currently decorating?
        int localX = site.worldX() - chunk.getPos().getMinBlockX();
        int localZ = site.worldZ() - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return;

        // 2. Rarity roll -- independent per site, so boulders scatter uniformly rather than in
        // noise-shaped patches. See HoodooClusterFeaturePlacer for the coherent-noise variant.
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        // 3. Terrain-shape eligibility, read from the (approximate) diffusion raster.
        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return; // underwater/at sea level

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || biomeKey.contains("ocean") || biomeKey.contains("beach")
                || biomeKey.contains("desert") || biomeKey.contains("badlands")
                || biomeKey.contains("mesa")) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        // 4. Anchor to the real generated surface (not the raster) now that we're committing.
        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinBuildHeight()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        int radius = Math.min(MAX_RADIUS, 1 + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * MAX_RADIUS));
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        for (int dz = -radius; dz <= radius; dz++) {
            int worldZ = site.worldZ() + dz;
            int localZ = worldZ - minZ;
            if (localZ < 0 || localZ > 15) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int worldX = site.worldX() + dx;
                int localX = worldX - minX;
                if (localX < 0 || localX > 15) continue;

                float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                if (horizontal > radius + 0.35f) continue;
                int height = Math.round((radius - horizontal) * 1.15f);
                if (SurfaceNoise.unitHash(site.seed(), dx, dz) > 0.7f) height++;
                BlockState block = blockFor(site.seed(), worldX, worldZ);

                for (int dy = 0; dy <= height; dy++) {
                    int worldY = groundY + 1 + dy;
                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(localX, worldY, localZ, block);
                    motionBlocking.update(localX, worldY, localZ, block);
                }
            }
        }
    }

    private static BlockState blockFor(long seed, int x, int z) {
        return SurfaceNoise.unitHash(seed ^ 0x51ED270B5AE4A24AL, x, z) < 0.4f
                ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                : Blocks.STONE.defaultBlockState();
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
