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
 * Root arch placer -- massive tree roots forming a natural arch over a path.
 * Single-anchor pattern based on {@link BoulderFeaturePlacer}, but builds a
 * parabolic arch shape instead of a blob. The arch sits at ground level with
 * legs that are thicker at the base and taper toward the apex.
 *
 * <p>Uses {@link Blocks#OAK_LOG} for the main structural blocks and {@link
 * Blocks#ROOTED_DIRT} for filler/texture. Shape is varied using {@link
 * SurfaceNoise#unitHash} for organic irregularity.</p>
 */
public final class RootArchFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x524F4F54L;
    private static final int CELL_SIZE = 56;
    private static final float SPAWN_CHANCE = 0.15f;
    private static final float MAX_SLOPE = 1.0f;
    private static final int MIN_SPAN = 4;
    private static final int MAX_SPAN = 6;
    private static final int ARCH_HEIGHT = 4;
    private static final int LEG_WIDTH = 2;

    @Override
    public String id() {
        return "root_arch";
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
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null) return;
        boolean validBiome = biomeKey.contains("swamp") || biomeKey.contains("forest")
                || biomeKey.contains("old_growth");
        if (!validBiome) return;
        if (TerrainSampling.slopeAt(data, row, col, 2) > MAX_SLOPE) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
        if (groundY <= chunk.getMinBuildHeight()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        int span = MIN_SPAN + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * (MAX_SPAN - MIN_SPAN + 1));
        int height = ARCH_HEIGHT + (int) (SurfaceNoise.unitHash(site.seed(), 1, 0) * 1);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int centerX = site.worldX();
        int centerZ = site.worldZ();
        int halfSpan = span / 2;

        for (int dx = -halfSpan; dx <= halfSpan; dx++) {
            float frac = Math.abs((float) dx / halfSpan);
            float archY = height * 4f * frac * (1f - frac);

            int legWidth = Math.round(LEG_WIDTH * (1f - frac * 0.5f));
            if (legWidth < 1) legWidth = 1;

            for (int dz = -legWidth; dz <= legWidth; dz++) {
                int wx = centerX + dx;
                int wz = centerZ + dz;
                int lx = wx - minX;
                int lz = wz - minZ;
                if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;

                for (int dy = 1; dy <= Math.round(archY) + 1; dy++) {
                    int wy = groundY + dy;
                    if (wy < chunk.getMinBuildHeight() || wy > chunk.getMaxBuildHeight() - 1) continue;

                    float irregularity = SurfaceNoise.unitHash(site.seed(), dx, dz + dy);
                    boolean skip = frac > 0.15 && frac < 0.85 && dy <= 2 && irregularity > 0.6f;
                    if (skip) continue;

                    pos.set(wx, wy, wz);
                    if (!chunk.getBlockState(pos).isAir()) continue;

                    BlockState block = blockFor(site.seed(), dx, dz, dy, span, height);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(lx, wy, lz, block);
                    motionBlocking.update(lx, wy, lz, block);
                }
            }
        }
    }

    private static BlockState blockFor(long seed, int dx, int dz, int dy, int span, int height) {
        float roll = SurfaceNoise.unitHash(seed ^ 0x524F4F54L, dx + dy, dz);
        float frac = Math.abs((float) dx / (span / 2));
        boolean nearApex = frac > 0.3 && frac < 0.7;

        if (nearApex && dy > 2 && roll < 0.4f) {
            return Blocks.ROOTED_DIRT.defaultBlockState();
        }
        if (roll < 0.25f) {
            return Blocks.ROOTED_DIRT.defaultBlockState();
        }
        return Blocks.OAK_LOG.defaultBlockState();
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
