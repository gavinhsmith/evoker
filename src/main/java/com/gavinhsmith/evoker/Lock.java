package com.gavinhsmith.evoker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;

/** evoker.lock: exactly what is installed. Written only by evoker. */
record Lock(int lockVersion, Locked server, Map<String, Entry> content) {
    static final String FILE = "evoker.lock";
    static final int VERSION = 1;

    Lock {
        content = content == null ? new TreeMap<>() : new TreeMap<>(content);
    }

    static Lock read(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) return new Lock(VERSION, null, null);
        Lock lock;
        try {
            lock = Json.MAPPER.readValue(file, Lock.class);
        } catch (JacksonException e) {
            throw new EvokerException("invalid " + FILE + ": " + e.getOriginalMessage(), e);
        }
        if (lock.lockVersion() > VERSION) {
            throw new EvokerException(FILE + " was written by a newer evoker (lockVersion " + lock.lockVersion() + ")");
        }
        return lock;
    }

    void write(Path dir) {
        Json.write(dir.resolve(FILE), this);
    }

    Lock withServer(Locked server) {
        return new Lock(VERSION, server, content);
    }

    /** The installed server jar (or installer). sha256 is computed by evoker. */
    record Locked(String software, String version, String build, String url, String sha256) {
        Locked withSha256(String sha256) {
            return new Locked(software, version, build, url, sha256);
        }
    }

    /** An installed content entry. */
    record Entry(String type, String projectId, String versionId, String version, String url, String sha256,
                 String sha1, List<String> requiredBy) {}
}
