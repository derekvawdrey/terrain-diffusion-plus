package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleDimensionOptions;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.dedicated.ServerPropertiesHandler;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the active world scale to newly created dedicated-server worlds.
 */
@Mixin(ServerPropertiesHandler.class)
public class ServerPropertiesHandlerMixin {

    @Inject(method = "createDimensionsRegistryHolder", at = @At("RETURN"), cancellable = true)
    private void terrainDiffusionMc$applyCurrentWorldScale(
            RegistryWrapper.WrapperLookup registryLookup,
            CallbackInfoReturnable<DimensionOptionsRegistryHolder> callbackInfo
    ) {
        DimensionOptionsRegistryHolder dimensions = callbackInfo.getReturnValue();
        if (!WorldScaleDimensionOptions.usesTerrainDiffusion(dimensions)) {
            return;
        }

        callbackInfo.setReturnValue(WorldScaleDimensionOptions.withScaleDimensionType(
                registryLookup.getOrThrow(RegistryKeys.DIMENSION_TYPE),
                dimensions,
                WorldScaleManager.getCurrentScale()
        ));
    }
}
