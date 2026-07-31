package com.github.xandergos.terraindiffusionmc.world.surface;

import java.util.List;

/**
 * Active surface-feature placers, in placement order. Add a new placer here once it's ready --
 * see {@link SurfaceFeaturePlacer} for the contract and {@link BoulderFeaturePlacer}/{@link
 * HoodooClusterFeaturePlacer}/{@link ArchFeaturePlacer} for the three template shapes to copy.
 */
public final class SurfaceFeatureRegistry {
    private SurfaceFeatureRegistry() {}

    public static final List<SurfaceFeaturePlacer> PLACERS = List.of(
            new BoulderFeaturePlacer(),
            new HoodooClusterFeaturePlacer(),
            new ArchFeaturePlacer()
    );
}
