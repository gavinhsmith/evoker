package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
            case "quilt" -> quilt(spec.version(), spec.build());
            case "neoforge" -> neoforge(spec.version(), spec.build());
            case "spigot" -> spigot(spec.version(), spec.build());
            default -> throw new EvokerException("unknown server software: " + spec.software());
        };
    }

    /**
     * The jar evoker downloads: the server itself, or for quilt/neoforge/spigot the installer that builds it.
     * Fabric's launcher and the Quilt installer put vanilla into server.jar themselves.
     */
    static String jarName(String software) {
        return switch (software) {
            case "fabric" -> "fabric-server-launch.jar";
            case "quilt" -> "quilt-installer.jar";
            case "neoforge" -> "neoforge-installer.jar";
            case "spigot" -> "BuildTools.jar";
            default -> "server.jar";
        };
    }

    static final String STAMP = ".evoker-installed";
    static final String INSTALLER_LOG = ".evoker-installer.log";
    static final String BUILDTOOLS_DIR = ".evoker-buildtools";

    /**
     * For installer-based software, runs the installer unless the stamp file says this exact
     * software/version/build is already installed. Other software needs nothing.
     */
    static void runInstaller(Path dir, Lock.Locked locked, Manifest.Settings settings) {
        Path cwd = dir;
        List<String> args = switch (locked.software()) {
            case "quilt" -> List.of("install", "server", locked.version(), locked.build(), "--download-server",
                    "--install-dir=.");
            case "neoforge" -> List.of("--installServer", ".");
            case "spigot" -> {
                // BuildTools clones and compiles in its own folder, then writes server.jar into the server folder.
                cwd = dir.resolve(BUILDTOOLS_DIR);
                yield List.of("--rev", locked.build(), "--compile", "spigot", "--output-dir",
                        dir.toAbsolutePath().toString(), "--final-name", "server.jar", "--nogui");
            }
            default -> null;
        };
        if (args == null) return;
        String stamp = locked.software() + " " + locked.version() + " " + locked.build() + " " + locked.sha256();
        Path stampFile = dir.resolve(STAMP);
        try {
            if (Files.exists(stampFile) && Files.readString(stampFile).equals(stamp)) return;
            Files.createDirectories(cwd);
            var command = new ArrayList<>(List.of(settings.java(), "-jar",
                    dir.resolve(jarName(locked.software())).toAbsolutePath().toString()));
            command.addAll(args);
            Main.log("running the " + locked.software() + " installer (output also saved to " + INSTALLER_LOG + ")"
                    + (locked.software().equals("spigot") ? "; building spigot takes several minutes" : ""));
            Process p = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close(); // installers need no input
            // Show the output live and keep a copy for after it scrolls away.
            try (InputStream in = p.getInputStream(); OutputStream log = Files.newOutputStream(dir.resolve(INSTALLER_LOG))) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    System.out.write(buf, 0, n);
                    System.out.flush();
                    log.write(buf, 0, n);
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                throw new EvokerException(locked.software() + " installer failed (exit " + exit + "); see " + INSTALLER_LOG);
            }
            Files.writeString(stampFile, stamp);
        } catch (IOException e) {
            throw new EvokerException("cannot run the " + locked.software() + " installer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvokerException("interrupted", e);
        }
    }

    static List<String> command(Lock.Locked locked, Manifest.Settings settings, Path dir) {
        var command = new ArrayList<String>();
        command.add(settings.java());
        command.addAll(settings.jvmArgs());
        switch (locked.software()) {
            case "quilt" -> command.addAll(List.of("-jar", "quilt-server-launch.jar"));
            case "neoforge" -> {
                // What NeoForge's own run.sh / run.bat do.
                if (Files.exists(dir.resolve("user_jvm_args.txt"))) command.add("@user_jvm_args.txt");
                String args = System.getProperty("os.name").startsWith("Windows") ? "win_args.txt" : "unix_args.txt";
                command.add("@libraries/net/neoforged/neoforge/" + locked.build() + "/" + args);
            }
            case "spigot" -> command.addAll(List.of("-jar", "server.jar"));
            default -> command.addAll(List.of("-jar", jarName(locked.software())));
        }
        command.add("nogui");
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
            loader = stableOrFirst(loaders.valueStream().map(l -> l.path("loader")).toList()).path("version").asString();
        }
        String installer = stableOrFirst(http.json(base + "/installer").valueStream().toList()).path("version").asString();
        // Fabric publishes no checksum for the launcher jar; evoker's own sha256 still pins it.
        return new Resolved(loader, base + "/loader/" + version + "/" + loader + "/" + installer + "/server/jar",
                null, null);
    }

    /** Quilt: the loader version, installed by the Quilt installer (which also fetches vanilla). */
    private Resolved quilt(String version, String loader) {
        String base = apis.quilt() + "/v3/versions";
        var available = new HashSet<String>();
        http.json(base + "/loader/" + version).forEach(l -> available.add(l.path("loader").path("version").asString()));
        if (available.isEmpty()) throw new EvokerException("quilt has no loader for " + version);
        if (loader.equals("latest")) {
            // The per-version list is unordered; the global list is newest first.
            String newest = null;
            for (JsonNode l : http.json(base + "/loader")) {
                String v = l.path("version").asString();
                if (!available.contains(v)) continue;
                if (newest == null) newest = v;
                if (!v.contains("-")) {
                    newest = v;
                    break;
                }
            }
            loader = newest != null ? newest : available.iterator().next();
        }
        // Quilt meta's installer "hashes" don't match the published jar (checked 2026-10), so they aren't verified;
        // evoker's own sha256 still pins the installer in the lock.
        JsonNode installer = http.json(base + "/installer").get(0);
        return new Resolved(loader, installer.path("url").asString(), null, null);
    }

    /** NeoForge: versions are named after the game version (1.21.4 → 21.4.x, 26.1 → 26.1.0.x). */
    private Resolved neoforge(String version, String build) {
        if (build.equals("latest")) {
            String prefix = neoforgePrefix(version) + ".";
            String newest = null, newestStable = null;
            for (JsonNode v : http.json(apis.neoforge() + "/api/maven/versions/releases/net/neoforged/neoforge")
                    .path("versions")) {
                String name = v.asString();
                if (!name.startsWith(prefix)) continue;
                newest = name; // oldest first, so the last match is the newest
                if (!name.contains("-")) newestStable = name;
            }
            if (newest == null) throw new EvokerException("neoforge has no version for " + version);
            build = newestStable != null ? newestStable : newest;
        }
        // ponytail: no upstream checksum fetched; evoker's own sha256 still pins the installer
        return new Resolved(build, apis.neoforge() + "/releases/net/neoforged/neoforge/" + build + "/neoforge-"
                + build + "-installer.jar", null, null);
    }

    /** Spigot: build is the Spigot build number (BuildTools --rev); evoker locks the BuildTools jar that builds it. */
    private Resolved spigot(String version, String build) {
        if (build.equals("latest")) {
            JsonNode info = http.jsonOrNull(apis.spigot() + "/versions/" + version + ".json");
            if (info == null) throw new EvokerException("spigot has no build for " + version);
            build = info.path("name").asString();
        }
        String jenkins = apis.spigot() + "/jenkins/job/BuildTools/";
        String tools = http.json(jenkins + "lastSuccessfulBuild/api/json").path("number").asString();
        // ponytail: no upstream checksum (Jenkins publishes none); evoker's own sha256 pins BuildTools
        return new Resolved(build, jenkins + tools + "/artifact/target/BuildTools.jar", null, null);
    }

    static String neoforgePrefix(String version) {
        String v = version.startsWith("1.") ? version.substring(2) : version;
        boolean old = version.startsWith("1.");
        long dots = v.chars().filter(c -> c == '.').count();
        if (old && dots == 0) return v + ".0";
        if (!old && dots == 1) return v + ".0";
        return v;
    }

    private static JsonNode stableOrFirst(List<JsonNode> list) {
        return list.stream().filter(n -> n.path("stable").asBoolean()).findFirst().orElse(list.get(0));
    }
}
