package com.github.xandergos.terraindiffusionmc.world;

/** Maps scaled world height back to the equivalent vanilla/model altitude. */
public final class ScaledAltitude {
    public static final int SEA_LEVEL = 63;

    private ScaledAltitude() {}

    public static int equivalentY(int worldY, int scale) {
        int clampedScale = WorldScaleManager.clampScale(scale);
        return SEA_LEVEL + Math.floorDiv(worldY - SEA_LEVEL, clampedScale);
    }

    /**
     * Maps a model altitude up into scaled world height, for content authored against a
     * vanilla-height world.
     *
     * <p>Only altitudes above sea level move. Every scale keeps the same {@code min_y} of -64, so
     * the underground is the same 127 blocks deep whatever the scale is, and stretching a
     * below-sea-level altitude would push it through the world floor. This is therefore the
     * inverse of {@link #equivalentY} above sea level and the identity at or below it.</p>
     */
    public static int worldY(int modelY, int scale) {
        if (modelY <= SEA_LEVEL) return modelY;
        return SEA_LEVEL + (modelY - SEA_LEVEL) * WorldScaleManager.clampScale(scale);
    }
}
