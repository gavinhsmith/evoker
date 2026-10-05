package com.gavinhsmith.evoker;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public final class Main {
    static final String VERSION =
            Objects.requireNonNullElse(Main.class.getPackage().getImplementationVersion(), "dev");

    private static final String USAGE = """
            usage: evoker <command>

              init [software] [version] [--git]
                                     create evoker.json (default: paper, latest release); --git also sets up git
              add <slug> [version]   add content (sodium, modrinth:lithium, hangar:ViaVersion) and install it
              add <url> --type <mod|plugin|datapack|resourcepack> [--name <name>]
                                     add a file from a URL
              import <pack>          import a Modrinth modpack (.mrpack file, URL, or modpack slug)
              remove <slug>          remove content and whatever only it needed
              install                download whatever evoker.json / evoker.lock say is missing
              update [slug]          move "latest" entries (and the server build) to their newest versions
              upgrade [--dry-run]    move everything, pins included, to the newest versions for this game version
              start                  install, then run the server
              command                print the command that starts the server (for systemd, Docker, panels)
              version                print the evoker version
            """;

    private final Path dir;
    private final Http http = new Http();
    private final Server server;
    private final Installer installer;
    private final Modrinth modrinth;
    private final Resolver resolver;

    Main(Path dir, Apis apis) {
        this.dir = dir;
        this.server = new Server(http, apis);
        this.installer = new Installer(dir, http);
        this.modrinth = new Modrinth(http, apis.modrinth());
        this.resolver = new Resolver(Map.of(
                "modrinth", modrinth,
                "hangar", new Hangar(http, apis.hangar()),
                "url", new UrlSource()));
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
                case "init" -> main.init(rest);
                case "import" -> main.importPack(arg(rest, 0, "import <file.mrpack | url | modrinth-slug>"));
                case "add" -> main.add(rest);
                case "remove" -> main.remove(arg(rest, 0, "remove <slug>"));
                case "install" -> main.install(Manifest.read(dir), false, key -> false);
                case "update" -> main.update(rest.isEmpty() ? null : rest.get(0));
                case "upgrade" -> main.upgrade(rest.contains("--dry-run"));
                case "start" -> {
                    return main.start();
                }
                case "command" -> System.out.println(main.command());
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

    static final List<String> SOFTWARE = List.of("vanilla", "paper", "purpur", "fabric", "quilt", "neoforge", "spigot");

    /** Ignores what evoker or the server can recreate; keeps configs and hand-added jars tracked. */
    static final String GITIGNORE = """
            # Downloaded by evoker (rebuilt from evoker.lock)
            server.jar
            fabric-server-launch.jar
            quilt-server-launch.jar
            *-installer.jar
            BuildTools.jar
            *-installer.jar.log
            .evoker-*
            run.sh
            run.bat
            mods/modrinth-*.jar
            mods/hangar-*.jar
            mods/url-*.jar
            plugins/modrinth-*.jar
            plugins/hangar-*.jar
            plugins/url-*.jar

            # Recreated by the server
            libraries/
            versions/
            cache/
            .fabric/
            .quilt/
            plugins/.paper-remapped/
            logs/
            crash-reports/
            debug/
            usercache.json

            # Worlds (large, constantly changing; back them up separately)
            world/
            world_nether/
            world_the_end/
            """;

    /** init [software] [version] [--git] */
    void init(List<String> args) {
        boolean git = args.contains("--git");
        List<String> positional = args.stream().filter(a -> !a.startsWith("--")).toList();
        if (Files.exists(dir.resolve(Manifest.FILE))) {
            throw new EvokerException(Manifest.FILE + " already exists in " + dir);
        }
        String software = positional.isEmpty() ? "paper" : positional.get(0).toLowerCase();
        if (!SOFTWARE.contains(software)) throw new EvokerException("unknown server software " + software + "; one of " + SOFTWARE);
        String version = positional.size() > 1 ? positional.get(1) : server.latestRelease();
        new Manifest(new Manifest.ServerSpec(software, version, null), false, null, null, null).write(dir);
        log("created " + Manifest.FILE + " for " + software + " " + version
                + "; set \"eula\": true to accept the Minecraft EULA (https://aka.ms/MinecraftEULA)");
        if (git) initGit();
    }

    private void initGit() {
        try {
            if (!Files.exists(dir.resolve(".git"))) {
                Process p = new ProcessBuilder("git", "init").directory(dir.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                p.getOutputStream().close(); // git needs no input; don't hand it ours
                if (p.waitFor() != 0) warn("git init failed");
            }
        } catch (IOException e) {
            warn("git not found; skipping git init");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvokerException("interrupted", e);
        }
        Path ignore = dir.resolve(".gitignore");
        if (Files.exists(ignore)) {
            warn(".gitignore already exists; leaving it alone");
        } else {
            try {
                Files.writeString(ignore, GITIGNORE);
            } catch (IOException e) {
                throw new EvokerException("cannot write " + ignore + ": " + e.getMessage(), e);
            }
            log("created .gitignore");
        }
        warn("server.properties can hold secrets (rcon.password); keep them out of public repositories");
    }

    /** import <file.mrpack | url | modrinth-slug>: a Modrinth modpack becomes evoker.json entries. */
    void importPack(String ref) {
        Path local = dir.resolve(ref);
        if (Files.isRegularFile(local)) {
            importPack(local);
            return;
        }
        String url = ref.startsWith("https://") || ref.startsWith("http://") ? ref
                : modrinth.packUrl(ref.startsWith("modrinth:") ? ref.substring("modrinth:".length()) : ref);
        log("downloading " + url);
        Path temp = http.download(url, dir, null, null).file();
        try {
            importPack(temp);
        } finally {
            Http.deleteQuietly(temp);
        }
    }

    /**
     * Sets the server from the pack and adds its server-side files: files Modrinth knows (by hash) as pinned
     * modrinth: entries, the rest of mods/ and plugins/ as url: entries. Overrides are copied without replacing
     * existing files, then everything is installed. Pack entries replace same-named evoker.json entries.
     */
    private void importPack(Path zip) {
        Mrpack pack = Mrpack.read(zip);
        Map<String, JsonNode> byHash =
                modrinth.versionsByHash(pack.files().stream().map(Mrpack.PackFile::sha512).toList());
        Map<String, String> slugs = modrinth.slugs(
                byHash.values().stream().map(v -> v.path("project_id").asString()).collect(Collectors.toSet()));

        var content = new LinkedHashMap<String, Manifest.Content>();
        var skipped = new ArrayList<String>();
        for (Mrpack.PackFile f : pack.files()) {
            // Packs also list resource packs, shaders and config files; only mods and plugins run on a server.
            String type = f.path().startsWith("mods/") ? "mod" : f.path().startsWith("plugins/") ? "plugin" : null;
            if (type == null) {
                skipped.add(f.path());
                continue;
            }
            var v = byHash.get(f.sha512());
            if (v != null) {
                content.put("modrinth:" + slugs.get(v.path("project_id").asString()),
                        new Manifest.Content(v.path("version_number").asString(), null, null));
            } else {
                content.put("url:" + fileName(f.path(), f.path()), new Manifest.Content(null, f.url(), type));
            }
        }
        if (!skipped.isEmpty()) warn("skipping " + skipped.size() + " files outside mods/ and plugins/: " + skipped);

        Manifest existing = Files.exists(dir.resolve(Manifest.FILE)) ? Manifest.read(dir) : null;
        var merged = new LinkedHashMap<>(existing == null ? Map.of() : existing.content());
        merged.putAll(content);
        Manifest updated = existing == null
                ? new Manifest(pack.server(), false, null, merged, null)
                : existing.withServer(pack.server()).withContent(merged);

        install(updated, false, content::containsKey);
        int[] overrides = Mrpack.extractOverrides(zip, dir); // only once the install worked
        updated.write(dir);

        long fromModrinth = content.keySet().stream().filter(k -> k.startsWith("modrinth:")).count();
        log("imported " + pack.name() + " " + pack.version() + " (" + pack.server().software() + " "
                + pack.server().version() + "): " + fromModrinth + " from Modrinth, " + (content.size() - fromModrinth)
                + " from URLs, " + pack.clientOnly() + " client-only skipped"
                + (skipped.isEmpty() ? "" : ", " + skipped.size() + " other files skipped"));
        log(overrides[0] + " override files copied" + (overrides[1] > 0 ? ", " + overrides[1] + " existing files kept" : ""));
        if (!updated.eula()) log("set \"eula\": true in " + Manifest.FILE + " to accept the Minecraft EULA (https://aka.ms/MinecraftEULA)");
    }

    /** add <slug> [version] | add <url> --type <type> [--name <name>] */
    void add(List<String> args) {
        var positional = new ArrayList<String>();
        var flags = new HashMap<String, String>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("--")) {
                if (i + 1 >= args.size()) throw new EvokerException(a + " needs a value");
                flags.put(a, args.get(++i));
            } else {
                positional.add(a);
            }
        }
        String ref = arg(positional, 0, "add <slug> [version]  or  add <url> --type <type> [--name <name>]");
        String key;
        Manifest.Content entry;
        if (ref.startsWith("https://") || ref.startsWith("http://")) {
            String type = flags.get("--type");
            if (type == null || !UrlSource.TYPES.contains(type)) {
                throw new EvokerException("adding a URL needs --type, one of " + UrlSource.TYPES);
            }
            if (ref.startsWith("http://")) warn("downloading over plain http; anyone on the network could swap the file");
            key = "url:" + flags.getOrDefault("--name", urlName(ref));
            entry = new Manifest.Content(null, ref, type);
        } else {
            key = Manifest.key(ref);
            entry = new Manifest.Content(positional.size() > 1 ? positional.get(1) : "latest", null, null);
        }
        add(key, entry);
    }

    /** The URL's file name without extension, reduced to characters safe in a file name. */
    static String urlName(String url) {
        return fileName(URI.create(url).getPath(), url);
    }

    /** The last path segment without extension, reduced to characters safe in a file name. */
    private static String fileName(String path, String what) {
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (name.contains(".")) name = name.substring(0, name.lastIndexOf('.'));
        name = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (name.isEmpty()) throw new EvokerException("cannot name " + what + "; pass --name");
        return name;
    }

    private void add(String key, Manifest.Content entry) {
        Manifest manifest = Manifest.read(dir);
        var content = new LinkedHashMap<>(manifest.content());
        content.put(key, entry);
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
        manifest.content().forEach((key, c) -> unpinned.put(key, c.url() != null ? c : Resolver.LATEST));
        var spec = manifest.server();
        Manifest latest = manifest.withServer(new Manifest.ServerSpec(spec.software(), spec.version(), "latest"))
                .withContent(unpinned);

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
        Manifest upgraded = manifest.withServer(new Manifest.ServerSpec(spec.software(), spec.version(), build))
                .withContent(content);
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
            if (x != null && y != null && same(x, y)) continue;
            changed++;
            if (x == null) log(key + ": added " + label(y));
            else if (y == null) log(key + ": removed");
            else if (x.versionId() == null) log(key + ": file changed");
            else log(key + ": " + x.version() + " -> " + y.version());
        }
        log(changed == 0 ? "content is up to date" : changed + " content change(s)");
        if (keys.stream().anyMatch(k -> k.startsWith("url:"))) {
            log("url entries are re-downloaded, not version-checked");
        }
    }

    /** Same version; url entries (no version) compare by URL and, when known, by hash. */
    private static boolean same(Lock.Entry x, Lock.Entry y) {
        if (x.versionId() != null) return x.versionId().equals(y.versionId());
        return Objects.equals(x.url(), y.url()) && (y.sha256() == null || y.sha256().equals(x.sha256()));
    }

    private static String label(Lock.Entry e) {
        return e.version() != null ? e.version() : e.url();
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

        // URL resource packs aren't stored, but server.properties needs their SHA-1: hash them once.
        var content = new TreeMap<>(plan.content());
        content.replaceAll((key, r) -> {
            Lock.Entry e = r.entry(), old = before.content().get(key);
            if (!e.type().equals("resourcepack") || e.sha1() != null) return r;
            if (old != null && e.url().equals(old.url()) && old.sha1() != null && !refresh.test(key)) {
                return r.withEntry(e.withHashes(old.sha256(), old.sha1()));
            }
            Http.Fetched f = installer.hash(key, e.url());
            return r.withEntry(e.withHashes(f.sha256(), f.sha1()));
        });

        var properties = new LinkedHashMap<>(resourcePack(content, manifest.properties()));
        properties.putAll(manifest.properties());
        installer.properties(properties);
        if (manifest.eula()) installer.acceptEula();

        Lock.Locked locked = plan.server();
        Server.Resolved download = plan.serverDownload();
        String label = locked.software() + " " + locked.version() + (locked.build() == null ? "" : " build " + locked.build());
        locked = locked.withSha256(installer.fetch(label, locked.url(), dir.resolve(Server.jarName(locked.software())),
                locked.sha256(), download == null ? null : download.algo(), download == null ? null : download.hash()));
        Server.runInstaller(dir, locked, manifest.evoker());

        String levelName = installer.levelName();
        var entries = new TreeMap<String, Lock.Entry>();
        var paths = new HashSet<Path>();
        content.forEach((key, r) -> {
            Lock.Entry e = r.entry();
            Path path = installer.path(key, e, levelName);
            if (path != null) {
                Lock.Entry old = before.content().get(key);
                // A refreshed url entry accepts whatever the URL serves now; otherwise the locked hash must match.
                boolean accept = refresh.test(key) && Resolver.source(key).equals("url");
                String lockedSha = !accept && old != null && Objects.equals(old.url(), e.url()) ? old.sha256() : null;
                e = e.withSha256(installer.fetch(key + " " + label(e), e.url(), path, lockedSha, r.algo(), r.hash()));
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
        ServerPlan server = planServer(manifest.server(), before.server(), updateServer);
        return new Plan(server.locked(), server.download(), planContent(manifest, before.content(), refresh));
    }

    /** The server to lock, and the download (with its upstream checksum) when a new jar must be fetched. */
    private record ServerPlan(Lock.Locked locked, Server.Resolved download) {}

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

    private ServerPlan planServer(Manifest.ServerSpec want, Lock.Locked have, boolean update) {
        var keep = new ServerPlan(have, null);
        boolean stale = have == null
                || !want.software().equals(have.software())
                || !want.version().equals(have.version())
                || (!want.build().equals("latest") && !want.build().equals(have.build()));
        if (!stale && !(update && want.build().equals("latest"))) return keep;
        Server.Resolved resolved;
        try {
            resolved = server.resolve(want);
        } catch (EvokerException e) {
            // Same software: keep what is installed (e.g. no build for a new game version yet) and let the user decide.
            if (have == null || !have.software().equals(want.software())) throw e;
            warn(e.getMessage() + "; keeping " + have.software() + " " + have.version()
                    + (have.build() == null ? "" : " build " + have.build()));
            return keep;
        }
        // Same build means nothing to update, even if a newer installer/launcher exists (rebuilding spigot takes minutes).
        if (!stale && (resolved.build() != null ? resolved.build().equals(have.build()) : resolved.url().equals(have.url()))) {
            return keep;
        }
        return new ServerPlan(new Lock.Locked(want.software(), want.version(), resolved.build(), resolved.url(), null),
                resolved);
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

    /** The launch command for what is locked, one line, offline; arguments with spaces are double-quoted. */
    String command() {
        Lock.Locked locked = Lock.read(dir).server();
        if (locked == null) throw new EvokerException("no server installed yet, run evoker install first");
        return Server.command(locked, Manifest.read(dir).evoker(), dir).stream()
                .map(a -> a.matches(".*\\s.*") ? '"' + a + '"' : a)
                .collect(Collectors.joining(" "));
    }

    /** Installs (with the configured auto-updates), then runs the server as a child process; returns its exit code. */
    int start() {
        Manifest manifest = Manifest.read(dir);
        Manifest.Settings settings = manifest.evoker();
        Lock lock = install(manifest, settings.autoUpdateServer(), key -> settings.autoUpdateDeps());
        var command = Server.command(lock.server(), settings, dir);
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
