package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Places generated river/lake columns before vanilla biome features run. */
public final class TerrainDiffusionRiverDecorator {
    /** Ignore only the sub-block antialias fringe; the centre of every selected headwater remains. */
    private static final int MIN_WATER_MASK = 8;
    private static final int MIN_FORCED_SINGLE_LAYER_MASK = 14;

    private TerrainDiffusionRiverDecorator() {}

    public static void decorate(ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        // Keep a one-block data halo so shoreline post-processing is chunk-boundary independent.
        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(startZ - 1, startX - 1, startZ + 17, startX + 17);
        if (data == null || data.riverWater == null || data.riverWaterSurface == null) return;

        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        long seed = LocalTerrainProvider.getSeed();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int dataZ = localZ + 1;
                int dataX = localX + 1;
                int strength = data.riverWater[dataZ][dataX];
                short surfaceMetres = data.riverWaterSurface[dataZ][dataX];
                if (strength < MIN_WATER_MASK || surfaceMetres == HeightmapData.NO_FLUVIAL_WATER) continue;
                short bedMetres = data.heightmap[dataZ][dataX];
                if (surfaceMetres < bedMetres) continue;

                int worldX = startX + localX;
                int worldZ = startZ + localZ;
                int bedY = HeightConverter.convertToMinecraftHeight(bedMetres) - 1;
                int surfaceY = HeightConverter.convertToMinecraftHeight(surfaceMetres) - 1;
                bedY = Math.max(minY, Math.min(maxY - 1, bedY));
                surfaceY = Math.max(minY, Math.min(maxY, surfaceY));
                if (surfaceY <= bedY) {
                    if (strength < MIN_FORCED_SINGLE_LAYER_MASK || surfaceY <= minY) continue;
                    // Match archive 29's continuous thin streams, but only when the projected
                    // surface is not below the bed and the hydraulic field is strong enough.
                    bedY = surfaceY - 1;
                }

                replaceSubmergedSurface(chunk, pos, worldX, worldZ, localX, localZ,
                        bedY, minY, strength, seed, worldSurface, oceanFloor);
                boolean shoreline = isShoreline(data, dataZ, dataX);
                for (int y = bedY + 1; y <= surfaceY; y++) {
                    pos.set(worldX, y, worldZ);
                    BlockState existing = chunk.getBlockState(pos);
                    if (canReplaceWithRiverWater(existing) || isNaturalTerrain(existing)) {
                        BlockState water = Blocks.WATER.defaultBlockState();
                        chunk.setBlockState(pos, water, false);
                        worldSurface.update(localX, y, localZ, water);
                        oceanFloor.update(localX, y, localZ, water);
                        if (y == surfaceY || shoreline) chunk.markPosForPostprocessing(pos);
                    }
                }

                boolean frozen = data.biomeIndexes != null
                        && com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry.instance().isFrozenRiver(data.biomeIndexes[dataZ][dataX]);
                if (frozen) {
                    pos.set(worldX, surfaceY, worldZ);
                    BlockState current = chunk.getBlockState(pos);
                    if (canReplaceWithRiverWater(current) || current.is(Blocks.WATER)) {
                        BlockState ice = frozenSurface(seed, worldX, worldZ);
                        chunk.setBlockState(pos, ice, false);
                        worldSurface.update(localX, surfaceY, localZ, ice);
                        oceanFloor.update(localX, surfaceY, localZ, ice);
                        chunk.markPosForPostprocessing(pos);
                    }
                    // Defensive cleanup: the block immediately below ice must never be air or vegetation.
                    if (surfaceY - 1 > bedY) {
                        pos.set(worldX, surfaceY - 1, worldZ);
                        BlockState below = chunk.getBlockState(pos);
                        if (canReplaceWithRiverWater(below)) {
                            chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                        }
                    }
                }
            }
        }
    }

    private static void replaceSubmergedSurface(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                                 int x, int z, int localX, int localZ,
                                                 int bedY, int minY, int strength, long seed,
                                                 Heightmap worldSurface, Heightmap oceanFloor) {
        BlockState material = bedMaterial(seed, x, z, strength);
        for (int depth = 0; depth < 3; depth++) {
            int y = bedY - depth;
            if (y < minY) break;
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (!isNaturalTerrain(state)) break;
            chunk.setBlockState(pos, material, false);
            worldSurface.update(localX, y, localZ, material);
            oceanFloor.update(localX, y, localZ, material);
        }
    }

    private static boolean isNaturalTerrain(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD)
                || state.is(Blocks.PACKED_MUD) || state.is(Blocks.CLAY)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND) || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.SNOW_BLOCK);
    }

    private static boolean canReplaceWithRiverWater(BlockState state) {
        return state.isAir() || state.getFluidState().is(FluidTags.WATER) || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.LILY_PAD) || state.is(Blocks.SNOW);
    }

    private static boolean isShoreline(HeightmapData data, int row, int col) {
        return !hasWater(data, row - 1, col) || !hasWater(data, row + 1, col)
                || !hasWater(data, row, col - 1) || !hasWater(data, row, col + 1);
    }

    private static boolean hasWater(HeightmapData data, int row, int col) {
        return row >= 0 && row < data.height && col >= 0 && col < data.width
                && data.riverWater[row][col] >= MIN_WATER_MASK
                && data.riverWaterSurface[row][col] != HeightmapData.NO_FLUVIAL_WATER;
    }

    private static BlockState bedMaterial(long seed, int x, int z, int strength) {
        int roll = hash(seed ^ 0x6A09E667F3BCC909L, x, z) & 255;
        if (strength < 72 && roll < 96) return Blocks.SAND.defaultBlockState();
        if (roll < 38) return Blocks.CLAY.defaultBlockState();
        return Blocks.GRAVEL.defaultBlockState();
    }

    private static BlockState frozenSurface(long seed, int x, int z) {
        // Blended value-noise contours form deterministic, connected patches without
        // exposing the square sampling grid at the surface.
        float coarse = valueNoise(seed ^ 0xA54FF53A5F1D36F1L, x, z, 19);
        float diagonal = valueNoise(seed ^ 0x510E527FADE682D1L, x + z, z - x, 11);
        float detail = valueNoise(seed ^ 0x1F83D9ABFB41BD6BL, x, z, 5);
        float patch = coarse * 0.62f + diagonal * 0.28f + detail * 0.10f;
        if (patch < 0.235f) return Blocks.BLUE_ICE.defaultBlockState();
        if (patch < 0.365f) return Blocks.PACKED_ICE.defaultBlockState();
        return Blocks.ICE.defaultBlockState();
    }

    private static float valueNoise(long seed, int x, int z, int spacing) {
        int cellX = Math.floorDiv(x, spacing);
        int cellZ = Math.floorDiv(z, spacing);
        float fx = Math.floorMod(x, spacing) / (float) spacing;
        float fz = Math.floorMod(z, spacing) / (float) spacing;
        fx = smoothstep(fx);
        fz = smoothstep(fz);

        float northWest = unitHash(seed, cellX, cellZ);
        float northEast = unitHash(seed, cellX + 1, cellZ);
        float southWest = unitHash(seed, cellX, cellZ + 1);
        float southEast = unitHash(seed, cellX + 1, cellZ + 1);
        float north = lerp(northWest, northEast, fx);
        float south = lerp(southWest, southEast, fx);
        return lerp(north, south, fz);
    }

    private static float unitHash(long seed, int x, int z) {
        return ((hash(seed, x, z) >>> 8) & 0x00FFFFFF) / 16777215.0f;
    }

    private static float smoothstep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static int hash(long seed, int x, int z) {
        long value = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (int) value;
    }
}
