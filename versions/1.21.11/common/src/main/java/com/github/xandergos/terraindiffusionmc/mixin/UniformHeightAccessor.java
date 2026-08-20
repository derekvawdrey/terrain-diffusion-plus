package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A uniform height range keeps its bounds private and exposes only a sample. Reading them lets
 * {@code ScaledCarvers} rebuild the range at world scale instead of guessing at it.
 */
@Mixin(UniformHeight.class)
public interface UniformHeightAccessor {
    @Accessor("minInclusive")
    VerticalAnchor terrainDiffusion$minInclusive();

    @Accessor("maxInclusive")
    VerticalAnchor terrainDiffusion$maxInclusive();
}
