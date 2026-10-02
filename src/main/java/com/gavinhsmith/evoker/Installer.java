package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

/** Puts files on disk and keeps server.properties / eula.txt in line with evoker.json. */
final class Installer {
    private final Path dir;
    private final Http http;

    Installer(Path dir, Http http) {
        this.dir = dir;
        this.http = http;
    }

    /**
     * Makes target hold the file at url and returns the sha256 to lock.
     * <p>
     * With a locked hash: a matching file on disk is kept without downloading; a download that no longer
     * matches the lock (the upstream file changed) is discarded with a warning and the lock is kept.
     */
    String fetch(String label, String url, Path target, String lockedSha256, String algo, String expected) {
        if (lockedSha256 != null && Files.exists(target) && lockedSha256.equals(Http.sha256(target))) {
            return lockedSha256;
        }
        Main.log("downloading " + label);
        Http.Fetched fetched = http.download(url, target.getParent(), algo, expected);
        try {
            if (lockedSha256 != null && !lockedSha256.equals(fetched.sha256())) {
                Main.warn(label + ": download does not match " + Lock.FILE + " (upstream file changed?), "
                        + (Files.exists(target) ? "keeping the existing file" : "not installing it"));
                return lockedSha256;
            }
            Files.move(fetched.file(), target, StandardCopyOption.REPLACE_EXISTING);
            return fetched.sha256();
        } catch (IOException e) {
            throw new EvokerException("cannot write " + target + ": " + e.getMessage(), e);
        } finally {
            Http.deleteQuietly(fetched.file());
        }
    }

    /**
     * Where a content entry lives: {@code <source>-<projectId>.<ext>} in mods/, plugins/ or the world's datapacks/.
     * Resource packs are not stored (server.properties points at their URL), so they have no path.
     */
    Path path(String key, Lock.Entry entry, String levelName) {
        String name = Resolver.source(key) + "-" + entry.projectId();
        return switch (entry.type()) {
            case "mod" -> dir.resolve("mods").resolve(name + ".jar");
            case "plugin" -> dir.resolve("plugins").resolve(name + ".jar");
            case "datapack" -> dir.resolve(levelName).resolve("datapacks").resolve(name + ".zip");
            default -> null;
        };
    }

    /** The world folder, from server.properties (Minecraft's default is "world"). */
    String levelName() {
        Path file = dir.resolve("server.properties");
        if (!Files.exists(file)) return "world";
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
        String name = props.getProperty("level-name", "").trim();
        return name.isEmpty() ? "world" : name;
    }

    void delete(String label, Path file) {
        try {
            if (Files.deleteIfExists(file)) Main.log("deleted " + dir.relativize(file) + " (" + label + ")");
        } catch (IOException e) {
            Main.warn("cannot delete " + file + ": " + e.getMessage());
        }
    }

    /** Sets the given keys in server.properties, leaving every other key alone. Writes only on change. */
    void properties(Map<String, Object> wanted) {
        if (wanted.isEmpty()) return;
        Path file = dir.resolve("server.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            boolean changed = false;
            for (var e : wanted.entrySet()) {
                String value = String.valueOf(e.getValue());
                if (!value.equals(props.getProperty(e.getKey()))) {
                    props.setProperty(e.getKey(), value);
                    changed = true;
                }
            }
            if (!changed) return;
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Minecraft server properties (keys from evoker.json are set by evoker)");
            }
        } catch (IOException e) {
            throw new EvokerException("cannot update " + file + ": " + e.getMessage(), e);
        }
    }

    void acceptEula() {
        Path file = dir.resolve("eula.txt");
        try {
            if (Files.exists(file) && Files.readString(file).contains("eula=true")) return;
            Files.writeString(file, "# Accepted via \"eula\": true in evoker.json (https://aka.ms/MinecraftEULA)\neula=true\n");
        } catch (IOException e) {
            throw new EvokerException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
