package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stamps a low, wave-like ridge of sandstone blocks resembling fossilized dune patterns.
 * Targets badlands, desert, and mesa biomes. The ridge is oriented in a random direction
 * derived from the site seed, with alternating sandstone variants creating a layered appearance.
 */
public final class PetrifiedDuneFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x50455444L;
    private static final float SPAWN_CHANCE = 0.2f;
    private static final int CELL_SIZE = 44;
    private static final int MIN_RIDGE_LENGTH = 8;
    private static final int MAX_RIDGE_LENGTH = 12;
    private static final int MIN_RIDGE_AMP = 2;
    private static final int MAX_RIDGE_AMP = 3;

    @Override
    public String id() {
        return "petrified_dune";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return (MAX_RIDGE_LENGTH / 2) + MAX_RIDGE_AMP + 2;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        // No site-in-chunk gate: every column below is anchored and clipped individually, so each
        // chunk this site reaches draws its own slice instead of the footprint being cut off at the
        // chunk border.
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || (!biomeKey.contains("badlands") && !biomeKey.contains("mesa") && !biomeKey.contains("desert"))) {
            return;
        }

        stampRidge(chunk, site);
    }

    private void stampRidge(ChunkAccess chunk, SiteGrid.Site site) {
        long seed = site.seed();
        int ridgeLength = MIN_RIDGE_LENGTH + (int) (SurfaceNoise.unitHash(seed, 0, 0) * (MAX_RIDGE_LENGTH - MIN_RIDGE_LENGTH + 1));
        int ridgeAmp = MIN_RIDGE_AMP + (int) (SurfaceNoise.unitHash(seed, 1, 0) * (MAX_RIDGE_AMP - MIN_RIDGE_AMP + 1));
        float angle = SurfaceNoise.unitHash(seed, 2, 0) * (float) (Math.PI * 2);
        int dirX = (int) Math.round(Math.cos(angle));
        int dirZ = (int) Math.round(Math.sin(angle));
        if (dirX == 0 && dirZ == 0) dirX = 1;

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int halfLength = ridgeLength / 2;
        for (int t = -halfLength; t <= halfLength; t++) {
            float normalizedT = (float) t / halfLength;
            float wave = (float) Math.sin(normalizedT * (float) Math.PI) * ridgeAmp;
            int ridgeHeight = Math.max(1, Math.round(wave));

            int offsetX = dirX * t;
            int offsetZ = dirZ * t;
            int perpX = -dirZ;
            int perpZ = dirX;
            int width = 1 + (int) (SurfaceNoise.unitHash(seed, 3, t) * 2);

            // Sample each column's base height once, then stack upward from it. Re-reading the
            // heightmap per layer feeds this loop's own writes back in, so every layer lands one
            // block higher than the last and the ridge comes out as detached floating stripes.
            for (int w = -width; w <= width; w++) {
                int worldX = site.worldX() + offsetX + perpX * w;
                int worldZ = site.worldZ() + offsetZ + perpZ * w;
                int localXc = worldX - minX;
                int localZc = worldZ - minZ;
                if (localXc < 0 || localXc > 15 || localZc < 0 || localZc > 15) continue;

                int columnBaseY = SurfaceStamp.surfaceY(chunk, localXc, localZc);
                if (columnBaseY <= chunk.getMinY()) continue;

                for (int layer = 0; layer < ridgeHeight; layer++) {
                    int worldY = columnBaseY + 1 + layer;
                    BlockState block = sandstoneForLayer(layer, t);
                    if (!SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking,
                            worldX, worldY, worldZ, block)) {
                        break;
                    }
                }
            }
        }
    }

    private static BlockState sandstoneForLayer(int layer, int t) {
        int pattern = (layer + Math.abs(t)) % 3;
        switch (pattern) {
            case 0: return Blocks.SANDSTONE.defaultBlockState();
            case 1: return Blocks.CUT_SANDSTONE.defaultBlockState();
            default: return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        }
    }
}
