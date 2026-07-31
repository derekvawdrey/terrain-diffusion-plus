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
 * A large chunk of ice beached on the shore. Uses hash-driven irregular shape generation
 * for an organic iceberg silhouette with mixed ice types.
 */
public final class BeachedIcebergFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x42454149L;
    private static final float SPAWN_CHANCE = 0.1f;
    private static final float MAX_SLOPE = 1.0f;
    private static final int MAX_RADIUS = 4;
    private static final int CELL_SIZE = 72;

    @Override
    public String id() {
        return "beached_iceberg";
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

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation < -2f || elevation > 5f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null) return;
        if (!biomeKey.contains("frozen") && !biomeKey.contains("ice_spikes")
                && !biomeKey.contains("tundra")) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > MAX_SLOPE) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
        if (groundY <= chunk.getMinY()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        int radius = Math.min(MAX_RADIUS, 2 + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * MAX_RADIUS));
        int height = 2 + (int) (SurfaceNoise.unitHash(site.seed(), 1, 1) * 3);
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

                for (int dy = 0; dy <= height; dy++) {
                    float fillChance = shapeFillChance(site.seed(), dx, dy, dz, radius, height);
                    if (SurfaceNoise.unitHash(site.seed() ^ 0x4943454CL, dx, dz + dy) >= fillChance) continue;

                    int worldY = groundY + 1 + dy;
                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    BlockState block = iceBlockFor(site.seed(), worldX, worldY, worldZ);
                    chunk.setBlockState(pos, block);
                    worldSurface.update(localX, worldY, localZ, block);
                    motionBlocking.update(localX, worldY, localZ, block);
                }
            }
        }
    }

    private static float shapeFillChance(long seed, int dx, int dy, int dz, int radius, int height) {
        float hDist = (float) Math.sqrt(dx * dx + dz * dz) / radius;
        float vDist = (float) dy / height;

        float baseFill = 1.0f - (hDist * hDist + vDist * vDist * 0.5f);

        float irregularity = SurfaceNoise.unitHash(seed ^ 0x53484150L, dx, dz + dy) - 0.5f;
        baseFill += irregularity * 0.3f;

        if (dy == 0) {
            baseFill *= 0.7f;
        }

        return Math.max(0f, Math.min(1f, baseFill));
    }

    private static BlockState iceBlockFor(long seed, int x, int y, int z) {
        float roll = SurfaceNoise.unitHash(seed ^ 0x49434554L, x, z + y);
        if (roll < 0.5f) {
            return Blocks.ICE.defaultBlockState();
        } else if (roll < 0.8f) {
            return Blocks.PACKED_ICE.defaultBlockState();
        } else {
            return Blocks.BLUE_ICE.defaultBlockState();
        }
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
