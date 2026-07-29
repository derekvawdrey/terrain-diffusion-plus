package com.github.xandergos.terraindiffusionmc.platform;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Loader-neutral access to runtime filesystem locations.
 */
public final class PlatformPaths {
    public static final String DEV_MODEL_DIRECTORY_PROPERTY = "terrainDiffusion.devModelDirectory";
    private static Path configDir = Path.of("config").toAbsolutePath().normalize();
    private static Path gameDir = Path.of("").toAbsolutePath().normalize();

    private PlatformPaths() {
    }

    /**
     * Must be called by each loader entrypoint before config or model assets are loaded.
     */
    public static synchronized void configure(Path configuredConfigDir, Path configuredGameDir) {
        configDir = Objects.requireNonNull(configuredConfigDir, "configuredConfigDir").toAbsolutePath().normalize();
        gameDir = Objects.requireNonNull(configuredGameDir, "configuredGameDir").toAbsolutePath().normalize();
    }

    /**
     * Returns the loader-provided config directory.
     */
    public static Path configDir() {
        return configDir;
    }

    /**
     * Returns the loader-provided game directory.
     */
    public static Path gameDir() {
        return gameDir;
    }

    /**
     * Returns the model asset directory. Development runs may override it with
     * {@link #DEV_MODEL_DIRECTORY_PROPERTY}; production keeps the game-directory path.
     */
    public static Path modelDir() {
        String developmentOverride = System.getProperty(DEV_MODEL_DIRECTORY_PROPERTY);
        if (developmentOverride != null && !developmentOverride.isBlank()) {
            return Path.of(developmentOverride).toAbsolutePath().normalize();
        }
        return gameDir.resolve("terrain-diffusion-models");
    }
}
