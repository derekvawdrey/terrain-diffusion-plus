package com.github.xandergos.terraindiffusionmc.pipeline;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone check for {@link ModelAssetManager}'s resumable download: a local server drops the
 * first connection part-way, the second request must ask for the remainder, and the installed
 * file must hash correctly. Also checks that a 404 is not retried. Prints PASS/FAIL and exits
 * non-zero on failure. Takes a few seconds because the first retry waits.
 */
public class ModelAssetDownloadTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        byte[] payload = new byte[3 * 1024 * 1024 + 12345];
        new Random(42).nextBytes(payload);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(payload));

        AtomicInteger requests = new AtomicInteger();
        StringBuilder rangeSeen = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.onnx", exchange -> {
            int n = requests.incrementAndGet();
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) rangeSeen.append(range).append(';');
            int from = 0;
            if (range != null && range.startsWith("bytes=")) {
                from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (payload.length - 1) + "/" + payload.length);
                exchange.sendResponseHeaders(206, payload.length - from);
            } else {
                exchange.sendResponseHeaders(200, payload.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                if (n == 1) {
                    // Drop the connection after the first megabyte.
                    out.write(payload, 0, 1024 * 1024);
                    out.flush();
                    exchange.close();
                    return;
                }
                out.write(payload, from, payload.length - from);
            }
        });
        server.createContext("/missing.onnx", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Path dir = Files.createTempDirectory("td-model-test");
        try {
            ModelAssetManager.ManifestAsset asset = new ModelAssetManager.ManifestAsset();
            asset.sha256 = sha;
            asset.sizeBytes = payload.length;
            asset.url = base + "/model.onnx";
            Path target = dir.resolve("model.onnx");
            long t0 = System.nanoTime();
            ModelAssetManager.downloadAndVerifyAsset(target, asset, "test", base);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            check("file installed", Files.isRegularFile(target));
            check("temporary file removed", !Files.exists(dir.resolve("model.onnx.tmp")));
            check("content matches", Arrays.equals(Files.readAllBytes(target), payload));
            check("second request resumed with a Range header (" + rangeSeen + ")", rangeSeen.toString().startsWith("bytes=1048576-"));
            check("exactly two requests (one drop, one resume): " + requests.get(), requests.get() == 2);
            System.out.println("resumed download took " + ms + " ms including the retry pause");

            requests.set(0);
            ModelAssetManager.ManifestAsset missing = new ModelAssetManager.ManifestAsset();
            missing.sha256 = sha;
            missing.sizeBytes = 10;
            missing.url = base + "/missing.onnx";
            boolean threw = false;
            try {
                ModelAssetManager.downloadAndVerifyAsset(dir.resolve("missing.onnx"), missing, "test", base);
            } catch (Exception e) {
                threw = true;
            }
            check("404 fails", threw);
            check("404 is not retried: " + requests.get() + " request(s)", requests.get() == 1);
        } finally {
            server.stop(0);
            try (var files = Files.walk(dir)) {
                files.sorted((a, b) -> b.compareTo(a)).forEach(f -> f.toFile().delete());
            }
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) System.exit(1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
