package com.github.xandergos.terraindiffusionmc.worldgen.surface;

/**
 * Deterministic seeded hashing/noise shared by surface-feature placers. Same splitmix64-style
 * mix that {@code TerrainDiffusionRiverDecorator} and the removed {@code ScarpCarver} each
 * carried their own copy of, pulled out here so every placer (and any new one a future agent
 * adds) samples from one implementation instead of drifting apart.
 */
public final class SurfaceNoise {
    private SurfaceNoise() {}

    /** 64-bit mix of (seed, x, z). Same output for the same inputs, always. */
    public static long hash(long seed, int x, int z) {
        long value = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    /** Uniform in [0, 1). */
    public static float unitHash(long seed, int x, int z) {
        return ((hash(seed, x, z) >>> 40) & 0xFFFFFF) / 16777216.0f;
    }

    /** Uniform in [-1, 1). */
    public static float signedHash(long seed, int x, int z) {
        return unitHash(seed, x, z) * 2.0f - 1.0f;
    }

    /**
     * Bilinear-interpolated coherent noise in [-1, 1]. {@code x}/{@code z} are pre-scaled by the
     * desired wavelength (e.g. {@code worldX / 220f}) -- this only interpolates between integer
     * lattice points. Use this (like {@code ScarpCarver} did) to gate a feature's density into
     * organic-looking patches instead of scattering it uniformly: roll it once per candidate site
     * and only place when the value clears a threshold.
     */
    public static float valueNoise(long seed, float x, float z) {
        int x0 = fastFloor(x);
        int z0 = fastFloor(z);
        float fx = smoothstep(x - x0);
        float fz = smoothstep(z - z0);
        float a = signedHash(seed, x0, z0);
        float b = signedHash(seed, x0 + 1, z0);
        float c = signedHash(seed, x0, z0 + 1);
        float d = signedHash(seed, x0 + 1, z0 + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    public static float smoothstep(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private static int fastFloor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
