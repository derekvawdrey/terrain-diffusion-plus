package com.github.xandergos.terraindiffusionmc.pipeline;

import ai.onnxruntime.*;
import ai.onnxruntime.providers.CoreMLFlags;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin wrapper around ONNX Runtime with aggressive VRAM optimization.
 *
 * <p>Only one model is resident in GPU VRAM at a time (GPU-slot swapping).
 * Model sessions are created from model files on disk instead of cached byte arrays,
 * keeping the Java heap free from multi-gigabyte ONNX weight buffers.
 */
public final class OnnxModel implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxModel.class);
    private static final String OPTIMIZED_MODELS_DIR_NAME = "onnx-cache";

    private static volatile String resolvedInferenceProvider = null;
    private static final AtomicBoolean providerLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean coremlWarnLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean cudaWarnLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean dmlWarnLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean noGpuWarnLoggedOnce = new AtomicBoolean(false);

    // GPU slot: when models are offloaded, only one session is alive at a time.
    private static final Object GPU_SLOT_LOCK = new Object();
    private static OnnxModel gpuSlotHolder = null;
    private static OrtSession activeGpuSession = null;

    /**
     * Every model currently holding a resident GPU session, so an out-of-memory failure during
     * inference can drop all of them at once and continue with GPU-slot swapping instead of
     * failing world generation.
     */
    private static final Set<OnnxModel> RESIDENT_MODELS = ConcurrentHashMap.newKeySet();
    private static volatile boolean residencyDowngraded = false;

    private final OrtEnvironment env;
    private final Path sessionModelPath;
    private final long sessionModelSizeBytes;
    private final String name;
    private OrtSession cpuSession;    // non-null in CPU-only mode
    private volatile OrtSession gpuSession;    // non-null while this model stays resident

    private static final class OptimizedModelLoadResult {
        private final Path sessionModelPath;
        private final Path optimizedModelPath;
        private final boolean loadedFromCache;
        private final long modelSizeBytes;

        private OptimizedModelLoadResult(Path sessionModelPath, Path optimizedModelPath, boolean loadedFromCache) throws java.io.IOException {
            this.sessionModelPath = sessionModelPath;
            this.optimizedModelPath = optimizedModelPath;
            this.loadedFromCache = loadedFromCache;
            this.modelSizeBytes = Files.size(sessionModelPath);
        }
    }

    public OnnxModel(Path modelFilePath, String name) {
        this.name = name;
        try {
            long start = System.currentTimeMillis();
            this.env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            Path sourceModelPath = modelFilePath.toAbsolutePath().normalize();
            OptimizedModelLoadResult initialOptimizedModelLoadResult = optimizeModelAtRuntime(sourceModelPath, false);
            OptimizedModelLoadResult loadedModel;
            try {
                initializeModelSession(initialOptimizedModelLoadResult.sessionModelPath,
                        initialOptimizedModelLoadResult.modelSizeBytes, start);
                loadedModel = initialOptimizedModelLoadResult;
            } catch (Exception initialLoadException) {
                if (!initialOptimizedModelLoadResult.loadedFromCache) {
                    throw initialLoadException;
                }
                closeLoadedSessions();
                LOG.warn("Cached optimized ONNX model '{}' failed to load. Rebuilding cache: {}",
                        name, initialLoadException.getMessage());
                deleteOptimizedCacheFile(initialOptimizedModelLoadResult.optimizedModelPath);
                OptimizedModelLoadResult rebuiltOptimizedModelLoadResult = optimizeModelAtRuntime(sourceModelPath, true);
                initializeModelSession(rebuiltOptimizedModelLoadResult.sessionModelPath,
                        rebuiltOptimizedModelLoadResult.modelSizeBytes, start);
                loadedModel = rebuiltOptimizedModelLoadResult;
            }
            this.sessionModelPath = loadedModel.sessionModelPath;
            this.sessionModelSizeBytes = loadedModel.modelSizeBytes;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load ONNX model: " + modelFilePath, e);
        }
    }

    /**
     * Optimizes the model and caches the optimized file in the config directory.
     * Falls back to the source model file if optimization or cache I/O fails.
     */
    private OptimizedModelLoadResult optimizeModelAtRuntime(Path sourceModelPath, boolean forceRebuildFromSource) {
        Path optimizedModelPath = resolveOptimizedModelPath(sourceModelPath);
        Path temporaryOptimizedModelPath = optimizedModelPath.resolveSibling(optimizedModelPath.getFileName() + ".tmp");
        try {
            if (!forceRebuildFromSource && Files.exists(optimizedModelPath)) {
                return new OptimizedModelLoadResult(optimizedModelPath, optimizedModelPath, true);
            }

            Files.createDirectories(optimizedModelPath.getParent());
            Files.deleteIfExists(temporaryOptimizedModelPath);
            try (OrtSession.SessionOptions optimizationOptions = new OrtSession.SessionOptions()) {
                optimizationOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);
                optimizationOptions.setOptimizedModelFilePath(temporaryOptimizedModelPath.toAbsolutePath().toString());
                try (OrtSession ignored = env.createSession(sourceModelPath.toString(), optimizationOptions)) {
                    // Session creation materializes the optimized model on disk.
                }
            }
            Files.move(
                    temporaryOptimizedModelPath,
                    optimizedModelPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
            long sourceModelSizeBytes = Files.size(sourceModelPath);
            long optimizedModelSizeBytes = Files.size(optimizedModelPath);
            LOG.info("Optimized ONNX model '{}' at runtime ({} KB -> {} KB)",
                    name, sourceModelSizeBytes / 1024, optimizedModelSizeBytes / 1024);
            return new OptimizedModelLoadResult(optimizedModelPath, optimizedModelPath, false);
        } catch (Exception optimizationException) {
            try {
                Files.deleteIfExists(temporaryOptimizedModelPath);
            } catch (Exception ignored) {
                // Best effort cleanup only.
            }
            LOG.warn("Runtime ONNX optimization failed for '{}', using source model file: {}",
                    name, optimizationException.getMessage());
            try {
                return new OptimizedModelLoadResult(sourceModelPath, optimizedModelPath, false);
            } catch (Exception resultException) {
                throw new RuntimeException("Failed to resolve ONNX model file for: " + name, resultException);
            }
        }
    }

    /** Returns the resolved inference provider name, or {@code "unknown"} if not yet determined. */
    public static String getResolvedInferenceProvider() {
        String provider = resolvedInferenceProvider;
        return provider != null ? provider : "unknown";
    }

    private static void setResolvedProviderOnce(String provider) {
        if (resolvedInferenceProvider == null) {
            resolvedInferenceProvider = provider;
        }
        if (providerLoggedOnce.compareAndSet(false, true)) {
            LOG.info("Terrain diffusion inference: {}", provider);
        }
    }

    /**
     * Loads model sessions for the active inference device configuration.
     */
    private void initializeModelSession(Path modelPath, long modelSizeBytes, long startMillis) throws OrtException {
        String modelPathString = modelPath.toAbsolutePath().toString();
        if ("cpu".equals(TerrainDiffusionConfig.inferenceDevice())) {
            try (OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions()) {
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                this.cpuSession = env.createSession(modelPathString, sessionOptions);
            }
            this.gpuSession = null;
            setResolvedProviderOnce("CPU");
            LOG.info("ONNX model '{}' loaded on CPU from file ({} KB) in {} ms",
                    name, modelSizeBytes / 1024, System.currentTimeMillis() - startMillis);
            return;
        }

        TerrainDiffusionConfig.ModelResidency residency = TerrainDiffusionConfig.modelResidency();
        boolean automatic = residency == TerrainDiffusionConfig.ModelResidency.AUTO;
        if (residency != TerrainDiffusionConfig.ModelResidency.OFFLOAD && !residencyDowngraded) {
            try {
                createResidentSession(modelPathString);
                this.cpuSession = null;
                LOG.info("ONNX model '{}' loaded on GPU from file ({} KB) in {} ms",
                        name, modelSizeBytes / 1024, System.currentTimeMillis() - startMillis);
                return;
            } catch (OrtException | RuntimeException residentFailure) {
                // A missing GPU is a configuration problem, not a capacity one: offloading would
                // hit exactly the same wall on the first inference, so let it surface here.
                if (!automatic || residentFailure instanceof GpuProviderUnavailableException) {
                    throw residentFailure;
                }
                // The GPU cannot hold every model at once; fall back to swapping one in at a time.
                LOG.info("Keeping ONNX model '{}' resident failed ({}); using GPU-slot swapping instead",
                        name, residentFailure.getMessage());
                downgradeResidency();
            }
        }
        this.cpuSession = null;
        this.gpuSession = null;
        LOG.info("ONNX model '{}' prepared for on-demand GPU loading from file ({} KB) in {} ms",
                name, modelSizeBytes / 1024, System.currentTimeMillis() - startMillis);
    }

    private void createResidentSession(String modelPathString) throws OrtException {
        try (OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions()) {
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            addGpuProvider(sessionOptions);
            if ("CoreML".equals(resolvedInferenceProvider)) {
                throw new OrtException("keeping models resident is not supported with CoreML. "
                        + "Set inference.offload_models=auto or true in terrain-diffusion-mc.properties.");
            }
            this.gpuSession = env.createSession(modelPathString, sessionOptions);
        }
        RESIDENT_MODELS.add(this);
    }

    /**
     * Drops every resident session and makes all models swap through the GPU slot from now on.
     * Called when the GPU turns out not to have room -- either while loading a model or on the
     * first inference that runs out of memory -- so a small GPU degrades to slower generation
     * instead of a failed chunk.
     */
    private static void downgradeResidency() {
        residencyDowngraded = true;
        for (OnnxModel model : RESIDENT_MODELS) {
            OrtSession resident = model.gpuSession;
            if (resident != null) {
                model.gpuSession = null;
                closeSessionQuietly(resident);
            }
        }
        RESIDENT_MODELS.clear();
    }

    private void closeLoadedSessions() {
        if (cpuSession != null) {
            closeSessionQuietly(cpuSession);
            cpuSession = null;
        }
        if (gpuSession != null) {
            closeSessionQuietly(gpuSession);
            gpuSession = null;
        }
    }

    private void deleteOptimizedCacheFile(Path optimizedModelPath) {
        try {
            Files.deleteIfExists(optimizedModelPath);
        } catch (Exception deleteException) {
            LOG.warn("Failed to delete optimized cache '{}' for '{}': {}",
                    optimizedModelPath, name, deleteException.getMessage());
        }
    }

    /**
     * Resolves a deterministic cache file path for an optimized model.
     */
    private Path resolveOptimizedModelPath(Path sourceModelPath) {
        String sourceModelHashPrefix = sha256Hex(sourceModelPath).substring(0, 16);
        String runtimeVersionTag = resolveOnnxRuntimeVersionTag();
        String optimizedFileName = name + "-" + runtimeVersionTag + "-" + sourceModelHashPrefix + ".onnx";
        return ModelAssetManager.resolveAssetPath(OPTIMIZED_MODELS_DIR_NAME)
                .resolve(optimizedFileName);
    }

    /**
     * Returns the ONNX Runtime version used as part of the optimization cache key.
     */
    private static String resolveOnnxRuntimeVersionTag() {
        Package onnxRuntimePackage = OrtEnvironment.class.getPackage();
        String implementationVersion = onnxRuntimePackage == null ? null : onnxRuntimePackage.getImplementationVersion();
        return implementationVersion == null ? "unknown" : implementationVersion;
    }

    /**
     * Computes a lowercase SHA-256 hex string for deterministic cache naming.
     */
    private static String sha256Hex(Path inputPath) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (InputStream inputStream = Files.newInputStream(inputPath)) {
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    messageDigest.update(buffer, 0, read);
                }
            }
            byte[] digestBytes = messageDigest.digest();
            StringBuilder hexBuilder = new StringBuilder(digestBytes.length * 2);
            for (byte digestByte : digestBytes) {
                hexBuilder.append(String.format("%02x", digestByte));
            }
            return hexBuilder.toString();
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("Missing SHA-256 algorithm", noSuchAlgorithmException);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to hash ONNX model file: " + inputPath, exception);
        }
    }

    /**
     * Run the model with a flat float array for each named input.
     * Each entry in {@code inputs} is (name, float[] data, long[] shape).
     *
     * @return the output tensor as a flat float array
     */
    public float[] run(Object[][] inputs) {
        if (cpuSession != null) {
            return timedRun(cpuSession, inputs);
        }
        OrtSession resident = gpuSession;
        if (resident != null) {
            try {
                return timedRun(resident, inputs);
            } catch (RuntimeException inferenceFailure) {
                if (!isOutOfMemory(inferenceFailure)) throw inferenceFailure;
                // Weights fit but the working memory did not: give up residency and retry the
                // same call through the GPU slot rather than failing the chunk.
                LOG.warn("ONNX model '{}' ran out of GPU memory while resident; falling back to "
                        + "loading one model at a time", name);
                synchronized (GPU_SLOT_LOCK) {
                    downgradeResidency();
                }
            }
        }
        synchronized (GPU_SLOT_LOCK) {
            claimGpuSlot();
            return timedRun(activeGpuSession, inputs);
        }
    }

    /** Thrown when {@code inference.device=gpu} but no GPU execution provider could be added. */
    static final class GpuProviderUnavailableException extends IllegalStateException {
        GpuProviderUnavailableException(String message) {
            super(message);
        }
    }

    private static boolean isOutOfMemory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase().contains("out of memory")) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    /** Times the inference itself, separately from any GPU-slot session load that preceded it. */
    private float[] timedRun(OrtSession session, Object[][] inputs) {
        long start = System.nanoTime();
        try {
            return runWithSession(session, inputs);
        } finally {
            InferenceStats.recordRun(name, System.nanoTime() - start, ((long[]) inputs[0][2])[0]);
        }
    }

    /** Convenience: run with x, noise_labels, and optional cond tensors. */
    public float[] runModel(float[] x, long[] xShape,
                            float[] noiseLabels,
                            float[][] condInputs, long[][] condShapes) {
        int nCond = condInputs == null ? 0 : condInputs.length;
        Object[][] inputs = new Object[2 + nCond][3];
        inputs[0] = new Object[]{"x", x, xShape};
        inputs[1] = new Object[]{"noise_labels", noiseLabels, new long[]{noiseLabels.length}};
        for (int i = 0; i < nCond; i++)
            inputs[2 + i] = new Object[]{"cond_" + i, condInputs[i], condShapes[i]};
        return run(inputs);
    }

    /**
     * Evicts the current GPU session if this model doesn't hold the slot,
     * then creates a fresh GPU session from the model file on disk.
     * Must be called under GPU_SLOT_LOCK.
     */
    private void claimGpuSlot() {
        if (gpuSlotHolder == this) return;
        long start = System.nanoTime();

        if (activeGpuSession != null) {
            LOG.debug("Evicting '{}' from GPU, loading '{}'",
                    gpuSlotHolder != null ? gpuSlotHolder.name : "?", name);
            closeSessionQuietly(activeGpuSession);
            activeGpuSession = null;
            gpuSlotHolder = null;
        }

        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            addGpuProvider(opts);
            activeGpuSession = env.createSession(sessionModelPath.toAbsolutePath().toString(), opts);
            gpuSlotHolder = this;
            InferenceStats.recordSessionCreate(name, System.nanoTime() - start);
            LOG.debug("GPU session ready for '{}' from file ({} KB)", name, sessionModelSizeBytes / 1024);
        } catch (OrtException e) {
            throw new RuntimeException("Failed to create GPU session for: " + name, e);
        }
    }

    private static void addGpuProvider(OrtSession.SessionOptions opts) throws OrtException {
        boolean gpuRequired = "gpu".equals(TerrainDiffusionConfig.inferenceDevice());
        boolean added = false;

        try (OrtCUDAProviderOptions cudaOpts = new OrtCUDAProviderOptions(0)) {
            // Only grow the BFC arena by exactly what is needed, never pre-allocate.
            cudaOpts.add("arena_extend_strategy", "kSameAsRequested");
            // Heuristic: fast startup, no exhaustive benchmarking, workspace-efficient.
            cudaOpts.add("cudnn_conv_algo_search", "HEURISTIC");
            cudaOpts.add("do_copy_in_default_stream", "1");
            opts.addCUDA(cudaOpts);
            added = true;
            setResolvedProviderOnce("CUDA");
        } catch (Throwable t) {
            if (cudaWarnLoggedOnce.compareAndSet(false, true)) {
                LOG.warn("CUDA not available: {} - {}. This is expected if you are not using a CUDA build.",
                        t.getClass().getSimpleName(), t.getMessage());
            }
        }

        if (!added) {
            try {
                opts.addDirectML(0);
                added = true;
                setResolvedProviderOnce("DirectML");
            } catch (Throwable t) {
                if (dmlWarnLoggedOnce.compareAndSet(false, true)) {
                    LOG.warn("DirectML not available: {} - {}. This is expected if you are not using a DirectML build.",
                            t.getClass().getSimpleName(), t.getMessage());
                }
            }
        }

        if (!added) {
            try {
                // ENABLE_ON_SUBGRAPH: allow CoreML to handle partial graphs with CPU fallback
                // for unsupported ops, maximising GPU utilisation without requiring full-graph support.
                opts.addCoreML(EnumSet.of(CoreMLFlags.ENABLE_ON_SUBGRAPH));
                added = true;
                setResolvedProviderOnce("CoreML");
            } catch (Throwable t) {
                if (coremlWarnLoggedOnce.compareAndSet(false, true)) {
                    LOG.warn("CoreML not available: {} - {}. This is expected on non-macOS platforms.",
                            t.getClass().getSimpleName(), t.getMessage());
                }
            }
        }
        if (gpuRequired && !added) {
            throw new GpuProviderUnavailableException(
                    "inference.device=gpu but no GPU provider (CUDA, DirectML, CoreML) is available. " +
                    "Use the appropriate build for your platform or set inference.device=cpu.");
        }
        if (!added) {
            setResolvedProviderOnce("CPU");
            if (noGpuWarnLoggedOnce.compareAndSet(false, true)) {
                LOG.warn("No GPU provider loaded. Check drivers and that the mod jar is the GPU build.");
            }
        }
    }

    private static float[] runWithSession(OrtSession session, Object[][] inputs) {
        Map<String, OnnxTensor> feed = new LinkedHashMap<>();
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try {
            for (Object[] inp : inputs) {
                feed.put((String) inp[0],
                        OnnxTensor.createTensor(env, FloatBuffer.wrap((float[]) inp[1]), (long[]) inp[2]));
            }
            try (OrtSession.Result result = session.run(feed)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                FloatBuffer buf = output.getFloatBuffer();
                float[] out = new float[buf.remaining()];
                buf.get(out);
                return out;
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed", e);
        } finally {
            for (OnnxTensor tensor : feed.values()) {
                tensor.close();
            }
            feed.clear();
        }
    }

    private static void closeSessionQuietly(OrtSession session) {
        try {
            session.close();
        } catch (OrtException ignored) {
            // Best effort cleanup only.
        }
    }

    @Override
    public void close() {
        synchronized (GPU_SLOT_LOCK) {
            if (gpuSlotHolder == this && activeGpuSession != null) {
                closeSessionQuietly(activeGpuSession);
                activeGpuSession = null;
                gpuSlotHolder = null;
            }
        }
        if (cpuSession != null) {
            closeSessionQuietly(cpuSession);
            cpuSession = null;
        }
        RESIDENT_MODELS.remove(this);
        OrtSession resident = gpuSession;
        if (resident != null) {
            gpuSession = null;
            closeSessionQuietly(resident);
        }
    }
}
