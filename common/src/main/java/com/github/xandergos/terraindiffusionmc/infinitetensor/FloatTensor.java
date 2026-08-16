package com.github.xandergos.terraindiffusionmc.infinitetensor;

import com.github.xandergos.terraindiffusionmc.hydrology.HydrologyParallel;

import java.util.Arrays;

/**
 * An N-dimensional float array with row-major (C-order) layout.
 * Used as the data container for InfiniteTensor computations.
 */
public class FloatTensor {
    public final int[] shape;
    public final float[] data;
    final int[] strides;

    public FloatTensor(int[] shape) {
        this.shape = shape.clone();
        int total = 1;
        for (int d : shape) total *= d;
        this.data = new float[total];
        this.strides = computeStrides(shape);
    }

    public FloatTensor(int[] shape, float[] data) {
        this.shape = shape.clone();
        this.data = data;
        this.strides = computeStrides(shape);
    }

    static int[] computeStrides(int[] shape) {
        int n = shape.length;
        int[] s = new int[n];
        int stride = 1;
        for (int i = n - 1; i >= 0; i--) {
            s[i] = stride;
            stride *= shape[i];
        }
        return s;
    }

    public int ndim() {
        return shape.length;
    }

    public long byteSize() {
        return (long) data.length * Float.BYTES;
    }

    /**
     * Add values from src into this tensor at a sub-region.
     * dstRegion[d] = {start, stop}, srcRegion[d] = {start, stop}.
     * The region sizes must match in every dimension.
     */
    public void addFrom(FloatTensor src, int[][] dstRegion, int[][] srcRegion) {
        int n = shape.length;
        int[] count = new int[n];
        int total = 1;
        for (int d = 0; d < n; d++) {
            count[d] = dstRegion[d][1] - dstRegion[d][0];
            total *= count[d];
        }
        if (total == 0) return;

        // Walk the region one innermost line at a time. Both tensors are row-major, so a line is
        // contiguous in each and costs one add per element; only the line's start offsets need the
        // index arithmetic. (Decomposing a flat index per element instead spent several integer
        // divisions on every float, which showed up as hundreds of milliseconds per tile once the
        // blended slices grew to millions of elements.)
        int innerCount = count[n - 1];
        int lineCount = total / innerCount;
        int innerDstStride = strides[n - 1];
        int innerSrcStride = src.strides[n - 1];

        HydrologyParallel.forEachRow(0, lineCount, innerCount, line -> {
            int dstFlat = dstRegion[n - 1][0] * innerDstStride;
            int srcFlat = srcRegion[n - 1][0] * innerSrcStride;
            int remaining = line;
            for (int d = n - 2; d >= 0; d--) {
                int idx = remaining % count[d];
                remaining /= count[d];
                dstFlat += (dstRegion[d][0] + idx) * strides[d];
                srcFlat += (srcRegion[d][0] + idx) * src.strides[d];
            }
            if (innerDstStride == 1 && innerSrcStride == 1) {
                for (int i = 0; i < innerCount; i++) {
                    data[dstFlat + i] += src.data[srcFlat + i];
                }
            } else {
                for (int i = 0; i < innerCount; i++) {
                    data[dstFlat + i * innerDstStride] += src.data[srcFlat + i * innerSrcStride];
                }
            }
        });
    }

    /**
     * Extract a contiguous sub-region as a new zero-based tensor.
     * region[d] = {start, stop}.
     */
    public FloatTensor slice(int[][] region) {
        int n = shape.length;
        int[] newShape = new int[n];
        for (int d = 0; d < n; d++) {
            newShape[d] = region[d][1] - region[d][0];
        }
        FloatTensor result = new FloatTensor(newShape);
        int[][] dstRegion = new int[n][2];
        for (int d = 0; d < n; d++) {
            dstRegion[d][0] = 0;
            dstRegion[d][1] = newShape[d];
        }
        result.addFrom(this, dstRegion, region);
        return result;
    }

    @Override
    public String toString() {
        return "FloatTensor(shape=" + Arrays.toString(shape) + ")";
    }
}
