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
 * Cluster of hexagonal-ish basalt columns. Follows the HoodooClusterFeaturePlacer pattern
 * (multi-anchor cluster with coherent noise gating) but stamps uniform-width columns instead
 * of tapered hoodoos. Each column is a straight vertical pillar of tuff/deepslate with a flat
 * top, mimicking columnar basalt formations.
 */
public final class BasaltColumnFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x42415341L;
    private static final int CELL_SIZE = 44;
    private static final int CLUSTER_RADIUS = 6;
    private static final int MAX_COLUMNS = 6;
    private static final int MIN_COLUMN_HEIGHT = 4;
    private static final int MAX_COLUMN_HEIGHT = 10;
    private static final int BASE_RADIUS = 1;

    private static final float PLACEMENT_NOISE_WAVELENGTH = 200.0f;
    private static final float PLACEMENT_THRESHOLD = 0.3f;

    /**
     * Kept to blocks that exist on every supported Minecraft version -- {@code POLISHED_TUFF}
     * arrived in 1.21 and broke the 1.20.1 build. Deepslate also reads better here than a polished
     * block: columnar basalt is raw stone, not something quarried.
     */
    private static final BlockState[] BASALT_BLOCKS = {
            Blocks.TUFF.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
    };

    @Override
    public String id() {
        return "basalt_columns";
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
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("badlands") || biomeKey.contains("mesa")
                || biomeKey.contains("mountain") || biomeKey.contains("peaks"))) return;

        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;
        float strength = SurfaceNoise.clamp01((placement - PLACEMENT_THRESHOLD) / (1.0f - PLACEMENT_THRESHOLD));

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

            int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
            if (groundY <= chunk.getMinBuildHeight()) continue;
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(columnWorldX, groundY, columnWorldZ);
            if (chunk.getBlockState(probe).isAir()) continue;

            int height = MIN_COLUMN_HEIGHT
                    + (int) (SurfaceNoise.unitHash(columnSeed, 3, 0) * (MAX_COLUMN_HEIGHT - MIN_COLUMN_HEIGHT));
            placeColumn(chunk, worldSurface, motionBlocking, columnSeed, columnWorldX, columnWorldZ,
                    localX, localZ, groundY, height, i);
        }
    }

    private void placeColumn(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long columnSeed,
                              int worldX, int worldZ, int localX, int localZ, int groundY, int height, int columnIndex) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState columnBlock = BASALT_BLOCKS[columnIndex % BASALT_BLOCKS.length];

        for (int dy = 0; dy <= height; dy++) {
            int worldY = groundY + 1 + dy;
            int r = BASE_RADIUS;
            for (int dz = -r; dz <= r; dz++) {
                int lz = localZ + dz;
                if (lz < 0 || lz > 15) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int lx = localX + dx;
                    if (lx < 0 || lx > 15) continue;
                    if (Math.sqrt(dx * dx + dz * dz) > BASE_RADIUS + 0.3f) continue;

                    int wx = worldX + dx;
                    int wz = worldZ + dz;
                    pos.set(wx, worldY, wz);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, columnBlock, false);
                    worldSurface.update(lx, worldY, lz, columnBlock);
                    motionBlocking.update(lx, worldY, lz, columnBlock);
                }
            }
        }
    }
}
