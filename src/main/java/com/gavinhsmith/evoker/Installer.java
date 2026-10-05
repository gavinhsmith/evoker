package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

/** Puts files into a server folder, and sets the few things evoker writes outside them (eula.txt, resource-pack). */
final class Installer {
    private final Path dir;
    private final Http http;

    Installer(Path dir, Http http) {
        this.dir = dir;
        this.http = http;
    }

    /**
     * Makes target hold the file at url, and returns its hash (lock format).
     * <p>
     * With a hash: a matching file on disk is kept without downloading, and a download that doesn't match
     * (the upstream file changed) is never installed: a warning, and whatever is there stays. Without one,
     * whatever url serves is installed and its sha256 returned, for the caller to pin.
     */
    String fetch(String label, String url, Path target, String hash) {
        String algo = hash == null ? "SHA-256" : Http.algo(hash);
        if (hash != null && Files.exists(target) && hash.equals(Http.hash(target, algo))) return hash;
        Main.log("downloading " + label);
        Http.Fetched fetched = http.download(url, target.getParent(), algo);
        try {
            if (hash != null && !hash.equals(fetched.hash())) {
                Main.warn(label + ": download does not match " + Lock.FILE + " (upstream file changed?), "
                        + (Files.exists(target) ? "keeping the existing file" : "not installing it"));
                return hash;
            }
            Files.move(fetched.file(), target, StandardCopyOption.REPLACE_EXISTING);
            return fetched.hash();
        } catch (IOException e) {
            throw new EvokerException("cannot write " + target + ": " + e.getMessage(), e);
        } finally {
            Http.deleteQuietly(fetched.file());
        }
    }

    /**
     * Where a content entry lives on a server: {@code <source>-<projectId>.<ext>} in mods/, plugins/ or the
     * world's datapacks/. Resource packs aren't stored (server.properties points at their URL), so they have no path.
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
        String name = load().getProperty("level-name", "").trim();
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
    void properties(Map<String, String> wanted) {
        Properties props = load();
        if (wanted.entrySet().stream().allMatch(e -> e.getValue().equals(props.getProperty(e.getKey())))) return;
        wanted.forEach(props::setProperty);
        Path file = dir.resolve("server.properties");
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Minecraft server properties");
        } catch (IOException e) {
            throw new EvokerException("cannot update " + file + ": " + e.getMessage(), e);
        }
    }

    private Properties load() {
        Path file = dir.resolve("server.properties");
        Properties props = new Properties();
        if (!Files.exists(file)) return props;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return props;
    }

    boolean eulaAccepted() {
        Path file = dir.resolve("eula.txt");
        try {
            return Files.exists(file) && Files.readString(file).contains("eula=true");
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    void acceptEula() {
        Path file = dir.resolve("eula.txt");
        try {
            Files.writeString(file, """
                    # Accepted with evoker install server (https://aka.ms/MinecraftEULA)
                    # evoker only writes this file. You, the server owner, remain responsible for following the
                    # Minecraft EULA and the Minecraft Usage Guidelines (https://www.minecraft.net/en-us/usage-guidelines).
                    eula=true
                    """);
        } catch (IOException e) {
            throw new EvokerException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
