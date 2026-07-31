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
 * Ice columns hanging down from steep slopes in frozen biomes. Anchors to ground level and
 * builds downward into air, tapering to a point like a frozen waterfall.
 */
public final class FrozenWaterfallFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x46525A57L;
    private static final int CELL_SIZE = 48;
    private static final float SPAWN_CHANCE = 0.2f;
    private static final float MIN_SLOPE = 2.0f;
    private static final int MAX_LENGTH = 8;
    private static final int TOP_RADIUS = 2;

    @Override
    public String id() {
        return "frozen_waterfall";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return 10;
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
        if (biomeKey == null || !(biomeKey.contains("frozen") || biomeKey.contains("snow")
                || biomeKey.contains("ice") || biomeKey.contains("tundra")
                || biomeKey.contains("ice_spikes"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) < MIN_SLOPE) return;

        float anchorElevation = TerrainSampling.elevationAt(data, row, col);
        int dx = Integer.signum(SurfaceNoise.signedHash(SALT ^ worldSeed, site.worldX(), 0) > 0 ? 1 : -1);
        int dz = Integer.signum(SurfaceNoise.signedHash(SALT ^ worldSeed, site.worldZ(), 0) > 0 ? 1 : -1);
        int nRow = row + dz;
        int nCol = col + dx;
        if (TerrainSampling.inBounds(data, nRow, nCol)) {
            float neighborElevation = TerrainSampling.elevationAt(data, nRow, nCol);
            if (neighborElevation >= anchorElevation) return;
        }

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
        if (groundY <= chunk.getMinY()) return;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (chunk.getBlockState(probe).isAir()) return;

        stamp(chunk, site, groundY, localX, localZ);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY, int localX, int localZ) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int topRadius = Math.min(TOP_RADIUS, 1 + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * TOP_RADIUS));

        for (int dy = 0; dy < MAX_LENGTH; dy++) {
            int worldY = groundY - dy;
            float taper = 1.0f - (dy / (float) MAX_LENGTH);
            float radius = topRadius * taper;

            if (radius < 0.1f) break;

            int r = Math.max(0, Math.round(radius));
            boolean placed = false;
            for (int dz = -r; dz <= r && !placed; dz++) {
                int lz = localZ + dz;
                if (lz < 0 || lz > 15) continue;
                for (int dx = -r; dx <= r && !placed; dx++) {
                    int lx = localX + dx;
                    if (lx < 0 || lx > 15) continue;
                    if (Math.sqrt(dx * dx + dz * dz) > radius + 0.3f) continue;

                    int wx = site.worldX() + dx;
                    int wz = site.worldZ() + dz;
                    pos.set(wx, worldY, wz);
                    if (!chunk.getBlockState(pos).isAir()) continue;

                    BlockState block = iceFor(site.seed(), wx, worldY, wz);
                    chunk.setBlockState(pos, block);
                    worldSurface.update(lx, worldY, lz, block);
                    motionBlocking.update(lx, worldY, lz, block);
                    placed = true;
                }
            }
            if (!placed) break;
        }
    }

    private static BlockState iceFor(long seed, int x, int y, int z) {
        return SurfaceNoise.unitHash(seed ^ 0x4943454CL, x, z) < 0.7f
                ? Blocks.PACKED_ICE.defaultBlockState()
                : Blocks.BLUE_ICE.defaultBlockState();
    }
}
