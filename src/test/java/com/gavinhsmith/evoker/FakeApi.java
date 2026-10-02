package com.gavinhsmith.evoker;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** An in-process HTTP server standing in for every upstream API. Unknown paths return 404. */
final class FakeApi implements AutoCloseable {
    final String base;
    final AtomicInteger hits = new AtomicInteger();
    private final HttpServer server;
    private final Map<String, byte[]> routes = new ConcurrentHashMap<>();

    FakeApi() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = routes.get(exchange.getRequestURI().toString());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Apis apis() {
        return new Apis(base + "/mojang", base + "/paper", base + "/purpur", base + "/fabric", base + "/modrinth",
                base + "/hangar");
    }

    FakeApi bytes(String path, byte[] body) {
        routes.put(path, body);
        return this;
    }

    /** Serves fixtures/name with {base} and the given {key} → value pairs substituted. */
    FakeApi json(String path, String fixture, String... vars) {
        String text;
        try (InputStream in = FakeApi.class.getResourceAsStream("/fixtures/" + fixture)) {
            if (in == null) throw new IllegalArgumentException("no fixture " + fixture);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        text = text.replace("{base}", base);
        for (int i = 0; i < vars.length; i += 2) text = text.replace("{" + vars[i] + "}", vars[i + 1]);
        return bytes(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /** The path Modrinth's client requests for a project's versions on a game version. */
    static String modrinthVersions(String projectId, String gameVersion) {
        return "/modrinth/v2/project/" + projectId + "/version?game_versions="
                + java.net.URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
