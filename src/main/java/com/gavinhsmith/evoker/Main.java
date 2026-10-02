package com.gavinhsmith.evoker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class Main {
    static final String VERSION =
            Objects.requireNonNullElse(Main.class.getPackage().getImplementationVersion(), "dev");

    private static final String USAGE = """
            usage: evoker <command>

              add <slug> [version]   add content (e.g. sodium, modrinth:lithium) and install it
              remove <slug>          remove content and whatever only it needed
              install                download whatever evoker.json / evoker.lock say is missing
              update [slug]          move "latest" entries (and the server build) to their newest versions
              upgrade [--dry-run]    move everything, pins included, to the newest versions for this game version
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
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        try {
            switch (args[0]) {
                case "add" -> main.add(arg(rest, 0, "add <slug> [version]"), rest.size() > 1 ? rest.get(1) : "latest");
                case "remove" -> main.remove(arg(rest, 0, "remove <slug>"));
                case "install" -> main.install(Manifest.read(dir), false, key -> false);
                case "update" -> main.update(rest.isEmpty() ? null : rest.get(0));
                case "upgrade" -> main.upgrade(rest.contains("--dry-run"));
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

    private static String arg(List<String> args, int i, String usage) {
        if (args.size() <= i) throw new EvokerException("usage: evoker " + usage);
        return args.get(i);
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

    /** Re-resolves "latest" entries (all, or one and its dependencies keep their locks) and the server build. */
    void update(String ref) {
        Manifest manifest = Manifest.read(dir);
        Lock before = Lock.read(dir);
        Predicate<String> refresh = key -> true;
        if (ref != null) {
            String key = Manifest.key(ref);
            if (!manifest.content().containsKey(key)) throw new EvokerException(key + " is not in " + Manifest.FILE);
            refresh = key::equals;
        }
        Lock after = install(manifest, ref == null, refresh);
        printChanges(before, after);
    }

    /**
     * Moves every entry, pins and the server build included, to the newest version for the current game version
     * and rewrites the pins in evoker.json. Anything without a compatible version keeps its current one (warning).
     */
    void upgrade(boolean dryRun) {
        Manifest manifest = Manifest.read(dir);
        Lock before = Lock.read(dir);
        var unpinned = new LinkedHashMap<String, Manifest.Content>();
        manifest.content().forEach((key, c) -> unpinned.put(key, c.url() != null ? c : new Manifest.Content("latest", null, null)));
        var spec = manifest.server();
        Manifest latest = new Manifest(new Manifest.ServerSpec(spec.software(), spec.version(), "latest"),
                manifest.eula(), manifest.properties(), unpinned, manifest.evoker());

        Lock after = dryRun ? plan(latest, before, true, key -> true).lock() : install(latest, true, key -> true);
        printChanges(before, after);
        if (dryRun) {
            log("dry run: nothing was changed");
            return;
        }
        // Write the new versions back into the entries that were pinned.
        var content = new LinkedHashMap<String, Manifest.Content>();
        manifest.content().forEach((key, c) -> {
            Lock.Entry e = after.content().get(key);
            boolean pinned = c.url() == null && !c.version().equals("latest");
            content.put(key, pinned && e != null ? new Manifest.Content(e.version(), null, null) : c);
        });
        String build = spec.build().equals("latest") || after.server() == null ? spec.build() : after.server().build();
        Manifest upgraded = new Manifest(new Manifest.ServerSpec(spec.software(), spec.version(), build),
                manifest.eula(), manifest.properties(), content, manifest.evoker());
        if (!upgraded.equals(manifest)) upgraded.write(dir);
    }

    private static void printChanges(Lock before, Lock after) {
        Lock.Locked a = before.server(), b = after.server();
        if (b != null && (a == null || !a.software().equals(b.software()) || !a.version().equals(b.version())
                || !Objects.equals(a.build(), b.build()))) {
            log("server: " + (a == null ? "" : a.software() + " " + a.version() + " " + Objects.toString(a.build(), "") + " -> ")
                    + b.software() + " " + b.version() + " " + Objects.toString(b.build(), ""));
        }
        var keys = new TreeSet<>(before.content().keySet());
        keys.addAll(after.content().keySet());
        int changed = 0;
        for (String key : keys) {
            Lock.Entry x = before.content().get(key), y = after.content().get(key);
            if (x != null && y != null && x.versionId().equals(y.versionId())) continue;
            changed++;
            if (x == null) log(key + ": added " + y.version());
            else if (y == null) log(key + ": removed");
            else log(key + ": " + x.version() + " -> " + y.version());
        }
        log(changed == 0 ? "content is up to date" : changed + " content change(s)");
    }

    /** What install will do: the resolved server and content, before anything is downloaded. */
    private record Plan(Lock.Locked server, Server.Resolved serverDownload, Map<String, Source.Resolution> content) {
        Lock lock() {
            var entries = new TreeMap<String, Lock.Entry>();
            content.forEach((key, r) -> entries.put(key, r.entry()));
            return new Lock(Lock.VERSION, server, entries);
        }
    }

    /**
     * Brings the disk in line with evoker.json and evoker.lock. Content is only re-resolved (network) when
     * evoker.json asks for something the lock doesn't have, or refresh selects it; otherwise the lock is used as is.
     */
    Lock install(Manifest manifest, boolean updateServer, Predicate<String> refresh) {
        Lock before = Lock.read(dir);
        Plan plan = plan(manifest, before, updateServer, refresh);

        var properties = new LinkedHashMap<>(resourcePack(plan.content(), manifest.properties()));
        properties.putAll(manifest.properties());
        installer.properties(properties);
        if (manifest.eula()) installer.acceptEula();

        Lock.Locked locked = plan.server();
        Server.Resolved download = plan.serverDownload();
        String label = locked.software() + " " + locked.version() + (locked.build() == null ? "" : " build " + locked.build());
        locked = locked.withSha256(installer.fetch(label, locked.url(), dir.resolve(Server.jarName(locked.software())),
                locked.sha256(), download == null ? null : download.algo(), download == null ? null : download.hash()));

        String levelName = installer.levelName();
        var entries = new TreeMap<String, Lock.Entry>();
        var paths = new HashSet<Path>();
        plan.content().forEach((key, r) -> {
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

    private Plan plan(Manifest manifest, Lock before, boolean updateServer, Predicate<String> refresh) {
        Server.Resolved[] download = {null};
        Lock.Locked server = planServer(manifest.server(), before.server(), updateServer, download);
        return new Plan(server, download[0], planContent(manifest, before.content(), refresh));
    }

    private Map<String, Source.Resolution> planContent(Manifest manifest, Map<String, Lock.Entry> locked,
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

    /** The server to lock. Sets download[0] when a new jar must be fetched (with its upstream checksum). */
    private Lock.Locked planServer(Manifest.ServerSpec want, Lock.Locked have, boolean update,
                                   Server.Resolved[] download) {
        boolean stale = have == null
                || !want.software().equals(have.software())
                || !want.version().equals(have.version())
                || (!want.build().equals("latest") && !want.build().equals(have.build()));
        if (!stale && !(update && want.build().equals("latest"))) return have;
        Server.Resolved resolved;
        try {
            resolved = server.resolve(want);
        } catch (EvokerException e) {
            // Same software: keep what is installed (e.g. no build for a new game version yet) and let the user decide.
            if (have == null || !have.software().equals(want.software())) throw e;
            warn(e.getMessage() + "; keeping " + have.software() + " " + have.version()
                    + (have.build() == null ? "" : " build " + have.build()));
            return have;
        }
        if (!stale && resolved.url().equals(have.url())) return have;
        download[0] = resolved;
        return new Lock.Locked(want.software(), want.version(), resolved.build(), resolved.url(), null);
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

    /** Installs (with the configured auto-updates), then runs the server as a child process; returns its exit code. */
    int start() {
        Manifest manifest = Manifest.read(dir);
        Manifest.Settings settings = manifest.evoker();
        install(manifest, settings.autoUpdateServer(), key -> settings.autoUpdateDeps());
        var command = Server.command(manifest.server().software(), settings);
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
