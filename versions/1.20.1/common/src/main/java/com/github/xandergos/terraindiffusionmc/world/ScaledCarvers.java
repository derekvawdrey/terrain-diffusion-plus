package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.mixin.CaveCarverConfigurationAccessor;
import com.github.xandergos.terraindiffusionmc.mixin.UniformHeightAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * ({@link ScaledAltitude#worldY}). Cave shape, size and block choice are untouched.</p>
 *
 * <h2>What gets rewritten</h2>
 * <ul>
 *   <li><b>The configuration types the game defines.</b> Their {@code y} range and {@code
 *       lavaLevel} are lifted directly.</li>
 *   <li><b>A mod's own configuration type that declares its altitudes.</b> Better Caves keeps its
 *       cave and cavern bands in {@code bottom_y}/{@code top_y}; those keys are lifted through the
 *       carver's own codec, so the mod's config is rebuilt by the mod's own parser and every other
 *       number in it -- noise thresholds, compression, spawn weights -- survives verbatim. See
 *       {@link CarverAltitudeRules}, which is also where a pack declares a carver of its own or
 *       opts one out.</li>
 *   <li><b>Nothing else.</b> A carver whose type declares no altitudes is passed through exactly
 *       as it came, because there is no general way to know which of its numbers are altitudes.
 *       Such a mod is responsible for its own vertical bounds, and the player's or pack's settings
 *       for it stand.</li>
 * </ul>
 *
 * <h2>Density</h2>
 * <p>Stretching a range is only half of the move. A carver like vanilla's places one cave system
 * per chunk with some probability and drops it anywhere in its range, so widening the range alone
 * spreads the same number of caves over several times the height -- fewer caves per slab of world
 * and, most visibly, far fewer cave mouths on a mountainside. The spawn chance is therefore raised
 * by the factor the range grew by, which restores the authored density per slab. See
 * {@link #compensate}.</p>
 */
public final class ScaledCarvers {

    private static final Logger LOG = LoggerFactory.getLogger("terrain-diffusion-mc");

    /** Namespace of the cave mod bundled with this mod, whose carvers {@code caves.bundled_cave_mod} drops. */
    private static final String BUNDLED_CAVE_MOD = "bettercaves";

    /** The world a vanilla-authored carver was written for: {@code min_y} -64, {@code height} 384. */
    private static final int VANILLA_MIN_Y = -64;
    private static final int VANILLA_MAX_Y = 319;

    /**
     * Keyed by the {@code HolderSet} a biome hands back, which is a stable per-biome instance:
     * {@code applyCarvers} asks for one on each of the 289 chunks around the one being carved.
     */
    private static final Map<Object, List<Holder<ConfiguredWorldCarver<?>>>> LIFTED = new ConcurrentHashMap<>();
    private static volatile int liftedScale;

    /**
     * The registries and height of the world being generated, bound at world load. Rebuilding a
     * mod's own carver configuration means running it through its codec, which needs the registries
     * to resolve block and tag references; the height is what {@code above_bottom}/{@code below_top}
     * anchors resolve against when measuring how much a range grew.
     */
    private static volatile RegistryAccess registries;
    private static volatile int worldMinY = VANILLA_MIN_Y;
    private static volatile int worldMaxY = VANILLA_MAX_Y;

    /** Carver types whose configuration could not be rebuilt; complained about once each. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ScaledCarvers() {}

    /**
     * Binds the world whose carvers are about to be lifted. Called once per world load, before any
     * chunk is carved.
     */
    public static void bindWorld(ServerLevel level) {
        registries = level.registryAccess();
        worldMinY = level.getMinBuildHeight();
        worldMaxY = level.getMaxBuildHeight() - 1;
        LIFTED.clear();
        WARNED.clear();
    }

    /** The carvers of one biome, with their altitudes moved into a world of the given scale. */
    public static Iterable<Holder<ConfiguredWorldCarver<?>>> lift(
            Iterable<Holder<ConfiguredWorldCarver<?>>> carvers, int scale) {
        boolean lifting = scale > 1 && TerrainDiffusionConfig.liftCarversToTerrain();
        if (!lifting && TerrainDiffusionConfig.bundledCaveModEnabled()) return carvers;
        if (scale != liftedScale) {
            // A single server has one scale; this only fires when a world of a different scale is
            // loaded in the same process, which invalidates everything cached for the old one.
            LIFTED.clear();
            liftedScale = scale;
        }
        int liftScale = lifting ? scale : 1;
        return LIFTED.computeIfAbsent(carvers, key -> liftAll(carvers, liftScale));
    }

    private static List<Holder<ConfiguredWorldCarver<?>>> liftAll(
            Iterable<Holder<ConfiguredWorldCarver<?>>> carvers, int scale) {
        boolean dropBundled = !TerrainDiffusionConfig.bundledCaveModEnabled();
        List<Holder<ConfiguredWorldCarver<?>>> lifted = new ArrayList<>();
        boolean dropped = false;
        boolean hasCaveCarver = false;
        for (Holder<ConfiguredWorldCarver<?>> holder : carvers) {
            if (dropBundled && isBundledCaveMod(holder)) {
                dropped = true;
                continue;
            }
            lifted.add(scale > 1 ? lift(holder, scale) : holder);
            hasCaveCarver |= carvesCaves(holder);
        }
        // Better Caves takes vanilla's cave carvers out of every overworld biome and puts its own
        // in. Dropping its carvers without putting something back would leave the biome with only
        // its canyons, so vanilla's are restored -- unless some third cave mod's carver is already
        // in the list, in which case that mod is the one generating caves and vanilla's would
        // double up on it.
        if (dropped && !hasCaveCarver) {
            lifted.addAll(vanillaCaveCarvers(scale));
        }
        return lifted;
    }

    /** Whether this is one of the carvers of the cave mod this mod bundles. */
    private static boolean isBundledCaveMod(Holder<ConfiguredWorldCarver<?>> holder) {
        return holder.unwrapKey()
                .map(key -> BUNDLED_CAVE_MOD.equals(key.location().getNamespace()))
                .orElse(false);
    }

    /**
     * Whether a carver makes caves, as opposed to the canyons and ravines that are the other thing
     * an overworld biome lists. A carver this mod has never heard of counts: it was added by a mod
     * or pack that means it to generate something, and assuming it does not is how a world ends up
     * with two cave systems cut through each other.
     */
    private static boolean carvesCaves(Holder<ConfiguredWorldCarver<?>> holder) {
        WorldCarver<?> carver = holder.value().worldCarver();
        return carver != WorldCarver.CANYON;
    }

    /** Vanilla's two cave carvers, lifted, or nothing if this world's registries lack them. */
    private static List<Holder<ConfiguredWorldCarver<?>>> vanillaCaveCarvers(int scale) {
        RegistryAccess access = registries;
        if (access == null) return List.of();
        Registry<ConfiguredWorldCarver<?>> registry = access.registry(Registries.CONFIGURED_CARVER).orElse(null);
        if (registry == null) return List.of();

        List<Holder<ConfiguredWorldCarver<?>>> restored = new ArrayList<>(2);
        for (String name : List.of("cave", "cave_extra_underground")) {
            ResourceLocation id = new ResourceLocation(name);
            ConfiguredWorldCarver<?> carver = registry
                    .getOptional(ResourceKey.create(Registries.CONFIGURED_CARVER, id)).orElse(null);
            if (carver == null) continue;
            ConfiguredWorldCarver<?> lifted = scale > 1 && !CarverAltitudeRules.isExcluded(id.toString())
                    ? liftValue(carver, scale) : null;
            restored.add(Holder.direct(lifted == null ? carver : lifted));
        }
        return restored;
    }

    private static Holder<ConfiguredWorldCarver<?>> lift(Holder<ConfiguredWorldCarver<?>> holder, int scale) {
        if (isExcluded(holder)) return holder;
        ConfiguredWorldCarver<?> lifted = liftValue(holder.value(), scale);
        return lifted == null ? holder : Holder.direct(lifted);
    }

    /** The carver with its altitudes moved into world space, or null to use it as it is. */
    private static ConfiguredWorldCarver<?> liftValue(ConfiguredWorldCarver<?> carver, int scale) {
        CarverConfiguration lifted = liftConfig(carver.config(), scale);
        if (lifted != null) return reconfigure(carver, lifted);
        return liftDeclaredAltitudes(carver, scale);
    }

    /** Whether a pack asked for this configured carver to be run exactly as authored. */
    private static boolean isExcluded(Holder<ConfiguredWorldCarver<?>> holder) {
        return holder.unwrapKey()
                .map(key -> CarverAltitudeRules.isExcluded(key.location().toString()))
                .orElse(false);
    }

    /** The configuration with lifted altitudes, or null when it should be used as it is. */
    private static CarverConfiguration liftConfig(CarverConfiguration config, int scale) {
        HeightProvider y = liftHeight(config.y, scale);
        VerticalAnchor lavaLevel = liftAnchor(config.lavaLevel, scale);
        if (y == config.y && lavaLevel == config.lavaLevel) return null;

        float probability = compensate(config.probability, config.y, y);
        // Exact classes, not instanceof: a mod that subclasses one of these carries fields we
        // cannot copy, and handing its carver a plain base-class config back would be worse than
        // leaving it alone. Such a config falls through to the declared-altitude path below.
        if (config.getClass() == CaveCarverConfiguration.class) {
            CaveCarverConfiguration cave = (CaveCarverConfiguration) config;
            return new CaveCarverConfiguration(probability, y, cave.yScale, lavaLevel,
                    cave.debugSettings, cave.replaceable, cave.horizontalRadiusMultiplier,
                    cave.verticalRadiusMultiplier,
                    ((CaveCarverConfigurationAccessor) cave).terrainDiffusion$floorLevel());
        }
        if (config.getClass() == CanyonCarverConfiguration.class) {
            CanyonCarverConfiguration canyon = (CanyonCarverConfiguration) config;
            return new CanyonCarverConfiguration(probability, y, canyon.yScale, lavaLevel,
                    canyon.debugSettings, canyon.replaceable, canyon.verticalRotation, canyon.shape);
        }
        if (config.getClass() == CarverConfiguration.class) {
            return new CarverConfiguration(probability, y, config.yScale, lavaLevel,
                    config.debugSettings, config.replaceable);
        }
        return null;
    }

    /**
     * The spawn chance that keeps caves as frequent per slab of world as they were authored to be.
     *
     * <p>A vanilla-style carver rolls {@code probability} once per chunk and, when it wins, samples
     * a single altitude uniformly from its range. Widening the range by a factor therefore divides
     * the caves per unit of height by that same factor, so the chance is multiplied by it. At a
     * chance of 1 the carver is already starting a cave system in every chunk and there is nothing
     * left to give -- that never happens for the carvers the game and the cave mods ship (vanilla's
     * caves sit at 0.15, canyons at 0.01), but the clamp is what a pack authored at a high chance
     * would run into, so it is logged.</p>
     */
    private static float compensate(float probability, HeightProvider before, HeightProvider after) {
        if (before == after || !TerrainDiffusionConfig.caveDensityCompensation()) return probability;
        int authored = span(before, VANILLA_MIN_Y, VANILLA_MAX_Y);
        int scaled = span(after, worldMinY, worldMaxY);
        if (authored <= 0 || scaled <= authored) return probability;

        float compensated = probability * scaled / authored;
        if (compensated > 1.0f) {
            LOG.info("Carver spawn chance {} would need {} to keep its authored cave density over a"
                    + " {}x taller range; capped at 1.", probability, compensated,
                    (float) scaled / authored);
            return 1.0f;
        }
        return compensated;
    }

    /** Height of the band a height provider draws from, in blocks, or 0 if it is not a range. */
    private static int span(HeightProvider height, int minY, int maxY) {
        if (!(height instanceof UniformHeight uniform)) return 0;
        UniformHeightAccessor bounds = (UniformHeightAccessor) uniform;
        int min = resolve(bounds.terrainDiffusion$minInclusive(), minY, maxY);
        int max = resolve(bounds.terrainDiffusion$maxInclusive(), minY, maxY);
        return max - min + 1;
    }

    /**
     * Where an anchor lands in a world of the given bounds. {@code WorldGenerationContext} does
     * this at carve time from a live level; we need the same answer for two different worlds at
     * once -- the vanilla one the range was written for and ours -- so it is done by hand.
     */
    private static int resolve(VerticalAnchor anchor, int minY, int maxY) {
        if (anchor instanceof VerticalAnchor.Absolute absolute) return absolute.y();
        if (anchor instanceof VerticalAnchor.AboveBottom aboveBottom) return minY + aboveBottom.offset();
        if (anchor instanceof VerticalAnchor.BelowTop belowTop) return maxY - belowTop.offset();
        return minY;
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

    /**
     * A mod's own carver, rebuilt with the altitudes its type declared moved into world space.
     *
     * <p>The configuration is round-tripped through the carver's own codec rather than reflected
     * over: the mod's parser is what puts the numbers back, so every field we do not name keeps
     * whatever the mod or the pack set it to, and a configuration that fails to serialize simply
     * leaves the carver untouched instead of half-rewritten.</p>
     *
     * @return the rebuilt carver, or null to use the original
     */
    private static ConfiguredWorldCarver<?> liftDeclaredAltitudes(ConfiguredWorldCarver<?> carver, int scale) {
        ResourceLocation type = BuiltInRegistries.CARVER.getKey(carver.worldCarver());
        if (type == null) return null;
        Set<String> altitudeKeys = CarverAltitudeRules.altitudeKeys(type.toString());
        if (altitudeKeys.isEmpty()) return null;

        RegistryAccess access = registries;
        if (access == null) return null;

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        JsonElement encoded = ConfiguredWorldCarver.DIRECT_CODEC.encodeStart(ops, carver)
                .result().orElse(null);
        if (encoded == null) return warnOnce(type, "could not be serialized");
        if (!liftAltitudeKeys(encoded, altitudeKeys, scale)) return null;

        ConfiguredWorldCarver<?> rebuilt = ConfiguredWorldCarver.DIRECT_CODEC.parse(ops, encoded)
                .result().orElse(null);
        if (rebuilt == null) return warnOnce(type, "could not be read back after lifting");
        return rebuilt;
    }

    private static ConfiguredWorldCarver<?> warnOnce(ResourceLocation type, String what) {
        if (WARNED.add(type.toString())) {
            LOG.warn("Carver {} {}; running it at its authored altitudes. Its caves will stop where"
                    + " a vanilla-height world ends.", type, what);
        }
        return null;
    }

    /**
     * Multiplies every declared altitude in a serialized configuration into world space.
     *
     * @return whether anything changed
     */
    private static boolean liftAltitudeKeys(JsonElement element, Set<String> altitudeKeys, int scale) {
        boolean changed = false;
        if (element instanceof JsonObject object) {
            for (String key : List.copyOf(object.keySet())) {
                JsonElement value = object.get(key);
                if (altitudeKeys.contains(key) && value != null && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isNumber()) {
                    int authored = value.getAsInt();
                    int lifted = ScaledAltitude.worldY(authored, scale);
                    if (lifted != authored) {
                        object.addProperty(key, lifted);
                        changed = true;
                    }
                } else {
                    changed |= liftAltitudeKeys(value, altitudeKeys, scale);
                }
            }
        } else if (element instanceof JsonArray array) {
            for (JsonElement item : array) {
                changed |= liftAltitudeKeys(item, altitudeKeys, scale);
            }
        }
        return changed;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ConfiguredWorldCarver<?> reconfigure(ConfiguredWorldCarver<?> carver, CarverConfiguration config) {
        return new ConfiguredWorldCarver((WorldCarver) carver.worldCarver(), config);
    }
}
