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
 * Ice spikes jutting from frozen surfaces. Uses the cluster pattern from
 * {@link HoodooClusterFeaturePlacer} -- multiple tapered columns per site, each independently
 * anchored. Spikes are sharper than hoodoos, coming to a single-block point with no capstone.
 * Prefers frozen lake and river surfaces.
 */
public final class IceSpikeFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x49434553L;
    private static final int CELL_SIZE = 36;
    private static final int CLUSTER_RADIUS = 8;
    private static final int MAX_COLUMNS = 5;
    private static final int MIN_COLUMN_HEIGHT = 4;
    private static final int MAX_COLUMN_HEIGHT = 12;
    private static final int BASE_RADIUS = 1;

    private static final float PLACEMENT_NOISE_WAVELENGTH = 160.0f;
    private static final float PLACEMENT_THRESHOLD = 0.3f;

    @Override
    public String id() {
        return "ice_spike";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return CLUSTER_RADIUS + BASE_RADIUS + 2;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation < 0 || elevation > 5) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("ice_spikes") || biomeKey.contains("frozen")
                || biomeKey.contains("tundra"))) {
            return;
        }

        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;
        float strength = SurfaceNoise.clamp01((placement - PLACEMENT_THRESHOLD) / (1.0f - PLACEMENT_THRESHOLD));

        boolean nearWater = hasFluvialWater(data, row, col);
        if (!nearWater && SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ() + 500) > 0.5f) return;

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int columnCount = 2 + (int) (strength * (MAX_COLUMNS - 2));
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        for (int i = 0; i < columnCount; i++) {
            long columnSeed = SurfaceNoise.hash(site.seed(), i, 1000 + i);
            float angle = SurfaceNoise.unitHash(columnSeed, 1, 0) * (float) (Math.PI * 2);
            float distance = SurfaceNoise.unitHash(columnSeed, 2, 0) * CLUSTER_RADIUS;
            int columnWorldX = site.worldX() + Math.round((float) Math.cos(angle) * distance);
            int columnWorldZ = site.worldZ() + Math.round((float) Math.sin(angle) * distance);

            int localX = columnWorldX - minX;
            int localZ = columnWorldZ - minZ;
            if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) continue;

            int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
            if (groundY <= chunk.getMinBuildHeight()) continue;
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(columnWorldX, groundY, columnWorldZ);
            if (chunk.getBlockState(probe).isAir()) continue;

            int height = MIN_COLUMN_HEIGHT
                    + (int) (SurfaceNoise.unitHash(columnSeed, 3, 0) * (MAX_COLUMN_HEIGHT - MIN_COLUMN_HEIGHT));
            placeSpike(chunk, worldSurface, motionBlocking, columnSeed, columnWorldX, columnWorldZ,
                    localX, localZ, groundY, height);
        }
    }

    private void placeSpike(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long columnSeed,
                             int worldX, int worldZ, int localX, int localZ, int groundY, int height) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < height; dy++) {
            int worldY = groundY + 1 + dy;
            float taper = 1.0f - (dy / (float) height);
            float radius = (BASE_RADIUS + 1) * taper;

            if (radius < 0.2f) break;

            int r = Math.max(0, Math.round(radius));
            for (int dz = -r; dz <= r; dz++) {
                int lz = localZ + dz;
                if (lz < 0 || lz > 15) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int lx = localX + dx;
                    if (lx < 0 || lx > 15) continue;
                    if (Math.sqrt(dx * dx + dz * dz) > radius + 0.2f) continue;

                    int wx = worldX + dx;
                    int wz = worldZ + dz;
                    pos.set(wx, worldY, wz);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    BlockState block = iceFor(columnSeed, wx, worldY, wz);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(lx, worldY, lz, block);
                    motionBlocking.update(lx, worldY, lz, block);
                }
            }
        }
    }

    private static BlockState iceFor(long seed, int x, int y, int z) {
        return SurfaceNoise.unitHash(seed ^ 0x4943454CL, x, z) < 0.8f
                ? Blocks.PACKED_ICE.defaultBlockState()
                : Blocks.BLUE_ICE.defaultBlockState();
    }

    private static boolean hasFluvialWater(HeightmapData data, int row, int col) {
        if (data.riverWaterSurface != null) {
            return data.riverWaterSurface[row][col] != HeightmapData.NO_FLUVIAL_WATER;
        }
        if (data.riverWater != null) {
            return data.riverWater[row][col] != 0;
        }
        return false;
    }
}
