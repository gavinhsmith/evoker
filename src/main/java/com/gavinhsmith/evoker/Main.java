package com.gavinhsmith.evoker;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public final class Main {
    static final String VERSION =
            Objects.requireNonNullElse(Main.class.getPackage().getImplementationVersion(), "dev");

    private static final String USAGE = """
            usage: evoker <command>

            pack commands (in the pack folder; they never install anything):
              create <name> <loader> [game_version] [client|server|both] [--git]
                                     start a pack (loader: vanilla, fabric, quilt, neoforge, paper, purpur, spigot)
              add <source:name>[@version] [--type <type>] [--side <side>] [--optional]
                                     add content (sodium, modrinth:lithium@0.14.3, hangar:ViaVersion)
              url <name> <url> <type> [--side <side>] [--optional]
                                     add a file from a URL
              remove <name>          remove content and whatever only it needed
              list [type] [--output=text|json]
                                     show the game and content: versions, pins, sides, dependencies
              update [name]          move "latest" entries (and the build) to their newest versions
              update list [--output=text|json]
                                     show what update would change
              upgrade [game_version] [--pinned|--keep-pinned]
                                     move to a game version (default: newest) and everything to its newest version
              upgrade list [game_version] [--output=text|json]
                                     show what upgrade would change
              import <pack>          start a pack from a Modrinth modpack (.mrpack file, URL, or modpack slug)

            server commands (in the server folder):
              install server <pack-url> | --local <pack-folder> [--accept-eula]
                                     install a pack as a server in this folder
              server update          fetch the pack again and apply its changes
              server update list [--output=text|json]
                                     show what server update would change
              server start           update (see updateOnStart), then run the server
              server command         print the command that starts the server (for systemd, Docker, panels)

            client commands:
              install <pack-url> | --local <pack-folder>
                                     install a pack as a new Prism Launcher instance
              client options <pack>  choose the pack's optional content again

              config [setting] [value] [--user]
                                     show or change evoker's own settings (this server's, or with --user yours)
              version                print the evoker version
            """;

    /** Plugin servers: their packs default to server-only, and their clients are vanilla. */
    static final List<String> PLUGIN_LOADERS = List.of("paper", "purpur", "spigot");

    private static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir"));

    private final Path dir;
    private final Http http = new Http();
    private final Server server;
    private final Modrinth modrinth;
    private final Resolver resolver;

    Main(Path dir, Apis apis) {
        this.dir = dir;
        this.server = new Server(http, apis);
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
        try {
            var rest = new ArrayList<String>();
            Map<String, String> flags = flags(Arrays.asList(args).subList(1, args.length), rest);
            String output = flags.getOrDefault("--output", "text");
            if (!output.equals("text") && !output.equals("json")) throw new EvokerException("--output must be text or json");
            boolean list = !rest.isEmpty() && rest.get(0).equals("list");
            boolean serverList = args[0].equals("server") && rest.size() > 1 && rest.get(1).equals("list");
            if (output.equals("json") && !args[0].equals("list") && !list && !serverList) {
                throw new EvokerException("--output=json only works with list, update list, upgrade list and server update list");
            }
            switch (args[0]) {
                case "create" -> main.create(rest, flags.containsKey("--git"));
                case "add" -> main.add(arg(rest, 0, "add <source:name>[@version]"), flags);
                case "url" -> main.url(rest, flags);
                case "remove" -> main.remove(arg(rest, 0, "remove <name>"));
                case "list" -> System.out.println(main.list(rest.isEmpty() ? null : rest.get(0), output));
                case "update" -> {
                    if (list) main.updateList(output);
                    else main.update(rest.isEmpty() ? null : rest.get(0));
                }
                case "upgrade" -> {
                    if (flags.containsKey("--pinned") && flags.containsKey("--keep-pinned")) {
                        throw new EvokerException("--pinned and --keep-pinned contradict each other");
                    }
                    Boolean pinned = flags.containsKey("--pinned") ? Boolean.TRUE
                            : flags.containsKey("--keep-pinned") ? Boolean.FALSE : null;
                    List<String> versions = list ? rest.subList(1, rest.size()) : rest;
                    main.upgrade(versions.isEmpty() ? null : versions.get(0), pinned, list, output);
                }
                case "import" -> main.importPack(arg(rest, 0, "import <file.mrpack | url | modrinth-slug>"));
                case "install" -> main.install(rest, flags);
                case "server" -> {
                    var folder = new ServerFolder(dir, main.http);
                    switch (arg(rest, 0, "server start | update [list] | command")) {
                        case "start" -> {
                            return folder.start();
                        }
                        case "update" -> folder.update(serverList, output);
                        case "command" -> System.out.println(folder.command().stream()
                                .map(a -> a.matches(".*\\s.*") ? '"' + a + '"' : a).collect(Collectors.joining(" ")));
                        default -> throw new EvokerException("usage: evoker server start | update [list] | command");
                    }
                }
                case "client" -> {
                    switch (arg(rest, 0, "client options <pack>")) {
                        case "options" -> PrismInstance.options(main.http, arg(rest, 1, "client options <pack>"));
                        default -> throw new EvokerException("usage: evoker client options <pack>");
                    }
                }
                case "config" -> System.out.println(main.config(rest, flags.containsKey("--user")));
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

    private static final Set<String> VALUE_FLAGS = Set.of("--type", "--side", "--output", "--local");
    private static final Set<String> SWITCHES = Set.of("--git", "--optional", "--pinned", "--keep-pinned",
            "--accept-eula", "--user");

    /** Splits args into positional (added to positional) and flags: --flag value, --flag=value, or a bare switch. */
    static Map<String, String> flags(List<String> args, List<String> positional) {
        var flags = new HashMap<String, String>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (!a.startsWith("--")) {
                positional.add(a);
                continue;
            }
            int eq = a.indexOf('=');
            String name = eq < 0 ? a : a.substring(0, eq);
            if (SWITCHES.contains(name)) {
                if (eq >= 0) throw new EvokerException(name + " takes no value");
                flags.put(name, "");
            } else if (!VALUE_FLAGS.contains(name)) {
                throw new EvokerException("unknown option " + name);
            } else if (eq >= 0) {
                flags.put(name, a.substring(eq + 1));
            } else if (i + 1 < args.size()) {
                flags.put(name, args.get(++i));
            } else {
                throw new EvokerException(name + " needs a value");
            }
        }
        return flags;
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

    /** create <name> <loader> [game_version] [client|server|both] [--git] */
    void create(List<String> positional, boolean git) {
        String usage = "create <name> <loader> [game_version] [client|server|both] [--git]";
        String name = arg(positional, 0, usage);
        String loader = arg(positional, 1, usage).toLowerCase();
        String version = null, side = null;
        for (String p : positional.subList(2, positional.size())) {
            if (side == null && Manifest.SIDES.contains(p)) side = p;
            else if (version == null) version = p;
            else throw new EvokerException("usage: evoker " + usage);
        }
        if (Files.exists(dir.resolve(Manifest.FILE))) throw new EvokerException(Manifest.FILE + " already exists in " + dir);
        if (!Manifest.LOADERS.contains(loader)) throw new EvokerException("unknown loader " + loader + "; one of " + Manifest.LOADERS);
        if (side == null) side = PLUGIN_LOADERS.contains(loader) ? "server" : "both";
        if (version == null) version = server.latestRelease();

        Manifest manifest = new Manifest(name, side, new Manifest.Game(version, loader, null), null);
        Lock lock = lock(manifest, Lock.empty(), key -> false, false);
        manifest.write(dir);
        lock.write(dir);
        log("created " + name + " (" + side + "): " + loader + " " + version
                + (lock.game().build() == null ? "" : " build " + lock.game().build()));
        if (git) initGit();
    }

    private void initGit() {
        if (Files.exists(dir.resolve(".git"))) return;
        try {
            Process p = new ProcessBuilder("git", "init").directory(dir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            p.getOutputStream().close(); // git needs no input; don't hand it ours
            if (p.waitFor() != 0) warn("git init failed");
        } catch (IOException e) {
            warn("git not found; skipping git init");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvokerException("interrupted", e);
        }
    }

    /** add <source:name>[@version] [--type] [--side] [--optional] */
    void add(String ref, Map<String, String> flags) {
        if (ref.startsWith("https://") || ref.startsWith("http://") || ref.startsWith("url:")) {
            throw new EvokerException("for a file from a URL, use: evoker url <name> <url> <type>");
        }
        String version = "latest";
        int at = ref.indexOf('@');
        if (at > 0) {
            version = ref.substring(at + 1);
            ref = ref.substring(0, at);
        }
        if (version.isEmpty()) throw new EvokerException("usage: evoker add <source:name>[@version]");
        String key = Manifest.key(ref);
        var entry = new Manifest.Content(version, flags.get("--side"), flags.containsKey("--optional"),
                flags.get("--type"), null);
        change(content -> content.put(key, entry), key::equals);
        log("added " + key);
    }

    /** url <name> <url> <type> [--side] [--optional] */
    void url(List<String> positional, Map<String, String> flags) {
        String usage = "url <name> <url> <type> [--side <side>] [--optional]";
        String name = arg(positional, 0, usage), url = arg(positional, 1, usage), type = arg(positional, 2, usage);
        if (!name.matches("[A-Za-z0-9._-]+")) {
            throw new EvokerException("a url entry's name can only use letters, digits, '.', '_' and '-'");
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new EvokerException(url + " is not an http(s) URL");
        Manifest.checkType(type);
        String side = flags.get("--side");
        if (side == null && Manifest.defaultSides(type) == null) {
            throw new EvokerException("a " + type + " from a URL needs --side (client, server or both)");
        }
        if (url.startsWith("http://")) warn("downloading over plain http; anyone on the network could swap the file");
        String key = "url:" + name;
        var entry = new Manifest.Content(null, side, flags.containsKey("--optional"), type, url);
        change(content -> content.put(key, entry), key::equals);
        log("added " + key);
    }

    void remove(String ref) {
        Manifest manifest = Manifest.read(dir);
        String key = Manifest.key(ref);
        if (!manifest.content().containsKey(key)) {
            Lock.Entry dep = Lock.read(dir).content().get(key);
            throw new EvokerException(key + " is not in " + Manifest.FILE
                    + (dep != null && dep.requiredBy() != null ? " (it is required by " + dep.requiredBy() + ")" : ""));
        }
        change(content -> content.remove(key), k -> false);
        log("removed " + key);
    }

    /** Edits evoker.json's content, resolves, and writes both files (nothing, if resolving fails). */
    private void change(Consumer<Map<String, Manifest.Content>> edit, Predicate<String> refresh) {
        Manifest manifest = Manifest.read(dir);
        var content = new LinkedHashMap<>(manifest.content());
        edit.accept(content);
        Manifest updated = manifest.withContent(content);
        Lock before = Lock.read(dir);
        save(updated, lock(updated, before, refresh, false), manifest, before);
    }

    private void save(Manifest manifest, Lock lock, Manifest oldManifest, Lock oldLock) {
        if (!manifest.equals(oldManifest)) manifest.write(dir);
        if (!lock.equals(oldLock)) lock.write(dir);
    }

    /** All "latest" entries and the build, or one entry (moving its pin, if pinned); dependencies keep their locks. */
    void update(String ref) {
        Manifest manifest = Manifest.read(dir);
        Lock before = Lock.read(dir);
        if (ref == null) {
            Lock after = lock(manifest, before, key -> true, true);
            save(manifest, after, manifest, before);
            printChanges(before, after, manifest, Set.of(), "text");
            return;
        }
        String key = Manifest.key(ref);
        Manifest.Content wanted = manifest.content().get(key);
        if (wanted == null) throw new EvokerException(key + " is not in " + Manifest.FILE);
        var content = new LinkedHashMap<>(manifest.content());
        if (wanted.pinned()) content.put(key, wanted.withVersion("latest"));
        Lock after = lock(manifest.withContent(content), before, key::equals, false);
        if (wanted.pinned()) content.put(key, wanted.withVersion(after.content().get(key).version()));
        Manifest updated = manifest.withContent(content);
        save(updated, after, manifest, before);
        printChanges(before, after, updated, Set.of(), "text");
    }

    void updateList(String output) {
        Manifest manifest = Manifest.read(dir);
        Lock before = Lock.read(dir);
        printChanges(before, lock(manifest, before, key -> true, true), manifest, Set.of(), output);
        if (output.equals("text")) log("nothing was changed (update list)");
    }

    /**
     * Moves the pack to a game version and every entry to its newest version for it; pinned entries too if
     * movePins (asked when null). Anything without a compatible version keeps its current one (warning); the
     * loader must have a build for the new version.
     */
    void upgrade(String version, Boolean movePins, boolean listOnly, String output) {
        Manifest manifest = Manifest.read(dir);
        Lock before = Lock.read(dir);
        if (version == null) version = server.latestRelease();
        List<String> pins = manifest.content().entrySet().stream().filter(e -> e.getValue().pinned())
                .map(Map.Entry::getKey).toList();
        boolean move = movePins != null ? movePins
                : listOnly || pins.isEmpty() || askAboutPins(pins);

        var content = new LinkedHashMap<String, Manifest.Content>();
        manifest.content().forEach((key, c) -> content.put(key, c.pinned() && move ? c.withVersion("latest") : c));
        Manifest.Game game = manifest.game();
        Manifest target = manifest.withGame(new Manifest.Game(version, game.loader(), "latest")).withContent(content);
        Lock after = lock(target, before, key -> true, true);
        Set<String> kept = move ? Set.of() : Set.copyOf(pins);
        if (listOnly) {
            printChanges(before, after, manifest, kept, output);
            if (output.equals("text")) log("nothing was changed (upgrade list)");
            return;
        }
        // Write the new versions back into the pins that moved, and into a pinned build.
        var written = new LinkedHashMap<String, Manifest.Content>();
        manifest.content().forEach((key, c) -> {
            Lock.Entry e = after.content().get(key);
            written.put(key, c.pinned() && move && e != null ? c.withVersion(e.version()) : c);
        });
        String build = game.build().equals("latest") ? "latest" : after.game().build();
        Manifest upgraded = manifest.withGame(new Manifest.Game(version, game.loader(), build)).withContent(written);
        save(upgraded, after, manifest, before);
        printChanges(before, after, upgraded, kept, "text");
    }

    private static boolean askAboutPins(List<String> pins) {
        String question = pins.size() + " pinned entries (" + String.join(", ", pins) + "): upgrade them too?";
        if (System.console() == null) {
            warn(question + " No console to ask in, so no (pass --pinned or --keep-pinned to choose)");
            return false;
        }
        return ask(question);
    }

    /** A yes/no question on the console; no when there is no console to ask in. */
    static boolean ask(String question) {
        var console = System.console();
        if (console == null) return false;
        String answer = console.readLine("%s [y/N] ", question);
        return answer != null && answer.trim().toLowerCase().startsWith("y");
    }

    /** install [client|server] <pack-url> | --local <path> [--accept-eula]: a Prism instance, or a server here. */
    void install(List<String> positional, Map<String, String> flags) {
        String usage = "install [server] <pack-url> | install [server] --local <pack-folder> [--accept-eula]";
        boolean server = !positional.isEmpty() && positional.get(0).equals("server");
        List<String> args = !positional.isEmpty() && (server || positional.get(0).equals("client"))
                ? positional.subList(1, positional.size()) : positional;
        String url = args.isEmpty() ? null : args.get(0), local = flags.get("--local");
        if ((url == null) == (local == null) || args.size() > 1) throw new EvokerException("usage: evoker " + usage);
        if (url != null && !url.startsWith("https://") && !url.startsWith("http://")) {
            throw new EvokerException(url + " is not a pack URL; for a pack on disk use --local");
        }
        var source = Pack.Source.of(url, local == null ? null : dir.resolve(local).toString());
        if (server) {
            new ServerFolder(dir, http).install(source, flags.containsKey("--accept-eula"));
        } else {
            if (flags.containsKey("--accept-eula")) throw new EvokerException("--accept-eula is for servers: evoker install server");
            PrismInstance.install(http, source);
        }
    }

    /** config [setting] [value] [--user]: this server's settings in a server folder, the user's otherwise. */
    String config(List<String> positional, boolean user) {
        Config config = !user && Files.isDirectory(dir.resolve(ServerFolder.STATE)) ? Config.server(dir) : Config.user();
        if (positional.isEmpty()) return config.show();
        String key = positional.get(0);
        if (positional.size() > 2) throw new EvokerException("usage: evoker config [setting] [value] [--user]");
        if (positional.size() == 2) config.set(key, positional.get(1));
        return key + " = " + config.get(key);
    }

    /** import <file.mrpack | url | modrinth-slug>: a Modrinth modpack becomes a new pack. */
    void importPack(String ref) {
        if (Files.exists(dir.resolve(Manifest.FILE))) {
            throw new EvokerException(Manifest.FILE + " already exists in " + dir + "; import starts a new pack");
        }
        Path local = dir.resolve(ref);
        if (Files.isRegularFile(local)) {
            importPack(local);
            return;
        }
        String url = ref.startsWith("https://") || ref.startsWith("http://") ? ref
                : modrinth.packUrl(ref.startsWith("modrinth:") ? ref.substring("modrinth:".length()) : ref);
        log("downloading " + url);
        Path temp = http.download(url, TEMP, null).file();
        try {
            importPack(temp);
        } finally {
            Http.deleteQuietly(temp);
        }
    }

    /**
     * Every mod, resource pack and shader in the pack becomes an entry, optional (and for mods, the side) taken
     * from the file's env: files Modrinth knows (by hash) as pinned modrinth: entries, the rest as url: entries.
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
            String folder = f.path().substring(0, Math.max(0, f.path().indexOf('/')));
            String type = switch (folder) {
                case "mods" -> "mod";
                case "resourcepacks" -> "resourcepack";
                case "shaderpacks" -> "shaderpack";
                case "plugins" -> "plugin";
                default -> null;
            };
            boolean client = !f.client().equals("unsupported"), server = !f.server().equals("unsupported");
            if (type == null || (!client && !server)) {
                skipped.add(f.path());
                continue;
            }
            // Only mods take their side from env; for the other types, the type decides (a shader is never server-side).
            String side = !type.equals("mod") ? null : client && server ? "both" : client ? "client" : "server";
            Boolean optional = client && f.client().equals("optional");
            var v = byHash.get(f.sha512());
            if (v != null) {
                content.put("modrinth:" + slugs.get(v.path("project_id").asString()),
                        new Manifest.Content(v.path("version_number").asString(), side, optional, type, null));
            } else {
                content.put("url:" + fileName(f.path()), new Manifest.Content(null, side, optional, type, f.url()));
            }
        }
        if (!skipped.isEmpty()) warn("skipping " + skipped.size() + " files evoker doesn't handle: " + skipped);
        if (pack.overrides() > 0) {
            warn(pack.overrides() + " override files (configs, mostly) aren't supported yet and were skipped");
        }

        String name = pack.name().isBlank() ? "Imported pack" : pack.name();
        Manifest manifest = new Manifest(name, "both", pack.game(), content);
        Lock lock = lock(manifest, Lock.empty(), key -> false, false);
        manifest.write(dir);
        lock.write(dir);
        long fromModrinth = content.keySet().stream().filter(k -> k.startsWith("modrinth:")).count();
        log("imported " + name + " " + pack.version() + " (" + pack.game().loader() + " " + pack.game().version() + "): "
                + fromModrinth + " from Modrinth, " + (content.size() - fromModrinth) + " from URLs");
    }

    /** The last path segment without extension, reduced to characters safe in a file name. */
    private static String fileName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.contains(".")) name = name.substring(0, name.lastIndexOf('.'));
        name = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (name.isEmpty()) throw new EvokerException("cannot name " + path);
        return name;
    }

    /**
     * What the pack resolves to. Only goes online for what the lock doesn't already satisfy, or what refresh
     * (content keys) and refreshBuild ask for; a different game version or loader refreshes everything.
     */
    Lock lock(Manifest manifest, Lock before, Predicate<String> refresh, boolean refreshBuild) {
        Lock.Game have = before.game();
        if (have == null || !have.version().equals(manifest.game().version())
                || !have.loader().equals(manifest.game().loader())) refresh = key -> true;
        GamePlan game = planGame(manifest, before, refreshBuild);
        Predicate<String> refreshed = refresh;
        Map<String, Lock.Entry> content = planContent(manifest, before.content(), refresh);
        content.replaceAll((key, e) -> hash(key, e, before.content().get(key), refreshed.test(key)));
        return new Lock(Lock.VERSION, game.game(), game.server(), content);
    }

    private record GamePlan(Lock.Game game, Lock.Server server) {}

    private GamePlan planGame(Manifest manifest, Lock before, boolean refresh) {
        Manifest.Game want = manifest.game();
        Lock.Game have = before.game();
        boolean serverSide = manifest.sides().contains("server");
        var keep = new GamePlan(have, before.server());
        boolean sameGame = have != null && have.loader().equals(want.loader()) && have.version().equals(want.version());
        boolean stale = !sameGame || (!want.build().equals("latest") && !want.build().equals(have.build()))
                || serverSide != (before.server() != null);
        if (!stale && !(refresh && want.build().equals("latest"))) return keep;
        Server.Resolved resolved;
        try {
            resolved = server.resolve(want);
        } catch (EvokerException e) {
            // Same game: keep what is locked and let the user decide. A new game version needs a loader build.
            if (!sameGame) throw e;
            warn(e.getMessage() + "; keeping " + have.loader() + " " + have.version()
                    + (have.build() == null ? "" : " build " + have.build()));
            return keep;
        }
        // Same build means nothing to update, even if a newer installer/launcher exists (rebuilding spigot takes minutes).
        if (!stale && Objects.equals(resolved.build(), have.build())) return keep;
        return new GamePlan(new Lock.Game(want.version(), want.loader(), resolved.build()),
                serverSide ? new Lock.Server(resolved.url(), Http.hash(resolved.algo(), resolved.hash())) : null);
    }

    private Map<String, Lock.Entry> planContent(Manifest manifest, Map<String, Lock.Entry> locked,
                                                Predicate<String> refresh) {
        boolean upToDate = manifest.content().entrySet().stream().allMatch(e -> {
            Lock.Entry have = locked.get(e.getKey());
            return have != null && !refresh.test(e.getKey()) && Resolver.satisfies(have, e.getValue(), manifest);
        });
        if (!upToDate) return resolver.resolve(manifest, locked, refresh);
        return Resolver.finish(Resolver.prune(locked, manifest.content().keySet()), manifest);
    }

    /**
     * Entries without an upstream hash (url entries, some Hangar links) are downloaded once, to a temp file, and
     * locked by their sha256. The locked hash is reused while the URL stays the same; a refreshed url entry
     * accepts whatever the URL serves now.
     */
    private Lock.Entry hash(String key, Lock.Entry e, Lock.Entry old, boolean refreshed) {
        if (e.hash() != null) return e;
        boolean accept = refreshed && Resolver.source(key).equals("url");
        if (old != null && !accept && old.hash() != null && Objects.equals(old.url(), e.url())) {
            return e.withHash(old.hash(), old.sha1());
        }
        log("hashing " + key);
        Http.Fetched fetched = http.download(e.url(), TEMP, null);
        Http.deleteQuietly(fetched.file());
        return e.withHash("sha256:" + fetched.sha256(), e.type().equals("resourcepack") ? fetched.sha1() : null);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record GameVersion(String version, String build) {}

    /** from is null when nothing was locked. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record GameChange(GameVersion from, GameVersion to) {}

    /** change is added, removed, updated or kept (a pin upgrade left alone); from / to are versions. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Change(String key, String change, String from, String to, Boolean pinned) {}

    /** null when the game version and build didn't change. */
    private static GameChange gameChange(Lock before, Lock after) {
        Lock.Game a = before.game(), b = after.game();
        if (b == null || (a != null && a.version().equals(b.version()) && Objects.equals(a.build(), b.build()))) return null;
        return new GameChange(a == null ? null : new GameVersion(a.version(), a.build()),
                new GameVersion(b.version(), b.build()));
    }

    private static List<Change> changes(Lock before, Lock after, Manifest manifest, Set<String> kept) {
        var keys = new TreeSet<>(before.content().keySet());
        keys.addAll(after.content().keySet());
        var changes = new ArrayList<Change>();
        for (String key : keys) {
            Lock.Entry x = before.content().get(key), y = after.content().get(key);
            Manifest.Content c = manifest.content().get(key);
            Boolean pinned = c == null || c.url() != null ? null : c.pinned();
            String from = x == null ? null : x.version(), to = y == null ? null : y.version();
            if (kept.contains(key)) changes.add(new Change(key, "kept", from, to, pinned));
            else if (x == null) changes.add(new Change(key, "added", null, to, pinned));
            else if (y == null) changes.add(new Change(key, "removed", from, null, pinned));
            else if (!same(x, y)) changes.add(new Change(key, "updated", from, to, pinned));
        }
        return changes;
    }

    /** Same version; url entries (no version) compare by URL and hash. */
    private static boolean same(Lock.Entry x, Lock.Entry y) {
        if (x.versionId() != null) return x.versionId().equals(y.versionId());
        return Objects.equals(x.url(), y.url()) && Objects.equals(x.hash(), y.hash());
    }

    static void printChanges(Lock before, Lock after, Manifest manifest, Set<String> kept, String output) {
        GameChange game = gameChange(before, after);
        List<Change> changes = changes(before, after, manifest, kept);
        if (output.equals("json")) {
            var json = new LinkedHashMap<String, Object>();
            json.put("format", 2);
            json.put("game", game);
            json.put("content", changes);
            System.out.println(Json.MAPPER.writeValueAsString(json));
            return;
        }
        if (game != null) {
            log("game: " + (game.from() == null ? "" : label(game.from()) + " -> ") + label(game.to()));
        }
        for (Change c : changes) {
            log(c.key() + switch (c.change()) {
                case "added" -> ": added" + (c.to() == null ? "" : " " + c.to());
                case "removed" -> ": removed";
                case "kept" -> ": kept " + c.from() + " (pinned)";
                default -> c.from() == null ? ": file changed" : ": " + c.from() + " -> " + c.to();
            });
        }
        long changed = changes.stream().filter(c -> !c.change().equals("kept")).count();
        log(changed == 0 ? "content is up to date" : changed + " content change(s)");
    }

    private static String label(GameVersion v) {
        return v.version() + (v.build() == null ? "" : " build " + v.build());
    }

    /** One row of evoker list; also its JSON shape. pinned is null for dependencies and URL entries. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Listed(String key, String type, String projectId, String version, Boolean pinned, List<String> sides,
                  boolean optional, List<String> requiredBy) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ListedGame(String version, String loader, String build, boolean pinned) {}

    /** list [type] [--output=text|json]: what evoker.json asks for and evoker.lock has, offline. */
    String list(String type, String output) {
        if (type != null) type = Manifest.checkType(type.endsWith("s") ? type.substring(0, type.length() - 1) : type);
        Manifest manifest = Manifest.read(dir);
        Lock lock = Lock.read(dir);
        Manifest.Game g = manifest.game();
        var game = new ListedGame(g.version(), g.loader(), lock.game() == null ? g.build() : lock.game().build(),
                !g.build().equals("latest"));
        var rows = new ArrayList<Listed>();
        var keys = new TreeSet<>(manifest.content().keySet());
        keys.addAll(lock.content().keySet());
        for (String key : keys) {
            Manifest.Content wanted = manifest.content().get(key);
            Lock.Entry entry = lock.content().get(key);
            Boolean pinned = wanted == null || wanted.url() != null ? null : wanted.pinned();
            Listed row = entry == null
                    ? new Listed(key, wanted.type(), null, wanted.version(), pinned, null, wanted.isOptional(), List.of())
                    : new Listed(key, entry.type(), entry.projectId(), entry.version(), pinned, entry.sides(),
                            Boolean.TRUE.equals(entry.optional()),
                            entry.requiredBy() == null ? List.of() : entry.requiredBy());
            if (type == null || type.equals(row.type())) rows.add(row);
        }
        if (output.equals("json")) {
            var json = new LinkedHashMap<String, Object>();
            json.put("format", 2);
            json.put("game", game);
            json.put("content", rows);
            return Json.MAPPER.writeValueAsString(json);
        }
        var out = new StringBuilder(manifest.name() + " (" + manifest.side() + "): " + g.loader() + " " + g.version()
                + (game.build() == null ? "" : " build " + game.build()) + (game.pinned() ? " (pinned)" : "") + "\n");
        int keyWidth = rows.stream().mapToInt(r -> r.key().length()).max().orElse(0);
        int versionWidth = rows.stream().mapToInt(r -> Objects.requireNonNullElse(r.version(), "-").length()).max().orElse(0);
        var groups = new LinkedHashMap<String, List<Listed>>();
        for (String t : Manifest.TYPES) groups.put(t + "s", new ArrayList<>());
        groups.put("not locked yet", new ArrayList<>());
        for (Listed r : rows) groups.get(r.sides() == null || r.type() == null ? "not locked yet" : r.type() + "s").add(r);
        groups.forEach((group, members) -> {
            if (members.isEmpty()) return;
            out.append("\n").append(group).append("\n");
            for (Listed r : members) {
                String sides = r.sides() == null ? "-" : r.sides().size() == 2 ? "both" : r.sides().get(0);
                String status = r.sides() == null ? "run evoker update"
                        : r.pinned() != null ? (r.pinned() ? "pinned" : "latest")
                        : !r.requiredBy().isEmpty() ? "dependency of " + String.join(", ", r.requiredBy())
                        : "url";
                out.append(String.format("  %-" + keyWidth + "s  %-" + versionWidth + "s  %-6s  %s%s%n",
                        r.key(), Objects.requireNonNullElse(r.version(), "-"), sides, status,
                        r.optional() ? ", optional" : ""));
            }
        });
        return out.toString().stripTrailing();
    }
}
