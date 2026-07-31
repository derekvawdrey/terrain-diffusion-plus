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
 * Glacial erratic placer -- large deepslate boulders in cold biomes at valley elevations.
 * The incongruous placement (deepslate in a valley, far from its source) mimics real glacial
 * erratics deposited by ancient ice sheets.
 *
 * <p>Based on {@link BoulderFeaturePlacer} (TEMPLATE A) with: larger size, cold-biome whitelist,
 * valley-floor elevation gate, and deepslate block palette.</p>
 */
public final class GlacialErraticFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x474C4345L;
    private static final float SPAWN_CHANCE = 0.12f;
    private static final float MAX_SLOPE_BLOCKS = 0.25f;
    private static final int MAX_RADIUS = 4;
    private static final int CELL_SIZE = 64;

    @Override
    public String id() {
        return "glacial_erratic";
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

        // 2. Rarity roll -- rare placement, scattered uniformly
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        // 3. Terrain-shape eligibility, read from the (approximate) diffusion raster.
        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation <= 0f || elevation >= SurfaceStamp.blocksToElevation(30f)) return;

        // Cold biomes only: tundra, ice spikes, frozen peaks, snowy slopes
        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null) return;
        if (!biomeKey.contains("tundra") && !biomeKey.contains("ice_spikes")
                && !biomeKey.contains("snow") && !biomeKey.contains("frozen")
                && !biomeKey.contains("ice")) {
            return;
        }
        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        // 4. Anchor to the real generated surface (not the raster) now that we're committing.
        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinY()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isSolidGround(chunk.getBlockState(pos))) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        int radius = Math.min(MAX_RADIUS, 3 + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * 2));
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

                // Per-column height variation using noise for irregular shape
                float columnNoise = SurfaceNoise.unitHash(site.seed(), dx, dz);
                int height = Math.round((radius - horizontal) * 1.15f);
                if (columnNoise > 0.6f) height++;
                BlockState block = blockFor(site.seed(), worldX, worldZ);

                for (int dy = 0; dy <= height; dy++) {
                    int worldY = groundY + 1 + dy;
                    pos.set(worldX, worldY, worldZ);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, block);
                    worldSurface.update(localX, worldY, localZ, block);
                    motionBlocking.update(localX, worldY, localZ, block);
                }
            }
        }
    }

    private static BlockState blockFor(long seed, int x, int z) {
        float hash = SurfaceNoise.unitHash(seed ^ 0x474C4345L, x, z);
        if (hash < 0.3f) {
            return Blocks.DEEPSLATE.defaultBlockState();
        } else if (hash < 0.6f) {
            return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        } else {
            return Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        }
    }

    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
