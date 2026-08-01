package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Lets {@link com.github.xandergos.terraindiffusionmc.mixin.WorldDimensionsMixin} rewrite the
 * overworld entry a datapack supplied. {@code LevelStem} is a record, so both components are
 * final and there is nothing to call; {@code LevelStemMixin} un-finals them.
 */
public interface MutableLevelStem {
    void terrainDiffusion$setType(Holder<DimensionType> type);

    void terrainDiffusion$setGenerator(ChunkGenerator generator);
}
