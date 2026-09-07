package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 1.0 when noise caves are on, 0.0 when they are off: the switch the shipped
 * {@code cave_density} density function tests before it evaluates the cave functions, so
 * turning them off in the properties costs nothing per block. A density function rather than
 * a resource condition because the choice depends on the properties file and on whether the
 * bundled cave mod is present, neither of which a datapack can see.
 */
public final class NoiseCavesFlagDensityFunction implements DensityFunction.SimpleFunction {
    public static final MapCodec<NoiseCavesFlagDensityFunction> CODEC =
            MapCodec.unit(NoiseCavesFlagDensityFunction::new);
    public static final KeyDispatchDataCodec<NoiseCavesFlagDensityFunction> CODEC_HOLDER = KeyDispatchDataCodec.of(CODEC);

    private final double value = TerrainDiffusionConfig.noiseCavesEnabled() ? 1.0 : 0.0;

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return value;
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC_HOLDER;
    }
}
