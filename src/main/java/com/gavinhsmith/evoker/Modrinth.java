package com.gavinhsmith.evoker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** Modrinth (https://docs.modrinth.com/api/): mods, plugins, data packs, resource packs and shaders. */
final class Modrinth implements Source {
    private final Http http;
    private final String api;

    Modrinth(Http http, String base) {
        this.http = http;
        this.api = base + "/v2";
    }

    @Override
    public Resolution resolve(String ref, Manifest.Content wanted, String exactVersionId, Manifest.Game game) {
        JsonNode project = http.jsonOrNull(api + "/project/" + Http.enc(ref));
        if (project == null) throw new EvokerException("no Modrinth project \"" + ref + "\"");
        String slug = project.path("slug").asString();
        JsonNode chosen = exactVersionId != null
                ? http.json(api + "/version/" + Http.enc(exactVersionId))
                : choose(slug, project.path("id").asString(), wanted, game);

        String type = type(chosen, game.loader(), wanted.type());
        if (type == null) {
            throw new EvokerException("modrinth:" + slug + " " + chosen.path("version_number").asString()
                    + (wanted.type() == null ? " does not run on " + game.loader() : " is not a " + wanted.type()));
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
                projectId = http.json(api + "/version/" + Http.enc(versionId)).path("project_id").asString();
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

        String sha512 = file.path("hashes").path("sha512").asString(null);
        var entry = new Lock.Entry(type,
                sides(type, project.path("client_side").asString(""), project.path("server_side").asString("")), null,
                project.path("title").asString(null), project.path("description").asString(null),
                project.path("id").asString(), chosen.path("id").asString(), chosen.path("version_number").asString(),
                file.path("url").asString(), sha512 == null ? null : "sha512:" + sha512,
                type.equals("resourcepack") ? file.path("hashes").path("sha1").asString(null) : null, null);
        return new Resolution(slug, entry, chosen.path("date_published").asString(), dependencies);
    }

    /**
     * Where a project runs. Types decide for everything but mods; mods go by Modrinth's client_side / server_side
     * (required, optional, unsupported; anything else counts as optional): a side that is required where the
     * other is optional wins, otherwise both.
     */
    static List<String> sides(String type, String client, String server) {
        List<String> byType = Manifest.defaultSides(type);
        if (byType != null) return byType;
        String c = client.equals("required") || client.equals("unsupported") ? client : "optional";
        String s = server.equals("required") || server.equals("unsupported") ? server : "optional";
        if (c.equals("unsupported") && !s.equals("unsupported")) return List.of("server");
        if (s.equals("unsupported") && !c.equals("unsupported")) return List.of("client");
        if (c.equals(s)) return List.of("client", "server");
        return c.equals("required") ? List.of("client") : List.of("server");
    }

    /** Modrinth versions by the sha512 of one of their files, for the hashes Modrinth knows. */
    Map<String, JsonNode> versionsByHash(List<String> sha512) {
        var found = new HashMap<String, JsonNode>();
        if (sha512.isEmpty()) return found;
        http.postJson(api + "/version_files", Map.of("hashes", sha512, "algorithm", "sha512"))
                .properties().forEach(e -> found.put(e.getKey(), e.getValue()));
        return found;
    }

    /** Project slugs by project id. */
    Map<String, String> slugs(Collection<String> projectIds) {
        var slugs = new HashMap<String, String>();
        if (projectIds.isEmpty()) return slugs;
        String ids = projectIds.stream().sorted().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
        for (JsonNode p : http.json(api + "/projects?ids=" + Http.enc(ids))) {
            slugs.put(p.path("id").asString(), p.path("slug").asString());
        }
        return slugs;
    }

    /** The .mrpack of a modpack project's newest release (or newest version). */
    String packUrl(String slug) {
        JsonNode versions = http.jsonOrNull(api + "/project/" + Http.enc(slug) + "/version");
        if (versions == null || versions.isEmpty()) throw new EvokerException("no Modrinth modpack \"" + slug + "\"");
        JsonNode chosen = versions.get(0);
        for (JsonNode v : versions) {
            if (v.path("version_type").asString().equals("release")) {
                chosen = v;
                break;
            }
        }
        for (JsonNode f : chosen.path("files")) {
            if (f.path("url").asString().endsWith(".mrpack")) return f.path("url").asString();
        }
        throw new EvokerException("modrinth:" + slug + " " + chosen.path("version_number").asString() + " has no .mrpack file");
    }

    /** Pinned: that version (warning if not marked compatible). latest: newest compatible, preferring releases. */
    private JsonNode choose(String slug, String projectId, Manifest.Content wanted, Manifest.Game game) {
        String version = wanted.version();
        String versions = api + "/project/" + projectId + "/version";
        List<JsonNode> compatible = byPreference(
                http.json(versions + "?game_versions=" + Http.enc("[\"" + game.version() + "\"]")), game.loader(),
                wanted.type());
        if (version.equals("latest")) {
            if (compatible.isEmpty()) {
                throw new EvokerException("modrinth:" + slug + " has no version for " + game.loader() + " "
                        + game.version());
            }
            return compatible.stream().filter(v -> v.path("version_type").asString().equals("release"))
                    .findFirst().orElse(compatible.get(0));
        }
        for (JsonNode v : compatible) {
            if (matches(v, version)) return v;
        }
        for (JsonNode v : http.json(versions)) {
            if (matches(v, version) && type(v, game.loader(), wanted.type()) != null) {
                Main.warn("modrinth:" + slug + " " + version + " is not marked compatible with "
                        + game.loader() + " " + game.version());
                return v;
            }
        }
        throw new EvokerException("modrinth:" + slug + " has no version " + version + " for " + game.loader());
    }

    private static boolean matches(JsonNode v, String version) {
        return v.path("version_number").asString().equals(version) || v.path("id").asString().equals(version);
    }

    /**
     * Versions usable with this loader, newest first, grouped by preference: the loader's own mods or plugins
     * first, then data packs, resource packs, shaders (or only the wanted type). Only the best non-empty group is
     * kept, so a project published both as a mod and as a data pack installs as the mod.
     */
    private static List<JsonNode> byPreference(JsonNode versions, String loader, String wantedType) {
        var sorted = new ArrayList<JsonNode>();
        versions.forEach(sorted::add);
        sorted.sort(Comparator.comparing((JsonNode v) -> v.path("date_published").asString()).reversed());
        for (String type : wantedType != null ? List.of(wantedType) : Manifest.TYPES) {
            List<JsonNode> group = sorted.stream().filter(v -> types(v, loader).contains(type)).toList();
            if (!group.isEmpty()) return group;
        }
        return List.of();
    }

    /** What a version is with this loader (the wanted type, if it can be that), or null if unusable. */
    static String type(JsonNode version, String loader, String wantedType) {
        List<String> types = types(version, loader);
        if (wantedType != null) return types.contains(wantedType) ? wantedType : null;
        return types.isEmpty() ? null : types.get(0);
    }

    /** Every type a version can be with this loader, best first. */
    private static List<String> types(JsonNode version, String loader) {
        var loaders = new ArrayList<String>();
        version.path("loaders").forEach(l -> loaders.add(l.asString()));
        var types = new ArrayList<String>();
        if (loaders(loader).stream().anyMatch(loaders::contains)) types.add(modded(loader) ? "mod" : "plugin");
        if (loaders.contains("datapack")) types.add("datapack");
        if (loaders.contains("minecraft")) types.add("resourcepack");
        if (loaders.contains("iris") || loaders.contains("optifine") || loaders.contains("canvas")) types.add("shaderpack");
        return types;
    }

    /** Modrinth loaders whose mods or plugins run with this loader. */
    static List<String> loaders(String loader) {
        return switch (loader) {
            case "fabric" -> List.of("fabric");
            case "quilt" -> List.of("quilt", "fabric");
            case "neoforge" -> List.of("neoforge");
            case "spigot" -> List.of("spigot", "bukkit");
            case "paper" -> List.of("paper", "spigot", "bukkit");
            case "purpur" -> List.of("purpur", "paper", "spigot", "bukkit");
            default -> List.of();
        };
    }

    private static boolean modded(String loader) {
        return List.of("fabric", "quilt", "neoforge").contains(loader);
    }
}
