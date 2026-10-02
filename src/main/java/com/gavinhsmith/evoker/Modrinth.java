package com.gavinhsmith.evoker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Modrinth (https://docs.modrinth.com/api/): mods, plugins, data packs and resource packs. */
final class Modrinth implements Source {
    private final Http http;
    private final String api;

    Modrinth(Http http, String base) {
        this.http = http;
        this.api = base + "/v2";
    }

    @Override
    public Resolution resolve(String ref, Manifest.Content wanted, String exactVersionId, Manifest.ServerSpec server) {
        JsonNode project = http.jsonOrNull(api + "/project/" + enc(ref));
        if (project == null) throw new EvokerException("no Modrinth project \"" + ref + "\"");
        String slug = project.path("slug").asString();
        // Only when choosing a version, so re-resolving an already locked entry doesn't repeat the warning.
        if (exactVersionId == null && project.path("server_side").asString().equals("unsupported")) {
            Main.warn("modrinth:" + slug + " is client-side only; it does nothing on a server");
        }
        JsonNode chosen = exactVersionId != null
                ? http.json(api + "/version/" + enc(exactVersionId))
                : choose(slug, project.path("id").asString(), wanted.version(), server);

        String type = type(chosen, server.software());
        if (type == null) {
            throw new EvokerException("modrinth:" + slug + " " + chosen.path("version_number").asString()
                    + " does not run on " + server.software());
        }
        JsonNode file = chosen.path("files").get(0);
        for (JsonNode f : chosen.path("files")) {
            if (f.path("primary").asBoolean()) file = f;
        }
        if (file == null) throw new EvokerException("modrinth:" + slug + " version has no files");

        var dependencies = new ArrayList<Dependency>();
        for (JsonNode d : chosen.path("dependencies")) {
            String kind = d.path("dependency_type").asString();
            String projectId = d.path("project_id").asString(null);
            String versionId = d.path("version_id").asString(null);
            if (!kind.equals("required") && !kind.equals("incompatible")) continue; // optional, embedded
            if (projectId == null && versionId != null) {
                projectId = http.json(api + "/version/" + enc(versionId)).path("project_id").asString();
            }
            if (projectId == null) {
                if (kind.equals("required")) {
                    Main.warn("modrinth:" + slug + " requires " + d.path("file_name").asString("a file")
                            + " from outside Modrinth; add it yourself");
                }
                continue;
            }
            dependencies.add(new Dependency(projectId, versionId, kind.equals("incompatible")));
        }

        var entry = new Lock.Entry(type, project.path("id").asString(), chosen.path("id").asString(),
                chosen.path("version_number").asString(), file.path("url").asString(), null,
                file.path("hashes").path("sha1").asString(null), null);
        return new Resolution(slug, entry, chosen.path("date_published").asString(), dependencies,
                "SHA-512", file.path("hashes").path("sha512").asString(null));
    }

    /** Pinned: that version (warning if not marked compatible). latest: newest compatible, preferring releases. */
    private JsonNode choose(String slug, String projectId, String version, Manifest.ServerSpec server) {
        String versions = api + "/project/" + projectId + "/version";
        List<JsonNode> compatible = byPreference(
                http.json(versions + "?game_versions=" + enc("[\"" + server.version() + "\"]")), server.software());
        if (version.equals("latest")) {
            if (compatible.isEmpty()) {
                throw new EvokerException("modrinth:" + slug + " has no version for " + server.software() + " "
                        + server.version());
            }
            return compatible.stream().filter(v -> v.path("version_type").asString().equals("release"))
                    .findFirst().orElse(compatible.get(0));
        }
        for (JsonNode v : compatible) {
            if (matches(v, version)) return v;
        }
        for (JsonNode v : http.json(versions)) {
            if (matches(v, version) && type(v, server.software()) != null) {
                Main.warn("modrinth:" + slug + " " + version + " is not marked compatible with "
                        + server.software() + " " + server.version());
                return v;
            }
        }
        throw new EvokerException("modrinth:" + slug + " has no version " + version + " for " + server.software());
    }

    private static boolean matches(JsonNode v, String version) {
        return v.path("version_number").asString().equals(version) || v.path("id").asString().equals(version);
    }

    /**
     * Versions usable on this software, newest first, grouped by preference: the software's own loader
     * (mod or plugin) first, then data packs, then resource packs. Only the best non-empty group is kept,
     * so a project published both as a mod and as a data pack installs as the mod.
     */
    private static List<JsonNode> byPreference(JsonNode versions, String software) {
        var sorted = new ArrayList<JsonNode>();
        versions.forEach(sorted::add);
        sorted.sort(Comparator.comparing((JsonNode v) -> v.path("date_published").asString()).reversed());
        for (String type : List.of("mod", "plugin", "datapack", "resourcepack")) {
            List<JsonNode> group = sorted.stream().filter(v -> type.equals(type(v, software))).toList();
            if (!group.isEmpty()) return group;
        }
        return List.of();
    }

    /** What a version is on this software: mod, plugin, datapack, resourcepack, or null if unusable. */
    static String type(JsonNode version, String software) {
        var loaders = new ArrayList<String>();
        version.path("loaders").forEach(l -> loaders.add(l.asString()));
        for (String loader : loaders(software)) {
            if (loaders.contains(loader)) return modded(software) ? "mod" : "plugin";
        }
        if (loaders.contains("datapack")) return "datapack";
        if (loaders.contains("minecraft")) return "resourcepack";
        return null;
    }

    /** Modrinth loaders whose mods or plugins run on this software. */
    static List<String> loaders(String software) {
        return switch (software) {
            case "fabric" -> List.of("fabric");
            case "quilt" -> List.of("quilt", "fabric");
            case "neoforge" -> List.of("neoforge");
            case "spigot" -> List.of("spigot", "bukkit");
            case "paper" -> List.of("paper", "spigot", "bukkit");
            case "purpur" -> List.of("purpur", "paper", "spigot", "bukkit");
            default -> List.of();
        };
    }

    private static boolean modded(String software) {
        return List.of("fabric", "quilt", "neoforge").contains(software);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
