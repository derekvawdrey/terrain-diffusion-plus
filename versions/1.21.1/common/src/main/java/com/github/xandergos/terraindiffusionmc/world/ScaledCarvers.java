package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.CaveCarverConfigurationAccessor;
import com.github.xandergos.terraindiffusionmc.mixin.UniformHeightAccessor;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifts carver altitudes from model space into scaled world space, so that carvers written for a
 * vanilla-height world reach our terrain.
 *
 * <h2>Why</h2>
 * <p>Carvers are the hook every cave mod, biome mod and datapack already uses: a biome lists
 * configured carvers, and the chunk generator runs them. That works unchanged in our worlds --
 * except for altitude. A carver is authored for a world whose surface sits around y=64..140, so
 * it says things like "between the world floor and y=180". Our dimension is up to 2032 blocks
 * tall and world scale stretches terrain above sea level by that factor, so an unmodified carver
 * carves a band near the bottom and leaves everything above it solid.</p>
 *
 * <p>So rather than shipping overrides of other mods' cave configs, we take their carvers exactly
 * as authored and only move the altitudes, by the same factor the terrain itself moved
 * ({@link ScaledAltitude#worldY}). Cave shape, size, frequency and block choice are untouched.</p>
 *
 * <h2>What is left alone</h2>
 * <p>Only the configuration types the game itself defines are rewritten. A carver whose config is
 * a mod's own class -- YUNG's Better Caves, for one -- is passed through exactly as it came,
 * because there is no general way to know which of its numbers are altitudes. Such a mod is
 * responsible for its own vertical bounds, and the player's or pack's settings for it stand.</p>
 */
public final class ScaledCarvers {

    /**
     * Keyed by the {@code HolderSet} a biome hands back, which is a stable per-biome instance:
     * {@code applyCarvers} asks for one on each of the 289 chunks around the one being carved.
     */
    private static final Map<Object, List<Holder<ConfiguredWorldCarver<?>>>> LIFTED = new ConcurrentHashMap<>();
    private static volatile int liftedScale;

    private ScaledCarvers() {}

    /** The carvers of one biome, with their altitudes moved into a world of the given scale. */
    public static Iterable<Holder<ConfiguredWorldCarver<?>>> lift(
            Iterable<Holder<ConfiguredWorldCarver<?>>> carvers, int scale) {
        if (scale <= 1) return carvers;
        if (scale != liftedScale) {
            // A single server has one scale; this only fires when a world of a different scale is
            // loaded in the same process, which invalidates everything cached for the old one.
            LIFTED.clear();
            liftedScale = scale;
        }
        return LIFTED.computeIfAbsent(carvers, key -> liftAll(carvers, scale));
    }

    private static List<Holder<ConfiguredWorldCarver<?>>> liftAll(
            Iterable<Holder<ConfiguredWorldCarver<?>>> carvers, int scale) {
        List<Holder<ConfiguredWorldCarver<?>>> lifted = new ArrayList<>();
        for (Holder<ConfiguredWorldCarver<?>> holder : carvers) {
            lifted.add(lift(holder, scale));
        }
        return lifted;
    }

    private static Holder<ConfiguredWorldCarver<?>> lift(Holder<ConfiguredWorldCarver<?>> holder, int scale) {
        ConfiguredWorldCarver<?> carver = holder.value();
        CarverConfiguration lifted = liftConfig(carver.config(), scale);
        return lifted == null ? holder : Holder.direct(reconfigure(carver, lifted));
    }

    /** The configuration with lifted altitudes, or null when it should be used as it is. */
    private static CarverConfiguration liftConfig(CarverConfiguration config, int scale) {
        HeightProvider y = liftHeight(config.y, scale);
        VerticalAnchor lavaLevel = liftAnchor(config.lavaLevel, scale);
        if (y == config.y && lavaLevel == config.lavaLevel) return null;

        if (config instanceof CaveCarverConfiguration cave) {
            return new CaveCarverConfiguration(cave.probability, y, cave.yScale, lavaLevel,
                    cave.debugSettings, cave.replaceable, cave.horizontalRadiusMultiplier,
                    cave.verticalRadiusMultiplier,
                    ((CaveCarverConfigurationAccessor) cave).terrainDiffusion$floorLevel());
        }
        if (config instanceof CanyonCarverConfiguration canyon) {
            return new CanyonCarverConfiguration(canyon.probability, y, canyon.yScale, lavaLevel,
                    canyon.debugSettings, canyon.replaceable, canyon.verticalRotation, canyon.shape);
        }
        if (config.getClass() == CarverConfiguration.class) {
            return new CarverConfiguration(config.probability, y, config.yScale, lavaLevel,
                    config.debugSettings, config.replaceable);
        }
        return null;
    }

    private static HeightProvider liftHeight(HeightProvider height, int scale) {
        if (height instanceof UniformHeight uniform) {
            UniformHeightAccessor bounds = (UniformHeightAccessor) uniform;
            VerticalAnchor min = liftAnchor(bounds.terrainDiffusion$minInclusive(), scale);
            VerticalAnchor max = liftAnchor(bounds.terrainDiffusion$maxInclusive(), scale);
            if (min == bounds.terrainDiffusion$minInclusive() && max == bounds.terrainDiffusion$maxInclusive()) {
                return height;
            }
            return UniformHeight.of(min, max);
        }
        if (height instanceof ConstantHeight constant) {
            VerticalAnchor value = liftAnchor(constant.getValue(), scale);
            return value == constant.getValue() ? height : ConstantHeight.of(value);
        }
        // Trapezoid and the biased providers: unused by any carver the game ships, and there is
        // nothing to gain from guessing at one a mod wrote.
        return height;
    }

    private static VerticalAnchor liftAnchor(VerticalAnchor anchor, int scale) {
        if (!(anchor instanceof VerticalAnchor.Absolute absolute)) {
            // above_bottom is measured from the world floor, which is y=-64 at every scale, and
            // below_top from a ceiling that is above the terrain either way. Neither means
            // anything different in a stretched world.
            return anchor;
        }
        int lifted = ScaledAltitude.worldY(absolute.y(), scale);
        return lifted == absolute.y() ? anchor : VerticalAnchor.absolute(lifted);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ConfiguredWorldCarver<?> reconfigure(ConfiguredWorldCarver<?> carver, CarverConfiguration config) {
        return new ConfiguredWorldCarver((WorldCarver) carver.worldCarver(), config);
    }
}
