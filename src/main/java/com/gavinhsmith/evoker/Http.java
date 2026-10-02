package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;

/** GET JSON and download files, with the User-Agent the upstream APIs ask for. */
final class Http {
    static final String USER_AGENT = "evoker/" + Main.VERSION + " (https://github.com/gavinhsmith/evoker)";

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    JsonNode json(String url) {
        JsonNode node = jsonOrNull(url);
        if (node == null) throw new EvokerException("not found: " + url);
        return node;
    }

    /** The parsed body, or null on 404. */
    JsonNode jsonOrNull(String url) {
        HttpResponse<String> response = send(url, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        check(url, response);
        return Json.MAPPER.readTree(response.body());
    }

    /** A downloaded temp file and its hashes. The caller moves or deletes it. */
    record Fetched(Path file, String sha256, String sha1) {}

    /**
     * Downloads url into a temp file in dir. If algo/expected (the upstream's own hash) are given,
     * a mismatch deletes the file and fails.
     */
    Fetched download(String url, Path dir, String algo, String expected) {
        HttpResponse<InputStream> response = send(url, HttpResponse.BodyHandlers.ofInputStream());
        check(url, response);
        Path temp = null;
        try (InputStream in = response.body()) {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, ".evoker-", ".tmp");
            MessageDigest sha256 = digest("SHA-256"), sha1 = digest("SHA-1");
            MessageDigest upstream = algo == null ? null : digest(algo);
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sha256.update(buf, 0, n);
                    sha1.update(buf, 0, n);
                    if (upstream != null) upstream.update(buf, 0, n);
                }
            }
            if (upstream != null && !hex(upstream).equalsIgnoreCase(expected)) {
                throw new EvokerException("download of " + url + " is corrupt (" + algo + " mismatch)");
            }
            Fetched fetched = new Fetched(temp, hex(sha256), hex(sha1));
            temp = null;
            return fetched;
        } catch (IOException e) {
            throw new EvokerException("download of " + url + " failed: " + e.getMessage(), e);
        } finally {
            if (temp != null) deleteQuietly(temp);
        }
    }

    static String sha256(Path file) {
        MessageDigest digest = digest("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) digest.update(buf, 0, n);
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return hex(digest);
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort; leftover temp files are hidden and harmless
        }
    }

    private <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).build();
        try {
            return client.send(request, handler);
        } catch (IOException e) {
            throw new EvokerException("request to " + url + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvokerException("interrupted", e);
        }
    }

    private static void check(String url, HttpResponse<?> response) {
        if (response.statusCode() >= 400) throw new EvokerException(url + " returned HTTP " + response.statusCode());
    }

    private static MessageDigest digest(String algo) {
        try {
            return MessageDigest.getInstance(algo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
