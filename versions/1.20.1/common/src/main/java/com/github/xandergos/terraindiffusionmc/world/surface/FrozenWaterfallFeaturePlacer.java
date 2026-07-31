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
 * Ice columns hanging down from steep slopes in frozen biomes. Anchors to ground level and
 * builds downward into air, tapering to a point like a frozen waterfall.
 */
public final class FrozenWaterfallFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x46525A57L;
    private static final int CELL_SIZE = 48;
    private static final float SPAWN_CHANCE = 0.2f;
    private static final float MIN_SLOPE_BLOCKS = 0.5f;
    /** A neighbour this many blocks lower counts as a cliff edge to hang ice from. */
    private static final float MIN_DROP_BLOCKS = 3f;
    private static final int DROP_SAMPLE_STEP = 2;
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
        if (TerrainSampling.slopeAt(data, row, col, 2) < SurfaceStamp.slopeFromBlocks(MIN_SLOPE_BLOCKS)) return;

        // Confirm the ground actually falls away next to the anchor. The direction used to be a
        // coin flip on each axis -- always one of the four diagonals, unrelated to which way the
        // terrain drops -- so this rejected most genuine cliff edges and accepted flat ground that
        // happened to dip diagonally.
        if (!hasDropAdjacent(data, row, col)) return;

        int groundY = SurfaceStamp.surfaceY(chunk, localX, localZ);
        if (groundY <= chunk.getMinBuildHeight()) return;
        if (!SurfaceStamp.isSolidGround(SurfaceStamp.stateAt(chunk, site.worldX(), groundY, site.worldZ()))) return;

        stamp(chunk, site, groundY);
    }

    /** True when any of the four cardinal neighbours sits at least a block lower than this cell. */
    private static boolean hasDropAdjacent(HeightmapData data, int row, int col) {
        float here = TerrainSampling.elevationAt(data, row, col);
        float minDrop = SurfaceStamp.blocksToElevation(MIN_DROP_BLOCKS);
        int[] dRow = {1, -1, 0, 0};
        int[] dCol = {0, 0, 1, -1};
        for (int i = 0; i < 4; i++) {
            int r = row + dRow[i] * DROP_SAMPLE_STEP;
            int c = col + dCol[i] * DROP_SAMPLE_STEP;
            if (!TerrainSampling.inBounds(data, r, c)) continue;
            if (here - TerrainSampling.elevationAt(data, r, c) >= minDrop) return true;
        }
        return false;
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int groundY) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        int topRadius = SurfaceStamp.randRange(site.seed(), 0, 0, 1, TOP_RADIUS);

        for (int dy = 0; dy < MAX_LENGTH; dy++) {
            int worldY = groundY - dy;
            float taper = 1.0f - (dy / (float) MAX_LENGTH);
            float radius = topRadius * taper;

            if (radius < 0.1f) break;

            // Fill the whole tapered disc at this level, not just the first air block found. The
            // `&& !placed` loop guards stopped after one block per layer, which left a single-block
            // thread hugging the cliff and made TOP_RADIUS and the taper dead constants.
            int r = Math.max(0, Math.round(radius));
            boolean placed = false;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.sqrt(dx * dx + dz * dz) > radius + 0.3f) continue;

                    int wx = site.worldX() + dx;
                    int wz = site.worldZ() + dz;
                    BlockState block = iceFor(site.seed(), wx, worldY, wz);
                    if (SurfaceStamp.placeIfAir(chunk, worldSurface, motionBlocking, wx, worldY, wz, block)) {
                        placed = true;
                    }
                }
            }
            // Nothing open at this level means the cliff face has closed up; stop descending.
            if (!placed) break;
        }
    }

    private static BlockState iceFor(long seed, int x, int y, int z) {
        return SurfaceNoise.unitHash(seed ^ 0x4943454CL, x, z) < 0.7f
                ? Blocks.PACKED_ICE.defaultBlockState()
                : Blocks.BLUE_ICE.defaultBlockState();
    }
}
