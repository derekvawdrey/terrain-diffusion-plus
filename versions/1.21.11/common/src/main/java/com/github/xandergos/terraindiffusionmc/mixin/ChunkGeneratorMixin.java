package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionRiverDecorator;
import net.minecraft.world.level.StructureManager;
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

    @Inject(method = "applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
            + "Lnet/minecraft/world/level/StructureManager;)V", at = @At("TAIL"))
    private void terrainDiffusion$finalizeRiverColumns(WorldGenLevel level, ChunkAccess chunk,
                                                        StructureManager structureManager, CallbackInfo ci) {
        if (getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            TerrainDiffusionRiverDecorator.decorate(chunk);
        }
    }
}
