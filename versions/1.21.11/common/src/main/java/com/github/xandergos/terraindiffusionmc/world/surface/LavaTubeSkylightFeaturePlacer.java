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
 * Lava tube skylight placer -- a collapsed opening exposing a lava cave below.
 * Based on {@link BoulderFeaturePlacer} (single-anchor pattern) but digs DOWN through
 * terrain to create a crater-like hole with lava visible at the bottom.
 *
 * <p>Pattern: gate on volcanic biome + flat slope + coherent noise clustering + rarity roll,
 * anchor to heightmap, then carve a circular hole downward with lined walls and contained lava.</p>
 */
public final class LavaTubeSkylightFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4C565453L;
    private static final float SPAWN_CHANCE = 0.06f;
    private static final int CELL_SIZE = 80;
    private static final float MAX_SLOPE_BLOCKS = 0.3f;
    private static final float MIN_ELEVATION_BLOCKS = 25f;
    private static final int HOLE_RADIUS_MIN = 2;
    private static final int HOLE_RADIUS_MAX = 3;
    private static final int HOLE_DEPTH_MIN = 3;
    private static final int HOLE_DEPTH_MAX = 5;

    private static final float PLACEMENT_NOISE_WAVELENGTH = 300.0f;
    private static final float PLACEMENT_THRESHOLD = 0.6f;

    private static final BlockState[] CARVABLE_BLOCKS = {
            Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.BLACKSTONE.defaultBlockState(),
            Blocks.POLISHED_BLACKSTONE.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
    };

    @Override
    public String id() {
        return "lava_tube_skylight";
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

        float elevation = TerrainSampling.elevationAt(data, row, col);
        if (elevation <= SurfaceStamp.blocksToElevation(MIN_ELEVATION_BLOCKS)) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !isVolcanicBiome(biomeKey)) return;

        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        float placement = SurfaceNoise.valueNoise(worldSeed ^ SALT,
                site.worldX() / PLACEMENT_NOISE_WAVELENGTH, site.worldZ() / PLACEMENT_NOISE_WAVELENGTH);
        if (placement <= PLACEMENT_THRESHOLD) return;

        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinY()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(site.worldX(), groundY, site.worldZ());
        if (!isCarvable(chunk.getBlockState(pos))) return;

        long seed = site.seed();
        int radius = HOLE_RADIUS_MIN + (int) (SurfaceNoise.unitHash(seed, 0, 0) * (HOLE_RADIUS_MAX - HOLE_RADIUS_MIN + 1));
        int depth = HOLE_DEPTH_MIN + (int) (SurfaceNoise.unitHash(seed, 1, 0) * (HOLE_DEPTH_MAX - HOLE_DEPTH_MIN + 1));
        carveSkylight(chunk, site, groundY, radius, depth);
    }

    private static boolean isVolcanicBiome(String biomeKey) {
        return biomeKey.contains("badlands") || biomeKey.contains("mesa")
                || biomeKey.contains("mountain") || biomeKey.contains("peaks");
    }

    private void carveSkylight(ChunkAccess chunk, SiteGrid.Site site, int groundY, int radius, int depth) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        long seed = site.seed();

        int minBuildHeight = chunk.getMinY();
        int lavaY = groundY - depth + 1;
        if (lavaY <= minBuildHeight) return;

        for (int dz = -radius; dz <= radius; dz++) {
            int worldZ = site.worldZ() + dz;
            int localZ = worldZ - minZ;
            if (localZ < 0 || localZ > 15) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > radius + 0.5f) continue;

                int worldX = site.worldX() + dx;
                int localX = worldX - minX;
                if (localX < 0 || localX > 15) continue;

                boolean isRim = dist > radius - 1f;

                for (int dy = 0; dy < depth; dy++) {
                    int worldY = groundY - dy;
                    if (worldY <= minBuildHeight) break;

                    pos.set(worldX, worldY, worldZ);
                    BlockState current = chunk.getBlockState(pos);

                    if (dy == depth - 1) {
                        // Floor of the shaft. The lava went in only when the target was *already*
                        // air, which at an uncarved floor block it never is -- so the skylight
                        // bottomed out in plain air and never showed any lava at all.
                        if (dist <= 1f) {
                            placeContainedLava(chunk, worldX, worldY, worldZ, worldSurface, motionBlocking);
                        } else if (isCarvable(current) || current.isAir()) {
                            SurfaceStamp.carve(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ);
                        }
                    } else if (isRim && dy == 0) {
                        if (isCarvable(current)) {
                            SurfaceStamp.placeReplacing(chunk, worldSurface, motionBlocking,
                                    worldX, worldY, worldZ, rimBlockFor(seed, worldX, worldZ));
                        }
                    } else if (isRim && isWallBlock(dy, depth, dist, radius)) {
                        if (isCarvable(current)) {
                            SurfaceStamp.placeReplacing(chunk, worldSurface, motionBlocking,
                                    worldX, worldY, worldZ, wallBlockFor(seed, worldX, worldY, worldZ));
                        }
                    } else if (isCarvable(current) || current.isAir()) {
                        // carve() lowers the heightmaps as it goes; a bare setBlockState(AIR) left
                        // WORLD_SURFACE_WG still reporting the terrain that used to be here.
                        SurfaceStamp.carve(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ);
                    }
                }
            }
        }
    }

    private static boolean isWallBlock(int dy, int depth, float dist, float radius) {
        return dist > radius - 0.5f && dy < depth - 2;
    }

    private static BlockState wallBlockFor(long seed, int x, int y, int z) {
        float h = SurfaceNoise.unitHash(seed ^ 0x57414C4CL, x, z);
        if (h < 0.3f) return Blocks.BLACKSTONE.defaultBlockState();
        if (h < 0.6f) return Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        return Blocks.TUFF.defaultBlockState();
    }

    private static BlockState rimBlockFor(long seed, int x, int z) {
        return SurfaceNoise.unitHash(seed ^ 0x52494D4CL, x, z) < 0.5f
                ? Blocks.CRIMSON_NYLIUM.defaultBlockState()
                : Blocks.MAGMA_BLOCK.defaultBlockState();
    }

    /**
     * Places the lava pool and dams any open side of it.
     *
     * <p>The containment ring is the one place this feature reaches outside the column it is
     * carving, so every write goes through {@link SurfaceStamp}: {@code ChunkAccess.setBlockState}
     * masks coordinates with {@code & 15}, so a raw write one block past the chunk edge silently
     * lands fifteen blocks away on the opposite side, and the matching {@code Heightmap.update}
     * with a negative local coordinate indexes out of the heightmap's backing storage and throws
     * mid-worldgen.</p>
     */
    private static void placeContainedLava(ChunkAccess chunk, int worldX, int worldY, int worldZ,
                                            Heightmap worldSurface, Heightmap motionBlocking) {
        SurfaceStamp.fill(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ,
                Blocks.LAVA.defaultBlockState());

        int[] dxDirs = {-1, 1, 0, 0};
        int[] dzDirs = {0, 0, -1, 1};
        for (int i = 0; i < dxDirs.length; i++) {
            int nx = worldX + dxDirs[i];
            int nz = worldZ + dzDirs[i];
            if (!SurfaceStamp.inChunk(chunk, nx, nz)) continue;
            if (!SurfaceStamp.stateAt(chunk, nx, worldY, nz).isAir()) continue;
            SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, nx, worldY, nz,
                    Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    private static boolean isCarvable(BlockState state) {
        for (BlockState carvable : CARVABLE_BLOCKS) {
            if (state.getBlock() == carvable.getBlock()) return true;
        }
        return false;
    }
}
