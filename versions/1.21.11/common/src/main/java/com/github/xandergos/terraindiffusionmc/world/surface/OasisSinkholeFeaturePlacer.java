package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Rare oasis sinkhole feature. Creates a circular depression filled with water,
 * surrounded by sand/grass and a few palm-like trees around the rim.
 * Targets desert biomes on flat, low-elevation terrain.
 */
public final class OasisSinkholeFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x4F415349L;
    private static final float SPAWN_CHANCE = 0.08f;
    private static final float MAX_SLOPE_BLOCKS = 0.08f;
    /** Oases sit in low desert basins, measured in blocks above sea level. */
    private static final float MAX_ELEVATION_BLOCKS = 40f;
    private static final int CELL_SIZE = 80;
    private static final int MIN_RADIUS = 4;
    private static final int MAX_RADIUS = 5;
    private static final int MIN_TREE_COUNT = 3;
    private static final int MAX_TREE_COUNT = 5;
    private static final int MIN_TREE_HEIGHT = 4;
    private static final int MAX_TREE_HEIGHT = 6;

    @Override
    public String id() {
        return "oasis_sinkhole";
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
        if (elevation <= 0f || elevation >= SurfaceStamp.blocksToElevation(MAX_ELEVATION_BLOCKS)) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !biomeKey.contains("desert")) return;

        if (TerrainSampling.slopeAt(data, row, col, 2) > SurfaceStamp.slopeFromBlocks(MAX_SLOPE_BLOCKS)) return;

        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinY()) return;

        stamp(chunk, site, groundY);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        long seed = site.seed();
        int radius = SurfaceStamp.randRange(seed, 0, 0, MIN_RADIUS, MAX_RADIUS);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int waterRadius = radius - 1;

        for (int dz = -radius; dz <= radius; dz++) {
            int worldZ = site.worldZ() + dz;
            int localZ = worldZ - minZ;
            if (localZ < 0 || localZ > 15) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int worldX = site.worldX() + dx;
                int localX = worldX - minX;
                if (localX < 0 || localX > 15) continue;

                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > radius) continue;

                float normalizedDist = dist / radius;
                int digDepth = Math.max(1, (int) (3f * (1f - normalizedDist)));

                if (dist <= waterRadius) {
                    // Fill the whole basin with water up to the waterline. Watering only the floor
                    // and the top course left an air gap between them, so the upper sheet had
                    // nothing holding it up and drained the moment the chunk ticked.
                    for (int dy = -digDepth; dy <= 0; dy++) {
                        SurfaceStamp.fill(chunk, worldSurface, motionBlocking, worldX, groundY + dy, worldZ,
                                Blocks.WATER.defaultBlockState());
                    }
                } else {
                    for (int dy = -digDepth; dy <= 0; dy++) {
                        int worldY = groundY + dy;
                        BlockState fillBlock = SurfaceNoise.unitHash(seed, worldX, worldZ) < 0.5f
                                ? Blocks.SAND.defaultBlockState()
                                : Blocks.GRASS_BLOCK.defaultBlockState();
                        SurfaceStamp.fill(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ, fillBlock);
                    }
                }
            }
        }

        int numTrees = SurfaceStamp.randRange(seed, 1, 0, MIN_TREE_COUNT, MAX_TREE_COUNT);
        for (int i = 0; i < numTrees; i++) {
            float angle = SurfaceNoise.unitHash(seed, 10 + i, 0) * (float) (Math.PI * 2);
            float treeDist = waterRadius + 0.5f + SurfaceNoise.unitHash(seed, 11 + i, 0) * 1.5f;
            int treeDx = (int) Math.round(Math.cos(angle) * treeDist);
            int treeDz = (int) Math.round(Math.sin(angle) * treeDist);
            int treeWorldX = site.worldX() + treeDx;
            int treeWorldZ = site.worldZ() + treeDz;
            int treeLocalX = treeWorldX - minX;
            int treeLocalZ = treeWorldZ - minZ;
            if (treeLocalX < 0 || treeLocalX > 15 || treeLocalZ < 0 || treeLocalZ > 15) continue;

            int treeGroundY = SurfaceStamp.surfaceY(chunk, treeLocalX, treeLocalZ);
            if (treeGroundY <= chunk.getMinY()) continue;

            int treeHeight = SurfaceStamp.randRange(seed, 20 + i, 0, MIN_TREE_HEIGHT, MAX_TREE_HEIGHT);
            placePalm(chunk, worldSurface, motionBlocking, treeWorldX, treeWorldZ, treeGroundY, treeHeight);
        }
    }

    /**
     * A trunk with a frond crown on top. The trunk alone -- which is all this used to place -- reads
     * as a bare post, not a palm.
     */
    private void placePalm(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                            int worldX, int worldZ, int groundY, int height) {
        BlockState log = Blocks.JUNGLE_LOG.defaultBlockState();
        for (int dy = 1; dy <= height; dy++) {
            if (!SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, worldX, groundY + dy, worldZ, log)) {
                return;
            }
        }

        int crownY = groundY + height;
        BlockState frond = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        BlockState outerFrond = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 2);

        SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, worldX, crownY + 1, worldZ, frond);
        int[] dxDirs = {-1, 1, 0, 0};
        int[] dzDirs = {0, 0, -1, 1};
        for (int i = 0; i < dxDirs.length; i++) {
            SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking,
                    worldX + dxDirs[i], crownY, worldZ + dzDirs[i], frond);
            SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking,
                    worldX + dxDirs[i] * 2, crownY, worldZ + dzDirs[i] * 2, outerFrond);
        }
    }
}
