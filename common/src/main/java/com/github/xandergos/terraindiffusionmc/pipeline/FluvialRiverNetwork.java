package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeCatalog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Deterministic fluvial post-process driven directly by the generated pipeline DEM and climate channels.
 *
 * <p>The implementation uses a priority-flood pass to create a hydrological surface. Depressions are not
 * discarded: they become filled basins/lakes whose outflow is routed through the lowest spillway. Flow
 * accumulation is then propagated along that drainage tree, making headwater rills grow into larger rivers
 * downstream and across confluences.</p>
 */
public final class FluvialRiverNetwork {
    private static final float SEA_LEVEL_METERS = 0.0f;
    private static final float MIN_VISIBLE_FLOW = 0.18f;
    private static final float RILL_FLOW = 0.08f;
    private static final float LAKE_MIN_DEPTH_M = 0.75f;
    private static final float MAX_LAKE_CARVE_DEPTH_M = 22.0f;
    private static final float MAX_CHANNEL_RADIUS_PX = 18.0f;
    private static final float MIN_RILL_RADIUS_PX = 0.85f;
    private static final float MIN_NAVIGABLE_RADIUS_PX = 1.60f;
    private static final float MAX_CHANNEL_DROP_BLOCKS_PER_CELL = 0.25f;
    private static final int MIN_NAVIGABLE_DEPTH_BLOCKS = 4;
    private static final int MIN_RENDERED_WATER_MASK = 12;
    private static final float EPS = 1e-5f;

    private static final int[] DR = {-1,-1,-1, 0,0, 1,1,1};
    private static final int[] DC = {-1, 0, 1,-1,1,-1,0,1};
    private static final float[] DIST = {
            1.41421356f, 1.0f, 1.41421356f, 1.0f, 1.0f, 1.41421356f, 1.0f, 1.41421356f
    };

    private FluvialRiverNetwork() {}

    /**
     * Padding used by callers that want a central crop without obvious drainage cut-offs at tile borders.
     */
    public static int analysisPaddingPixels(float pixelSizeM) {
        float meters = Math.max(768.0f, pixelSizeM * 10.0f);
        int pad = (int) Math.ceil(meters / Math.max(1.0f, pixelSizeM));
        return Math.max(256, Math.min(512, pad));
    }

    public static RiverResult build(int i0, int j0, float[] elevation, float[] climate, int height, int width, float pixelSizeM) {
        return build(
                i0, j0, elevation, climate,
                null, null, null, null, 0,
                height, width, pixelSizeM);
    }

    public static RiverResult build(int i0, int j0, float[] elevation, float[] climate,
                                    float[] regionalLakeSurface, int height, int width, float pixelSizeM) {
        return build(
                i0, j0, elevation, climate,
                regionalLakeSurface, null, null, null, 0,
                height, width, pixelSizeM);
    }

    public static RiverResult build(int i0, int j0, float[] elevation, float[] climate,
                                    float[] regionalLakeSurface, byte[] regionalLakeOutflowDirection,
                                    int height, int width, float pixelSizeM) {
        return build(
                i0, j0, elevation, climate,
                regionalLakeSurface, regionalLakeOutflowDirection, null, null, 0,
                height, width, pixelSizeM);
    }

    public static RiverResult build(int i0, int j0, float[] elevation, float[] climate,
                                    float[] regionalLakeSurface, byte[] regionalLakeOutflowDirection,
                                    float[] regionalFlowAccumulation, int[] regionalCoarseCellToken,
                                    int regionalCoarseCellSpanBlocks,
                                    int height, int width, float pixelSizeM) {
        int n = height * width;
        if (elevation.length != n) {
            throw new IllegalArgumentException("elevation length does not match grid shape");
        }
        if (regionalLakeSurface != null && regionalLakeSurface.length != n) {
            throw new IllegalArgumentException("regional lake surface length does not match grid shape");
        }
        if (regionalLakeOutflowDirection != null && regionalLakeOutflowDirection.length != n) {
            throw new IllegalArgumentException("regional lake outflow length does not match grid shape");
        }
        if (regionalFlowAccumulation != null && regionalFlowAccumulation.length != n) {
            throw new IllegalArgumentException("regional flow accumulation length does not match grid shape");
        }
        if (regionalCoarseCellToken != null && regionalCoarseCellToken.length != n) {
            throw new IllegalArgumentException("regional coarse-cell token length does not match grid shape");
        }
        if (height <= 2 || width <= 2) {
            return RiverResult.empty(elevation, height, width);
        }

        PriorityFlood flood = runPriorityFlood(elevation, height, width);
        float[] stabilizedSurface = stabilizeRegionalLakeSurfaces(
                elevation, flood.filledSurface, regionalLakeSurface);
        float[] accumulation = accumulateRunoff(elevation, climate, flood.downstream, flood.order, flood.orderSize,
                height, width, pixelSizeM);

        float[] adjustedElevation = elevation.clone();
        float[] river = new float[n];
        float[] lake = new float[n];
        float[] waterSurface = new float[n];
        Arrays.fill(waterSurface, Float.NaN);

        carveLakes(elevation, stabilizedSurface, accumulation, adjustedElevation, lake, waterSurface, height, width);
        normalizeConnectedLakeSurfaces(
                elevation, regionalLakeSurface, stabilizedSurface,
                adjustedElevation, lake, waterSurface, height, width);
        float[] channelSurface = buildContinuousChannelSurface(
                stabilizedSurface, flood.downstream, flood.order, flood.orderSize, lake, width, pixelSizeM);
        float[] forcedOutflow = new float[n];
        float[] forcedOutflowSurface = new float[n];
        Arrays.fill(forcedOutflowSurface, Float.NaN);
        routeRegionalFlowTrunks(
                elevation, channelSurface, flood.downstream, accumulation, lake,
                regionalFlowAccumulation, regionalCoarseCellToken, regionalCoarseCellSpanBlocks,
                forcedOutflow, forcedOutflowSurface, height, width, pixelSizeM);
        routeLakeOutflows(
                elevation, stabilizedSurface, flood.downstream, accumulation, lake,
                regionalLakeOutflowDirection, forcedOutflow, forcedOutflowSurface,
                height, width, pixelSizeM);
        carveChannels(elevation, channelSurface, flood.downstream, accumulation, forcedOutflow,
                forcedOutflowSurface, adjustedElevation, river, lake, waterSurface, height, width, pixelSizeM);
        lowerWaterSurfacesOneBlock(
                elevation, adjustedElevation, river, lake, waterSurface, pixelSizeM);
        smoothChannelEdges(adjustedElevation, river, lake, height, width);

        return new RiverResult(adjustedElevation, river, lake, waterSurface, height, width);
    }

    public static void applyRiverBiomes(short[] biomes, float[] climate, RiverResult rivers, int height, int width) {
        int n = height * width;
        if (biomes == null || biomes.length != n || rivers == null) return;
        float[] river = rivers.riverStrength();
        float[] lake = rivers.lakeDepth();
        for (int idx = 0; idx < n; idx++) {
            if (river[idx] < 0.22f && lake[idx] < LAKE_MIN_DEPTH_M) continue;
            short current = biomes[idx];
            if (isOceanOrShore(current)) continue;
            float temp = climate != null && climate.length >= n ? climate[idx] : 8.0f;
            biomes[idx] = temp < -1.0f ? TerrainBiomeCatalog.FROZEN_RIVER : TerrainBiomeCatalog.RIVER;
        }
    }

    private static boolean isOceanOrShore(short biome) {
        return biome == TerrainBiomeCatalog.WARM_OCEAN
                || biome == TerrainBiomeCatalog.LUKEWARM_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_LUKEWARM_OCEAN
                || biome == TerrainBiomeCatalog.OCEAN
                || biome == TerrainBiomeCatalog.DEEP_OCEAN
                || biome == TerrainBiomeCatalog.COLD_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_COLD_OCEAN
                || biome == TerrainBiomeCatalog.FROZEN_OCEAN
                || biome == TerrainBiomeCatalog.DEEP_FROZEN_OCEAN
                || biome == TerrainBiomeCatalog.BEACH
                || biome == TerrainBiomeCatalog.SNOWY_BEACH
                || biome == TerrainBiomeCatalog.STONY_SHORE;
    }

    private static PriorityFlood runPriorityFlood(float[] elevation, int height, int width) {
        int n = height * width;
        boolean[] visited = new boolean[n];
        float[] filled = new float[n];
        int[] downstream = new int[n];
        int[] order = new int[n];
        Arrays.fill(downstream, -1);
        PriorityQueue<Node> queue = new PriorityQueue<>();

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int idx = r * width + c;
                boolean edge = r == 0 || c == 0 || r == height - 1 || c == width - 1;
                boolean ocean = elevation[idx] <= SEA_LEVEL_METERS;
                if (!edge && !ocean) continue;
                if (visited[idx]) continue;
                visited[idx] = true;
                filled[idx] = elevation[idx];
                queue.add(new Node(idx, filled[idx]));
            }
        }

        int orderSize = 0;
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            int idx = node.index;
            order[orderSize++] = idx;
            int r = idx / width;
            int c = idx - r * width;
            for (int k = 0; k < 8; k++) {
                int nr = r + DR[k];
                int nc = c + DC[k];
                if (nr < 0 || nr >= height || nc < 0 || nc >= width) continue;
                int ni = nr * width + nc;
                if (visited[ni]) continue;
                visited[ni] = true;
                downstream[ni] = idx;
                filled[ni] = Math.max(elevation[ni], filled[idx]);
                queue.add(new Node(ni, filled[ni]));
            }
        }

        return new PriorityFlood(filled, downstream, order, orderSize);
    }

    private static float[] accumulateRunoff(float[] elevation, float[] climate, int[] downstream, int[] order, int orderSize,
                                             int height, int width, float pixelSizeM) {
        int n = height * width;
        float[] accumulation = new float[n];
        float cellAreaKm2 = (pixelSizeM * pixelSizeM) / 1_000_000.0f;
        for (int idx = 0; idx < n; idx++) {
            if (elevation[idx] <= SEA_LEVEL_METERS) {
                accumulation[idx] = 0.0f;
                continue;
            }
            accumulation[idx] = localRunoff(idx, elevation[idx], climate, n) * cellAreaKm2;
        }

        for (int oi = orderSize - 1; oi >= 0; oi--) {
            int idx = order[oi];
            int down = downstream[idx];
            if (down >= 0) {
                accumulation[down] += accumulation[idx];
            }
        }
        return accumulation;
    }

    private static float[] stabilizeRegionalLakeSurfaces(
            float[] elevation, float[] localFilledSurface, float[] regionalLakeSurface) {
        float[] stabilized = localFilledSurface.clone();
        if (regionalLakeSurface == null) {
            return stabilized;
        }
        for (int idx = 0; idx < elevation.length; idx++) {
            float regionalSurface = regionalLakeSurface[idx];
            if (!Float.isFinite(regionalSurface)
                    || elevation[idx] <= SEA_LEVEL_METERS
                    || localFilledSurface[idx] - elevation[idx] < LAKE_MIN_DEPTH_M
                    || regionalSurface - elevation[idx] < LAKE_MIN_DEPTH_M) {
                continue;
            }
            // The regional solution is authoritative. Using max(local, regional) here would
            // reintroduce a different surface on every generation tile.
            stabilized[idx] = regionalSurface;
        }
        return stabilized;
    }

    private static float localRunoff(int idx, float elevationM, float[] climate, int n) {
        if (climate == null || climate.length < 4 * n) {
            float altitude = Math.max(0.0f, elevationM);
            return 0.35f + 0.45f * clamp01(altitude / 2500.0f);
        }
        float temp = climate[idx];
        float precipMm = Math.max(0.0f, climate[2 * n + idx]);
        float precipCv = Math.max(0.0f, climate[3 * n + idx]);
        float altitude = Math.max(0.0f, elevationM);

        float wetness = precipMm / 1000.0f;
        float runoffCoefficient = 0.18f + 0.28f * clamp01((precipMm - 350.0f) / 1650.0f);
        runoffCoefficient += 0.10f * clamp01(altitude / 3000.0f);
        runoffCoefficient += temp < 2.0f ? 0.08f : 0.0f;
        runoffCoefficient *= 1.0f + 0.18f * clamp01(precipCv / 120.0f);
        return Math.max(0.035f, wetness * runoffCoefficient);
    }

    private static void carveLakes(float[] elevation, float[] filledSurface, float[] accumulation,
                                   float[] adjusted, float[] lake, float[] waterSurface, int height, int width) {
        int n = height * width;
        for (int idx = 0; idx < n; idx++) {
            if (elevation[idx] <= SEA_LEVEL_METERS) continue;
            float lakeDepth = filledSurface[idx] - elevation[idx];
            if (lakeDepth < LAKE_MIN_DEPTH_M) continue;
            float basinWater = Math.max(lakeDepth, 0.0f);
            float flowGate = clamp01(accumulation[idx] / MIN_VISIBLE_FLOW);
            if (flowGate <= 0.05f && basinWater < 3.0f) continue;
            float visibleDepth = basinWater * (0.35f + 0.65f * flowGate);
            // Keep the physical basin depth as the lake marker. Using the visibility-
            // attenuated depth here allowed a river stamp to overwrite shallow parts of
            // the same lake with a different surface level.
            lake[idx] = Math.max(lake[idx], basinWater);
            waterSurface[idx] = filledSurface[idx];
            float bedDepth = Math.min(MAX_LAKE_CARVE_DEPTH_M, 1.5f + visibleDepth * 0.65f);
            adjusted[idx] = Math.min(adjusted[idx], filledSurface[idx] - bedDepth);
        }
    }

    /**
     * Propagates one authoritative level over every connected fine lake component.
     *
     * <p>A lake may cross several coarse cells. Some cells then receive a regional
     * level while others retain the local priority-flood level. The component pass
     * removes that mixture before channels and spillways are routed.</p>
     */
    private static void normalizeConnectedLakeSurfaces(
            float[] elevation,
            float[] regionalLakeSurface,
            float[] hydrologicalSurface,
            float[] adjusted,
            float[] lake,
            float[] waterSurface,
            int height,
            int width
    ) {
        int n = height * width;
        boolean[] visited = new boolean[n];
        int[] queue = new int[n];

        for (int start = 0; start < n; start++) {
            if (visited[start] || lake[start] < LAKE_MIN_DEPTH_M
                    || !Float.isFinite(waterSurface[start])) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            float componentSurface = waterSurface[start];

            while (head < tail) {
                int idx = queue[head++];
                componentSurface = Math.max(componentSurface, waterSurface[idx]);
                if (regionalLakeSurface != null && Float.isFinite(regionalLakeSurface[idx])) {
                    componentSurface = Math.max(componentSurface, regionalLakeSurface[idx]);
                }
                int r = idx / width;
                int c = idx - r * width;
                for (int k = 0; k < DR.length; k++) {
                    int nr = r + DR[k];
                    int nc = c + DC[k];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) {
                        continue;
                    }
                    int next = nr * width + nc;
                    if (!visited[next] && lake[next] >= LAKE_MIN_DEPTH_M
                            && Float.isFinite(waterSurface[next])) {
                        visited[next] = true;
                        queue[tail++] = next;
                    }
                }
            }

            for (int i = 0; i < tail; i++) {
                int idx = queue[i];
                float normalizedDepth = componentSurface - elevation[idx];
                if (normalizedDepth < LAKE_MIN_DEPTH_M) {
                    continue;
                }
                hydrologicalSurface[idx] = componentSurface;
                waterSurface[idx] = componentSurface;
                lake[idx] = normalizedDepth;
                float bedDepth = Math.min(
                        MAX_LAKE_CARVE_DEPTH_M, 1.5f + normalizedDepth * 0.65f);
                adjusted[idx] = Math.min(adjusted[idx], componentSurface - bedDepth);
            }
        }
    }

    /**
     * Lowers every rendered water surface by exactly one Minecraft block. Lake
     * shoreline cells that are no longer below the lowered level are restored to
     * terrain instead of being carved and filled, preventing lateral flooding.
     */
    private static void lowerWaterSurfacesOneBlock(
            float[] elevation,
            float[] adjusted,
            float[] river,
            float[] lake,
            float[] waterSurface,
            float pixelSizeM
    ) {
        float oneBlockMeters = Math.max(1.0f, pixelSizeM);
        float minimumRenderedRiver = MIN_RENDERED_WATER_MASK / 255.0f;
        for (int idx = 0; idx < waterSurface.length; idx++) {
            if (!Float.isFinite(waterSurface[idx])) {
                continue;
            }
            float loweredSurface = waterSurface[idx] - oneBlockMeters;
            if (lake[idx] >= LAKE_MIN_DEPTH_M) {
                float loweredLakeDepth = Math.max(0.0f, loweredSurface - elevation[idx]);
                boolean renderedLake = Math.round(
                        clamp01(loweredLakeDepth / 24.0f) * 255.0f)
                        >= MIN_RENDERED_WATER_MASK;
                if (!renderedLake && river[idx] < minimumRenderedRiver) {
                    lake[idx] = 0.0f;
                    waterSurface[idx] = Float.NaN;
                    adjusted[idx] = elevation[idx];
                    continue;
                }
                lake[idx] = loweredLakeDepth;
            }
            waterSurface[idx] = loweredSurface;
        }
    }

    private static float[] buildContinuousChannelSurface(
            float[] filledSurface,
            int[] downstream,
            int[] order,
            int orderSize,
            float[] lake,
            int width,
            float pixelSizeM
    ) {
        float[] channelSurface = filledSurface.clone();
        for (int oi = 0; oi < orderSize; oi++) {
            int idx = order[oi];
            int down = downstream[idx];
            if (down < 0 || lake[idx] >= LAKE_MIN_DEPTH_M) {
                continue;
            }
            int r = idx / width;
            int c = idx - r * width;
            int dr = Math.abs(down / width - r);
            int dc = Math.abs(down % width - c);
            float distance = dr + dc == 2 ? 1.41421356f : 1.0f;
            float maxDropMeters = Math.max(1.0f, pixelSizeM)
                    * MAX_CHANNEL_DROP_BLOCKS_PER_CELL * distance;
            channelSurface[idx] = Math.min(
                    channelSurface[idx], channelSurface[down] + maxDropMeters);
        }
        return channelSurface;
    }

    /**
     * Preserves a trunk channel through coarse drainage cells that are fully visible
     * in the fine analysis window. The fine maximum chooses the actual river bed, while
     * regional accumulation prevents its discharge from resetting at a tile boundary.
     */
    private static void routeRegionalFlowTrunks(
            float[] elevation,
            float[] channelSurface,
            int[] downstream,
            float[] accumulation,
            float[] lake,
            float[] regionalFlowAccumulation,
            int[] regionalCoarseCellToken,
            int regionalCoarseCellSpanBlocks,
            float[] forcedOutflow,
            float[] forcedOutflowSurface,
            int height,
            int width,
            float pixelSizeM
    ) {
        if (regionalFlowAccumulation == null || regionalCoarseCellToken == null
                || regionalCoarseCellSpanBlocks <= 0) {
            return;
        }
        long expectedCellCount =
                (long) regionalCoarseCellSpanBlocks * regionalCoarseCellSpanBlocks;
        if (expectedCellCount <= 0L || expectedCellCount > (long) height * width) {
            return;
        }

        Map<Integer, RegionalTrunkCandidate> candidates = new HashMap<>();
        for (int idx = 0; idx < elevation.length; idx++) {
            int token = regionalCoarseCellToken[idx];
            RegionalTrunkCandidate candidate =
                    candidates.computeIfAbsent(token, ignored -> new RegionalTrunkCandidate());
            candidate.cellCount++;
            candidate.coarseFlow = Math.max(candidate.coarseFlow, regionalFlowAccumulation[idx]);
            if (elevation[idx] <= SEA_LEVEL_METERS || lake[idx] >= LAKE_MIN_DEPTH_M) {
                continue;
            }
            if (candidate.bestIndex < 0 || accumulation[idx] > candidate.bestFineFlow) {
                candidate.bestIndex = idx;
                candidate.bestFineFlow = accumulation[idx];
            }
        }

        long minimumCoverage = Math.max(1L, Math.round(expectedCellCount * 0.90));
        int n = height * width;
        for (RegionalTrunkCandidate candidate : candidates.values()) {
            if (candidate.cellCount < minimumCoverage
                    || candidate.bestIndex < 0
                    || candidate.coarseFlow < 2.0f) {
                continue;
            }
            float discharge = Math.max(
                    candidate.bestFineFlow,
                    MIN_VISIBLE_FLOW * Math.min(
                            24.0f, 1.5f + (float) Math.sqrt(candidate.coarseFlow)));
            int current = candidate.bestIndex;
            int previous = -1;
            float previousSurface = channelSurface[current];
            int steps = 0;
            while (current >= 0 && steps++ < n) {
                if (elevation[current] <= SEA_LEVEL_METERS
                        || lake[current] >= LAKE_MIN_DEPTH_M) {
                    break;
                }
                if (steps > 1 && forcedOutflow[current] >= discharge * 0.99f) {
                    break;
                }

                float routedSurface;
                if (previous < 0) {
                    routedSurface = channelSurface[current];
                } else {
                    int pr = previous / width;
                    int pc = previous - pr * width;
                    int cr = current / width;
                    int cc = current - cr * width;
                    float distance = Math.abs(pr - cr) + Math.abs(pc - cc) == 2
                            ? 1.41421356f : 1.0f;
                    float maxDrop = Math.max(1.0f, pixelSizeM)
                            * MAX_CHANNEL_DROP_BLOCKS_PER_CELL * distance;
                    routedSurface = Math.min(
                            previousSurface,
                            Math.max(channelSurface[current], previousSurface - maxDrop));
                }
                forcedOutflow[current] = Math.max(forcedOutflow[current], discharge);
                if (!Float.isFinite(forcedOutflowSurface[current])) {
                    forcedOutflowSurface[current] = routedSurface;
                } else {
                    forcedOutflowSurface[current] =
                            Math.min(forcedOutflowSurface[current], routedSurface);
                }
                previousSurface = routedSurface;

                if (steps > 2 && accumulation[current] >= discharge * 1.05f) {
                    break;
                }
                int down = downstream[current];
                if (down < 0 || down == current || down == previous) {
                    break;
                }
                previous = current;
                current = down;
            }
        }
    }

    private static final class RegionalTrunkCandidate {
        int cellCount;
        int bestIndex = -1;
        float bestFineFlow;
        float coarseFlow;
    }

    /**
     * Forces one discharge path out of every resolved lake component. The priority-flood
     * tree already knows where water leaves the basin; this pass preserves enough discharge
     * on that path to reach an existing downstream network instead of fading at the shore.
     */
    private static void routeLakeOutflows(
            float[] elevation,
            float[] filledSurface,
            int[] downstream,
            float[] accumulation,
            float[] lake,
            byte[] regionalLakeOutflowDirection,
            float[] forcedOutflow,
            float[] forcedOutflowSurface,
            int height,
            int width,
            float pixelSizeM
    ) {
        int n = height * width;
        int[] component = new int[n];
        int[] queue = new int[n];
        Arrays.fill(component, -1);
        int componentId = 0;

        for (int start = 0; start < n; start++) {
            if (lake[start] < LAKE_MIN_DEPTH_M || component[start] >= 0) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            component[start] = componentId;
            int outlet = -1;
            int regionalOutlet = -1;
            int regionalExit = -1;
            float outletBarrier = Float.POSITIVE_INFINITY;
            float regionalOutletBarrier = Float.POSITIVE_INFINITY;
            float strongestOutletFlow = 0.0f;

            while (head < tail) {
                int idx = queue[head++];
                int r = idx / width;
                int c = idx - r * width;
                int down = downstream[idx];
                if (down >= 0 && lake[down] < LAKE_MIN_DEPTH_M) {
                    float barrier = Math.max(elevation[idx], elevation[down]);
                    if (barrier < outletBarrier - EPS
                            || (Math.abs(barrier - outletBarrier) <= EPS
                            && accumulation[idx] > strongestOutletFlow)) {
                        outlet = idx;
                        outletBarrier = barrier;
                        strongestOutletFlow = accumulation[idx];
                    }
                }
                int encodedDirection = regionalLakeOutflowDirection != null
                        ? regionalLakeOutflowDirection[idx] & 0xFF : 0;
                if (encodedDirection > 0 && encodedDirection <= DR.length) {
                    int direction = encodedDirection - 1;
                    int nr = r + DR[direction];
                    int nc = c + DC[direction];
                    if (nr >= 0 && nr < height && nc >= 0 && nc < width) {
                        int next = nr * width + nc;
                        if (lake[next] < LAKE_MIN_DEPTH_M) {
                            float barrier = Math.max(elevation[idx], elevation[next]);
                            if (barrier < regionalOutletBarrier) {
                                regionalOutlet = idx;
                                regionalExit = next;
                                regionalOutletBarrier = barrier;
                            }
                        }
                    }
                }

                for (int k = 0; k < 8; k++) {
                    int nr = r + DR[k];
                    int nc = c + DC[k];
                    if (nr < 0 || nr >= height || nc < 0 || nc >= width) {
                        continue;
                    }
                    int next = nr * width + nc;
                    if (lake[next] >= LAKE_MIN_DEPTH_M && component[next] < 0) {
                        component[next] = componentId;
                        queue[tail++] = next;
                    }
                }
            }

            if (regionalOutlet >= 0) {
                outlet = regionalOutlet;
            }
            if (outlet >= 0) {
                float componentAreaKm2 = tail * pixelSizeM * pixelSizeM / 1_000_000.0f;
                float discharge = Math.max(
                        accumulation[outlet],
                        Math.max(MIN_VISIBLE_FLOW * 3.0f, componentAreaKm2 * 0.20f));
                int current = regionalOutlet >= 0 ? regionalExit : downstream[outlet];
                int previous = outlet;
                float previousSurface = filledSurface[outlet];
                int steps = 0;
                while (current >= 0 && steps++ < n) {
                    if (elevation[current] <= SEA_LEVEL_METERS) {
                        break;
                    }
                    if (steps > 2 && forcedOutflow[current] >= discharge * 0.99f) {
                        break;
                    }
                    int down = nextOutflowCell(
                            current, previous, downstream, elevation, filledSurface,
                            lake, height, width);
                    int pr = previous / width;
                    int pc = previous - pr * width;
                    int cr = current / width;
                    int cc = current - cr * width;
                    float distance = Math.abs(pr - cr) + Math.abs(pc - cc) == 2
                            ? 1.41421356f : 1.0f;
                    float maxDrop = Math.max(1.0f, pixelSizeM)
                            * MAX_CHANNEL_DROP_BLOCKS_PER_CELL * distance;
                    float naturalSurface = filledSurface[current];
                    float routedSurface = Math.min(
                            previousSurface,
                            Math.max(naturalSurface, previousSurface - maxDrop));
                    forcedOutflow[current] = Math.max(forcedOutflow[current], discharge);
                    if (!Float.isFinite(forcedOutflowSurface[current])) {
                        forcedOutflowSurface[current] = routedSurface;
                    } else {
                        forcedOutflowSurface[current] =
                                Math.min(forcedOutflowSurface[current], routedSurface);
                    }
                    previousSurface = routedSurface;

                    if (steps > 2 && accumulation[current] >= discharge * 1.05f) {
                        break;
                    }
                    if (down < 0 || down == current) {
                        break;
                    }
                    previous = current;
                    current = down;
                }
            }
            componentId++;
        }
    }

    private static int nextOutflowCell(
            int current,
            int previous,
            int[] downstream,
            float[] elevation,
            float[] filledSurface,
            float[] lake,
            int height,
            int width
    ) {
        int natural = downstream[current];
        if (natural >= 0 && natural != previous && lake[natural] < LAKE_MIN_DEPTH_M) {
            return natural;
        }

        int r = current / width;
        int c = current - r * width;
        int best = -1;
        float bestSurface = Float.POSITIVE_INFINITY;
        for (int k = 0; k < DR.length; k++) {
            int nr = r + DR[k];
            int nc = c + DC[k];
            if (nr < 0 || nr >= height || nc < 0 || nc >= width) {
                continue;
            }
            int next = nr * width + nc;
            if (next == previous || lake[next] >= LAKE_MIN_DEPTH_M
                    || elevation[next] <= SEA_LEVEL_METERS) {
                continue;
            }
            float candidateSurface = Math.max(elevation[next], filledSurface[next]);
            if (candidateSurface < bestSurface) {
                best = next;
                bestSurface = candidateSurface;
            }
        }
        return best;
    }

    private static void carveChannels(float[] elevation, float[] channelSurface, int[] downstream,
                                      float[] accumulation, float[] forcedOutflow, float[] forcedOutflowSurface,
                                      float[] adjusted, float[] river, float[] lake, float[] waterSurface,
                                      int height, int width, float pixelSizeM) {
        int n = height * width;
        for (int idx = 0; idx < n; idx++) {
            if (elevation[idx] <= SEA_LEVEL_METERS) continue;
            if (lake[idx] >= LAKE_MIN_DEPTH_M) continue;
            float flow = Math.max(accumulation[idx], forcedOutflow[idx]);
            if (flow < RILL_FLOW) continue;
            int down = downstream[idx];
            float slope = slopeToDownstream(idx, down, channelSurface, width, pixelSizeM);
            float power = flow * (float) Math.sqrt(Math.max(0.0002f, slope));
            float normalized = clamp01((flow - RILL_FLOW) / (MIN_VISIBLE_FLOW * 18.0f));
            float channelWidthM = 1.2f + 10.5f * (float) Math.pow(Math.max(0.0f, flow), 0.42)
                    + 36.0f * (float) Math.pow(Math.max(0.0f, power), 0.30);
            float radiusPx = Math.max(0.42f, channelWidthM / Math.max(1.0f, pixelSizeM) * 0.5f);
            boolean navigable = flow >= MIN_VISIBLE_FLOW || forcedOutflow[idx] > 0.0f;
            radiusPx = Math.max(radiusPx, navigable ? MIN_NAVIGABLE_RADIUS_PX : MIN_RILL_RADIUS_PX);
            radiusPx = Math.min(MAX_CHANNEL_RADIUS_PX, radiusPx);
            float depthM = 0.55f + 4.6f * (float) Math.pow(Math.max(0.0f, flow), 0.34)
                    + 3.0f * clamp01(slope * 350.0f);
            if (navigable) {
                depthM = Math.max(depthM, MIN_NAVIGABLE_DEPTH_BLOCKS * pixelSizeM);
            }
            float maxDepthM = Math.max(38.0f, 8.0f * pixelSizeM);
            depthM = Math.min(maxDepthM, depthM);
            float centerStrength = navigable
                    ? Math.max(0.32f, normalized)
                    : Math.max(0.14f, normalized);
            float surface = Float.isFinite(forcedOutflowSurface[idx])
                    ? forcedOutflowSurface[idx] : channelSurface[idx];
            float downstreamSurface = down >= 0
                    ? (Float.isFinite(forcedOutflowSurface[down])
                    ? forcedOutflowSurface[down] : channelSurface[down])
                    : surface;
            stampChannelSegment(
                    idx, down, surface, downstreamSurface, depthM, radiusPx, centerStrength,
                    elevation, adjusted, river, lake, waterSurface, height, width);
        }
    }

    private static float slopeToDownstream(int idx, int down, float[] filledSurface, int width, float pixelSizeM) {
        if (down < 0) return 0.001f;
        int r = idx / width, c = idx - r * width;
        int dr = Math.abs(down / width - r);
        int dc = Math.abs(down % width - c);
        float dist = (dr + dc == 2 ? 1.41421356f : 1.0f) * Math.max(1.0f, pixelSizeM);
        return Math.max(0.00005f, (filledSurface[idx] - filledSurface[down]) / dist);
    }

    private static void stampChannelSegment(
            int start,
            int end,
            float startSurface,
            float endSurface,
            float depthM,
            float radiusPx,
            float centerStrength,
            float[] terrainElevation,
            float[] adjusted,
            float[] river,
            float[] lake,
            float[] waterSurface,
            int height,
            int width
    ) {
        if (end < 0) {
            stampChannel(start, startSurface, depthM, radiusPx, centerStrength,
                    terrainElevation, adjusted, river, lake, waterSurface, height, width);
            return;
        }
        int startR = start / width;
        int startC = start - startR * width;
        int endR = end / width;
        int endC = end - endR * width;
        int steps = Math.max(Math.abs(endR - startR), Math.abs(endC - startC));
        steps = Math.max(1, steps);
        for (int step = 0; step <= steps; step++) {
            float t = step / (float) steps;
            int r = Math.round(startR + (endR - startR) * t);
            int c = Math.round(startC + (endC - startC) * t);
            float surface = startSurface + (endSurface - startSurface) * t;
            stampChannel(r * width + c, surface, depthM, radiusPx, centerStrength,
                    terrainElevation, adjusted, river, lake, waterSurface, height, width);
        }
    }

    private static void stampChannel(int center, float surface, float depthM, float radiusPx, float centerStrength,
                                     float[] terrainElevation, float[] adjusted,
                                     float[] river, float[] lake, float[] waterSurface,
                                     int height, int width) {
        int cr = center / width;
        int cc = center - cr * width;
        int rRadius = Math.max(1, (int) Math.ceil(radiusPx));
        for (int r = Math.max(0, cr - rRadius); r <= Math.min(height - 1, cr + rRadius); r++) {
            for (int c = Math.max(0, cc - rRadius); c <= Math.min(width - 1, cc + rRadius); c++) {
                float d = (float) Math.sqrt((r - cr) * (r - cr) + (c - cc) * (c - cc));
                if (d > radiusPx + 0.5f) continue;
                int idx = r * width + c;
                // A channel is cut into the existing land surface. Keeping the water
                // at or below that local surface avoids raised one-block water walls
                // along sloping banks while the longitudinal profile stays continuous.
                float localSurface = Math.min(surface, terrainElevation[idx]);
                float targetBed = localSurface - depthM;
                float x = clamp01(1.0f - d / Math.max(0.5f, radiusPx + 0.35f));
                float crossSection = x * x * (3.0f - 2.0f * x);
                float localBed = adjusted[idx] + (targetBed - adjusted[idx]) * crossSection;
                adjusted[idx] = Math.min(adjusted[idx], localBed);
                float stampedStrength = centerStrength * crossSection;
                if (stampedStrength > river[idx]) {
                    river[idx] = stampedStrength;
                    // A lake's priority-flood spill elevation is authoritative. A river stamp crossing
                    // the lake must not raise part of its surface and create an in-game water wall.
                    if (lake[idx] < LAKE_MIN_DEPTH_M) {
                        waterSurface[idx] = localSurface;
                    }
                } else if (lake[idx] < LAKE_MIN_DEPTH_M
                        && stampedStrength > 0.0f
                        && Float.isFinite(waterSurface[idx])) {
                    // Where two channel stamps overlap, keep the lower surface. This makes
                    // confluences drain into one another instead of building one-block dams.
                    waterSurface[idx] = Math.min(waterSurface[idx], localSurface);
                }
            }
        }
    }

    private static void smoothChannelEdges(float[] adjusted, float[] river, float[] lake, int height, int width) {
        float[] src = adjusted.clone();
        for (int r = 1; r < height - 1; r++) {
            for (int c = 1; c < width - 1; c++) {
                int idx = r * width + c;
                if (river[idx] < 0.08f && lake[idx] < LAKE_MIN_DEPTH_M) continue;
                float sum = 0.0f;
                float weight = 0.0f;
                for (int k = 0; k < 8; k++) {
                    int ni = (r + DR[k]) * width + (c + DC[k]);
                    float w = 1.0f / DIST[k];
                    sum += src[ni] * w;
                    weight += w;
                }
                float avg = sum / Math.max(EPS, weight);
                float blend = lake[idx] >= LAKE_MIN_DEPTH_M ? 0.12f : 0.18f * clamp01(river[idx]);
                adjusted[idx] = adjusted[idx] * (1.0f - blend) + avg * blend;
            }
        }
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private record Node(int index, float priority) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            return Float.compare(this.priority, other.priority);
        }
    }

    private record PriorityFlood(float[] filledSurface, int[] downstream, int[] order, int orderSize) {}

    public record RiverResult(float[] adjustedElevation, float[] riverStrength, float[] lakeDepth,
                              float[] waterSurface, int height, int width) {
        static RiverResult empty(float[] elevation, int height, int width) {
            int n = height * width;
            float[] water = new float[n];
            Arrays.fill(water, Float.NaN);
            return new RiverResult(elevation.clone(), new float[n], new float[n], water, height, width);
        }

        public RiverResult crop(int row0, int col0, int cropHeight, int cropWidth) {
            return new RiverResult(
                    cropArray(adjustedElevation, width, row0, col0, cropHeight, cropWidth),
                    cropArray(riverStrength, width, row0, col0, cropHeight, cropWidth),
                    cropArray(lakeDepth, width, row0, col0, cropHeight, cropWidth),
                    cropArray(waterSurface, width, row0, col0, cropHeight, cropWidth),
                    cropHeight,
                    cropWidth
            );
        }

        public byte[] waterMaskBytes() {
            int n = height * width;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                float riverValue = clamp01(riverStrength[i]) * 255.0f;
                float lakeValue = clamp01(lakeDepth[i] / 24.0f) * 255.0f;
                out[i] = (byte) Math.max(0, Math.min(255, Math.round(Math.max(riverValue, lakeValue))));
            }
            return out;
        }

        /**
         * Minimum playable water depth for every rendered water cell.
         *
         * <p>The fluvial model works in metres, but at low world scales a realistic
         * channel can round down to one Minecraft block. These discrete depth bands
         * preserve the smooth cross-section while keeping the central channel navigable.</p>
         */
        public byte[] waterDepthBlocks() {
            int n = height * width;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                int depth;
                if (lakeDepth[i] >= LAKE_MIN_DEPTH_M) {
                    depth = lakeDepth[i] >= 18.0f ? 6 : lakeDepth[i] >= 7.0f ? 5 : 4;
                } else {
                    float strength = riverStrength[i];
                    if (strength >= 0.70f) {
                        depth = 6;
                    } else if (strength >= 0.32f) {
                        depth = 4;
                    } else if (strength >= 0.12f) {
                        depth = 3;
                    } else if (strength * 255.0f >= 12.0f) {
                        depth = 2;
                    } else {
                        depth = 0;
                    }
                }
                out[i] = (byte) depth;
            }
            return out;
        }

        private static float[] cropArray(float[] src, int srcWidth, int row0, int col0, int cropHeight, int cropWidth) {
            float[] out = new float[cropHeight * cropWidth];
            int srcHeight = src.length / srcWidth;
            for (int r = 0; r < cropHeight; r++) {
                int sr = Math.max(0, Math.min(srcHeight - 1, row0 + r));
                for (int c = 0; c < cropWidth; c++) {
                    int sc = Math.max(0, Math.min(srcWidth - 1, col0 + c));
                    out[r * cropWidth + c] = src[sr * srcWidth + sc];
                }
            }
            return out;
        }
    }
}
