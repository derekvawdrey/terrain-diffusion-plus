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
 * Tall, thin rock spires in mountain biomes above treeline. Modeled after hoodoo clusters but
 * with aggressive tapering to single-block tips and mountain-appropriate stone types.
 */
public final class NeedlePeakFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4E454544L;
    private static final int CELL_SIZE = 52;
    private static final int CLUSTER_RADIUS = 10;
    private static final int MAX_COLUMNS = 3;
    private static final int MIN_COLUMN_HEIGHT = 8;
    private static final int MAX_COLUMN_HEIGHT = 20;
    private static final int BASE_RADIUS = 1;
    private static final float PLACEMENT_NOISE_WAVELENGTH = 250.0f;
    private static final float PLACEMENT_THRESHOLD = 0.5f;

    private static final BlockState[] MOUNTAIN_BANDS = {
            Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
    };

    @Override
    public String id() {
        return "needle_peak";
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
        if (TerrainSampling.elevationAt(data, row, col) <= 150f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("mountain") || biomeKey.contains("peaks")
                || biomeKey.contains("jagged") || biomeKey.contains("stony")
                || biomeKey.contains("grove"))) {
            return;
        }

        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;
        float strength = SurfaceNoise.clamp01((placement - PLACEMENT_THRESHOLD) / (1.0f - PLACEMENT_THRESHOLD));

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int columnCount = 1 + (int) (strength * (MAX_COLUMNS - 1));
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
            placeColumn(chunk, worldSurface, motionBlocking, columnSeed, columnWorldX, columnWorldZ,
                    localX, localZ, groundY, height);
        }
    }

    private void placeColumn(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long columnSeed,
                              int worldX, int worldZ, int localX, int localZ, int groundY, int height) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= height; dy++) {
            int worldY = groundY + 1 + dy;
            float taper = 1.0f - (dy / (float) height) * 0.5f;
            float radius = BASE_RADIUS * taper;

            int r = Math.max(0, Math.round(radius));
            for (int dz = -r; dz <= r; dz++) {
                int lz = localZ + dz;
                if (lz < 0 || lz > 15) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int lx = localX + dx;
                    if (lx < 0 || lx > 15) continue;
                    if (Math.sqrt(dx * dx + dz * dz) > radius + 0.3f) continue;

                    int wx = worldX + dx;
                    int wz = worldZ + dz;
                    pos.set(wx, worldY, wz);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    BlockState block = bandFor(columnSeed, wx, worldY, wz);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(lx, worldY, lz, block);
                    motionBlocking.update(lx, worldY, lz, block);
                }
            }
        }
    }

    private static BlockState bandFor(long columnSeed, int x, int y, int z) {
        int band = Math.floorMod(y + (int) (SurfaceNoise.unitHash(columnSeed, x, z) * 2), MOUNTAIN_BANDS.length);
        return MOUNTAIN_BANDS[band];
    }
}
