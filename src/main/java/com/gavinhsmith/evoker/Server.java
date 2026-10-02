package com.gavinhsmith.evoker;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Resolves and launches each server software. */
final class Server {
    /** A resolved server download. algo/hash are the upstream's own checksum, if it publishes one. */
    record Resolved(String build, String url, String algo, String hash) {}

    private final Http http;
    private final Apis apis;

    Server(Http http, Apis apis) {
        this.http = http;
        this.apis = apis;
    }

    Resolved resolve(Manifest.ServerSpec spec) {
        return switch (spec.software()) {
            case "vanilla" -> vanilla(spec.version());
            case "paper" -> paper(spec.version(), spec.build());
            case "purpur" -> purpur(spec.version(), spec.build());
            case "fabric" -> fabric(spec.version(), spec.build());
            case "spigot", "quilt", "neoforge" ->
                    throw new EvokerException(spec.software() + " is not supported yet");
            default -> throw new EvokerException("unknown server software: " + spec.software());
        };
    }

    /** The jar evoker downloads. Fabric's launcher downloads vanilla into server.jar itself. */
    static String jarName(String software) {
        return software.equals("fabric") ? "fabric-server-launch.jar" : "server.jar";
    }

    static List<String> command(String software, Manifest.Settings settings) {
        var command = new ArrayList<String>();
        command.add(settings.java());
        command.addAll(settings.jvmArgs());
        command.addAll(List.of("-jar", jarName(software), "nogui"));
        return command;
    }

    /** The newest Minecraft release, e.g. for evoker init. */
    String latestRelease() {
        return http.json(apis.mojang() + "/mc/game/version_manifest_v2.json").path("latest").path("release").asString();
    }

    private Resolved vanilla(String version) {
        JsonNode manifest = http.json(apis.mojang() + "/mc/game/version_manifest_v2.json");
        for (JsonNode v : manifest.path("versions")) {
            if (v.path("id").asString().equals(version)) {
                JsonNode server = http.json(v.path("url").asString()).path("downloads").path("server");
                if (server.isMissingNode()) throw new EvokerException("vanilla " + version + " has no server jar");
                return new Resolved(null, server.path("url").asString(), "SHA-1", server.path("sha1").asString());
            }
        }
        throw new EvokerException("unknown Minecraft version: " + version);
    }

    private Resolved paper(String version, String build) {
        String base = apis.paper() + "/v3/projects/paper/versions/" + version + "/builds";
        JsonNode chosen;
        if (build.equals("latest")) {
            JsonNode builds = http.jsonOrNull(base);
            if (builds == null || builds.isEmpty()) throw new EvokerException("paper has no builds for " + version);
            chosen = builds.get(0);
            for (JsonNode b : builds) {
                String channel = b.path("channel").asString();
                if (channel.equals("STABLE") || channel.equals("RECOMMENDED")) {
                    chosen = b;
                    break;
                }
            }
        } else {
            chosen = http.jsonOrNull(base + "/" + build);
            if (chosen == null) throw new EvokerException("paper has no build " + build + " for " + version);
        }
        JsonNode download = chosen.path("downloads").path("server:default");
        return new Resolved(chosen.path("id").asString(), download.path("url").asString(),
                "SHA-256", download.path("checksums").path("sha256").asString());
    }

    private Resolved purpur(String version, String build) {
        String base = apis.purpur() + "/v2/purpur/" + version;
        if (build.equals("latest")) {
            JsonNode info = http.jsonOrNull(base);
            if (info == null) throw new EvokerException("purpur has no builds for " + version);
            build = info.path("builds").path("latest").asString();
        }
        JsonNode info = http.jsonOrNull(base + "/" + build);
        if (info == null) throw new EvokerException("purpur has no build " + build + " for " + version);
        return new Resolved(build, base + "/" + build + "/download", "MD5", info.path("md5").asString());
    }

    private Resolved fabric(String version, String loader) {
        String base = apis.fabric() + "/v2/versions";
        if (loader.equals("latest")) {
            JsonNode loaders = http.json(base + "/loader/" + version);
            if (loaders.isEmpty()) throw new EvokerException("fabric has no loader for " + version);
            loader = stableOrFirst(loaders, "loader").path("version").asString();
        }
        String installer = stableOrFirst(http.json(base + "/installer"), null).path("version").asString();
        // Fabric publishes no checksum for the launcher jar; evoker's own sha256 still pins it.
        return new Resolved(loader, base + "/loader/" + version + "/" + loader + "/" + installer + "/server/jar",
                null, null);
    }

    private static JsonNode stableOrFirst(JsonNode list, String field) {
        for (JsonNode item : list) {
            JsonNode node = field == null ? item : item.path(field);
            if (node.path("stable").asBoolean()) return node;
        }
        return field == null ? list.get(0) : list.get(0).path(field);
    }
}
