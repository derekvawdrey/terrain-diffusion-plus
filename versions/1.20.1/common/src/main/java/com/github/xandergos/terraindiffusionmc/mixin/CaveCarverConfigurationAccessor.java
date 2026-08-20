package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code floorLevel} is the one component of a cave carver's configuration that is not public, and
 * copying a configuration requires reading it. See {@code ScaledCarvers}.
 */
@Mixin(CaveCarverConfiguration.class)
public interface CaveCarverConfigurationAccessor {
    @Accessor("floorLevel")
    FloatProvider terrainDiffusion$floorLevel();
}
