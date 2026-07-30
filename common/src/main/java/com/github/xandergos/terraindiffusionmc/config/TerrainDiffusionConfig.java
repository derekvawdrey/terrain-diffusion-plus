package com.github.xandergos.terraindiffusionmc.config;

import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class TerrainDiffusionConfig {
    private static final String FILE_NAME = "terrain-diffusion-mc.properties";
    private static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final Properties PROPERTIES = new Properties();
    private static final String BUILD_VARIANT = readBuildVariant();
    private static final boolean DEFAULT_OFFLOAD_MODELS = true;
    private static final boolean DEFAULT_VALIDATE_MODEL = true;
    private static final int DEFAULT_EXPLORER_PORT = 19801;
    private static final int DEFAULT_TILE_SIZE = 256;
    private static final int DEFAULT_TERRAIN_REGION_CACHE_MAX_MIB = 64;
    private static final int DEFAULT_TERRAIN_REGION_CACHE_MAX_ENTRIES = 32;
    private static final int DEFAULT_PIPELINE_TENSOR_CACHE_MAX_MIB = 64;
    private static final int DEFAULT_HYDROLOGY_TILE_SIZE = 2048;
    private static final int MAX_HYDROLOGY_TILE_SIZE = 8192;
    private static final int DEFAULT_HYDROLOGY_ANALYSIS_HALO = 128;
    private static final int DEFAULT_HYDROLOGY_WORKER_THREADS = 0;
    private static final int MAX_HYDROLOGY_WORKER_THREADS = 64;
    private static final int DEFAULT_INFERENCE_WORKER_THREADS = 0;
    private static final int MAX_INFERENCE_WORKER_THREADS = 16;
    private static final int DEFAULT_HYDROLOGY_CACHE_MAX_MIB = 160;
    private static final int DEFAULT_HYDROLOGY_CACHE_MAX_ENTRIES = 5;
    private static final boolean DEFAULT_HYDROLOGY_DISK_CACHE_ENABLED = true;

    static {
        loadDefaults();
        Path configPath = resolveConfigPath();
        if (configPath != null) {
            loadOverrides(configPath);
        }
    }

    private TerrainDiffusionConfig() {
    }

    /** Inference device: "cpu", "gpu", or "auto" (try GPU then fall back to CPU). */
    public static String inferenceDevice() {
        String device = readString("inference.device", "gpu");
        // On the CPU build "gpu" is meaningless (no dedicated GPU provider), so treat it as "auto":
        // tries CoreML on macOS, falls back to CPU elsewhere.
        if ("cpu".equals(BUILD_VARIANT)) {
            return "auto";
        }
        return device;
    }

    /** Whether to offload inactive models from VRAM between pipeline stages. */
    public static boolean offloadModels() {
        return readBoolean("inference.offload_models", DEFAULT_OFFLOAD_MODELS);
    }

    /**
     * Concurrent region/tile computations. Zero selects an automatic value: 1 when
     * {@link #offloadModels()} is true (GPU-slot swapping already serializes every inference
     * call, so extra threads would only add queuing overhead and risk swap thrashing between
     * different models), or a small pool sized off available CPUs when models stay resident.
     */
    public static int inferenceWorkerThreads() {
        int configured = readInt("inference.worker_threads", DEFAULT_INFERENCE_WORKER_THREADS);
        if (configured == 0) {
            if (offloadModels()) return 1;
            int available = Math.max(1, Runtime.getRuntime().availableProcessors());
            return Math.max(1, Math.min(4, available / 2));
        }
        if (configured < 1 || configured > MAX_INFERENCE_WORKER_THREADS) {
            System.err.println("Invalid inference.worker_threads: " + configured + ", using 1");
            return 1;
        }
        return configured;
    }

    /** TCP port for the local terrain explorer HTTP server. */
    public static int explorerPort() {
        return readInt("explorer.port", DEFAULT_EXPLORER_PORT);
    }

    /** Whether to validate SHA-256 for pre-existing local model files before use. */
    public static boolean validateModel() {
        return readBoolean("validate_model", DEFAULT_VALIDATE_MODEL);
    }

    /** Initial coarse-pixel radius for spawn land search (NxN region centered at origin). */
    public static int spawnSearchInitialSize() {
        return readInt("spawn_search.initial_size", 16);
    }

    /** Maximum coarse-pixel region size for spawn land search before giving up. */
    public static int spawnSearchMaxSize() {
        return readInt("spawn_search.max_size", 128);
    }

    /** Region side length in blocks. Must be a positive power of 2 (128, 256, 512, ...). */
    public static int tileSize() {
        int configuredTileSize = readInt("tile_size", DEFAULT_TILE_SIZE);
        if (configuredTileSize <= 0 || !isPowerOfTwo(configuredTileSize)) {
            System.err.println("Invalid tile_size: " + configuredTileSize + ", using default " + DEFAULT_TILE_SIZE);
            return DEFAULT_TILE_SIZE;
        }
        return configuredTileSize;
    }

    /** Maximum retained generated heightmap/biome regions, in bytes. */
    public static long terrainRegionCacheMaxBytes() {
        return positiveMibToBytes("terrain_cache.max_mib", DEFAULT_TERRAIN_REGION_CACHE_MAX_MIB);
    }

    /** Maximum retained generated heightmap/biome regions, by entry count. */
    public static int terrainRegionCacheMaxEntries() {
        return readPositiveInt("terrain_cache.max_entries", DEFAULT_TERRAIN_REGION_CACHE_MAX_ENTRIES);
    }

    /** Per-tensor cache limit for pipeline windows, in bytes. */
    public static long pipelineTensorCacheMaxBytes() {
        return positiveMibToBytes("pipeline_cache.tensor_max_mib", DEFAULT_PIPELINE_TENSOR_CACHE_MAX_MIB);
    }

    /** Side length of a canonical hydrology tile in output pixels/blocks. */
    public static int hydrologyTileSize() {
        int configured = readInt("hydrology.tile_size", DEFAULT_HYDROLOGY_TILE_SIZE);
        if (configured < 256 || configured > MAX_HYDROLOGY_TILE_SIZE || !isPowerOfTwo(configured)) {
            System.err.println("Invalid hydrology.tile_size: " + configured
                    + ", using default " + DEFAULT_HYDROLOGY_TILE_SIZE);
            return DEFAULT_HYDROLOGY_TILE_SIZE;
        }
        return configured;
    }

    /** Fixed analysis halo around each canonical hydrology tile. */
    public static int hydrologyAnalysisHalo() {
        int configured = readPositiveInt("hydrology.analysis_halo", DEFAULT_HYDROLOGY_ANALYSIS_HALO);
        int max = Math.max(1, hydrologyTileSize() / 2);
        if (configured > max) {
            System.err.println("Invalid hydrology.analysis_halo: " + configured
                    + ", clamping to " + max);
            return max;
        }
        return configured;
    }

    /** CPU workers used by independent hydrology passes. Zero selects all logical CPUs except one. */
    public static int hydrologyWorkerThreads() {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        int automatic = Math.max(1, available - 1);
        int configured = readInt("hydrology.worker_threads", DEFAULT_HYDROLOGY_WORKER_THREADS);
        if (configured == 0) return automatic;
        if (configured < 0 || configured > MAX_HYDROLOGY_WORKER_THREADS) {
            System.err.println("Invalid hydrology.worker_threads: " + configured
                    + ", using automatic value " + automatic);
            return automatic;
        }
        return Math.min(configured, available);
    }

    /** Maximum retained canonical hydrology tiles, in bytes. */
    public static long hydrologyCacheMaxBytes() {
        return positiveMibToBytes("hydrology.cache.max_mib", DEFAULT_HYDROLOGY_CACHE_MAX_MIB);
    }

    /** Maximum retained canonical hydrology tiles, by entry count. */
    public static int hydrologyCacheMaxEntries() {
        return readPositiveInt("hydrology.cache.max_entries", DEFAULT_HYDROLOGY_CACHE_MAX_ENTRIES);
    }

    /** Whether compact canonical hydrology tiles are persisted under the game cache directory. */
    public static boolean hydrologyDiskCacheEnabled() {
        return readBoolean("hydrology.disk_cache.enabled", DEFAULT_HYDROLOGY_DISK_CACHE_ENABLED);
    }

    /** Namespace used to invalidate disk tiles when custom terrain models are replaced. */
    public static String hydrologyDiskCacheNamespace() {
        return readString("hydrology.disk_cache.namespace", "default");
    }

    private static void loadDefaults() {
        boolean loadedFromResource = false;
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                PROPERTIES.load(in);
                loadedFromResource = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to load default config from resource: " + e.getMessage());
        }

        if (!loadedFromResource) {
            PROPERTIES.setProperty("inference.device", "gpu");
            PROPERTIES.setProperty("validate_model", String.valueOf(DEFAULT_VALIDATE_MODEL));
            PROPERTIES.setProperty("tile_size", String.valueOf(DEFAULT_TILE_SIZE));
            PROPERTIES.setProperty("terrain_cache.max_mib", String.valueOf(DEFAULT_TERRAIN_REGION_CACHE_MAX_MIB));
            PROPERTIES.setProperty("terrain_cache.max_entries", String.valueOf(DEFAULT_TERRAIN_REGION_CACHE_MAX_ENTRIES));
            PROPERTIES.setProperty("pipeline_cache.tensor_max_mib", String.valueOf(DEFAULT_PIPELINE_TENSOR_CACHE_MAX_MIB));
            PROPERTIES.setProperty("hydrology.tile_size", String.valueOf(DEFAULT_HYDROLOGY_TILE_SIZE));
            PROPERTIES.setProperty("hydrology.analysis_halo", String.valueOf(DEFAULT_HYDROLOGY_ANALYSIS_HALO));
            PROPERTIES.setProperty("hydrology.worker_threads", String.valueOf(DEFAULT_HYDROLOGY_WORKER_THREADS));
            PROPERTIES.setProperty("hydrology.cache.max_mib", String.valueOf(DEFAULT_HYDROLOGY_CACHE_MAX_MIB));
            PROPERTIES.setProperty("hydrology.cache.max_entries", String.valueOf(DEFAULT_HYDROLOGY_CACHE_MAX_ENTRIES));
            PROPERTIES.setProperty("hydrology.disk_cache.enabled", String.valueOf(DEFAULT_HYDROLOGY_DISK_CACHE_ENABLED));
            PROPERTIES.setProperty("hydrology.disk_cache.namespace", "default");
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? value.trim().toLowerCase() : defaultValue;
    }

    private static Path resolveConfigPath() {
        try {
            return PlatformPaths.configDir().resolve(FILE_NAME);
        } catch (RuntimeException e) {
            System.err.println("Loader config directory unavailable: " + e.getMessage());
            return null;
        }
    }

    private static void loadOverrides(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    Properties overrides = new Properties();
                    overrides.load(in);
                    PROPERTIES.putAll(overrides);
                }
            } else {
                writeConfig(configPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to read config file: " + e.getMessage());
        }
    }

    private static void writeConfig(Path configPath) {
        try (InputStream defaultConfigInputStream = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (defaultConfigInputStream != null) {
                Files.copy(defaultConfigInputStream, configPath);
                return;
            }
            System.err.println("Default config resource not found: " + RESOURCE_PATH);
        } catch (IOException e) {
            System.err.println("Failed to copy default config resource: " + e.getMessage());
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid int for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static int readPositiveInt(String key, int defaultValue) {
        int value = readInt(key, defaultValue);
        if (value <= 0) {
            System.err.println("Invalid positive int for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
        return value;
    }

    private static long positiveMibToBytes(String key, int defaultMib) {
        int mib = readPositiveInt(key, defaultMib);
        return (long) mib * 1024L * 1024L;
    }

    private static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0;
    }

    private static String readBuildVariant() {
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream("/build-variant.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("build.variant", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

}
