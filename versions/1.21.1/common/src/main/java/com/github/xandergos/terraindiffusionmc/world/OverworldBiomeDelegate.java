package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asks the pack how it fills the underground, instead of deciding for it.
 *
 * <h2>The idea</h2>
 * <p>Cave biomes in Minecraft are not a separate system: they are ordinary entries in the
 * overworld's multi-noise table, distinguished only by sitting at a {@code depth} the surface
 * never reaches. Vanilla puts lush caves at depth 0.4-0.9 and humidity 0.7-1, the deep dark at
 * depth 1.1 and erosion -1..-0.375, and every mod that adds a cave biome adds a row to that same
 * table -- Sengoku Jidai's {@code caverns} and {@code suisho_caves} are rows in the list inlined
 * in its {@code minecraft:overworld} dimension.</p>
 *
 * <p>We cannot sample that table the way vanilla does, because our noise router carries no climate
 * fields to sample -- our terrain comes from the diffusion model, not from continentalness and
 * erosion. But {@link MultiNoiseBiomeSource#getNoiseBiome(Climate.TargetPoint)} is public, so we
 * can hand the table a point we built ourselves.</p>
 *
 * <h2>What we inject</h2>
 * <p>Only depth, which is the one axis we genuinely know: our heightmap says how far below the
 * surface a block is. Every other coordinate is borrowed from the table itself. At startup we
 * sweep the climate space once and record, for each biome the table produces at the surface, a
 * representative point that produces it. So the question we ask is not "here are six numbers I
 * invented" but "you place {@code X} at the surface here, and this block is 60 below it -- what do
 * you put there?" Whatever answer the pack's own parameters give is what generates. A little
 * coherent noise perturbs erosion, continentalness and weirdness per column, so cave biomes form
 * patches rather than a uniform slab under each surface biome.</p>
 *
 * <p>An answer is taken only for a biome the table reserves for underground, identified by
 * measuring how much of the climate space each biome claims below the surface against how much it
 * claims at it -- see {@link #UNDERGROUND_VOLUME_RATIO}. Merely differing from the surface answer
 * is not enough, because nearest-match search drifts sideways as depth changes and would otherwise
 * bury a column of plains under ocean.</p>
 */
public final class OverworldBiomeDelegate {

    private static final Logger LOG = LoggerFactory.getLogger(OverworldBiomeDelegate.class);

    /**
     * Depth rises with distance below the surface, and separately with distance below sea level.
     * The first is what makes caves follow terrain; the second is what keeps the deep dark at the
     * bottom of the world instead of 140 blocks under a mountain peak. The surface-relative term
     * is capped below the deep dark's depth so only true altitude can reach it.
     *
     * <p>Vanilla's depth gradient runs 3.0 over the 384 blocks of its world. Ours is scaled to put
     * the 0.4-0.9 cave band roughly 43-96 blocks under the surface, and the deep dark's 1.1 below
     * y=-47, which is where the world floor is close enough to feel like the bottom.</p>
     */
    private static final float DEPTH_PER_BLOCK_BELOW_SURFACE = 1.0f / 106.0f;
    private static final float DEPTH_PER_BLOCK_BELOW_SEA = 1.0f / 100.0f;
    private static final float MAX_SURFACE_RELATIVE_DEPTH = 1.0f;

    /** Nothing overrides the surface biome in the first few blocks of stone under it. */
    public static final int MIN_DEPTH_BELOW_SURFACE = 24;

    /** Steps per axis in the startup sweep; weirdness gets fewer because fewer rows split on it. */
    private static final int SWEEP_STEPS = 9;
    private static final int WEIRDNESS_SWEEP_STEPS = 5;
    /** Depths sampled when measuring how much of the climate space a biome claims underground. */
    private static final float[] SWEEP_DEPTHS = {0.45f, 0.6f, 0.75f, 0.9f, 1.1f, 1.25f};

    /**
     * How much more of the climate space a biome must claim below the surface than at it before we
     * will place it underground.
     *
     * <p>The separation this keys on is structural rather than tuned. A surface biome is written
     * into the table twice, once at depth 0 and once at depth 1, so it claims essentially the same
     * volume at both -- measured against Sengoku Jidai's 13183-row table, every one of its surface
     * biomes lands within 10% of a 1.0 ratio. A cave biome has rows only in the middle of the
     * depth axis, so its ratio is unbounded: lush caves come out at 2261, {@code suisho_caves} at
     * 300, and dripstone, {@code caverns} and the deep dark at infinity, never appearing at the
     * surface at all. Anything in the wide gap between those two populations does not occur, so
     * the exact value here does not much matter.</p>
     */
    private static final int UNDERGROUND_VOLUME_RATIO = 8;

    private static volatile MultiNoiseBiomeSource captured;
    private static volatile Delegate active;

    private OverworldBiomeDelegate() {}

    /**
     * Offers the biome source of whatever the datapack defined as the overworld, before we replace
     * that dimension with ours. This is the highest-fidelity delegate available: it is the table
     * the world would have generated from, including any rows a total-conversion mod inlined into
     * its own {@code minecraft:overworld}.
     */
    public static void capture(BiomeSource datapackOverworld) {
        captured = datapackOverworld instanceof MultiNoiseBiomeSource multiNoise ? multiNoise : null;
    }

    /**
     * Resolves the delegate and sweeps it. Must run after the datapack registries are built and
     * before any chunk is generated.
     */
    public static void initialize(RegistryAccess registries) {
        active = null;
        MultiNoiseBiomeSource source = captured;
        String origin = "the datapack's own overworld";
        if (source == null) {
            source = fromOverworldPreset(registries);
            origin = "the minecraft:overworld parameter list";
        }
        if (source == null) return;

        long startedAt = System.nanoTime();
        Delegate delegate = sweep(source);
        active = delegate;
        LOG.info("Underground biomes will follow {}: {} surface biomes mapped, {} reachable below "
                        + "the surface, swept in {} ms",
                origin, delegate.representatives.size(), delegate.undergroundBiomes.size(),
                (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static MultiNoiseBiomeSource fromOverworldPreset(RegistryAccess registries) {
        try {
            return MultiNoiseBiomeSource.createFromPreset(
                    registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                            .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        } catch (RuntimeException e) {
            LOG.warn("No overworld multi-noise parameter list to take underground biomes from; "
                    + "falling back to built-in cave biome placement", e);
            return null;
        }
    }

    /**
     * A stamp that changes whenever the delegate is replaced, so a biome source can tell that the
     * set of biomes it may return has grown.
     */
    public static int stamp() {
        Delegate delegate = active;
        return delegate == null ? 0 : delegate.stamp;
    }

    /** Biomes this delegate places below the surface and never at it. Empty when unavailable. */
    public static List<Holder<Biome>> undergroundBiomes() {
        Delegate delegate = active;
        return delegate == null ? List.of() : delegate.undergroundBiomes;
    }

    /**
     * The biome the pack puts this far under the given surface biome, or null to leave the surface
     * biome extending downward -- which is also the answer whenever there is no delegate, or the
     * surface biome is one the delegate's table never produces.
     *
     * @param surfaceBiome            what our own model chose for this column
     * @param depthBelowSurface       blocks between the surface and the block being filled
     * @param blockY                  absolute height, anchoring the deepest rows to the world floor
     * @param jitterErosion           coherent noise in -1..1, breaking the underground into patches
     * @param jitterContinentalness   likewise for continentalness
     * @param jitterWeirdness         likewise for weirdness
     */
    public static Holder<Biome> undergroundBiome(Holder<Biome> surfaceBiome, int depthBelowSurface,
                                                 int blockY, float jitterErosion,
                                                 float jitterContinentalness, float jitterWeirdness) {
        Delegate delegate = active;
        if (delegate == null || depthBelowSurface < MIN_DEPTH_BELOW_SURFACE) return null;

        Point representative = delegate.representatives.get(surfaceBiome);
        if (representative == null) return null;

        float depth = depthOf(depthBelowSurface, blockY);
        Holder<Biome> candidate = delegate.source.getNoiseBiome(Climate.target(
                representative.temperature,
                representative.humidity,
                clampParameter(representative.continentalness + JITTER_STRENGTH * jitterContinentalness),
                clampParameter(representative.erosion + JITTER_STRENGTH * jitterErosion),
                depth,
                clampParameter(representative.weirdness + JITTER_STRENGTH * jitterWeirdness)));

        // Take over only for a biome the table reserves for underground. Merely differing from the
        // surface answer is not enough: nearest-match search drifts sideways as depth moves, so a
        // plains column at depth 0.5 can land on ocean. That is not a cave, and our own surface
        // biome is the one that belongs there.
        return delegate.underground.contains(candidate) ? candidate : null;
    }

    /** How far the per-column noise may move a borrowed coordinate. */
    private static final float JITTER_STRENGTH = 0.18f;

    private static float depthOf(int depthBelowSurface, int blockY) {
        float belowSurface = Math.min(MAX_SURFACE_RELATIVE_DEPTH,
                depthBelowSurface * DEPTH_PER_BLOCK_BELOW_SURFACE);
        float belowSea = (ScaledAltitude.SEA_LEVEL - blockY) * DEPTH_PER_BLOCK_BELOW_SEA;
        return Math.max(belowSurface, belowSea);
    }

    private static float clampParameter(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    /**
     * Walks the climate space once at the surface and again at several depths, recording a
     * representative point for every biome the table produces at the surface and measuring how
     * much of the space each biome claims at each level.
     *
     * <p>Measuring volumes is what makes the classification robust. The obvious test -- "this
     * biome never appears at depth 0" -- fails on real tables, because they have gaps: in
     * Sengoku's, a very cold, very wet, flat column has no surface row anywhere near it, so a cave
     * row wins there even at depth 0. That artifact costs lush caves and {@code suisho_caves}
     * their classification under a strict test, while barely moving their volume ratio.</p>
     */
    @SuppressWarnings("unchecked")
    private static Delegate sweep(MultiNoiseBiomeSource source) {
        int points = SWEEP_STEPS * SWEEP_STEPS * SWEEP_STEPS * SWEEP_STEPS * WEIRDNESS_SWEEP_STEPS;
        Holder<Biome>[] atSurface = new Holder[points];
        Map<Holder<Biome>, Integer> surfaceVolume = new HashMap<>();
        Map<Holder<Biome>, int[]> deepVolume = new HashMap<>();
        Map<Holder<Biome>, float[]> centreSum = new HashMap<>();

        for (int index = 0; index < points; index++) {
            float[] at = coordinatesOf(index);
            Holder<Biome> surface = source.getNoiseBiome(
                    Climate.target(at[0], at[1], at[2], at[3], 0.0f, at[4]));
            atSurface[index] = surface;
            surfaceVolume.merge(surface, 1, Integer::sum);

            float[] centre = centreSum.computeIfAbsent(surface, key -> new float[AXES]);
            for (int a = 0; a < AXES; a++) centre[a] += at[a];

            for (int d = 0; d < SWEEP_DEPTHS.length; d++) {
                Holder<Biome> below = source.getNoiseBiome(
                        Climate.target(at[0], at[1], at[2], at[3], SWEEP_DEPTHS[d], at[4]));
                deepVolume.computeIfAbsent(below, key -> new int[SWEEP_DEPTHS.length])[d]++;
            }
        }

        Map<Holder<Biome>, Point> representatives = representatives(atSurface, surfaceVolume, centreSum);

        // The most of the space a biome ever claims at one depth, against what it claims at the
        // surface. Taking the largest single depth rather than the total keeps the ratio
        // independent of how many depths were sampled, and of how many of them a biome spans --
        // the deep dark occupies only the bottom two, lush caves only the middle.
        List<Holder<Biome>> underground = new ArrayList<>();
        for (Map.Entry<Holder<Biome>, int[]> entry : deepVolume.entrySet()) {
            int surfaceClaim = surfaceVolume.getOrDefault(entry.getKey(), 0);
            int below = 0;
            for (int perDepth : entry.getValue()) below = Math.max(below, perDepth);
            if (below > (long) UNDERGROUND_VOLUME_RATIO * surfaceClaim) underground.add(entry.getKey());
        }
        return new Delegate(source, representatives, List.copyOf(underground));
    }

    /**
     * Picks, for each biome, the sampled point nearest the centre of all the points that produced
     * it -- the most typical climate the table associates with that biome.
     *
     * <p>The obvious alternative, taking the first point that produced it, gives badly atypical
     * answers. Nearest-match fills every gap in a table, so a biome turns up in corners that have
     * nothing to do with it: against Sengoku's table the first point producing desert sits at
     * temperature -1.0, and asking what lies under a "cold desert" then yields lush caves. The
     * centre of desert's 222 points comes out at temperature 0.0, humidity -0.25, which behaves
     * like a desert should.</p>
     */
    private static Map<Holder<Biome>, Point> representatives(Holder<Biome>[] atSurface,
                                                             Map<Holder<Biome>, Integer> volume,
                                                             Map<Holder<Biome>, float[]> centreSum) {
        Map<Holder<Biome>, Point> representatives = new HashMap<>();
        Map<Holder<Biome>, Float> nearest = new HashMap<>();
        for (int index = 0; index < atSurface.length; index++) {
            Holder<Biome> biome = atSurface[index];
            float[] at = coordinatesOf(index);
            float[] centre = centreSum.get(biome);
            int count = volume.get(biome);

            float distance = 0.0f;
            for (int a = 0; a < AXES; a++) {
                float delta = at[a] - centre[a] / count;
                distance += delta * delta;
            }

            Float best = nearest.get(biome);
            if (best == null || distance < best) {
                nearest.put(biome, distance);
                representatives.put(biome, new Point(at[0], at[1], at[2], at[3], at[4]));
            }
        }
        return Map.copyOf(representatives);
    }

    /** Temperature, humidity, continentalness, erosion and weirdness -- depth is ours to supply. */
    private static final int AXES = 5;

    /** Unpacks a sweep index into its point, so the grid need not be held in memory. */
    private static float[] coordinatesOf(int index) {
        int w = index % WEIRDNESS_SWEEP_STEPS;
        int rest = index / WEIRDNESS_SWEEP_STEPS;
        int e = rest % SWEEP_STEPS;
        rest /= SWEEP_STEPS;
        int c = rest % SWEEP_STEPS;
        rest /= SWEEP_STEPS;
        int h = rest % SWEEP_STEPS;
        int t = rest / SWEEP_STEPS;
        return new float[]{axis(t, SWEEP_STEPS), axis(h, SWEEP_STEPS), axis(c, SWEEP_STEPS),
                axis(e, SWEEP_STEPS), axis(w, WEIRDNESS_SWEEP_STEPS)};
    }

    /** Evenly spaced across the -1..1 a climate parameter is defined over, endpoints included. */
    private static float axis(int step, int steps) {
        return -1.0f + 2.0f * step / (steps - 1);
    }

    private record Point(float temperature, float humidity, float continentalness, float erosion,
                         float weirdness) {}

    private static final class Delegate {
        private static int nextStamp = 1;

        final MultiNoiseBiomeSource source;
        final Map<Holder<Biome>, Point> representatives;
        final List<Holder<Biome>> undergroundBiomes;
        final Set<Holder<Biome>> underground;
        final int stamp;

        Delegate(MultiNoiseBiomeSource source, Map<Holder<Biome>, Point> representatives,
                 List<Holder<Biome>> undergroundBiomes) {
            this.source = source;
            this.representatives = representatives;
            this.undergroundBiomes = undergroundBiomes;
            this.underground = Set.copyOf(undergroundBiomes);
            synchronized (Delegate.class) {
                this.stamp = nextStamp++;
            }
        }
    }
}
