package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.MutableLevelStem;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/** Makes {@link LevelStem}'s record components assignable; see {@link MutableLevelStem}. */
@Mixin(LevelStem.class)
public abstract class LevelStemMixin implements MutableLevelStem {

    @Mutable
    @Shadow
    @Final
    private Holder<DimensionType> type;

    @Mutable
    @Shadow
    @Final
    private ChunkGenerator generator;

    @Override
    public void terrainDiffusion$setType(Holder<DimensionType> type) {
        this.type = type;
    }

    @Override
    public void terrainDiffusion$setGenerator(ChunkGenerator generator) {
        this.generator = generator;
    }
}
