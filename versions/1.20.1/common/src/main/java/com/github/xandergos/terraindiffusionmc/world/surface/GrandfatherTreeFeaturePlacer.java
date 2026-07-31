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
 * A massive ancient tree with a thick trunk and wide canopy. Uses coherent placement noise so
 * grandfather trees appear in rare, organic clusters across forest biomes rather than uniformly.
 */
public final class GrandfatherTreeFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x47524E44L;
    private static final int CELL_SIZE = 80;
    private static final float SPAWN_CHANCE = 0.05f;
    private static final float MAX_SLOPE = 1.0f;
    private static final float PLACEMENT_NOISE_WAVELENGTH = 300.0f;
    private static final float PLACEMENT_THRESHOLD = 0.6f;
    private static final int TRUNK_RADIUS = 2;
    private static final int MIN_HEIGHT = 8;
    private static final int MAX_HEIGHT = 12;
    private static final int MIN_CANOPY_RADIUS = 4;
    private static final int MAX_CANOPY_RADIUS = 5;
    private static final int CANOPY_THICKNESS = 3;

    @Override
    public String id() {
        return "grandfather_tree";
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

        // Coherent placement noise: only place in highest-noise areas for very rare, clustered placement
        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;
        float strength = SurfaceNoise.clamp01((placement - PLACEMENT_THRESHOLD) / (1.0f - PLACEMENT_THRESHOLD));
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE / strength) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !(biomeKey.contains("forest") || biomeKey.contains("old_growth")
                || biomeKey.contains("taiga") || biomeKey.contains("grove") || biomeKey.contains("plains"))) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > MAX_SLOPE) return;

        int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) - 1;
        if (groundY <= chunk.getMinBuildHeight()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        long seed = site.seed();
        boolean isDarkOak = SurfaceNoise.unitHash(seed, 0, 0) < 0.5f;
        BlockState trunkBlock = isDarkOak ? Blocks.DARK_OAK_LOG.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState();
        BlockState leafBlock = isDarkOak ? Blocks.DARK_OAK_LEAVES.defaultBlockState() : Blocks.OAK_LEAVES.defaultBlockState();

        int height = MIN_HEIGHT + (int) (SurfaceNoise.unitHash(seed, 1, 0) * (MAX_HEIGHT - MIN_HEIGHT));
        int canopyRadius = MIN_CANOPY_RADIUS + (int) (SurfaceNoise.unitHash(seed, 2, 0) * (MAX_CANOPY_RADIUS - MIN_CANOPY_RADIUS));
        int canopyThickness = 2 + (int) (SurfaceNoise.unitHash(seed, 3, 0) * (CANOPY_THICKNESS - 2 + 1));

        int topY = groundY + 1 + height;
        if (topY + canopyThickness >= chunk.getMaxBuildHeight()) return;

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        // Trunk
        for (int dy = 0; dy <= height; dy++) {
            int worldY = groundY + 1 + dy;
            for (int dz = -TRUNK_RADIUS; dz <= TRUNK_RADIUS; dz++) {
                int worldZ = site.worldZ() + dz;
                int localZ = worldZ - minZ;
                if (localZ < 0 || localZ > 15) continue;
                for (int dx = -TRUNK_RADIUS; dx <= TRUNK_RADIUS; dx++) {
                    int worldX = site.worldX() + dx;
                    int localX = worldX - minX;
                    if (localX < 0 || localX > 15) continue;
                    float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                    if (horizontal > TRUNK_RADIUS + 0.3f) continue;
                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, trunkBlock, false);
                    worldSurface.update(localX, worldY, localZ, trunkBlock);
                    motionBlocking.update(localX, worldY, localZ, trunkBlock);
                }
            }
        }

        // Canopy
        for (int cy = 0; cy < canopyThickness; cy++) {
            int worldY = topY + cy;
            for (int dz = -canopyRadius; dz <= canopyRadius; dz++) {
                int worldZ = site.worldZ() + dz;
                int localZ = worldZ - minZ;
                if (localZ < 0 || localZ > 15) continue;
                for (int dx = -canopyRadius; dx <= canopyRadius; dx++) {
                    int worldX = site.worldX() + dx;
                    int localX = worldX - minX;
                    if (localX < 0 || localX > 15) continue;
                    float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
                    if (horizontal > canopyRadius + 0.3f) continue;
                    if (SurfaceNoise.unitHash(seed, dx, dz) > 0.85f) continue;
                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, leafBlock, false);
                    worldSurface.update(localX, worldY, localZ, leafBlock);
                    motionBlocking.update(localX, worldY, localZ, leafBlock);
                }
            }
        }
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
