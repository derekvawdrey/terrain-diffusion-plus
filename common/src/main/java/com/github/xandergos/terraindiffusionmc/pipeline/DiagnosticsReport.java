package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * What the mod is doing right now, as lines for {@code /td-status} or a log. Everything a bug
 * report needs and a player can read in one screen: the inference device the models actually
 * landed on, the world scale, how much terrain is cached, whether anything is building, and
 * the settings that change generated terrain.
 */
public final class DiagnosticsReport {
    private DiagnosticsReport() {}

    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("Terrain Diffusion status");
        out.add("Inference: " + OnnxModel.getResolvedInferenceProvider()
                + " (configured " + TerrainDiffusionConfig.inferenceDevice()
                + ", offload " + TerrainDiffusionConfig.offloadModels()
                + ", workers " + TerrainDiffusionConfig.inferenceWorkerThreads()
                + ", decoder batch " + TerrainDiffusionConfig.decoderBatchSize()
                + ", overlap " + TerrainDiffusionConfig.windowOverlap().name().toLowerCase() + ")");
        out.add("World scale " + WorldScaleManager.getCurrentScale()
                + ", seed " + LocalTerrainProvider.getSeed()
                + ", hydrology tile " + TerrainDiffusionConfig.hydrologyTileSize()
                + " + halo " + TerrainDiffusionConfig.hydrologyAnalysisHalo());
        out.addAll(LocalTerrainProvider.statusLines());
        out.add("Terrain-changing settings: warm mountains "
                + (TerrainDiffusionConfig.warmMountainsEnabled()
                        ? TerrainDiffusionConfig.warmMountainLapseCPerKm() + " C/km" : "off")
                + "; caves lift " + TerrainDiffusionConfig.liftCarversToTerrain()
                + ", reach summits " + TerrainDiffusionConfig.carversReachSummits()
                + ", mountain caverns " + (TerrainDiffusionConfig.mountainCavernsEnabled()
                        ? TerrainDiffusionConfig.mountainCavernChancePercent() + "%" : "off")
                + ", bundled cave mod " + TerrainDiffusionConfig.bundledCaveModEnabled());
        String inference = InferenceStats.format().strip();
        if (!inference.isEmpty()) {
            out.add("Model time since start:");
            for (String line : inference.split("\n")) out.add("  " + line.strip());
        }
        return out;
    }
}
