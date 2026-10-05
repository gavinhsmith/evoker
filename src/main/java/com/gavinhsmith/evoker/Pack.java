package com.gavinhsmith.evoker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** A published pack, as servers and clients fetch it: evoker.json, evoker.lock and maybe an icon. */
record Pack(Manifest manifest, Lock lock) {
    /** Where a pack comes from: a pack URL (the folder holding evoker.json) or a local pack folder. */
    record Source(String url, String path) {
        /** A URL or path ending in evoker.json means its folder. */
        static Source of(String url, String path) {
            if (url != null) {
                if (url.endsWith("/" + Manifest.FILE)) url = url.substring(0, url.length() - Manifest.FILE.length());
                return new Source(url.endsWith("/") ? url : url + "/", null);
            }
            Path p = Path.of(path).toAbsolutePath().normalize();
            if (p.getFileName() != null && p.getFileName().toString().equals(Manifest.FILE)) p = p.getParent();
            return new Source(null, p.toString());
        }

        @Override
        public String toString() {
            return url != null ? url : path;
        }
    }

    /** A pack icon: its bytes and extension. */
    record Icon(byte[] data, String extension) {}

    /** Fetches evoker.json and evoker.lock; a pack without a lock can't be installed. */
    static Pack fetch(Http http, Source source) {
        if (source.url() != null) {
            JsonNode manifest = http.jsonOrNull(source.url() + Manifest.FILE);
            if (manifest == null) throw new EvokerException("no " + Manifest.FILE + " at " + source.url());
            JsonNode lock = http.jsonOrNull(source.url() + Lock.FILE);
            if (lock == null) throw new EvokerException("the pack at " + source.url() + " has no " + Lock.FILE);
            return new Pack(Manifest.of(manifest, source.url() + Manifest.FILE), Lock.of(lock, source.url() + Lock.FILE));
        }
        Path pack = Path.of(source.path());
        if (!Files.exists(pack.resolve(Lock.FILE))) throw new EvokerException("the pack in " + pack + " has no " + Lock.FILE);
        return new Pack(Manifest.read(pack), Lock.read(pack));
    }

    /** icon.png or icon.jpg next to evoker.json, or null. */
    static Icon icon(Http http, Source source) {
        for (String extension : List.of("png", "jpg")) {
            byte[] data;
            if (source.url() != null) {
                data = http.bytesOrNull(source.url() + "icon." + extension);
            } else {
                Path file = Path.of(source.path(), "icon." + extension);
                try {
                    data = Files.exists(file) ? Files.readAllBytes(file) : null;
                } catch (IOException e) {
                    throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
                }
            }
            if (data != null) return new Icon(data, extension);
        }
        return null;
    }
}
