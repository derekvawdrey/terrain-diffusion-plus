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
            new ArchFeaturePlacer(),
            new GiantArchFeaturePlacer(),
            new NaturalBridgeFeaturePlacer(),
            new MushroomRockFeaturePlacer(),
            new BalancedRockFeaturePlacer(),
            new SeaStackFeaturePlacer(),
            new SeaArchFeaturePlacer(),
            new MesasFeaturePlacer(),
            new PetrifiedDuneFeaturePlacer(),
            new OasisSinkholeFeaturePlacer(),
            new SandDuneCrestFeaturePlacer(),
            new NeedlePeakFeaturePlacer(),
            new FrozenWaterfallFeaturePlacer(),
            new TalusScreeFeaturePlacer(),
            new CorniceFeaturePlacer(),
            new GlacialErraticFeaturePlacer(),
            new BlowholeFeaturePlacer(),
            new TidePoolFeaturePlacer(),
            new ReefSpiresFeaturePlacer(),
            new DriftwoodFeaturePlacer(),
            new FallenLogFeaturePlacer(),
            new RootArchFeaturePlacer(),
            new GrandfatherTreeFeaturePlacer(),
            new MossBoulderFeaturePlacer(),
            new IceSpikeFeaturePlacer(),
            new PressureRidgeFeaturePlacer(),
            new BeachedIcebergFeaturePlacer(),
            new BasaltColumnFeaturePlacer(),
            new FumaroleFeaturePlacer(),
            new LavaTubeSkylightFeaturePlacer()
    );
}
