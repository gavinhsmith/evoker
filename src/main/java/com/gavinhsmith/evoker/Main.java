package com.gavinhsmith.evoker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class Main {
    static final String VERSION =
            Objects.requireNonNullElse(Main.class.getPackage().getImplementationVersion(), "dev");

    private static final String USAGE = """
            usage: evoker <command>

              add <slug> [version]   add content (e.g. sodium, modrinth:lithium) and install it
              remove <slug>          remove content and whatever only it needed
              install                download whatever evoker.json / evoker.lock say is missing
              start                  install, then run the server
              version                print the evoker version
            """;

    private final Path dir;
    private final Server server;
    private final Installer installer;
    private final Resolver resolver;

    Main(Path dir, Apis apis) {
        Http http = new Http();
        this.dir = dir;
        this.server = new Server(http, apis);
        this.installer = new Installer(dir, http);
        this.resolver = new Resolver(Map.of("modrinth", new Modrinth(http, apis.modrinth())));
    }

    public static void main(String[] args) {
        System.exit(run(Path.of("").toAbsolutePath(), Apis.DEFAULT, args));
    }

    /** Runs one command in dir and returns the exit code. */
    static int run(Path dir, Apis apis, String... args) {
        if (args.length == 0) {
            System.err.print(USAGE);
            return 1;
        }
        Main main = new Main(dir, apis);
        try {
            switch (args[0]) {
                case "add" -> main.add(arg(args, 1, "add <slug> [version]"), args.length > 2 ? args[2] : "latest");
                case "remove" -> main.remove(arg(args, 1, "remove <slug>"));
                case "install" -> main.install(Manifest.read(dir), false, key -> false);
                case "start" -> {
                    return main.start();
                }
                case "version", "--version" -> System.out.println("evoker " + VERSION);
                case "help", "-h", "--help" -> System.out.print(USAGE);
                default -> {
                    System.err.print("unknown command: " + args[0] + "\n\n" + USAGE);
                    return 1;
                }
            }
            return 0;
        } catch (EvokerException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static String arg(String[] args, int i, String usage) {
        if (args.length <= i) throw new EvokerException("usage: evoker " + usage);
        return args[i];
    }

    static void log(String message) {
        System.out.println("evoker: " + message);
    }

    static void warn(String message) {
        System.err.println("evoker: warning: " + message);
    }

    void add(String ref, String version) {
        Manifest manifest = Manifest.read(dir);
        String key = Manifest.key(ref);
        var content = new LinkedHashMap<>(manifest.content());
        content.put(key, new Manifest.Content(version, null, null));
        Manifest updated = manifest.withContent(content);
        install(updated, false, key::equals);
        updated.write(dir);
        log("added " + key + " to " + Manifest.FILE);
    }

    void remove(String ref) {
        Manifest manifest = Manifest.read(dir);
        String key = Manifest.key(ref);
        if (!manifest.content().containsKey(key)) {
            Lock.Entry dep = Lock.read(dir).content().get(key);
            throw new EvokerException(key + " is not in " + Manifest.FILE
                    + (dep != null && dep.requiredBy() != null ? " (it is required by " + dep.requiredBy() + ")" : ""));
        }
        var content = new LinkedHashMap<>(manifest.content());
        content.remove(key);
        Manifest updated = manifest.withContent(content);
        install(updated, false, k -> false);
        updated.write(dir);
        log("removed " + key + " from " + Manifest.FILE);
    }

    /**
     * Brings the disk in line with evoker.json and evoker.lock. Content is only re-resolved (network) when
     * evoker.json asks for something the lock doesn't have, or refresh selects it; otherwise the lock is used as is.
     */
    Lock install(Manifest manifest, boolean updateServer, Predicate<String> refresh) {
        Lock before = Lock.read(dir);
        Lock.Locked locked = installServer(manifest.server(), before.server(), updateServer);

        Map<String, Source.Resolution> content = resolveContent(manifest, before.content(), refresh);

        var properties = new LinkedHashMap<>(resourcePack(content, manifest.properties()));
        properties.putAll(manifest.properties());
        installer.properties(properties);
        if (manifest.eula()) installer.acceptEula();

        String levelName = installer.levelName();
        var entries = new TreeMap<String, Lock.Entry>();
        var paths = new HashSet<Path>();
        content.forEach((key, r) -> {
            Lock.Entry e = r.entry();
            Path path = installer.path(key, e, levelName);
            if (path != null) {
                Lock.Entry old = before.content().get(key);
                String lockedSha = old != null && Objects.equals(old.url(), e.url()) ? old.sha256() : null;
                e = e.withSha256(installer.fetch(key + " " + e.version(), e.url(), path, lockedSha, r.algo(), r.hash()));
                paths.add(path);
            }
            entries.put(key, e);
        });
        before.content().forEach((key, old) -> {
            Path path = installer.path(key, old, levelName);
            if (path != null && !paths.contains(path)) installer.delete(key, path);
        });

        Lock lock = new Lock(Lock.VERSION, locked, entries);
        if (!lock.equals(before)) lock.write(dir);
        return lock;
    }

    private Map<String, Source.Resolution> resolveContent(Manifest manifest, Map<String, Lock.Entry> locked,
                                                          Predicate<String> refresh) {
        boolean upToDate = manifest.content().entrySet().stream().allMatch(e -> {
            Lock.Entry have = locked.get(e.getKey());
            return have != null && !refresh.test(e.getKey()) && Resolver.satisfies(have, e.getValue());
        });
        if (!upToDate) return resolver.resolve(manifest, locked, refresh);
        var kept = new TreeMap<String, Source.Resolution>();
        Resolver.prune(locked, manifest.content().keySet())
                .forEach((key, e) -> kept.put(key, new Source.Resolution(null, e, null, null, null, null)));
        return kept;
    }

    /** server.properties keys for the (single) resource pack, unless evoker.json sets them itself. */
    private static Map<String, Object> resourcePack(Map<String, Source.Resolution> content, Map<String, Object> props) {
        var packs = content.entrySet().stream().filter(e -> e.getValue().entry().type().equals("resourcepack")).toList();
        if (packs.isEmpty()) return Map.of();
        String key = packs.get(0).getKey();
        if (packs.size() > 1) warn("server.properties holds one resource pack; using " + key);
        if (props.containsKey("resource-pack")) {
            warn(key + " is ignored: evoker.json sets resource-pack in properties");
            return Map.of();
        }
        Lock.Entry pack = packs.get(0).getValue().entry();
        return Map.of("resource-pack", pack.url(), "resource-pack-sha1", Objects.requireNonNullElse(pack.sha1(), ""));
    }

    private Lock.Locked installServer(Manifest.ServerSpec want, Lock.Locked have, boolean update) {
        boolean stale = have == null
                || !want.software().equals(have.software())
                || !want.version().equals(have.version())
                || (!want.build().equals("latest") && !want.build().equals(have.build()));
        Lock.Locked locked = have;
        Server.Resolved resolved = null;
        if (stale || (update && want.build().equals("latest"))) {
            resolved = server.resolve(want);
            if (stale || !resolved.url().equals(have.url())) {
                locked = new Lock.Locked(want.software(), want.version(), resolved.build(), resolved.url(), null);
            } else {
                resolved = null;
            }
        }
        String label = locked.software() + " " + locked.version() + (locked.build() == null ? "" : " build " + locked.build());
        String sha256 = installer.fetch(label, locked.url(), dir.resolve(Server.jarName(locked.software())),
                locked.sha256(), resolved == null ? null : resolved.algo(), resolved == null ? null : resolved.hash());
        return locked.withSha256(sha256);
    }

    /** Installs, then runs the server as a child process and returns its exit code. */
    int start() {
        Manifest manifest = Manifest.read(dir);
        install(manifest, manifest.evoker().autoUpdateServer(), key -> false);
        var command = Server.command(manifest.server().software(), manifest.evoker());
        log("starting " + String.join(" ", command));
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
}
