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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
        instance.apply(pack, instance.ask(pack, Set.of(), Set.of()));
        Main.log("installed " + name + " as a Prism instance (" + dir + "); open Prism Launcher and launch it");
    }

    /**
     * client update <pack>, also what Prism runs before every launch: fetches the pack, installs what changed and
     * returns 0, or, when the instance's game or loader version had to change, returns 1 so Prism stops this launch
     * (it read the versions before running us). Never blocks a launch otherwise: if the pack can't be fetched or a
     * file can't be downloaded, what is installed stays.
     */
    static int update(Http http, String ref) {
        PrismInstance instance = find(http, ref);
        instance.hook();
        Pack installed = instance.installedPack();
        Pack pack;
        try {
            pack = Pack.fetch(http, instance.source());
        } catch (EvokerException e) {
            Main.warn("cannot fetch the pack (" + e.getMessage() + "); launching with what is installed");
            pack = installed;
        }
        // Earlier answers stay; optional entries that are new in this version of the pack are asked about.
        var known = new TreeSet<String>();
        installed.lock().content().forEach((key, e) -> {
            if (Boolean.TRUE.equals(e.optional())) known.add(key);
        });
        var chosen = new TreeSet<>(instance.options().chosen());
        Set<String> asked = instance.ask(pack, chosen, known);
        instance.apply(pack, asked);
        Main.printChanges(clientSide(installed.lock()), clientSide(pack.lock()), pack.manifest().withContent(Map.of()),
                Set.of(), "text");

        Lock.Game game = pack.lock().game();
        if (instance.components(game)) {
            String message = pack.manifest().name() + " was updated to Minecraft " + game.version()
                    + (loaderUid(game.loader()) == null ? "" : " (" + game.loader() + " " + game.build() + ")")
                    + ". Press Launch again.";
            Main.log(message);
            // Under Prism: guard the change while the player reads the message.
            Thread guard = null;
            if (System.getenv("INST_ID") != null) {
                guard = new Thread(() -> instance.guardComponents(game));
                guard.start();
            }
            if (Dialogs.available()) Dialogs.message(pack.manifest().name(), message);
            if (guard != null) {
                try {
                    guard.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return 1;
        }
        return 0;
    }

    /** client options <pack>: asks about every optional entry again, and installs or removes files to match. */
    static void options(Http http, String ref) {
        PrismInstance instance = find(http, ref);
        if (System.console() == null) throw new EvokerException("choosing optional content needs a console to ask in");
        Pack pack = instance.installedPack();
        instance.apply(pack, instance.ask(pack, Set.copyOf(instance.options().chosen()), Set.of()));
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

    /**
     * Writes instance.cfg (with the pre-launch hook), mmc-pack.json (the game and loader; Prism adds the rest)
     * and the game folder.
     */
    void create(String name, Lock.Game version, String iconKey) {
        write(dir.resolve("instance.cfg"), ("[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=" + quote(name)
                + "\niconKey=" + quote(iconKey) + "\nOverrideCommands=true\nPreLaunchCommand=" + quote(preLaunchCommand())
                + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    /**
     * What Prism runs before every launch: this evoker.jar on the instance's own Java. Prism fills in $INST_JAVA
     * and $INST_DIR itself, on every OS.
     */
    static String preLaunchCommand() {
        return "\"$INST_JAVA\" -jar \"" + Main.jar() + "\" client update \"$INST_DIR\"";
    }

    /** Points the pre-launch command at this evoker.jar (it may have moved); writes instance.cfg only if it changed. */
    void hook() {
        Path cfg = dir.resolve("instance.cfg");
        Map<String, String> values = readIni(cfg);
        if ("true".equals(values.get("OverrideCommands")) && preLaunchCommand().equals(values.get("PreLaunchCommand"))) return;
        setIni(cfg, Map.of("OverrideCommands", "true", "PreLaunchCommand", quote(preLaunchCommand())));
        Main.log("pointed the pre-launch command at " + Main.jar());
    }

    /**
     * Makes mmc-pack.json run this game version and loader; true if it had to change. Components Prism added as
     * dependencies (LWJGL, mappings) are dropped so Prism resolves them again for the new versions.
     */
    boolean components(Lock.Game version) {
        Path file = dir.resolve("mmc-pack.json");
        ObjectNode pack;
        try {
            pack = (ObjectNode) Json.MAPPER.readTree(file);
        } catch (JacksonException | ClassCastException e) {
            throw new EvokerException("invalid " + file + ": " + e.getMessage(), e);
        }
        String loader = loaderUid(version.loader());
        var have = new LinkedHashMap<String, String>();
        pack.path("components").forEach(c -> have.put(c.path("uid").asString(), c.path("version").asString()));
        boolean otherLoader = List.of("net.fabricmc.fabric-loader", "org.quiltmc.quilt-loader", "net.neoforged").stream()
                .anyMatch(uid -> !uid.equals(loader) && have.containsKey(uid));
        if (version.version().equals(have.get("net.minecraft")) && !otherLoader
                && (loader == null || Objects.equals(version.build(), have.get(loader)))) return false;

        ArrayNode components = Json.MAPPER.createArrayNode();
        components.add(Json.MAPPER.valueToTree(component("net.minecraft", version.version(), true)));
        if (loader != null) components.add(Json.MAPPER.valueToTree(component(loader, version.build(), false)));
        for (JsonNode c : pack.path("components")) {
            String uid = c.path("uid").asString();
            boolean ours = uid.equals("net.minecraft") || have.containsKey(uid) && List.of("net.fabricmc.fabric-loader",
                    "org.quiltmc.quilt-loader", "net.neoforged").contains(uid);
            if (!ours && !c.path("dependencyOnly").asBoolean()) components.add(c); // something the player added
        }
        pack.set("components", components);
        Json.write(file, pack);
        return true;
    }

    /**
     * When Prism runs us before a launch, it saves its own copy of mmc-pack.json about 5 seconds after loading it
     * (while we run), which would undo a version change. Watches for that and redoes the change.
     */
    private void guardComponents(Lock.Game version) {
        Main.log("waiting a few seconds so Prism doesn't undo the version change");
        long deadline = System.nanoTime() + 7_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(250);
                if (components(version)) Main.log("Prism saved its old versions; set them again");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (EvokerException e) {
                // mid-save; look again
            }
        }
    }

    /** The lock with only client-side entries. */
    private static Lock clientSide(Lock lock) {
        var content = new java.util.TreeMap<String, Lock.Entry>();
        lock.content().forEach((key, e) -> {
            if (e.sides() != null && e.sides().contains("client")) content.put(key, e);
        });
        return new Lock(lock.lockVersion(), lock.game(), lock.server(), content);
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
     * Asks about each optional client entry not in known (whose answers are kept); previous answers say what is
     * installed now. Without a console, nothing is asked: new optional entries are left out.
     */
    Set<String> ask(Pack pack, Set<String> previous, Set<String> known) {
        var optional = new ArrayList<String>();
        var chosen = new TreeSet<String>();
        pack.lock().content().forEach((key, e) -> {
            if (!Boolean.TRUE.equals(e.optional()) || !e.sides().contains("client")) return;
            if (known.contains(key)) {
                if (previous.contains(key)) chosen.add(key);
            } else {
                optional.add(key);
            }
        });
        if (optional.isEmpty()) return chosen;
        if (System.console() == null && Dialogs.available()) {
            chosen.addAll(Dialogs.choose(pack.manifest().name(), optional.stream().map(key -> {
                Lock.Entry e = pack.lock().content().get(key);
                return new Dialogs.Choice(key, Objects.requireNonNullElse(e.title(), key), e.description(),
                        previous.contains(key));
            }).toList()));
            return chosen;
        }
        if (System.console() == null) {
            optional.stream().filter(previous::contains).forEach(chosen::add);
            long left = optional.stream().filter(k -> !previous.contains(k)).count();
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
                values.put(line.substring(0, eq).strip(), iniValue(line.substring(eq + 1).strip()));
            }
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return values;
    }

    /**
     * A QSettings INI value as text: quotes only group (they aren't part of the value) and backslash escapes apply
     * inside and outside them. Prism writes {@code \"$INST_JAVA\" ...} unquoted, for example.
     */
    static String iniValue(String raw) {
        var out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') continue;
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Sets keys (values already in INI form) in the [General] section, keeping every other line. */
    static void setIni(Path file, Map<String, String> values) {
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.exists(file) ? Files.readAllLines(file) : List.of());
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
        var missing = new LinkedHashMap<>(values);
        for (int i = 0; i < lines.size(); i++) {
            int eq = lines.get(i).indexOf('=');
            if (eq < 0) continue;
            String value = missing.remove(lines.get(i).substring(0, eq).strip());
            if (value != null) lines.set(i, lines.get(i).substring(0, eq) + "=" + value);
        }
        int general = lines.indexOf("[General]");
        if (general < 0) {
            lines.add(0, "[General]");
            general = 0;
        }
        int at = general + 1;
        for (var e : missing.entrySet()) lines.add(at++, e.getKey() + "=" + e.getValue());
        write(file, (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
