package com.github.xandergos.terraindiffusionmc.world;

/** Maps scaled world height back to the equivalent vanilla/model altitude. */
public final class ScaledAltitude {
    public static final int SEA_LEVEL = 63;

    private ScaledAltitude() {}

    public static int equivalentY(int worldY, int scale) {
        int clampedScale = WorldScaleManager.clampScale(scale);
        return SEA_LEVEL + Math.floorDiv(worldY - SEA_LEVEL, clampedScale);
    }
}
