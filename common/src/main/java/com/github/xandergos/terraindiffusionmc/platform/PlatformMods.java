package com.github.xandergos.terraindiffusionmc.platform;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Loader-neutral view of which other mods are installed, so data files can declare optional
 * integrations ("this biome only exists when Biomes O' Plenty is present") without the mod
 * taking a compile-time or load-time dependency on them.
 *
 * <p>Mirrors {@link PlatformPaths}: each loader entrypoint calls {@link #configure} during
 * bootstrap, before anything touches {@link com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry}.</p>
 *
 * <h2>Unconfigured means permissive, on purpose</h2>
 * <p>Standalone harnesses -- the terrain explorer, {@code BiomeCatalogSmokeTest}, biome-lab's
 * Java probes -- run the classifier with no loader present and want to see the <i>whole</i>
 * catalog, not the vanilla subset. So an unconfigured registry treats every gate as satisfied.
 * In the game the loaders always configure it, and a mis-ordered bootstrap degrades safely
 * rather than silently: an ungated modded biome still resolves through its {@code fallbackKey}
 * to a vanilla one (see {@code TerrainDiffusionBiomeSource.resolveBiome}).</p>
 */
public final class PlatformMods {
    private static volatile Supplier<Collection<String>> source = null;
    private static volatile Set<String> resolved = null;

    private PlatformMods() {
    }

    /**
     * Registers how to obtain the loader's mod list. Called once per launch from each loader
     * entrypoint via {@code TerrainDiffusionLifecycle.bootstrap}.
     *
     * <p>The supplier is <b>not</b> invoked here. Loader mod lists are not reliably queryable
     * from inside a mod constructor (NeoForge/Forge build {@code ModList} while constructing
     * mods), so resolution is deferred to the first gate check -- which happens at world load,
     * long after loading finishes -- and memoized from then on.</p>
     */
    public static synchronized void configure(Supplier<Collection<String>> presentModIds) {
        source = Objects.requireNonNull(presentModIds, "presentModIds");
        resolved = null;
    }

    /** Whether a loader has registered a mod-list supplier yet. */
    public static boolean isConfigured() {
        return source != null;
    }

    /** The loader's mod ids, resolving and memoizing the supplier on first call. */
    public static Set<String> loadedModIds() {
        Set<String> current = resolved;
        if (current != null) return current;
        return resolve();
    }

    private static synchronized Set<String> resolve() {
        if (resolved != null) return resolved;
        Supplier<Collection<String>> supplier = source;
        if (supplier == null) return Set.of();
        Set<String> ids;
        try {
            Collection<String> supplied = supplier.get();
            ids = supplied == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(supplied));
        } catch (Exception e) {
            // A loader API that misbehaves must not take worldgen down with it; falling back to
            // the permissive empty set means gated biomes stay enabled and resolve via fallbackKey.
            System.err.println("Failed to query loader mod list, treating all mod gates as satisfied: " + e);
            ids = Set.of();
            source = null;
        }
        resolved = ids;
        return ids;
    }

    /**
     * Whether {@code modId} is installed. Always {@code true} while unconfigured -- see the
     * class comment for why that is the useful default rather than {@code false}.
     */
    public static boolean isLoaded(String modId) {
        Set<String> ids = loadedModIds();
        if (ids.isEmpty()) return true;
        return modId != null && ids.contains(modId);
    }

    /**
     * Whether every id in {@code requiredModIds} is installed. A null or empty requirement is
     * satisfied by definition, so ungated catalog entries cost nothing to check.
     */
    public static boolean allLoaded(Collection<String> requiredModIds) {
        if (requiredModIds == null || requiredModIds.isEmpty()) return true;
        Set<String> ids = loadedModIds();
        if (ids.isEmpty()) return true;
        for (String modId : requiredModIds) {
            if (modId != null && !ids.contains(modId)) return false;
        }
        return true;
    }

    /** Test hook: drops back to the unconfigured (permissive) state. */
    public static synchronized void reset() {
        source = null;
        resolved = null;
    }
}
