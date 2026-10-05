package com.gavinhsmith.evoker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** evoker.json: a pack, as its author describes it. */
record Manifest(String name, String side, Game game, Map<String, Content> content) {
    static final String FILE = "evoker.json";
    static final List<String> SIDES = List.of("client", "server", "both");
    static final List<String> LOADERS = List.of("vanilla", "fabric", "quilt", "neoforge", "paper", "purpur", "spigot");
    static final List<String> TYPES = List.of("mod", "plugin", "datapack", "resourcepack", "shaderpack");

    Manifest {
        if (name == null || name.isBlank()) throw new EvokerException(FILE + " is missing \"name\"");
        side = checkSide(side, "\"side\"");
        if (side == null) throw new EvokerException(FILE + " is missing \"side\" (client, server or both)");
        if (game == null) throw new EvokerException(FILE + " is missing \"game\"");
        var normalized = new LinkedHashMap<String, Content>();
        if (content != null) content.forEach((k, v) -> normalized.put(key(k), v));
        content = normalized;
    }

    /** "sodium" means "modrinth:sodium". */
    static String key(String key) {
        return key.contains(":") ? key : "modrinth:" + key;
    }

    /** The sides this pack is installed on. */
    List<String> sides() {
        return sides(side);
    }

    /** "both" → [client, server]. */
    static List<String> sides(String side) {
        return side.equals("both") ? List.of("client", "server") : List.of(side);
    }

    /** The sides in both a and b, client first. */
    static List<String> sides(Collection<String> a, Collection<String> b) {
        return List.of("client", "server").stream().filter(s -> a.contains(s) && b.contains(s)).toList();
    }

    /** Where a type of content runs, or null for mods (which say so themselves). */
    static List<String> defaultSides(String type) {
        return switch (type) {
            case "plugin", "datapack" -> List.of("server");
            case "resourcepack", "shaderpack" -> List.of("client");
            default -> null;
        };
    }

    static String checkSide(String side, String what) {
        if (side != null && !SIDES.contains(side)) throw new EvokerException(what + " must be client, server or both, not " + side);
        return side;
    }

    static String checkType(String type) {
        if (type != null && !TYPES.contains(type)) throw new EvokerException("unknown type " + type + "; one of " + TYPES);
        return type;
    }

    static Manifest read(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) throw new EvokerException("no " + FILE + " in " + dir + " (run evoker create)");
        try {
            return of(Json.MAPPER.readTree(file), FILE);
        } catch (JacksonException e) {
            throw new EvokerException("invalid " + FILE + ": " + e.getOriginalMessage(), e);
        }
    }

    /** Parses an evoker.json; where names it in errors. */
    static Manifest of(JsonNode json, String where) {
        try {
            return Json.MAPPER.treeToValue(json, Manifest.class);
        } catch (JacksonException e) {
            Throwable cause = e.getCause() instanceof EvokerException ee ? ee : e;
            throw new EvokerException("invalid " + where + ": " + cause.getMessage(), e);
        }
    }

    Manifest withContent(Map<String, Content> content) {
        return new Manifest(name, side, game, content);
    }

    Manifest withGame(Game game) {
        return new Manifest(name, side, game, content);
    }

    void write(Path dir) {
        Json.write(dir.resolve(FILE), this);
    }

    record Game(String version, String loader, String build) {
        Game {
            if (version == null || loader == null) throw new EvokerException("game needs \"version\" and \"loader\"");
            loader = loader.toLowerCase();
            if (!LOADERS.contains(loader)) throw new EvokerException("unknown loader " + loader + "; one of " + LOADERS);
            if (build == null) build = "latest";
        }
    }

    /** A content entry: a version ("latest" or pinned) plus optional settings; url entries have a url instead. */
    record Content(String version, String side, Boolean optional, String type, String url) {
        Content {
            checkSide(side, "side");
            checkType(type);
            if (Boolean.FALSE.equals(optional)) optional = null;
        }

        static Content of(String version) {
            return new Content(version, null, null, null, null);
        }

        boolean pinned() {
            return url == null && !"latest".equals(version);
        }

        boolean isOptional() {
            return Boolean.TRUE.equals(optional);
        }

        Content withVersion(String version) {
            return new Content(version, side, optional, type, url);
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static Content of(JsonNode node) {
            if (node.isString()) return of(node.asString());
            return new Content(node.path("version").asString(null), node.path("side").asString(null),
                    node.path("optional").asBoolean(false), node.path("type").asString(null),
                    node.path("url").asString(null));
        }

        /** Just the version when nothing else is set. */
        @JsonValue
        Object json() {
            if (side == null && optional == null && type == null && url == null) return version;
            var map = new LinkedHashMap<String, Object>();
            if (version != null) map.put("version", version);
            if (side != null) map.put("side", side);
            if (optional != null) map.put("optional", true);
            if (type != null) map.put("type", type);
            if (url != null) map.put("url", url);
            return map;
        }
    }
}
