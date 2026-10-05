package com.gavinhsmith.evoker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;

/** A Prism Launcher instance a pack is installed into: creating it, client content, optional choices. */
final class PrismInstance {
    static final String STATE = ".evoker";

    /** Files evoker installed, relative to the instance's game folder. */
    record Installed(List<String> files) {
        Installed {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** The optional entries the player chose. */
    record Options(List<String> chosen) {
        Options {
            chosen = chosen == null ? List.of() : List.copyOf(chosen);
        }
    }

    /** Where Prism keeps instances and icons. */
    record Folders(Path instances, Path icons) {}

    private final Path dir;
    private final Path game;
    private final Path state;
    private final Http http;

    PrismInstance(Path dir, Http http) {
        this.dir = dir;
        // Prism names the game folder minecraft/; instances from older launchers use .minecraft/.
        this.game = Files.isDirectory(dir.resolve(".minecraft")) && !Files.isDirectory(dir.resolve("minecraft"))
                ? dir.resolve(".minecraft") : dir.resolve("minecraft");
        this.state = dir.resolve(STATE);
        this.http = http;
    }

    /** The user's instanceDir setting, or Prism's own settings in its usual data folder. */
    static Folders folders() {
        String configured = Config.user().string("instanceDir");
        if (!configured.isEmpty()) {
            Path instances = Path.of(configured).toAbsolutePath();
            if (!Files.isDirectory(instances)) {
                throw new EvokerException("instanceDir " + instances + " doesn't exist; change it with evoker config instanceDir <path>");
            }
            return new Folders(instances, instances.getParent().resolve("icons"));
        }
        for (Path data : dataDirs()) {
            if (Files.isDirectory(data)) return folders(data);
        }
        throw new EvokerException("Prism Launcher's folder wasn't found; tell evoker where its instances are:"
                + " evoker config instanceDir <path>");
    }

    /** InstanceDir and IconsDir from prismlauncher.cfg, relative to Prism's data folder unless absolute. */
    static Folders folders(Path data) {
        Map<String, String> cfg = readIni(data.resolve("prismlauncher.cfg"));
        return new Folders(data.resolve(cfg.getOrDefault("InstanceDir", "instances")),
                data.resolve(cfg.getOrDefault("IconsDir", "icons")));
    }

    /** Prism's usual data folders on this OS. */
    private static List<Path> dataDirs() {
        String home = System.getProperty("user.home"), os = System.getProperty("os.name");
        if (os.startsWith("Windows")) {
            String appData = System.getenv("APPDATA");
            return List.of((appData != null ? Path.of(appData) : Path.of(home, "AppData", "Roaming")).resolve("PrismLauncher"));
        }
        if (os.startsWith("Mac")) return List.of(Path.of(home, "Library", "Application Support", "PrismLauncher"));
        String xdg = System.getenv("XDG_DATA_HOME");
        return List.of((xdg != null && !xdg.isEmpty() ? Path.of(xdg) : Path.of(home, ".local", "share")).resolve("PrismLauncher"),
                Path.of(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"));
    }

    /** install <pack-url> | --local: the pack as a new instance, then the optional questions. */
    static void install(Http http, Pack.Source source) {
        Pack pack = Pack.fetch(http, source);
        String name = pack.manifest().name();
        if (!pack.manifest().sides().contains("client")) {
            throw new EvokerException(name + " is a server pack; install it with evoker install server");
        }
        if (pack.lock().game() == null) throw new EvokerException("the pack's " + Lock.FILE + " has no game version");
        Folders folders = folders();
        String folder = folderName(name);
        Path dir = folders.instances().resolve(folder);
        for (int n = 2; Files.exists(dir); n++) {
            if (source.equals(new PrismInstance(dir, http).source())) {
                throw new EvokerException(name + " is already installed in " + dir + " (update it with evoker client update)");
            }
            dir = folders.instances().resolve(folder + " (" + n + ")");
        }

        var instance = new PrismInstance(dir, http);
        String iconKey = "default";
        Pack.Icon icon = Pack.icon(http, source);
        if (icon != null) {
            iconKey = "evoker-" + dir.getFileName();
            write(folders.icons().resolve(iconKey + "." + icon.extension()), icon.data());
        }
        instance.create(name, pack.lock().game(), iconKey);
        Json.write(instance.state.resolve("source.json"), source);
        instance.apply(pack, instance.ask(pack, Set.of()));
        Main.log("installed " + name + " as a Prism instance (" + dir + "); open Prism Launcher and launch it");
    }

    /** client options <pack>: asks about every optional entry again, and installs or removes files to match. */
    static void options(Http http, String ref) {
        PrismInstance instance = find(http, ref);
        if (System.console() == null) throw new EvokerException("choosing optional content needs a console to ask in");
        Pack pack = instance.installedPack();
        instance.apply(pack, instance.ask(pack, Set.copyOf(instance.options().chosen())));
    }

    /** A pack's instance: by its folder, or by name in Prism's instance folder. */
    static PrismInstance find(Http http, String ref) {
        var direct = new PrismInstance(Path.of(ref).toAbsolutePath(), http);
        if (direct.source() != null) return direct;
        Path instances = folders().instances();
        var byFolder = new PrismInstance(instances.resolve(folderName(ref)), http);
        if (byFolder.source() != null) return byFolder;
        try (var dirs = Files.list(instances)) {
            for (Path d : dirs.toList()) {
                var instance = new PrismInstance(d, http);
                if (instance.source() != null && ref.equals(readIni(d.resolve("instance.cfg")).get("name"))) return instance;
            }
        } catch (IOException e) {
            throw new EvokerException("cannot list " + instances + ": " + e.getMessage(), e);
        }
        throw new EvokerException("no pack named " + ref + " is installed in " + instances);
    }

    /** Writes instance.cfg, mmc-pack.json (the game and loader; Prism adds the rest) and the game folder. */
    void create(String name, Lock.Game version, String iconKey) {
        write(dir.resolve("instance.cfg"), ("[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=" + quote(name)
                + "\niconKey=" + quote(iconKey) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var components = new ArrayList<Map<String, Object>>();
        components.add(component("net.minecraft", version.version(), true));
        String loader = loaderUid(version.loader());
        if (loader != null) components.add(component(loader, version.build(), false));
        var pack = new LinkedHashMap<String, Object>();
        pack.put("components", components);
        pack.put("formatVersion", 1);
        Json.write(dir.resolve("mmc-pack.json"), pack);
        try {
            Files.createDirectories(game);
        } catch (IOException e) {
            throw new EvokerException("cannot create " + game + ": " + e.getMessage(), e);
        }
    }

    /** Prism's component for a loader; plugin-server packs and vanilla have none (a vanilla client). */
    static String loaderUid(String loader) {
        return switch (loader) {
            case "fabric" -> "net.fabricmc.fabric-loader";
            case "quilt" -> "org.quiltmc.quilt-loader";
            case "neoforge" -> "net.neoforged";
            default -> null;
        };
    }

    private static Map<String, Object> component(String uid, String version, boolean important) {
        var c = new LinkedHashMap<String, Object>();
        c.put("uid", uid);
        c.put("version", version);
        if (important) c.put("important", true);
        return c;
    }

    /**
     * Asks about each optional client entry; previous answers say what is installed now. Without a console,
     * keeps the previous answers (new optional entries are left out).
     */
    Set<String> ask(Pack pack, Set<String> previous) {
        var optional = new ArrayList<String>();
        pack.lock().content().forEach((key, e) -> {
            if (Boolean.TRUE.equals(e.optional()) && e.sides().contains("client")) optional.add(key);
        });
        var chosen = new TreeSet<String>();
        if (System.console() == null) {
            optional.stream().filter(previous::contains).forEach(chosen::add);
            long left = optional.size() - chosen.size();
            if (left > 0) {
                Main.log(left + " optional entries left out (no console to ask in); choose with: evoker client options \""
                        + pack.manifest().name() + "\"");
            }
            return chosen;
        }
        for (String key : optional) {
            Lock.Entry e = pack.lock().content().get(key);
            String question = "Optional: " + Objects.requireNonNullElse(e.title(), key)
                    + (e.description() == null ? "" : " (" + e.description() + ")")
                    + (previous.contains(key) ? ", installed now." : ".") + " Install it?";
            if (Main.ask(question)) chosen.add(key);
        }
        return chosen;
    }

    /**
     * Installs every client entry the pack needs (optional ones only if chosen, dependencies only if something
     * installed needs them), deletes files evoker installed that are no longer wanted, and records it all.
     */
    void apply(Pack pack, Set<String> chosen) {
        Lock lock = pack.lock();
        Installed before = read("installed.json", Installed.class);
        var installer = new Installer(game, http);
        var files = new TreeSet<String>();
        for (String key : wanted(lock, pack.manifest().content().keySet(), chosen)) {
            Lock.Entry entry = lock.content().get(key);
            Path path = path(key, entry);
            if (path == null) continue;
            try {
                installer.fetch(entry.version() == null ? key : key + " " + entry.version(), entry.url(), path, entry.hash());
            } catch (EvokerException e) {
                Main.warn(e.getMessage() + "; keeping what is installed");
            }
            files.add(game.relativize(path).toString().replace('\\', '/'));
        }
        if (before != null) {
            for (String old : before.files()) {
                if (!files.contains(old)) installer.delete("no longer wanted", game.resolve(old));
            }
        }
        Json.write(state.resolve("installed.json"), new Installed(List.copyOf(files)));
        Json.write(state.resolve("options.json"), new Options(List.copyOf(chosen)));
        pack.manifest().write(state);
        lock.write(state);
    }

    /** Client entries to install: evoker.json entries (optional ones if chosen), then what they need. */
    static Set<String> wanted(Lock lock, Set<String> explicit, Set<String> chosen) {
        var wanted = new TreeSet<String>();
        lock.content().forEach((key, e) -> {
            if (e.sides().contains("client") && explicit.contains(key) && (!Boolean.TRUE.equals(e.optional()) || chosen.contains(key))) {
                wanted.add(key);
            }
        });
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var e : lock.content().entrySet()) {
                List<String> by = e.getValue().requiredBy();
                if (!wanted.contains(e.getKey()) && e.getValue().sides().contains("client") && by != null
                        && by.stream().anyMatch(wanted::contains)) {
                    wanted.add(e.getKey());
                    grew = true;
                }
            }
        }
        return wanted;
    }

    /** Where a content entry lives in the game folder: {@code <source>-<projectId>.<ext>}; null for server-only types. */
    Path path(String key, Lock.Entry entry) {
        String name = Resolver.source(key) + "-" + entry.projectId();
        return switch (entry.type()) {
            case "mod" -> game.resolve("mods").resolve(name + ".jar");
            case "resourcepack" -> game.resolve("resourcepacks").resolve(name + ".zip");
            case "shaderpack" -> game.resolve("shaderpacks").resolve(name + ".zip");
            default -> null;
        };
    }

    Pack.Source source() {
        return read("source.json", Pack.Source.class);
    }

    Options options() {
        Options options = read("options.json", Options.class);
        return options != null ? options : new Options(null);
    }

    Pack installedPack() {
        return new Pack(Manifest.read(state), Lock.read(state));
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

    /** A pack name as a folder name: characters Windows doesn't allow become '-'. */
    static String folderName(String name) {
        String folder = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "-").strip();
        return folder.isEmpty() || folder.matches("\\.+") ? "pack" : folder;
    }

    /** A value for Prism's INI files (QSettings): quoted, so commas and other special characters stay literal. */
    static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** key=value pairs of a QSettings INI file, any section; quoted values unquoted. Missing file: empty. */
    static Map<String, String> readIni(Path file) {
        var values = new LinkedHashMap<String, String>();
        if (!Files.exists(file)) return values;
        try {
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (line.startsWith("[") || line.startsWith(";") || eq < 0) continue;
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                }
                values.put(line.substring(0, eq).strip(), value);
            }
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return values;
    }

    private static void write(Path file, byte[] data) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data);
        } catch (IOException e) {
            throw new EvokerException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
