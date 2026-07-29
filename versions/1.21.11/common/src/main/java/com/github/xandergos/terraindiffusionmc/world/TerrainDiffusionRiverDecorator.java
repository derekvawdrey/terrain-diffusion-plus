package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Finalises generated river/lake columns after vanilla features have run. */
public final class TerrainDiffusionRiverDecorator {
    private static final int MIN_WATER_MASK = 1;

    private TerrainDiffusionRiverDecorator() {}

    public static void decorate(ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(startZ, startX, startZ + 16, startX + 16);
        if (data == null || data.riverWater == null || data.riverWaterSurface == null) return;

        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        long seed = LocalTerrainProvider.getSeed();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int strength = data.riverWater[localZ][localX];
                short surfaceMetres = data.riverWaterSurface[localZ][localX];
                if (strength < MIN_WATER_MASK || surfaceMetres == HeightmapData.NO_FLUVIAL_WATER) continue;

                int worldX = startX + localX;
                int worldZ = startZ + localZ;
                int bedY = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]) - 1;
                int surfaceY = HeightConverter.convertToMinecraftHeight(surfaceMetres) - 1;
                bedY = Math.max(minY, Math.min(maxY - 1, bedY));
                surfaceY = Math.max(bedY + 1, Math.min(maxY, surfaceY));

                replaceSubmergedSurface(chunk, pos, worldX, worldZ, bedY, minY, strength, seed);
                for (int y = bedY + 1; y <= surfaceY; y++) {
                    pos.set(worldX, y, worldZ);
                    BlockState existing = chunk.getBlockState(pos);
                    if (canReplaceWithRiverWater(existing)) {
                        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState());
                    }
                }

                boolean frozen = data.biomeIndexes != null
                        && data.biomeIndexes[localZ][localX]
                        == com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog.FROZEN_RIVER;
                if (frozen) {
                    pos.set(worldX, surfaceY, worldZ);
                    BlockState current = chunk.getBlockState(pos);
                    if (canReplaceWithRiverWater(current) || current.is(Blocks.WATER)) {
                        chunk.setBlockState(pos, frozenSurface(seed, worldX, worldZ));
                    }
                    // Defensive cleanup: the block immediately below ice must never be air or vegetation.
                    if (surfaceY - 1 > bedY) {
                        pos.set(worldX, surfaceY - 1, worldZ);
                        BlockState below = chunk.getBlockState(pos);
                        if (canReplaceWithRiverWater(below)) {
                            chunk.setBlockState(pos, Blocks.WATER.defaultBlockState());
                        }
                    }
                }
            }
        }
    }

    private static void replaceSubmergedSurface(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                                 int x, int z, int bedY, int minY, int strength, long seed) {
        BlockState material = bedMaterial(seed, x, z, strength);
        for (int depth = 0; depth < 2; depth++) {
            int y = bedY - depth;
            if (y < minY) break;
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (isSurfaceSoil(state)) chunk.setBlockState(pos, material);
        }
    }

    private static boolean isSurfaceSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.MUD) || state.is(Blocks.CLAY)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND);
    }

    private static boolean canReplaceWithRiverWater(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.LILY_PAD) || state.is(Blocks.SNOW);
    }

    private static BlockState bedMaterial(long seed, int x, int z, int strength) {
        int roll = hash(seed ^ 0x6A09E667F3BCC909L, x, z) & 255;
        if (strength < 72 && roll < 96) return Blocks.SAND.defaultBlockState();
        if (roll < 38) return Blocks.CLAY.defaultBlockState();
        return Blocks.GRAVEL.defaultBlockState();
    }

    private static BlockState frozenSurface(long seed, int x, int z) {
        // Coarse-cell hashing creates connected patches rather than single-block confetti.
        int roll = hash(seed ^ 0xBB67AE8584CAA73BL, Math.floorDiv(x, 4), Math.floorDiv(z, 4)) & 255;
        if (roll < 8) return Blocks.BLUE_ICE.defaultBlockState();
        if (roll < 48) return Blocks.PACKED_ICE.defaultBlockState();
        return Blocks.ICE.defaultBlockState();
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
