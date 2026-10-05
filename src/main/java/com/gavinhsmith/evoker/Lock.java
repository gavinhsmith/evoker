package com.gavinhsmith.evoker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** evoker.lock: exactly which files a pack resolves to. Written only by evoker. */
record Lock(int lockVersion, Game game, Server server, Map<String, Entry> content) {
    static final String FILE = "evoker.lock";
    static final int VERSION = 2;

    Lock {
        content = content == null ? new TreeMap<>() : new TreeMap<>(content);
    }

    static Lock empty() {
        return new Lock(VERSION, null, null, null);
    }

    static Lock read(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) return empty();
        try {
            JsonNode tree = Json.MAPPER.readTree(file);
            int version = tree.path("lockVersion").asInt(1);
            if (version > VERSION) {
                throw new EvokerException(FILE + " was written by a newer evoker (lockVersion " + version + ")");
            }
            if (version < VERSION) {
                throw new EvokerException(FILE + " is from evoker 0.3 or older; start the pack again with evoker create");
            }
            return Json.MAPPER.treeToValue(tree, Lock.class);
        } catch (JacksonException e) {
            throw new EvokerException("invalid " + FILE + ": " + e.getOriginalMessage(), e);
        }
    }

    void write(Path dir) {
        Json.write(dir.resolve(FILE), this);
    }

    /** The resolved game: exact loader build (null for vanilla). */
    record Game(String version, String loader, String build) {}

    /** The server jar or installer, when the pack has a server side. hash is null when upstream publishes none. */
    record Server(String url, String hash) {}

    /**
     * A resolved content entry. hash is {@code <algorithm>:<hex>}; sha1 is kept for resource packs only
     * (server.properties needs it). title / description are kept for optional entries only.
     */
    record Entry(String type, List<String> sides, Boolean optional, String title, String description,
                 String projectId, String versionId, String version, String url, String hash, String sha1,
                 List<String> requiredBy) {
        Entry withHash(String hash, String sha1) {
            return new Entry(type, sides, optional, title, description, projectId, versionId, version, url, hash, sha1,
                    requiredBy);
        }

        Entry withSides(List<String> sides) {
            return new Entry(type, sides, optional, title, description, projectId, versionId, version, url, hash, sha1,
                    requiredBy);
        }

        /** Optional entries keep their title and description, for asking players. */
        Entry withOptional(boolean optional) {
            return new Entry(type, sides, optional ? true : null, optional ? title : null, optional ? description : null,
                    projectId, versionId, version, url, hash, sha1, requiredBy);
        }

        /** An empty list is stored as null, so the lock omits it. */
        Entry withRequiredBy(List<String> requiredBy) {
            return new Entry(type, sides, optional, title, description, projectId, versionId, version, url, hash, sha1,
                    requiredBy == null || requiredBy.isEmpty() ? null : List.copyOf(requiredBy));
        }
    }
}
