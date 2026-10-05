package com.gavinhsmith.evoker;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Files a pack ships as they are, mostly configs: overrides/ (both sides), client-overrides/ and server-overrides/
 * (which win over overrides/ on their side). Once a player or server owner changes one, it is theirs.
 */
final class Overrides {
    static final List<String> FOLDERS = List.of("overrides", "client-overrides", "server-overrides");

    private Overrides() {}

    /** Every file in the pack's override folders: pack path (e.g. "overrides/config/a.toml") → sha256. */
    static Map<String, String> scan(Path pack) {
        var files = new TreeMap<String, String>();
        for (String folder : FOLDERS) {
            Path root = pack.resolve(folder);
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    files.put(pack.relativize(file).toString().replace('\\', '/'), Http.hash(file, "SHA-256"));
                }
            } catch (IOException e) {
                throw new EvokerException("cannot read " + root + ": " + e.getMessage(), e);
            }
        }
        return files;
    }

    /** What lands on a side: target path (inside the server or game folder) → pack path. The side's own folder wins. */
    static Map<String, String> forSide(Map<String, String> overrides, String side) {
        var targets = new TreeMap<String, String>();
        for (String folder : List.of("overrides", side + "-overrides")) {
            overrides.keySet().stream().filter(p -> p.startsWith(folder + "/"))
                    .forEach(p -> targets.put(p.substring(folder.length() + 1), p));
        }
        return targets;
    }

    /**
     * Puts a side's overrides into root and returns what is installed: target path → hash. before is the previous
     * result. A missing file is installed; one evoker installed and nobody changed follows the pack (updated, or
     * deleted when the pack drops it); one that was there before evoker, or that was changed, stays.
     */
    static Map<String, String> apply(Path root, String side, Map<String, String> overrides, Map<String, String> before,
                                     Pack.Source source, Http http) {
        var installer = new Installer(root, http);
        var installed = new TreeMap<String, String>();
        forSide(overrides, side).forEach((target, packPath) -> {
            String hash = overrides.get(packPath), old = before.get(target);
            Path file = inside(root, target);
            if (!Files.exists(file)) {
                if (fetch(installer, source, packPath, file, hash)) installed.put(target, hash);
                return;
            }
            String disk = Http.hash(file, "SHA-256");
            if (disk.equals(hash)) {
                installed.put(target, hash);
            } else if (old == null) {
                Main.log("keeping " + target + ": it was there before the pack");
            } else if (disk.equals(old)) {
                installed.put(target, fetch(installer, source, packPath, file, hash) ? hash : old);
            } else {
                Main.warn("keeping your changed " + target + "; the pack's new version of it isn't installed");
                installed.put(target, old);
            }
        });
        before.forEach((target, old) -> {
            Path file = inside(root, target);
            if (installed.containsKey(target) || !Files.exists(file)) return;
            if (Http.hash(file, "SHA-256").equals(old)) installer.delete("no longer in the pack", file);
            else Main.warn("keeping your changed " + target + ", which the pack no longer has");
        });
        return installed;
    }

    /** Installs one override from the pack: downloaded from a pack URL, copied from a local pack. */
    private static boolean fetch(Installer installer, Pack.Source source, String packPath, Path file, String hash) {
        try {
            Files.createDirectories(file.getParent());
            if (source.url() != null) {
                installer.fetch(packPath, source.url() + encode(packPath), file, hash);
                return Files.exists(file) && hash.equals(Http.hash(file, "SHA-256"));
            }
            Path from = inside(Path.of(source.path()), packPath);
            if (!hash.equals(Http.hash(from, "SHA-256"))) {
                Main.warn(packPath + " changed since the pack's " + Lock.FILE + " was written; not installing it");
                return false;
            }
            Files.copy(from, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            Main.warn("cannot install " + packPath + ": " + e.getMessage());
            return false;
        } catch (EvokerException e) {
            Main.warn(e.getMessage() + "; keeping what is installed");
            return false;
        }
    }

    /** root/path, refusing paths that would leave root. */
    static Path inside(Path root, String path) {
        Path base = root.toAbsolutePath().normalize(), file = base.resolve(path).normalize();
        if (!file.startsWith(base) || file.equals(base)) throw new EvokerException("override path escapes its folder: " + path);
        return file;
    }

    /** A pack path as a URL path: each segment percent-encoded (spaces as %20). */
    static String encode(String path) {
        try {
            return new URI(null, null, path, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new EvokerException("bad override path " + path, e);
        }
    }
}
