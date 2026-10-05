package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    /** The body, or null on 404. */
    byte[] bytesOrNull(String url) {
        HttpResponse<byte[]> response = send(url, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return null;
        check(url, response);
        return response.body();
    }

    /** POSTs body as JSON and parses the JSON reply. */
    JsonNode postJson(String url, Object body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body))).build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        check(url, response);
        return Json.MAPPER.readTree(response.body());
    }

    /** A downloaded temp file, its sha256 / sha1, and its hash with the requested algorithm (lock format, or null). */
    record Fetched(Path file, String sha256, String sha1, String hash) {}

    /** Downloads url into a temp file in dir, hashing it with SHA-256, SHA-1 and algo (if not null). */
    Fetched download(String url, Path dir, String algo) {
        HttpResponse<InputStream> response = send(url, HttpResponse.BodyHandlers.ofInputStream());
        check(url, response);
        Path temp = null;
        try (InputStream in = response.body()) {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, ".evoker-", ".tmp");
            MessageDigest sha256 = digest("SHA-256"), sha1 = digest("SHA-1");
            MessageDigest other = algo == null ? null : digest(algo);
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sha256.update(buf, 0, n);
                    sha1.update(buf, 0, n);
                    if (other != null) other.update(buf, 0, n);
                }
            }
            Fetched fetched = new Fetched(temp, hex(sha256), hex(sha1), other == null ? null : hash(algo, hex(other)));
            temp = null;
            return fetched;
        } catch (IOException e) {
            throw new EvokerException("download of " + url + " failed: " + e.getMessage(), e);
        } finally {
            if (temp != null) deleteQuietly(temp);
        }
    }

    /** A hash as evoker.lock stores it, e.g. "sha256:ab12..."; null when algo is null. */
    static String hash(String algo, String hex) {
        return algo == null ? null : algo.toLowerCase(java.util.Locale.ROOT).replace("-", "") + ":"
                + hex.toLowerCase(java.util.Locale.ROOT);
    }

    /** The MessageDigest algorithm of a lock hash: "sha512:..." → "SHA-512". */
    static String algo(String hash) {
        return switch (hash.substring(0, hash.indexOf(':'))) {
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            case "md5" -> "MD5";
            default -> throw new EvokerException("unknown hash " + hash);
        };
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** A file's hash in lock format, e.g. "sha512:ab12...". */
    static String hash(Path file, String algo) {
        MessageDigest digest = digest(algo);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) digest.update(buf, 0, n);
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return hash(algo, hex(digest));
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort; leftover temp files are hidden and harmless
        }
    }

    private <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler) {
        return send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).build(), handler);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        String url = request.uri().toString();
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
