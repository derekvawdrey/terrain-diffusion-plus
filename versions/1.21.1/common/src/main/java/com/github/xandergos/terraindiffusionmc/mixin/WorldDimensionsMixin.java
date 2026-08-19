package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.MutableLevelStem;
import com.github.xandergos.terraindiffusionmc.world.OverworldBiomeDelegate;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.Registry;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the overworld a player chose our world type for, against total-conversion mods that
 * redefine it.
 *
 * <h2>The problem</h2>
 * <p>We install our terrain through a world <i>preset</i>. Total-conversion mods instead ship
 * {@code data/minecraft/dimension/overworld.json}, which redefines the overworld dimension
 * outright. {@link WorldDimensions#bake} resolves every dimension as
 * {@code stemRegistry.getOptional(key).or(() -> this.dimensions.get(key))} -- the datapack
 * registry unconditionally outranks the preset -- so picking "Terrain Diffusion" silently gave
 * their generator instead, on world creation <i>and</i> on every subsequent load.</p>
 *
 * <h2>The fix, and how narrow it is</h2>
 * <p>This inverts that precedence for exactly one case: the overworld, when the value we would
 * otherwise lose is already a terrain-diffusion generator. That value comes from the selected
 * world preset when creating and from the world's own saved settings when loading, so in both
 * cases it represents a choice already made rather than a preference being imposed. Every other
 * dimension, and any world not created with our world type, resolves exactly as vanilla does --
 * installing this mod does not change how any other world generates.</p>
 *
 * <p>It rewrites the datapack's {@code LevelStem} in place rather than the registry, which is
 * frozen by this point, and copies the dimension <i>type</i> along with the generator: our
 * terrain needs the tall build height our dimension types declare, and inheriting the overriding
 * mod's 384-block vanilla overworld type would clip it.</p>
 */
@Mixin(WorldDimensions.class)
public abstract class WorldDimensionsMixin {
    @Unique
    private static final Logger terrainDiffusion$LOG = LoggerFactory.getLogger(WorldDimensionsMixin.class);

    @Inject(method = "bake", at = @At("HEAD"))
    private void terrainDiffusion$reclaimOverworld(Registry<LevelStem> stemRegistry,
                                                   CallbackInfoReturnable<WorldDimensions.Complete> cir) {
        WorldDimensions self = (WorldDimensions) (Object) this;
        LevelStem chosen = self.dimensions().get(LevelStem.OVERWORLD);
        if (chosen == null || !terrainDiffusion$isOurs(chosen.generator())) {
            return;
        }

        LevelStem fromDatapack = stemRegistry.getOptional(LevelStem.OVERWORLD).orElse(null);
        if (fromDatapack == null || terrainDiffusion$isOurs(fromDatapack.generator())) {
            return;
        }

        // The overworld we are about to displace still knows how this pack fills its underground.
        // Keep its biome source so cave biomes can come from it rather than from our own guesses.
        OverworldBiomeDelegate.capture(fromDatapack.generator().getBiomeSource());

        ((MutableLevelStem) (Object) fromDatapack).terrainDiffusion$setType(chosen.type());
        ((MutableLevelStem) (Object) fromDatapack).terrainDiffusion$setGenerator(chosen.generator());
        terrainDiffusion$LOG.info(
                "Reclaimed the overworld: another datapack redefined minecraft:overworld, but this "
                        + "world was created with the Terrain Diffusion world type");
    }

    @Unique
    private static boolean terrainDiffusion$isOurs(ChunkGenerator generator) {
        return generator != null && generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource;
    }
}
