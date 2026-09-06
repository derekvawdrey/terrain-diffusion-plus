package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Keeps YUNG's Better Caves' aquifer context present while our dimension generates.
 *
 * <p>Better Caves decides where its water and lava regions go inside {@code Aquifer.computeSubstance},
 * reading the level from a thread-local it pushes around each chunk step and around the noise
 * fill. In this dimension that thread-local is intermittently empty when the fill starts -- a
 * burst of "Failed to fetch the AquiferContext" on every worker the moment many chunks begin
 * generating at once, after which those chunks get no liquid regions. The pop that empties it
 * comes from Better Caves' own unconditional pop at the end of a fill, so the exact interleaving
 * is theirs to fix; until then, a fill that finds the context missing gets it re-established
 * from the overworld this mod bound at world load. Everything is resolved reflectively so the
 * class is inert when the mod is absent ({@code caves.bundled_cave_mod=false} leaves it loaded
 * but unused).</p>
 */
public final class BetterCavesAquiferContext {
    private static final Logger LOG = LoggerFactory.getLogger("terrain-diffusion-mc");
    private static final String CONTEXT_CLASS = "com.yungnickyoung.minecraft.bettercaves.worldgen.context.AquiferContext";

    private static final MethodHandle PEEK;
    private static final MethodHandle PUSH;
    private static volatile ServerLevel level;
    private static volatile boolean reported;

    static {
        MethodHandle peek = null, push = null;
        try {
            Class<?> context = Class.forName(CONTEXT_CLASS);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            peek = lookup.findStatic(context, "peek", MethodType.methodType(context))
                    .asType(MethodType.methodType(Object.class));
            push = lookup.findStatic(context, "push", MethodType.methodType(void.class, ServerLevel.class));
        } catch (ReflectiveOperationException | RuntimeException absent) {
            // Not installed, or a version whose context lives elsewhere: nothing to keep alive.
        }
        PEEK = peek;
        PUSH = push;
    }

    private BetterCavesAquiferContext() {}

    /** The overworld whose chunks the aquifer is about to be asked about; bound at world load. */
    public static void bind(ServerLevel overworld) {
        level = overworld;
    }

    public static void unbind() {
        level = null;
    }

    /** Re-establishes Better Caves' context on this thread if the mod is present and it is missing. */
    public static void ensure() {
        if (PEEK == null) return;
        ServerLevel bound = level;
        if (bound == null) return;
        try {
            if (PEEK.invoke() != null) return;
            PUSH.invoke(bound);
            if (!reported) {
                reported = true;
                LOG.info("Better Caves' aquifer context was missing on {}; re-established it so its liquid"
                        + " regions keep generating.", Thread.currentThread().getName());
            }
        } catch (Throwable t) {
            if (!reported) {
                reported = true;
                LOG.warn("Could not re-establish Better Caves' aquifer context: {}", t.toString());
            }
        }
    }
}
