package com.gavinhsmith.evoker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** evoker.json: the server as the user wants it. */
record Manifest(ServerSpec server, boolean eula, Map<String, Object> properties, Map<String, Content> content,
                Settings evoker) {
    static final String FILE = "evoker.json";

    Manifest {
        if (server == null) throw new EvokerException(FILE + " is missing \"server\"");
        properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        var normalized = new LinkedHashMap<String, Content>();
        if (content != null) content.forEach((k, v) -> normalized.put(key(k), v));
        content = normalized;
        if (evoker == null) evoker = new Settings(false, false, null, null);
    }

    /** "sodium" means "modrinth:sodium". */
    static String key(String key) {
        return key.contains(":") ? key : "modrinth:" + key;
    }

    static Manifest read(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) throw new EvokerException("no " + FILE + " in " + dir + " (run evoker init)");
        try {
            return Json.MAPPER.readValue(file, Manifest.class);
        } catch (JacksonException e) {
            Throwable cause = e.getCause() instanceof EvokerException ee ? ee : e;
            throw new EvokerException("invalid " + FILE + ": " + cause.getMessage(), e);
        }
    }

    Manifest withContent(Map<String, Content> content) {
        return new Manifest(server, eula, properties, content, evoker);
    }

    Manifest withServer(ServerSpec server) {
        return new Manifest(server, eula, properties, content, evoker);
    }

    void write(Path dir) {
        Json.write(dir.resolve(FILE), this);
    }

    record ServerSpec(String software, String version, String build) {
        ServerSpec {
            if (software == null || version == null) {
                throw new EvokerException("server needs \"software\" and \"version\"");
            }
            software = software.toLowerCase();
            if (build == null) build = "latest";
        }
    }

    /** A content entry: a version ("latest" or pinned), or for url entries {url, type}. */
    record Content(String version, String url, String type) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static Content of(JsonNode node) {
            if (node.isString()) return new Content(node.asString(), null, null);
            return new Content(null, node.path("url").asString(null), node.path("type").asString(null));
        }

        @JsonValue
        Object json() {
            if (url == null) return version;
            var map = new LinkedHashMap<String, String>();
            map.put("url", url);
            map.put("type", type);
            return map;
        }
    }

    /** The "evoker" block: how evoker itself behaves. */
    record Settings(boolean autoUpdateDeps, boolean autoUpdateServer, String java, List<String> jvmArgs) {
        Settings {
            if (java == null) java = "java";
            jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        }
    }
}
