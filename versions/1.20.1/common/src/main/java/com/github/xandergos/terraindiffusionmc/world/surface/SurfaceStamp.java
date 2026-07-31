package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shared anchoring and block-writing helpers for {@link SurfaceFeaturePlacer} implementations.
 *
 * <p>Two things here exist because getting them wrong is easy and the failure is silent:</p>
 * <ul>
 *     <li><b>Anchoring.</b> {@code ChunkAccess.getHeight(type, x, z)} returns
 *     {@code getFirstAvailable() - 1}, i.e. the Y of the <em>topmost non-air block</em> -- not the
 *     first free Y. Use {@link #surfaceY} and treat its result as "the block the feature rests on";
 *     the first writable Y is {@code surfaceY() + 1}. Subtracting one from {@code getHeight} (as
 *     the original templates did) anchors a block inside the terrain, which silently eats the
 *     bottom layer of a blob and completely erases single-layer features.</li>
 *     <li><b>Writing.</b> {@code ChunkAccess.setBlockState} masks coordinates with {@code & 15}, so
 *     a write outside the current chunk silently lands on the opposite edge of the same chunk, and
 *     a {@code Heightmap.update} with a negative local coordinate throws. Every write here is
 *     clipped to the chunk and to build height first, so callers cannot corrupt a neighbour.</li>
 * </ul>
 */
public final class SurfaceStamp {
    private SurfaceStamp() {}

    /**
     * Y of the topmost non-air block in this column -- the block a surface feature sits on. The
     * first Y a feature may write is {@code surfaceY(...) + 1}.
     *
     * <p>Counts fluids: over an ocean or lake this is the water surface, not the floor. Features
     * that need the solid floor under water want {@link #seabedY}.</p>
     */
    public static int surfaceY(ChunkAccess chunk, int localX, int localZ) {
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
    }

    /**
     * Y of the topmost block that is neither air nor fluid, scanning down from the column's
     * surface. Returns {@link Integer#MIN_VALUE} if the column is fluid or air all the way down.
     * Use this to sit a feature on the seabed rather than on the water surface.
     */
    public static int seabedY(ChunkAccess chunk, int worldX, int localX, int worldZ, int localZ) {
        int top = surfaceY(chunk, localX, localZ);
        if (top <= chunk.getMinBuildHeight()) return Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = top; y > chunk.getMinBuildHeight(); y--) {
            pos.set(worldX, y, worldZ);
            if (isSolidGround(chunk.getBlockState(pos))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** A block a feature can rest on: present, and not a fluid. */
    public static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** True when {@code worldX}/{@code worldZ} fall inside {@code chunk}'s own 16x16 columns. */
    public static boolean inChunk(ChunkAccess chunk, int worldX, int worldZ) {
        int localX = worldX - chunk.getPos().getMinBlockX();
        int localZ = worldZ - chunk.getPos().getMinBlockZ();
        return localX >= 0 && localX <= 15 && localZ >= 0 && localZ <= 15;
    }

    private static boolean inBuildRange(ChunkAccess chunk, int worldY) {
        return worldY >= chunk.getMinBuildHeight() && worldY <= chunk.getMaxBuildHeight() - 1;
    }

    /**
     * Writes {@code block} only where the target is currently air. Silently does nothing outside
     * this chunk or outside build height. Returns true if a block was written.
     */
    public static boolean placeIfAir(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                      int worldX, int worldY, int worldZ, BlockState block) {
        return place(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ, block, false);
    }

    /**
     * Like {@link #placeIfAir}, but also overwrites fluids -- for features built underwater, where
     * every target block is water rather than air.
     */
    public static boolean placeIfAirOrFluid(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                             int worldX, int worldY, int worldZ, BlockState block) {
        return place(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ, block, true);
    }

    private static boolean place(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                  int worldX, int worldY, int worldZ, BlockState block, boolean replaceFluid) {
        if (!inBuildRange(chunk, worldY)) return false;
        int localX = worldX - chunk.getPos().getMinBlockX();
        int localZ = worldZ - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        BlockState current = chunk.getBlockState(pos);
        if (!current.isAir() && !(replaceFluid && !current.getFluidState().isEmpty())) return false;

        chunk.setBlockState(pos, block, false);
        worldSurface.update(localX, worldY, localZ, block);
        motionBlocking.update(localX, worldY, localZ, block);
        return true;
    }

    /**
     * Overwrites whatever is there, for the load-bearing parts of a structure (arch legs, and the
     * like) that must not be interrupted by existing terrain. Still clipped to this chunk.
     */
    public static boolean placeReplacing(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                          int worldX, int worldY, int worldZ, BlockState block) {
        if (!inBuildRange(chunk, worldY)) return false;
        int localX = worldX - chunk.getPos().getMinBlockX();
        int localZ = worldZ - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        chunk.setBlockState(pos, block, false);
        worldSurface.update(localX, worldY, localZ, block);
        motionBlocking.update(localX, worldY, localZ, block);
        return true;
    }

    /**
     * Replaces a block with air and lets the heightmaps drop.
     *
     * <p>{@link Heightmap#update} only lowers a column when it is called with a non-opaque state at
     * exactly the current top Y, so carving without this leaves {@code WORLD_SURFACE_WG} and
     * {@code MOTION_BLOCKING} reporting terrain that is no longer there.</p>
     */
    public static boolean carve(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                 int worldX, int worldY, int worldZ) {
        return fill(chunk, worldSurface, motionBlocking, worldX, worldY, worldZ,
                Blocks.AIR.defaultBlockState());
    }

    /**
     * Unconditionally sets a block (air, water, lava, ...) and updates the heightmaps in both
     * directions. Clipped to this chunk and build height.
     */
    public static boolean fill(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking,
                                int worldX, int worldY, int worldZ, BlockState block) {
        if (!inBuildRange(chunk, worldY)) return false;
        int localX = worldX - chunk.getPos().getMinBlockX();
        int localZ = worldZ - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        chunk.setBlockState(pos, block, false);
        worldSurface.update(localX, worldY, localZ, block);
        motionBlocking.update(localX, worldY, localZ, block);
        return true;
    }

    /** Block state at a world position, or air when the position is outside this chunk. */
    public static BlockState stateAt(ChunkAccess chunk, int worldX, int worldY, int worldZ) {
        if (!inChunk(chunk, worldX, worldZ) || !inBuildRange(chunk, worldY)) {
            return Blocks.AIR.defaultBlockState();
        }
        return chunk.getBlockState(new BlockPos.MutableBlockPos(worldX, worldY, worldZ));
    }

    /**
     * Uniform integer in {@code [minInclusive, maxInclusive]}. Placers previously open-coded this
     * as {@code min + (int)(hash * (max - min))}, which can never return {@code max}.
     */
    public static int randRange(long seed, int saltX, int saltZ, int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        int span = maxInclusive - minInclusive + 1;
        int roll = (int) (SurfaceNoise.unitHash(seed, saltX, saltZ) * span);
        if (roll >= span) roll = span - 1;
        return minInclusive + roll;
    }

    /**
     * Converts a height difference expressed in blocks into the model's elevation units, so
     * eligibility gates read in blocks instead of raw metres.
     *
     * <p>The diffusion raster stores elevation in model metres, and one block is
     * {@code nativeResolution / worldScale} metres (30/2 = 15 by default) -- so a literal like
     * {@code elevation > 150} is "10 blocks above sea level", not "Y=150". Anything comparing
     * against {@link com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling#elevationAt}
     * or {@code slopeAt} should go through here.</p>
     */
    public static float blocksToElevation(float blocks) {
        return blocks * metresPerBlock();
    }

    /**
     * Elevation units (metres) per block at the world's current scale -- the same resolution
     * {@link HeightConverter} divides by, mirrored here so gates and block placement agree.
     */
    public static float metresPerBlock() {
        return WorldPipelineModelConfig.nativeResolution()
                / WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
    }

    /**
     * Slope threshold in blocks-of-rise per block-of-run, converted to the metres-per-block units
     * {@code TerrainSampling.slopeAt} returns.
     */
    public static float slopeFromBlocks(float blocksPerBlock) {
        return blocksToElevation(blocksPerBlock);
    }
}
