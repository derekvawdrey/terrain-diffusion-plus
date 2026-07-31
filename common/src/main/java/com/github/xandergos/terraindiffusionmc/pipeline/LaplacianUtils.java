package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;

/**
 * Port of terrain_diffusion/data/laplacian_encoder.py.
 *
 * <p>Implements laplacian_decode and laplacian_denoise used by WorldPipeline._compute_elev.
 * Uses bilinear interpolation and Gaussian blur on 2D float arrays.
 */
public final class LaplacianUtils {

    /**
     * laplacian_decode: upsample lowres to residual size (bilinear) and add residual.
     *
     * @param residual 2D array (H, W)
     * @param lowres   2D array (h, w) where {@code h <= H}
     * @return decoded array (H, W) = residual + bilinear_upsample(lowres, H, W)
     */
    public static float[][] laplacianDecode(float[][] residual, float[][] lowres) {
        int H = residual.length, W = residual[0].length;
        float[][] lowresUp = bilinearResize(lowres, H, W);
        float[][] result = new float[H][W];
        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++)
                result[r][c] = residual[r][c] + lowresUp[r][c];
        });
        return result;
    }

    /**
     * laplacian_denoise(residual, lowres, sigma) with extrapolate=True:
     * 1. decoded = laplacian_decode(residual, lowres, extrapolate=True)
     * 2. _, new_lowres = laplacian_encode(decoded, lowres.shape[-1], sigma)
     * Returns (residual unchanged, new_lowres)
     */
    public static float[][] laplacianDenoise(float[][] residual, float[][] lowres, float sigma) {
        int H = residual.length, W = residual[0].length;
        int lH = lowres.length, lW = lowres[0].length;

        // Step 1: decode with extrapolation
        float[][] lowresUpEx = bilinearResizeExtrapolated(lowres, H, W);
        float[][] decoded = new float[H][W];
        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++)
                decoded[r][c] = residual[r][c] + lowresUpEx[r][c];
        });

        // Step 2: laplacian_encode(decoded, lW, sigma)
        // = downsample to (lH, lW), blur, return blurred as new_lowres
        float[][] downsampled = bilinearResize(decoded, lH, lW);
        float[][] kernelSigma = gaussianKernel1D(sigma);
        float[][] newLowres = separableGaussianBlur(downsampled, kernelSigma);
        return newLowres;
    }

    /** Bilinear resize (align_corners=False, as in PyTorch). */
    public static float[][] bilinearResize(float[][] src, int dstH, int dstW) {
        int srcH = src.length, srcW = src[0].length;
        float[][] dst = new float[dstH][dstW];
        HydrologyParallel.forEachRow(0, dstH, dstW, r -> {
            float srcR = ((r + 0.5f) * srcH / dstH) - 0.5f;
            int r0 = (int) Math.floor(srcR);
            int r1 = r0 + 1;
            float wr = srcR - r0;
            r0 = Math.max(0, Math.min(srcH - 1, r0));
            r1 = Math.max(0, Math.min(srcH - 1, r1));
            for (int c = 0; c < dstW; c++) {
                float srcC = ((c + 0.5f) * srcW / dstW) - 0.5f;
                int c0 = (int) Math.floor(srcC);
                int c1 = c0 + 1;
                float wc = srcC - c0;
                c0 = Math.max(0, Math.min(srcW - 1, c0));
                c1 = Math.max(0, Math.min(srcW - 1, c1));
                dst[r][c] = (1 - wr) * (1 - wc) * src[r0][c0]
                        + (1 - wr) * wc * src[r0][c1]
                        + wr * (1 - wc) * src[r1][c0]
                        + wr * wc * src[r1][c1];
            }
        });
        return dst;
    }

    /**
     * Bilinear resize with linear extrapolation padding (for laplacian_denoise extrapolate=True).
     * Pads by 1 pixel on each side using linear extrapolation before resizing.
     */
    static float[][] bilinearResizeExtrapolated(float[][] src, int dstH, int dstW) {
        // Pad with linear extrapolation
        int sH = src.length, sW = src[0].length;
        float[][] padded = new float[sH + 2][sW + 2];

        // Copy interior
        HydrologyParallel.forEachRow(0, sH, sW, r -> {
            for (int c = 0; c < sW; c++)
                padded[r + 1][c + 1] = src[r][c];
        });

        // Extrapolate rows
        for (int c = 1; c <= sW; c++) {
            padded[0][c] = (sH > 1) ? 2 * src[0][c - 1] - src[1][c - 1] : src[0][c - 1];
            padded[sH + 1][c] = (sH > 1) ? 2 * src[sH - 1][c - 1] - src[sH - 2][c - 1] : src[sH - 1][c - 1];
        }
        // Extrapolate cols (on already-padded rows)
        HydrologyParallel.forEachRow(0, sH + 2, sW + 2, r -> {
            padded[r][0] = (sW > 1) ? 2 * padded[r][1] - padded[r][2] : padded[r][1];
            padded[r][sW + 1] = (sW > 1) ? 2 * padded[r][sW] - padded[r][sW - 1] : padded[r][sW];
        });

        // Resize padded array: scale by (dstH/sH) in each dim, crop center
        int newH = (int) Math.round(dstH + 2.0 * dstH / sH);
        int newW = (int) Math.round(dstW + 2.0 * dstW / sW);
        float[][] resized = bilinearResize(padded, newH, newW);

        int padH = (int) Math.round((double) dstH / sH);
        int padW = (int) Math.round((double) dstW / sW);
        float[][] cropped = new float[dstH][dstW];
        HydrologyParallel.forEachRow(0, dstH, dstW, r -> {
            for (int c = 0; c < dstW; c++)
                cropped[r][c] = resized[r + padH][c + padW];
        });
        return cropped;
    }

    /** Build 1D Gaussian kernel for separable blur. */
    public static float[][] gaussianKernel1D(float sigma) {
        int ks = ((int) (sigma * 2) / 2) * 2 + 1;  // matches PyTorch gaussian_blur
        float[] k = new float[ks];
        float sum = 0;
        int half = ks / 2;
        for (int i = 0; i < ks; i++) {
            float x = i - half;
            k[i] = (float) Math.exp(-0.5 * x * x / (sigma * sigma));
            sum += k[i];
        }
        for (int i = 0; i < ks; i++) k[i] /= sum;
        return new float[][]{k};
    }

    /** Separable Gaussian blur with reflect padding. */
    public static float[][] separableGaussianBlur(float[][] src, float[][] kernel1D) {
        float[] k = kernel1D[0];
        int ks = k.length;
        int pad = ks / 2;
        int H = src.length, W = src[0].length;

        // Horizontal pass
        float[][] tmp = new float[H][W];
        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++) {
                float sum = 0;
                for (int ki = 0; ki < ks; ki++) {
                    int cc = Math.max(0, Math.min(W - 1, c + ki - pad));
                    sum += src[r][cc] * k[ki];
                }
                tmp[r][c] = sum;
            }
        });

        // Vertical pass
        float[][] result = new float[H][W];
        HydrologyParallel.forEachRow(0, H, W, r -> {
            for (int c = 0; c < W; c++) {
                float sum = 0;
                for (int ki = 0; ki < ks; ki++) {
                    int rr = Math.max(0, Math.min(H - 1, r + ki - pad));
                    sum += tmp[rr][c] * k[ki];
                }
                result[r][c] = sum;
            }
        });
        return result;
    }

    /**
     * Windowed weighted linear regression of T on elevation e to estimate lapse rate.
     * Port of postprocessing.local_baseline_temperature_torch.
     *
     * @param T   2D temperature map (H, W)
     * @param e   2D elevation map (H, W)
     * @param win window size (odd)
     * @param fallbackThreshold minimum land fraction to use regression (else fallback)
     * @return float[2][H - win + 1][W - win + 1]: [0] = T_sea, [1] = beta
     */
    public static float[][][] localBaselineTemperature(float[][] T, float[][] e, int win, float fallbackThreshold) {
        return localBaselineTemperature(T, e, win, fallbackThreshold, 0, 0, 1);
    }

    /**
     * Same as {@link #localBaselineTemperature(float[][], float[][], int, float)}, but the
     * regression's upper slope clamp ({@code betaMax}) is allowed to rise above 0 in regions
     * flagged by the large-wavelength {@link BiomeClassifier#sampleRegionNoise} field (the same
     * field used to gate rare biomes and river density). Everywhere else the clamp stays at 0,
     * preserving the normal "elevation never gets warmer" behavior.
     *
     * @param originCoarseRow world-space coarse-grid row of {@code T[0]}/{@code e[0]}
     * @param originCoarseCol world-space coarse-grid column of {@code T[0]}/{@code e[0]}
     * @param coarseStride    native blocks per coarse grid cell
     */
    public static float[][][] localBaselineTemperature(float[][] T, float[][] e, int win, float fallbackThreshold,
                                                         int originCoarseRow, int originCoarseCol, int coarseStride) {
        int H = T.length, W = T[0].length;
        int outH = H - win + 1, outW = W - win + 1;
        float[][][] result = new float[2][outH][outW];

        float fallbackBeta = -0.0065f;
        float betaMin = -0.012f;
        float eps = 1e-6f;
        int pad = (win - 1) / 2;

        HydrologyParallel.forEachRow(0, outH, outW * win * win, r -> {
            for (int c = 0; c < outW; c++) {
                // Compute windowed weighted averages (weight = land mask = e > 0)
                double muT = 0, muE = 0, muE2 = 0, muET = 0, sumW = 0;
                int n = win * win;
                for (int dr = 0; dr < win; dr++) {
                    for (int dc = 0; dc < win; dc++) {
                        float land = (e[r + dr][c + dc] > 0) ? 1.0f : 0.0f;
                        muT += T[r + dr][c + dc] * land;
                        muE += e[r + dr][c + dc] * land;
                        muE2 += e[r + dr][c + dc] * e[r + dr][c + dc] * land;
                        muET += e[r + dr][c + dc] * T[r + dr][c + dc] * land;
                        sumW += land;
                    }
                }
                double den = sumW + eps;
                muT /= den; muE /= den; muE2 /= den; muET /= den;
                double varE = muE2 - muE * muE;
                double covET = muET - muE * muT;
                double beta = (varE < 1.0 || sumW < fallbackThreshold * n) ? fallbackBeta : (covET / (varE + eps));

                float worldX = (originCoarseCol + c + pad) * (float) coarseStride;
                float worldZ = (originCoarseRow + r + pad) * (float) coarseStride;
                float betaMax = warmRegionBetaMax(worldX, worldZ);
                beta = Math.max(betaMin, Math.min(betaMax, beta));

                float Tc = T[r + pad][c + pad];
                float ec = e[r + pad][c + pad];
                result[0][r][c] = (float) (Tc - beta * ec);
                result[1][r][c] = (float) beta;
            }
        });
        return result;
    }

    /**
     * {@link BiomeClassifier#sampleRegionNoise} is a 2-octave FBm Perlin field (gain 0.5) whose
     * actual amplitude never reaches +-1.0 -- empirically measured (9M-sample scan over a 120km
     * window) to top out around +-0.73. {@link #warmRegionBetaMax} normalizes against this real
     * ceiling rather than 1.0, so its nominal cap is actually reachable at the warmest spots
     * instead of asymptotically approached.
     */
    private static final float REGION_NOISE_MAX = 0.73f;

    /** Below this |regionNoise|, elevation never warms temperature (betaMax = 0). */
    private static final float WARM_REGION_THRESHOLD = 0.05f;

    /** Lapse-rate slope cap at the warmest regions, in C per meter (+0.024 = +24C/km). */
    private static final float WARM_REGION_BETA_MAX_CAP = 0.024f;

    /**
     * Upper clamp on the lapse-rate slope for this world position. 0 everywhere except inside
     * the tropical/arid "special region" belts (|regionNoise| beyond {@link #WARM_REGION_THRESHOLD},
     * matching the thresholds already used to gate rare BoP biomes), where it ramps up to
     * {@link #WARM_REGION_BETA_MAX_CAP}, letting mountains in those regions get warmer with
     * elevation instead of always colder.
     */
    private static float warmRegionBetaMax(float worldX, float worldZ) {
        float n = Math.abs(BiomeClassifier.sampleRegionNoise(worldX, worldZ));
        float t = Math.max(0f, Math.min(1f, (n - WARM_REGION_THRESHOLD) / (REGION_NOISE_MAX - WARM_REGION_THRESHOLD)));
        float smooth = t * t * (3f - 2f * t);
        return smooth * WARM_REGION_BETA_MAX_CAP;
    }
}
