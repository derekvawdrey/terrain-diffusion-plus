package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionRiverDecorator;
import com.github.xandergos.terraindiffusionmc.world.surface.SurfaceFeatureDecorator;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Shadow
    public abstract BiomeSource getBiomeSource();

    /**
     * A structure search -- {@code /locate}, a cartographer's explorer map, an eye of ender --
     * probes chunk after chunk over terrain nobody has generated, and every probe asks the biome
     * source and the density function for that spot. Answering each with a real tile build is
     * seconds of GPU work per tile on the server thread. Inside the search the terrain provider
     * answers from the coarse map instead; see {@code LocalTerrainProvider.beginFarLookup}.
     */
    @Inject(method = "findNearestMapStructure", at = @At("HEAD"))
    private void terrainDiffusion$beginFarLookup(ServerLevel level, HolderSet<Structure> structures,
                                                  BlockPos pos, int radius, boolean skipKnownStructures,
                                                  CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        if (getBiomeSource() instanceof TerrainDiffusionBiomeSource) LocalTerrainProvider.beginFarLookup();
    }

    @Inject(method = "findNearestMapStructure", at = @At("RETURN"))
    private void terrainDiffusion$endFarLookup(ServerLevel level, HolderSet<Structure> structures,
                                                BlockPos pos, int radius, boolean skipKnownStructures,
                                                CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        if (getBiomeSource() instanceof TerrainDiffusionBiomeSource) LocalTerrainProvider.endFarLookup();
    }

    @Inject(method = "applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
            + "Lnet/minecraft/world/level/StructureManager;)V", at = @At("HEAD"))
    private void terrainDiffusion$decorateTerrain(WorldGenLevel level, ChunkAccess chunk,
                                                   StructureManager structureManager, CallbackInfo ci) {
        if (getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            TerrainDiffusionRiverDecorator.decorate(chunk);
            SurfaceFeatureDecorator.decorate(chunk);
        }
    }
}
