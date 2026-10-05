package com.gavinhsmith.evoker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** A folder a pack is installed into as a server: installing, updating and running it. evoker's state is in .evoker/. */
final class ServerFolder {
    static final String STATE = ".evoker";

    /** Where the pack comes from: a pack URL (the folder holding evoker.json) or a local pack folder. */
    record Source(String url, String path) {
        /** A URL ending in /evoker.json means its folder. */
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

    /** What evoker installed: the server jar (pinned by hash) and every file it owns, relative to the folder. */
    record Installed(Lock.Server server, List<String> files) {
        Installed {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    record Pack(Manifest manifest, Lock lock) {}

    private final Path dir;
    private final Path state;
    private final Http http;
    private final Installer installer;

    ServerFolder(Path dir, Http http) {
        this.dir = dir;
        this.state = dir.resolve(STATE);
        this.http = http;
        this.installer = new Installer(dir, http);
    }

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

    /** install server: the pack's server and server-side content into this folder, then the EULA question. */
    void install(Source source, boolean acceptEula) {
        if (Files.exists(dir.resolve(Manifest.FILE))) {
            throw new EvokerException(dir + " is a pack folder; install the server into another folder"
                    + " (evoker install server --local " + dir + ")");
        }
        Source existing = source();
        if (existing != null && !existing.equals(source)) {
            throw new EvokerException("this folder already has a pack installed from " + existing);
        }
        Pack pack = fetch(http, source);
        if (!pack.manifest().sides().contains("server")) {
            throw new EvokerException(pack.manifest().name() + " is a client pack; it can't be installed as a server");
        }
        Json.write(state.resolve("source.json"), source);
        apply(pack);
        if (acceptEula) {
            installer.acceptEula();
        } else if (!installer.eulaAccepted()) {
            if (Main.ask("Do you accept the Minecraft EULA (https://aka.ms/MinecraftEULA)?")) {
                installer.acceptEula();
            } else {
                Main.warn("the Minecraft EULA isn't accepted, so the server won't start: run evoker install server"
                        + " again with --accept-eula, or set eula=true in eula.txt");
            }
        }
        Main.log("installed " + pack.manifest().name() + "; start it with: evoker server start");
    }

    /**
     * server update: fetches the pack again and applies the difference (or with listOnly, prints it). If the pack
     * can't be fetched, warns and keeps what is installed.
     */
    void update(boolean listOnly, String output) {
        Source source = requireSource();
        Pack pack;
        try {
            pack = fetch(http, source);
        } catch (EvokerException e) {
            Main.warn("cannot fetch the pack (" + e.getMessage() + "); keeping what is installed");
            return;
        }
        Lock before = serverSide(installedLock());
        Manifest noPins = pack.manifest().withContent(Map.of());
        if (listOnly) {
            Main.printChanges(before, serverSide(pack.lock()), noPins, Set.of(), output);
            if (output.equals("text")) Main.log("nothing was changed (server update list)");
            return;
        }
        apply(pack);
        Main.printChanges(before, serverSide(pack.lock()), noPins, Set.of(), "text");
    }

    /**
     * Puts the locked server and every server-side entry in place, deletes files evoker installed that are no
     * longer in the pack, and records it all. A content file that can't be downloaded keeps what is there.
     */
    private void apply(Pack pack) {
        Lock lock = pack.lock();
        if (lock.game() == null || lock.server() == null) {
            throw new EvokerException("the pack's " + Lock.FILE + " has no server; its author needs to run evoker update");
        }
        Installed before = installed();
        Lock.Game game = lock.game();
        var files = new TreeSet<String>();

        String jar = Server.jarName(game.loader());
        String label = game.loader() + " " + game.version() + (game.build() == null ? "" : " build " + game.build());
        String pin = lock.server().hash();
        // Upstreams without a checksum: pin the first download of this URL.
        if (pin == null && before.server() != null && lock.server().url().equals(before.server().url())) {
            pin = before.server().hash();
        }
        String hash = installer.fetch(label, lock.server().url(), dir.resolve(jar), pin);
        files.add(jar);
        Server.runInstaller(dir, game.loader(), game.version(), game.build(), hash, Config.server(dir).string("java"));

        String levelName = installer.levelName();
        String resourcePack = null;
        for (var e : lock.content().entrySet()) {
            String key = e.getKey();
            Lock.Entry entry = e.getValue();
            if (!entry.sides().contains("server")) continue;
            if (entry.type().equals("resourcepack")) {
                if (resourcePack == null) {
                    resourcePack = key;
                    installer.properties(Map.of("resource-pack", entry.url(),
                            "resource-pack-sha1", Objects.requireNonNullElse(entry.sha1(), "")));
                } else {
                    Main.warn("server.properties holds one resource pack; using " + resourcePack + ", not " + key);
                }
                continue;
            }
            Path path = installer.path(key, entry, levelName);
            if (path == null) continue;
            try {
                installer.fetch(key + " " + Objects.requireNonNullElse(entry.version(), ""), entry.url(), path, entry.hash());
            } catch (EvokerException ex) {
                Main.warn(ex.getMessage() + "; keeping what is installed");
            }
            files.add(relative(path));
        }
        for (String old : before.files()) {
            if (!files.contains(old)) installer.delete("no longer in the pack", dir.resolve(old));
        }

        Json.write(state.resolve("installed.json"), new Installed(new Lock.Server(lock.server().url(), hash),
                List.copyOf(files)));
        pack.manifest().write(state);
        lock.write(state);
    }

    /** server start: updates (unless updateOnStart is off; never blocking), then runs the server; its exit code. */
    int start() {
        requireSource();
        if (Config.server(dir).bool("updateOnStart")) {
            try {
                update(false, "text");
            } catch (EvokerException e) {
                Main.warn(e.getMessage() + "; starting with what is installed");
            }
        }
        List<String> command = command();
        Main.log("starting " + String.join(" ", command));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        } catch (IOException e) {
            throw new EvokerException("cannot start the server: " + e.getMessage(), e);
        }
        // Ctrl+C reaches the server too (same console), so on shutdown just wait for it to save and exit.
        // On Unix also forward a plain SIGTERM (e.g. from systemd); Windows' destroy() is a hard kill, so skip it there.
        Thread hook = new Thread(() -> {
            if (!process.isAlive()) return;
            if (!System.getProperty("os.name").startsWith("Windows")) process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                // JVM is exiting anyway
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvokerException("interrupted", e);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // the hook is already running
            }
        }
    }

    /** The launch command for what is installed, from the server's java / jvmArgs settings. Offline. */
    List<String> command() {
        Lock lock = installedLock();
        if (lock.game() == null) throw new EvokerException("nothing is installed here yet (run evoker install server)");
        Config config = Config.server(dir);
        return Server.command(lock.game().loader(), lock.game().build(), config.string("java"), config.strings("jvmArgs"), dir);
    }

    private Source source() {
        return read("source.json", Source.class);
    }

    private Source requireSource() {
        Source source = source();
        if (source == null) throw new EvokerException("no pack is installed in " + dir + " (run evoker install server)");
        return source;
    }

    private Installed installed() {
        Installed installed = read("installed.json", Installed.class);
        return installed != null ? installed : new Installed(null, null);
    }

    private Lock installedLock() {
        return Files.exists(state.resolve(Lock.FILE)) ? Lock.read(state) : Lock.empty();
    }

    private <T> T read(String name, Class<T> type) {
        Path file = state.resolve(name);
        if (!Files.exists(file)) return null;
        try {
            return Json.MAPPER.readValue(file, type);
        } catch (JacksonException e) {
            throw new EvokerException("invalid " + file + ": " + e.getOriginalMessage(), e);
        }
    }

    private String relative(Path path) {
        return dir.relativize(path).toString().replace('\\', '/');
    }

    /** The lock with only server-side entries. */
    private static Lock serverSide(Lock lock) {
        var content = new TreeMap<String, Lock.Entry>();
        lock.content().forEach((key, e) -> {
            if (e.sides() != null && e.sides().contains("server")) content.put(key, e);
        });
        return new Lock(lock.lockVersion(), lock.game(), lock.server(), content);
    }
}
