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
    private static final float MAX_SLOPE_BLOCKS = 0.2f;
    private static final int MIN_SPAN = 4;
    private static final int MAX_SPAN = 6;
    private static final int ARCH_HEIGHT = 4;
    private static final int LEG_WIDTH = 2;
    /** Thickness of the arch crown, in blocks. */
    private static final int ARCH_THICKNESS = 2;

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
        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
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
        int centerX = site.worldX();
        int centerZ = site.worldZ();
        int halfSpan = span / 2;

        for (int dx = -halfSpan; dx <= halfSpan; dx++) {
            // Position along the span, 0 at one foot and 1 at the other, so 4t(1-t) peaks in the
            // middle. Using |dx|/halfSpan instead put the peak halfway out on each side and drove
            // the apex to zero at the centre -- two humps with a hole between them, not an arch.
            float frac = (dx + halfSpan) / (float) (2 * halfSpan);
            float archY = height * 4f * frac * (1f - frac);
            int crownY = Math.round(archY);

            int legWidth = Math.round(LEG_WIDTH * (1f - Math.abs(frac - 0.5f)));
            if (legWidth < 1) legWidth = 1;

            for (int dz = -legWidth; dz <= legWidth; dz++) {
                int wx = centerX + dx;
                int wz = centerZ + dz;

                // Only the crown is solid; below it stays open so the arch is something you can
                // walk under rather than a solid mound of logs.
                int lowestY = isFoot(frac) ? 1 : Math.max(1, crownY - ARCH_THICKNESS + 1);
                for (int dy = lowestY; dy <= crownY + 1; dy++) {
                    int wy = groundY + dy;

                    float irregularity = SurfaceNoise.unitHash(site.seed(), dx, dz + dy);
                    if (!isFoot(frac) && dy < crownY && irregularity > 0.7f) continue;

                    BlockState block = blockFor(site.seed(), dx, dz, dy, frac);
                    SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, wx, wy, wz, block);
                }
            }
        }
    }

    /** The two ends of the span, where the arch comes down to the ground as a solid leg. */
    private static boolean isFoot(float frac) {
        return frac < 0.2f || frac > 0.8f;
    }

    private static BlockState blockFor(long seed, int dx, int dz, int dy, float frac) {
        float roll = SurfaceNoise.unitHash(seed ^ 0x524F4F54L, dx + dy, dz);
        boolean nearApex = frac > 0.3f && frac < 0.7f;

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
