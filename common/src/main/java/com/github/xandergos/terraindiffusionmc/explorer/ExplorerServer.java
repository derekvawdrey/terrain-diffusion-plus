package com.github.xandergos.terraindiffusionmc.explorer;

import com.github.xandergos.terraindiffusionmc.biome.BiomeRuleGenerator;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRule;
import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeSettlement;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Embedded terrain explorer HTTP server. Java port of
 * terrain_diffusion/inference/explorer/server.py.
 *
 * <p>Bound to 127.0.0.1 only. All pipeline calls are routed through
 * LocalTerrainProvider's inference thread for thread safety.
 */
public final class ExplorerServer {

    private static final Logger LOG = LoggerFactory.getLogger(ExplorerServer.class);
    private static final Gson GSON = new Gson();

    private static final String[] CHANNEL_NAMES = {"Elev", "p5", "Temp", "T std", "Precip", "Precip CV"};
    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static volatile HttpServer SERVER;
    private static volatile int SERVER_PORT = -1;
    private static volatile double COMMAND_ORIGIN_X = Double.NaN;
    private static volatile double COMMAND_ORIGIN_Z = Double.NaN;

    /**
     * Every biome key ({@code namespace:path}) currently in the running instance's real
     * Minecraft biome registry (vanilla + every installed mod), as resolved by the
     * version-specific lifecycle code from a live {@code RegistryAccess}/{@code Registries.BIOME}
     * at the {@code /td-explore} command call site (this top-level {@code common} module can't
     * reference Minecraft-version-specific registry types directly -- see
     * {@link #setAvailableBiomeKeys}). Backs {@code /api/biomes/available}.
     */
    private static volatile List<String> AVAILABLE_BIOME_KEYS = List.of();

    private ExplorerServer() {}

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Start the server if not already running. Returns the port.
     */
    public static synchronized int startIfNotRunning() throws IOException {
        if (SERVER != null) return SERVER_PORT;
        int port = TerrainDiffusionConfig.explorerPort();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", port);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/", ExplorerServer::handleRoot);
        server.createContext("/api/status", ExplorerServer::handleStatus);
        server.createContext("/api/seed", ExplorerServer::handleSeed);
        server.createContext("/api/new_seed", ExplorerServer::handleNewSeed);
        server.createContext("/api/coarse.png", ExplorerServer::handleCoarsePng);
        server.createContext("/api/coarse_data.json", ExplorerServer::handleCoarseData);
        server.createContext("/api/coarse_stats", ExplorerServer::handleCoarseStats);
        server.createContext("/api/detail.png", ExplorerServer::handleDetailPng);
        server.createContext("/api/detail_raw", ExplorerServer::handleDetailRaw);
        server.createContext("/api/biomes/available", ExplorerServer::handleBiomesAvailable);
        server.createContext("/api/biomes/preview", ExplorerServer::handleBiomesPreview);
        server.createContext("/api/biomes/apply", ExplorerServer::handleBiomesApply);
        // Single-thread executor matches Python's threaded=False
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terrain-explorer-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        SERVER = server;
        SERVER_PORT = port;
        LOG.info("Terrain explorer started at http://127.0.0.1:{}", port);
        return port;
    }

    public static synchronized void stop() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
            SERVER_PORT = -1;
            LOG.info("Terrain explorer stopped.");
        }
    }

    public static boolean isRunning() {
        return SERVER != null;
    }

    public static int getPort() {
        return SERVER_PORT;
    }

    /**
     * Stores the world position from which /td-explore was last executed.
     * The browser uses this as a stable navigation landmark on the coarse map.
     */
    public static void setCommandOrigin(double x, double z) {
        COMMAND_ORIGIN_X = x;
        COMMAND_ORIGIN_Z = z;
    }

    /**
     * Called by each version-specific {@code TerrainDiffusionLifecycle.executeExplore} with the
     * full list of biome keys ({@code namespace:path}) currently in the live server's
     * {@code Registries.BIOME}, resolved there (not here) because only version-specific code can
     * reference {@code RegistryAccess}/{@code ResourceKey} types -- this module stays
     * Minecraft-version-agnostic. Safe to call every time {@code /td-explore} runs, even if the
     * server is already up, so the list stays fresh if it's ever called again in the same JVM.
     */
    public static void setAvailableBiomeKeys(List<String> biomeKeys) {
        AVAILABLE_BIOME_KEYS = biomeKeys != null ? List.copyOf(biomeKeys) : List.of();
    }

    // =========================================================================
    // Handlers — direct port of server.py routes
    // =========================================================================

    /** GET / → serve index.html */
    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try (InputStream in = ExplorerServer.class.getResourceAsStream(
                "/assets/terrain-diffusion-mc/explorer/index.html")) {
            if (in == null) {
                sendError(ex, 404, "index.html not found");
                return;
            }
            byte[] body = in.readAllBytes();
            setNoStoreHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        } finally {
            ex.close();
        }
    }

    /** GET /api/status → {seed, channels, native_resolution, scale, block_sources_below_775m} */
    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            resp.put("channels", Arrays.asList(CHANNEL_NAMES));
            resp.put("native_resolution", NATIVE_RESOLUTION);
            int scale = WorldScaleManager.getCurrentScale();
            resp.put("scale", scale);
            resp.put("block_sources_below_775m", WorldScaleManager.shouldBlockLowAltitudeSources());
            resp.put("biomes", TerrainBiomeRegistry.instance().indexToKeyMap());
            resp.put("biome_colors", TerrainBiomeRegistry.instance().indexToColorMap());
            if (!Double.isNaN(COMMAND_ORIGIN_X) && !Double.isNaN(COMMAND_ORIGIN_Z)) {
                double safeScale = Math.max(1.0, scale);
                Map<String, Object> origin = new LinkedHashMap<>();
                origin.put("x", COMMAND_ORIGIN_X);
                origin.put("z", COMMAND_ORIGIN_Z);
                origin.put("coarse_i", COMMAND_ORIGIN_Z / safeScale / 256.0);
                origin.put("coarse_j", COMMAND_ORIGIN_X / safeScale / 256.0);
                resp.put("command_origin", origin);
            }
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/seed body={seed:int} → {seed} */
    private static void handleSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            String body = readBody(ex, 1024);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = GSON.fromJson(body, Map.class);
            if (!data.containsKey("seed")) { sendError(ex, 400, "seed required"); return; }
            long newSeed = ((Number) data.get("seed")).longValue();
            LocalTerrainProvider.changeSeedFromExplorer(newSeed);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** POST /api/new_seed → {seed} */
    private static void handleNewSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            long newSeed = LocalTerrainProvider.generateRandomSeedFromExplorer();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(newSeed));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /**
     * GET /api/coarse.png — port of coarse_png() + _coarse_channel().
     * Query params: channel, ci0, ci1, cj0, cj1, ch{0,2,3,4,5}_min/max
     * Response headers: X-Vmin, X-Vmax
     */
    private static void handleCoarsePng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        boolean headersSent = false;
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int channel = getInt(q, "channel", 0);
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            float[] data = coarseChannel(ci0, ci1, cj0, cj1, channel);
            int H = ci1 - ci0, W = cj1 - cj0;

            // Precipitation: log1p(max(v,0)) before normalizing (matches Python)
            float[] display = data.clone();
            if (channel == 4) {
                for (int i = 0; i < display.length; i++)
                    display[i] = (float) Math.log1p(Math.max(0f, display[i]));
            }
            float vmin = nanMin(display), vmax = nanMax(display);
            if (vmax == vmin) vmax = vmin + 1f;

            // Viridis colormap
            float[][] rgba = new float[4][H * W];
            for (int i = 0; i < H * W; i++) {
                float t = (display[i] - vmin) / (vmax - vmin);
                float[] rgb = Colormaps.viridis(clamp01(t));
                rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
            }

            // Optional filter: dim non-matching pixels to 30% (matches Python rgba[~mask, :3] *= 0.3)
            int[] filterChs = {0, 2, 3, 4, 5};
            boolean filterActive = false;
            for (int ch : filterChs) {
                if (q.containsKey("ch" + ch + "_min") || q.containsKey("ch" + ch + "_max")) {
                    filterActive = true; break;
                }
            }
            if (filterActive) {
                boolean[] mask = new boolean[H * W];
                Arrays.fill(mask, true);
                for (int ch : filterChs) {
                    Float lo = getFloat(q, "ch" + ch + "_min");
                    Float hi = getFloat(q, "ch" + ch + "_max");
                    if (lo == null && hi == null) continue;
                    float[] chData = coarseChannel(ci0, ci1, cj0, cj1, ch);
                    for (int i = 0; i < H * W; i++) {
                        if (lo != null && chData[i] < lo) mask[i] = false;
                        if (hi != null && chData[i] > hi) mask[i] = false;
                    }
                }
                for (int i = 0; i < H * W; i++) {
                    if (!mask[i]) {
                        rgba[0][i] *= 0.3f; rgba[1][i] *= 0.3f; rgba[2][i] *= 0.3f;
                    }
                }
            }

            byte[] png = toPng(rgba, H, W);
            setNoStoreHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Vmin", String.format("%.3f", vmin));
            ex.getResponseHeaders().set("X-Vmax", String.format("%.3f", vmax));
            ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Vmin, X-Vmax");
            headersSent = true;
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            logHandlerError("coarse.png", e);
            if (!headersSent) sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/coarse_data.json — port of coarse_data().
     * Returns all 6 channel values as 2D arrays for client-side hover.
     */
    private static void handleCoarseData(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);
            int H = ci1 - ci0, W = cj1 - cj0;

            Map<String, Object> channels = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] flat = coarseChannel(ci0, ci1, cj0, cj1, ch);
                channels.put(CHANNEL_NAMES[ch], roundedGrid(flat, H, W, 2));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ci0", ci0); resp.put("ci1", ci1);
            resp.put("cj0", cj0); resp.put("cj1", cj1);
            resp.put("channels", channels);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** GET /api/coarse_stats — port of coarse_stats(). */
    private static void handleCoarseStats(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            Map<String, Object> stats = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] data = coarseChannel(ci0, ci1, cj0, cj1, ch);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", CHANNEL_NAMES[ch]);
                entry.put("min", round3(nanMin(data)));
                entry.put("max", round3(nanMax(data)));
                stats.put(String.valueOf(ch), entry);
            }
            sendJson(ex, 200, stats);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /**
     * GET /api/detail.png — port of detail_png().
     * Query params: ci, cj, detail_size, pan_i, pan_j, mode
     */
    private static void handleDetailPng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        boolean headersSent = false;
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);
            String mode    = q.getOrDefault("mode", "relief");

            int scale = WorldScaleManager.getCurrentScale();
            int centerI = ci * 256 * scale + panI;
            int centerJ = cj * 256 * scale + panJ;
            int half    = detailSize / 2;

            int H = detailSize, W = detailSize;

            LocalTerrainProvider.ExplorerDetailData detailData = LocalTerrainProvider.getExplorerDetailData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half);
            float[] elevFlat = shortsToFloats(detailData.elevation);

            float[][] rgba;
            if (mode.equals("biome") && detailData.biomeIndexes != null) {
                rgba = applyBiomeColors(detailData.biomeIndexes, H, W);
            } else if (mode.equals("elevation")) {
                float vmin = nanMin(elevFlat), vmax = nanMax(elevFlat);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(elevFlat, H, W, vmin, vmax, "terrain");
            } else if (mode.equals("temperature") && detailData.temperatureCentiC != null) {
                float[] temp = centiDegreesToFloats(detailData.temperatureCentiC);
                float vmin = nanMin(temp), vmax = nanMax(temp);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(temp, H, W, vmin, vmax, "rdbu_r");
            } else {
                // relief mode (default)
                float[][] reliefRgb = ReliefMap.getReliefMap(elevFlat, H, W, 90.0);
                rgba = new float[4][H * W];
                for (int i = 0; i < H * W; i++) {
                    rgba[0][i] = reliefRgb[0][i];
                    rgba[1][i] = reliefRgb[1][i];
                    rgba[2][i] = reliefRgb[2][i];
                    rgba[3][i] = 1f;
                }
            }
            applyWaterOverlay(rgba, detailData.waterMask, detailData.biomeIndexes);
            byte[] png = toPng(rgba, H, W);
            setNoStoreHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            headersSent = true;
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            logHandlerError("detail.png", e);
            if (!headersSent) sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/detail_raw — port of detail_raw().
     * Binary: int16-LE elevation, optional temperature, biome, water surface, then uint8 water mask.
     * All data comes from the canonical hydrology tile at the active user-selected world scale.
     */
    private static void handleDetailRaw(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        boolean headersSent = false;
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);

            int scale = WorldScaleManager.getCurrentScale();
            int centerI = ci * 256 * scale + panI;
            int centerJ = cj * 256 * scale + panJ;
            int half    = detailSize / 2;
            int H = detailSize, W = detailSize;

            LocalTerrainProvider.ExplorerDetailData detailData = LocalTerrainProvider.getExplorerDetailData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half);
            short[] elevation = detailData.elevation;
            short[] temperature = detailData.temperatureCentiC;
            short[] biomeIndexes = detailData.biomeIndexes;
            short[] waterSurface = detailData.waterSurface;
            byte[] waterMask = detailData.waterMask;

            boolean hasTemp = temperature != null;
            int cells = Math.multiplyExact(H, W);
            int shortChannels = hasTemp ? 4 : 3;
            int payloadSize = Math.addExact(Math.multiplyExact(cells, shortChannels * Short.BYTES), cells);
            ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN);
            for (short value : elevation) payloadBuffer.putShort(value);
            if (hasTemp) for (short value : temperature) payloadBuffer.putShort(value);
            for (int index = 0; index < cells; index++) payloadBuffer.putShort(biomeIndexes[index]);
            for (int index = 0; index < cells; index++) payloadBuffer.putShort(waterSurface[index]);
            payloadBuffer.put(waterMask);
            byte[] payload = payloadBuffer.array();

            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            setNoStoreHeaders(ex);
            ex.getResponseHeaders().set("X-Height", String.valueOf(H));
            ex.getResponseHeaders().set("X-Width", String.valueOf(W));
            ex.getResponseHeaders().set("X-Has-Temp", hasTemp ? "1" : "0");
            ex.getResponseHeaders().set("X-Has-Biome", "1");
            ex.getResponseHeaders().set("X-Has-Water", "1");
            ex.getResponseHeaders().set("X-Temp-Encoding", "int16-centi-celsius");
            ex.getResponseHeaders().set("X-Water-Surface-Encoding", "int16-model-metres-min-value-none");
            ex.getResponseHeaders().set("Access-Control-Expose-Headers",
                    "X-Height, X-Width, X-Has-Temp, X-Has-Biome, X-Has-Water, "
                            + "X-Temp-Encoding, X-Water-Surface-Encoding");
            headersSent = true;
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
        } catch (Exception e) {
            logHandlerError("detail_raw", e);
            if (!headersSent) sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    // =========================================================================
    // Biome Config — enumerate real biomes, generate/validate/apply catalog rules
    // =========================================================================

    /**
     * GET /api/biomes/available — every biome in {@link #AVAILABLE_BIOME_KEYS} (the live
     * Minecraft biome registry, vanilla + all installed mods), each annotated with whether it
     * already has a {@code biome_catalog.json} entry and, if so, a short rule summary.
     */
    private static void handleBiomesAvailable(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
            List<Map<String, Object>> biomes = new ArrayList<>();
            for (String key : AVAILABLE_BIOME_KEYS) {
                TerrainBiomeSettlement settlement = registry.byKey(key);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", key);
                int colon = key.indexOf(':');
                entry.put("namespace", colon >= 0 ? key.substring(0, colon) : "");
                boolean configured = settlement != null;
                entry.put("configured", configured);
                if (configured) {
                    Set<String> zones = new LinkedHashSet<>();
                    for (TerrainBiomeRule rule : settlement.rules()) zones.add(rule.zone());
                    entry.put("zones", new ArrayList<>(zones));
                    entry.put("ruleCount", settlement.rules().size());
                    entry.put("index", settlement.index());
                } else {
                    entry.put("zones", List.of());
                    entry.put("ruleCount", 0);
                }
                biomes.add(entry);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("biomes", biomes);
            resp.put("count", biomes.size());
            if (AVAILABLE_BIOME_KEYS.isEmpty()) {
                resp.put("warning", "No biome registry data yet — run /td-explore in-world once "
                        + "so the server can enumerate Registries.BIOME.");
            }
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/biomes/preview — generate + validate a rule, never mutates or persists. */
    private static void handleBiomesPreview(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            Map<String, Object> data = readJsonBody(ex);
            BiomeRuleGenerator.Request req = parseBiomeRequest(data);
            BiomeRuleGenerator.Result result =
                    BiomeRuleGenerator.generate(TerrainBiomeRegistry.instance(), req);
            Map<String, Object> resp = buildGenerationResponse(result);
            resp.put("ok", true);
            resp.put("applied", false);
            sendJson(ex, 200, resp);
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        } catch (Exception e) {
            LOG.error("biomes/preview error", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /**
     * POST /api/biomes/apply — generate + validate a rule; if (and only if) validation passes,
     * add it to the in-memory {@link TerrainBiomeRegistry} and persist the whole catalog back to
     * the config-dir {@code biome_catalog.json} (backing up the previous file first). Refuses to
     * write anything on a validation failure.
     */
    private static void handleBiomesApply(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            Map<String, Object> data = readJsonBody(ex);
            BiomeRuleGenerator.Request req = parseBiomeRequest(data);
            TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
            BiomeRuleGenerator.Result result = BiomeRuleGenerator.generate(registry, req);
            Map<String, Object> resp = buildGenerationResponse(result);

            if (!result.valid()) {
                resp.put("ok", false);
                resp.put("applied", false);
                resp.put("error", "Validation failed — this rule can never match a real pixel, "
                        + "refusing to write it. See validation.findings for why.");
                sendJson(ex, 200, resp);
                return;
            }

            result.settlement().addRule(result.rule());
            registry.register(result.settlement());
            registry.rebuild();
            registry.saveToConfigDir();

            resp.put("ok", true);
            resp.put("applied", true);
            sendJson(ex, 200, resp);
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        } catch (Exception e) {
            LOG.error("biomes/apply error", e);
            sendError(ex, 500, "Failed to apply rule: " + e.getMessage());
        }
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        String body = readBody(ex, 8192);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = GSON.fromJson(body, Map.class);
        if (data == null) throw new IllegalArgumentException("Request body must be a JSON object");
        return data;
    }

    private static BiomeRuleGenerator.Request parseBiomeRequest(Map<String, Object> data) {
        String biomeKey = requireString(data, "biomeKey");
        String zone = requireString(data, "zone");
        if (!BiomeRuleGenerator.ZONES.contains(zone)) {
            throw new IllegalArgumentException("Unknown zone '" + zone + "', expected one of "
                    + BiomeRuleGenerator.ZONES);
        }
        BiomeRuleGenerator.TemperatureBand temperatureBand =
                parseEnum(BiomeRuleGenerator.TemperatureBand.class, requireString(data, "temperatureBand"));
        BiomeRuleGenerator.MoistureBand moistureBand =
                parseEnum(BiomeRuleGenerator.MoistureBand.class, requireString(data, "moistureBand"));
        BiomeRuleGenerator.TreeDensity treeDensity =
                parseEnum(BiomeRuleGenerator.TreeDensity.class, requireString(data, "treeDensity"));
        BiomeRuleGenerator.Rarity rarity =
                parseEnum(BiomeRuleGenerator.Rarity.class, requireString(data, "rarity"));
        return new BiomeRuleGenerator.Request(biomeKey, zone, temperatureBand, moistureBand, treeDensity, rarity);
    }

    private static String requireString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String str) || str.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        return str;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value '" + raw + "', expected one of "
                    + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static Map<String, Object> buildGenerationResponse(BiomeRuleGenerator.Result result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("biomeKey", result.biomeKey());
        resp.put("newSettlement", result.newSettlement());
        resp.put("assignedIndex", result.assignedIndex());
        resp.put("priority", result.priority());
        resp.put("newTier", result.newTier());
        if (result.anchor() != null) {
            Map<String, Object> anchor = new LinkedHashMap<>();
            anchor.put("biomeKey", result.anchor().biomeKey());
            anchor.put("biomeIndex", result.anchor().biomeIndex());
            anchor.put("priority", result.anchor().priority());
            anchor.put("reason", result.anchor().reason());
            resp.put("anchor", anchor);
        } else {
            resp.put("anchor", null);
        }
        resp.put("rule", GSON.toJsonTree(result.rule()));
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("passed", result.valid());
        validation.put("findings", result.validationFindings());
        resp.put("validation", validation);
        return resp;
    }

    private static void applyWaterOverlay(float[][] rgba, byte[] waterMask, short[] biomes) {
        if (rgba == null || waterMask == null) return;
        int cells = Math.min(waterMask.length, rgba[0].length);
        for (int index = 0; index < cells; index++) {
            int intensity = Byte.toUnsignedInt(waterMask[index]);
            if (intensity == 0) continue;
            float alpha = 0.28f + 0.42f * (intensity / 255.0f);
            boolean frozen = biomes != null && TerrainBiomeRegistry.instance().isFrozenRiver(biomes[index]);
            float red = frozen ? 0.72f : 0.08f;
            float green = frozen ? 0.88f : 0.36f;
            float blue = frozen ? 0.96f : 0.78f;
            rgba[0][index] = rgba[0][index] * (1.0f - alpha) + red * alpha;
            rgba[1][index] = rgba[1][index] * (1.0f - alpha) + green * alpha;
            rgba[2][index] = rgba[2][index] * (1.0f - alpha) + blue * alpha;
        }
    }

    private static float[] shortsToFloats(short[] values) {
        float[] result = new float[values.length];
        for (int index = 0; index < values.length; index++) result[index] = values[index];
        return result;
    }

    private static float[] centiDegreesToFloats(short[] values) {
        float[] result = new float[values.length];
        for (int index = 0; index < values.length; index++) result[index] = values[index] / 100.0f;
        return result;
    }

    // =========================================================================
    // Coarse channel helper — port of _coarse_channel() in server.py
    // =========================================================================

    /**
     * Return the given channel of the coarse map in real units.
     * Channels 0 and 1: undo signed-sqrt (sign(v) * v^2).
     */
    private static float[] coarseChannel(int ci0, int ci1, int cj0, int cj1, int channel) throws Exception {
        FloatTensor slice = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
        int H = ci1 - ci0, W = cj1 - cj0;
        float[] result = new float[H * W];
        for (int i = 0; i < H * W; i++) {
            float w   = slice.data[6 * H * W + i];
            float raw = (w > 1e-8f) ? slice.data[channel * H * W + i] / w : 0f;
            // Channels 0 (elev) and 1 (p5): signed-sqrt → real units via sign(v)*v^2
            result[i] = (channel <= 1) ? (float) (Math.signum(raw) * raw * raw) : raw;
        }
        return result;
    }

    // =========================================================================
    // PNG rendering
    // =========================================================================

    /** Encode RGBA channels (float[4][H*W]) to a PNG byte array. */
    private static byte[] toPng(float[][] rgba, int H, int W) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                int ri = (int) (clamp01(rgba[0][idx]) * 255f + 0.5f);
                int gi = (int) (clamp01(rgba[1][idx]) * 255f + 0.5f);
                int bi = (int) (clamp01(rgba[2][idx]) * 255f + 0.5f);
                int ai = (int) (clamp01(rgba[3][idx]) * 255f + 0.5f);
                img.setRGB(c, r, (ai << 24) | (ri << 16) | (gi << 8) | bi);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static float[][] applyColormap1D(float[] data, int H, int W, float vmin, float vmax, String cmap) {
        float[][] rgba = new float[4][H * W];
        for (int i = 0; i < H * W; i++) {
            float t = (data[i] - vmin) / (vmax - vmin);
            float[] rgb;
            switch (cmap) {
                case "terrain": rgb = Colormaps.terrain(clamp01(t)); break;
                case "rdbu_r":  rgb = Colormaps.rdBuR(clamp01(t));   break;
                default:        rgb = Colormaps.viridis(clamp01(t)); break;
            }
            rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
        }
        return rgba;
    }

    private static float[][] applyBiomeColors(short[] biomeIndexes, int H, int W) {
        float[][] rgba = new float[4][H * W];
        for (int i = 0; i < H * W; i++) {
            int color = TerrainBiomeRegistry.instance().colorForIndex(biomeIndexes[i]);
            rgba[0][i] = ((color >> 16) & 0xFF) / 255f;
            rgba[1][i] = ((color >> 8) & 0xFF) / 255f;
            rgba[2][i] = (color & 0xFF) / 255f;
            rgba[3][i] = 1f;
        }
        return rgba;
    }

    // =========================================================================
    // HTTP utilities
    // =========================================================================

    private static void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] body = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        setNoStoreHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    private static void setNoStoreHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("Expires", "0");
    }

    private static void sendError(HttpExchange ex, int status, String msg) throws IOException {
        Map<String, String> err = new HashMap<>();
        err.put("error", msg != null ? msg : "unknown error");
        sendJson(ex, status, err);
    }

    /**
     * The explorer UI cancels in-flight requests whenever the user pans away before a slow
     * detail/coarse image finishes streaming, which surfaces here as a "Broken pipe" IOException.
     * That's an expected client hangup, not a server bug, so it's logged quietly instead of at
     * ERROR with a full stack trace.
     */
    private static void logHandlerError(String endpoint, Exception e) {
        if (e instanceof IOException && e.getMessage() != null
                && (e.getMessage().contains("Broken pipe") || e.getMessage().contains("Connection reset"))) {
            LOG.debug("{}: client disconnected mid-response", endpoint);
        } else {
            LOG.error("{} error", endpoint, e);
        }
    }

    private static void send405(HttpExchange ex) throws IOException {
        sendError(ex, 405, "Method Not Allowed");
    }

    private static String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(maxBytes);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else {
                map.put(pair, "");
            }
        }
        return map;
    }

    // =========================================================================
    // Math utilities
    // =========================================================================

    private static float nanMin(float[] arr) {
        float min = Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v < min) min = v;
        return min == Float.MAX_VALUE ? 0f : min;
    }

    private static float nanMax(float[] arr) {
        float max = -Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v > max) max = v;
        return max == -Float.MAX_VALUE ? 0f : max;
    }

    private static float clamp01(float v) {
        return Math.min(1f, Math.max(0f, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Rounded 2-D list for coarse_data JSON (np.round equivalent). */
    private static List<List<Double>> roundedGrid(float[] flat, int H, int W, int decimals) {
        double factor = Math.pow(10, decimals);
        List<List<Double>> grid = new ArrayList<>(H);
        for (int r = 0; r < H; r++) {
            List<Double> row = new ArrayList<>(W);
            for (int c = 0; c < W; c++) {
                row.add(Math.round(flat[r * W + c] * factor) / factor);
            }
            grid.add(row);
        }
        return grid;
    }

    private static int getInt(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static Float getFloat(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null) return null;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return null; }
    }
}
