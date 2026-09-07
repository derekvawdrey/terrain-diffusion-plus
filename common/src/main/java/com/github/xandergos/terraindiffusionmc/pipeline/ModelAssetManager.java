package com.github.xandergos.terraindiffusionmc.pipeline;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ensures model assets exist locally and match the expected SHA-256 hashes.
 *
 * <p>Assets are downloaded from a pinned Hugging Face commit into the directory
 * resolved by {@link PlatformPaths#modelDir()}. Production uses the game directory;
 * Gradle development runs may supply the shared repository-root override.
 */
public final class ModelAssetManager {
    private static final Logger LOG = LoggerFactory.getLogger(ModelAssetManager.class);
    private static final String MANIFEST_RESOURCE_PATH = "/model-assets-manifest.json";
    private static final long PROGRESS_LOG_THRESHOLD_BYTES = 100L * 1024L * 1024L;
    private static final AtomicBoolean READY = new AtomicBoolean(false);
    private static final Gson GSON = new Gson();
    private static final Type MANIFEST_TYPE = new TypeToken<ModelAssetManifest>() {}.getType();

    private ModelAssetManager() {
    }

    /**
     * Ensures all required assets are present and verified.
     */
    public static void ensureAssetsReady() {
        if (READY.get()) {
            return;
        }
        synchronized (ModelAssetManager.class) {
            if (READY.get()) {
                return;
            }
            try {
                Path modelDirectory = PlatformPaths.modelDir();
                Files.createDirectories(modelDirectory);
                ModelAssetManifest manifest = loadManifest();
                String offlineHelpUrl = buildOfflineHelpUrl(manifest);
                boolean shouldValidatePreExistingModels = TerrainDiffusionConfig.validateModel();
                LOG.info("Preparing terrain diffusion model assets in {}", modelDirectory);
                for (Map.Entry<String, ManifestAsset> assetEntry : manifest.assets.entrySet()) {
                    String fileName = assetEntry.getKey();
                    ManifestAsset assetMetadata = assetEntry.getValue();
                    Path localAssetPath = modelDirectory.resolve(fileName);
                    ensureSingleAsset(localAssetPath, assetMetadata, manifest.revision, offlineHelpUrl, shouldValidatePreExistingModels);
                }
                LOG.info("Terrain diffusion model assets ready");
                READY.set(true);
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to prepare terrain diffusion model assets", exception);
            }
        }
    }

    /**
     * Returns the local path for an asset in the model directory.
     */
    public static Path resolveAssetPath(String fileName) {
        return PlatformPaths.modelDir().resolve(fileName);
    }

    private static void ensureSingleAsset(
            Path localAssetPath,
            ManifestAsset assetMetadata,
            String revision,
            String offlineHelpUrl,
            boolean shouldValidatePreExistingModels
    ) throws IOException, InterruptedException {
        if (Files.exists(localAssetPath)) {
            if (!shouldValidatePreExistingModels) {
                LOG.info("Using pre-existing model asset '{}' without SHA validation (validate_model=false)",
                        localAssetPath.getFileName());
                return;
            }
            String existingHash = sha256Hex(localAssetPath);
            if (existingHash.equalsIgnoreCase(assetMetadata.sha256)) {
                LOG.info("Model asset '{}' already present and verified", localAssetPath.getFileName());
                return;
            }
            LOG.warn("Model asset '{}' hash mismatch. Re-downloading expected revision.", localAssetPath.getFileName());
            Files.delete(localAssetPath);
        }
        downloadAndVerifyAsset(localAssetPath, assetMetadata, revision, offlineHelpUrl);
    }

    /** Attempts per asset before giving up; the wait between them grows from the first entry. */
    private static final int MAX_DOWNLOAD_ATTEMPTS = 6;
    private static final long[] RETRY_DELAY_SECONDS = {3, 6, 12, 24, 45};
    private static final int CONNECT_TIMEOUT_SECONDS = 30;

    /**
     * Downloads one asset into {@code <name>.tmp}, then verifies and renames it into place.
     *
     * <p>A dropped connection used to throw away everything received so far, and on the 2 GB
     * base model that made a flaky line unable to finish at all -- the most common "failed to
     * load models" report. The partial file is now kept and the next attempt asks the server
     * for the remainder ({@code Range}); a server that ignores the range restarts from zero.
     * Transient failures are retried with a growing pause; a response that says the file will
     * never arrive (a 4xx other than 429) or a hash mismatch is not.</p>
     */
    static void downloadAndVerifyAsset(Path localAssetPath, ManifestAsset assetMetadata, String revision, String offlineHelpUrl) throws IOException, InterruptedException {
        Path temporaryAssetPath = localAssetPath.resolveSibling(localAssetPath.getFileName() + ".tmp");
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();

        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                downloadOnce(httpClient, localAssetPath, temporaryAssetPath, assetMetadata, attempt);
                LOG.info("Downloaded and verified model asset '{}'", localAssetPath.getFileName());
                return;
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (PermanentDownloadFailure permanent) {
                Files.deleteIfExists(temporaryAssetPath);
                lastFailure = permanent;
                break;
            } catch (Exception exception) {
                lastFailure = exception;
                if (attempt == MAX_DOWNLOAD_ATTEMPTS) break;
                long delay = RETRY_DELAY_SECONDS[Math.min(attempt - 1, RETRY_DELAY_SECONDS.length - 1)];
                LOG.warn("Downloading '{}' failed on attempt {} of {} ({}); retrying in {} s, keeping the {} received so far",
                        localAssetPath.getFileName(), attempt, MAX_DOWNLOAD_ATTEMPTS, describe(exception), delay,
                        humanReadableBytes(partialSize(temporaryAssetPath)));
                Thread.sleep(delay * 1000L);
            }
        }

        Exception exception = Objects.requireNonNull(lastFailure);
        {
            if (isOfflineError(exception)) {
                throw new IllegalStateException(
                        "Terrain Diffusion models are missing and must be downloaded while online. " +
                                "Connect to the internet and restart Minecraft. Direct download: " + offlineHelpUrl +
                                " (revision " + revision + ")",
                        exception);
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            if (exception instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            throw new IllegalStateException("Failed downloading model asset: " + localAssetPath.getFileName(), exception);
        }
    }

    /** A failure that another attempt cannot fix. */
    private static final class PermanentDownloadFailure extends IOException {
        PermanentDownloadFailure(String message) {
            super(message);
        }
    }

    private static void downloadOnce(HttpClient httpClient, Path localAssetPath, Path temporaryAssetPath,
                                     ManifestAsset assetMetadata, int attempt) throws IOException, InterruptedException {
        long resumeFrom = partialSize(temporaryAssetPath);
        if (resumeFrom > 0 && assetMetadata.sizeBytes > 0 && resumeFrom > assetMetadata.sizeBytes) {
            // Larger than the whole file: not a partial download of this revision.
            Files.deleteIfExists(temporaryAssetPath);
            resumeFrom = 0;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(assetMetadata.url)).GET();
        if (resumeFrom > 0) {
            request.header("Range", "bytes=" + resumeFrom + "-");
            LOG.info("Resuming model asset '{}' from {} of {} (attempt {})",
                    localAssetPath.getFileName(), humanReadableBytes(resumeFrom),
                    humanReadableBytes(assetMetadata.sizeBytes), attempt);
        } else {
            LOG.info("Downloading model asset '{}' ({}){}",
                    localAssetPath.getFileName(), humanReadableBytes(assetMetadata.sizeBytes),
                    attempt > 1 ? " (attempt " + attempt + ")" : "");
        }

        HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        int statusCode = response.statusCode();
        boolean append;
        if (statusCode == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0) {
            append = true;
        } else if (statusCode == HttpURLConnection.HTTP_OK) {
            // Full body, whether or not a range was asked for: start over.
            append = false;
            resumeFrom = 0;
        } else if (statusCode == 416 && resumeFrom == assetMetadata.sizeBytes) {
            // Nothing left to fetch: the partial file is already complete.
            response.body().close();
            verifyAndInstall(localAssetPath, temporaryAssetPath, assetMetadata);
            return;
        } else if (statusCode >= 400 && statusCode < 500 && statusCode != 429 && statusCode != 408) {
            response.body().close();
            throw new PermanentDownloadFailure("Failed to download model asset from " + assetMetadata.url
                    + " (HTTP " + statusCode + ")");
        } else {
            response.body().close();
            throw new IOException("HTTP " + statusCode + " from " + assetMetadata.url);
        }

        try (InputStream responseStream = response.body();
             OutputStream fileOutputStream = append
                     ? Files.newOutputStream(temporaryAssetPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                     : Files.newOutputStream(temporaryAssetPath)) {
            copyWithProgress(responseStream, fileOutputStream, localAssetPath.getFileName().toString(),
                    assetMetadata.sizeBytes, resumeFrom);
        }
        if (assetMetadata.sizeBytes > 0 && partialSize(temporaryAssetPath) < assetMetadata.sizeBytes) {
            throw new IOException("connection closed after " + humanReadableBytes(partialSize(temporaryAssetPath))
                    + " of " + humanReadableBytes(assetMetadata.sizeBytes));
        }
        verifyAndInstall(localAssetPath, temporaryAssetPath, assetMetadata);
    }

    private static void verifyAndInstall(Path localAssetPath, Path temporaryAssetPath, ManifestAsset assetMetadata) throws IOException {
        String downloadedHash = sha256Hex(temporaryAssetPath);
        if (!downloadedHash.equalsIgnoreCase(assetMetadata.sha256)) {
            Files.deleteIfExists(temporaryAssetPath);
            throw new PermanentDownloadFailure("SHA-256 mismatch for " + localAssetPath.getFileName()
                    + ". Expected " + assetMetadata.sha256 + " but got " + downloadedHash);
        }
        Files.move(temporaryAssetPath, localAssetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long partialSize(Path temporaryAssetPath) {
        try {
            return Files.isRegularFile(temporaryAssetPath) ? Files.size(temporaryAssetPath) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String describe(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) root = root.getCause();
        return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
    }

    private static boolean isOfflineError(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ModelAssetManifest loadManifest() {
        try (InputStream manifestStream = ModelAssetManager.class.getResourceAsStream(MANIFEST_RESOURCE_PATH);
             InputStreamReader manifestReader = new InputStreamReader(Objects.requireNonNull(manifestStream), StandardCharsets.UTF_8)) {
            ModelAssetManifest modelAssetManifest = GSON.fromJson(manifestReader, MANIFEST_TYPE);
            if (modelAssetManifest == null || modelAssetManifest.assets == null || modelAssetManifest.assets.isEmpty()) {
                throw new IllegalStateException("Model asset manifest is empty: " + MANIFEST_RESOURCE_PATH);
            }
            return modelAssetManifest;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load model asset manifest from " + MANIFEST_RESOURCE_PATH, exception);
        }
    }

    private static String buildOfflineHelpUrl(ModelAssetManifest manifest) {
        return "https://huggingface.co/" + manifest.repositorySlug + "/tree/" + manifest.revision;
    }

    private static void copyWithProgress(
            InputStream responseStream,
            OutputStream fileOutputStream,
            String fileName,
            long expectedSizeBytes,
            long alreadyDownloadedBytes
    ) throws IOException {
        boolean shouldLogProgress = expectedSizeBytes >= PROGRESS_LOG_THRESHOLD_BYTES;
        byte[] copyBuffer = new byte[256 * 1024];
        long downloadedBytes = alreadyDownloadedBytes;
        int nextProgressPercent = 10;
        if (expectedSizeBytes > 0) {
            int completedPercent = (int) ((downloadedBytes * 100L) / expectedSizeBytes);
            while (completedPercent >= nextProgressPercent && nextProgressPercent <= 100) nextProgressPercent += 10;
        }
        int readCount;
        while ((readCount = responseStream.read(copyBuffer)) != -1) {
            fileOutputStream.write(copyBuffer, 0, readCount);
            downloadedBytes += readCount;
            if (shouldLogProgress && expectedSizeBytes > 0) {
                int completedPercent = (int) ((downloadedBytes * 100L) / expectedSizeBytes);
                while (completedPercent >= nextProgressPercent && nextProgressPercent <= 100) {
                    LOG.info("Downloading '{}'... {}% ({}/{})",
                            fileName,
                            nextProgressPercent,
                            humanReadableBytes(downloadedBytes),
                            humanReadableBytes(expectedSizeBytes));
                    nextProgressPercent += 10;
                }
            }
        }
        if (expectedSizeBytes <= 0 || !shouldLogProgress) {
            LOG.info("Downloading '{}'... {} downloaded",
                    fileName, humanReadableBytes(downloadedBytes));
        }
    }

    private static String sha256Hex(Path localFilePath) throws IOException {
        MessageDigest messageDigest = createSha256Digest();
        try (InputStream fileStream = Files.newInputStream(localFilePath);
             DigestInputStream digestInputStream = new DigestInputStream(fileStream, messageDigest)) {
            byte[] readBuffer = new byte[64 * 1024];
            while (digestInputStream.read(readBuffer) != -1) {
                // Streaming digest update happens inside DigestInputStream.
            }
        }
        return toHex(messageDigest.digest());
    }

    private static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", noSuchAlgorithmException);
        }
    }

    private static String toHex(byte[] digestBytes) {
        StringBuilder hexBuilder = new StringBuilder(digestBytes.length * 2);
        for (byte digestByte : digestBytes) {
            hexBuilder.append(String.format("%02x", digestByte));
        }
        return hexBuilder.toString();
    }

    private static String humanReadableBytes(long byteCount) {
        if (byteCount < 1024L) {
            return byteCount + " B";
        }
        double kibibytes = byteCount / 1024.0;
        if (kibibytes < 1024.0) {
            return String.format("%.1f KiB", kibibytes);
        }
        double mebibytes = kibibytes / 1024.0;
        if (mebibytes < 1024.0) {
            return String.format("%.1f MiB", mebibytes);
        }
        double gibibytes = mebibytes / 1024.0;
        return String.format("%.2f GiB", gibibytes);
    }

    private static final class ModelAssetManifest {
        String repositorySlug;
        String revision;
        Map<String, ManifestAsset> assets;
    }

    static final class ManifestAsset {
        String sha256;
        long sizeBytes;
        String url;
    }
}
