package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** install <pack> as a Prism instance end to end, against a fake Prism data folder and upstream. */
class PrismInstanceTest {
    @TempDir
    Path prism;

    @TempDir
    Path packs;

    @TempDir
    Path user;

    final FakeApi api = new FakeApi();
    final Map<String, Lock.Entry> content = new TreeMap<>();
    final Map<String, Manifest.Content> wanted = new TreeMap<>();

    @BeforeEach
    void prismFolder() throws IOException {
        System.setProperty("evoker.configDir", user.toString());
        Files.createDirectories(prism.resolve("instances"));
        Config.user().set("instanceDir", prism.resolve("instances").toString());
    }

    @AfterEach
    void close() {
        api.close();
        System.clearProperty("evoker.configDir");
    }

    private int run(String... args) {
        return Main.run(packs, api.apis(), args);
    }

    private Path pack() {
        return packs.resolve("pack");
    }

    private Path instance(String folder) {
        return prism.resolve("instances").resolve(folder);
    }

    /** An entry served at /files/<projectId>, its content the project id. */
    private void entry(String key, String type, String sides, String optional, String... requiredBy) {
        String id = key.substring(key.indexOf(':') + 1);
        api.bytes("/files/" + id, id.getBytes());
        content.put(key, new Lock.Entry(type, Manifest.sides(sides), optional == null ? null : true, optional, null, id,
                "v1", "1.0", api.base + "/files/" + id, "sha256:" + FakeServer.hash("SHA-256", id.getBytes()), null,
                requiredBy.length == 0 ? null : List.of(requiredBy)));
        if (requiredBy.length == 0) wanted.put(key, Manifest.Content.of("latest"));
    }

    private void writePack(String name, String side) {
        new Manifest(name, side, new Manifest.Game("1.21.4", "fabric", null), wanted).write(pack());
        new Lock(Lock.VERSION, new Lock.Game("1.21.4", "fabric", "0.19.5"), null, content, Overrides.scan(pack())).write(pack());
    }

    /** sodium (client), lithium (server), iris (optional, needs irislib), a resource pack, a shader, a data pack. */
    private void fabricPack() {
        entry("modrinth:sodium", "mod", "client", null);
        entry("modrinth:lithium", "mod", "server", null);
        entry("modrinth:iris", "mod", "client", "Iris Shaders");
        entry("modrinth:irislib", "mod", "client", null, "modrinth:iris");
        entry("modrinth:faithful", "resourcepack", "client", null);
        entry("modrinth:bsl", "shaderpack", "client", null);
        entry("modrinth:terralith", "datapack", "server", null);
        writePack("My Pack", "both");
    }

    @Test
    void installCreatesAnInstanceWithTheClientContent() throws IOException {
        fabricPack();
        Files.write(pack().resolve("icon.png"), new byte[] {1, 2, 3});

        String out = Output.out(() -> assertEquals(0, run("install", "--local", "pack")));

        Path dir = instance("My Pack");
        String cfg = Files.readString(dir.resolve("instance.cfg"));
        assertTrue(cfg.contains("InstanceType=OneSix"), cfg);
        assertTrue(cfg.contains("name=\"My Pack\""), cfg);
        assertTrue(cfg.contains("iconKey=\"evoker-My Pack\""), cfg);
        assertTrue(Files.exists(prism.resolve("icons/evoker-My Pack.png")));
        var components = Json.MAPPER.readTree(dir.resolve("mmc-pack.json").toFile()).path("components");
        assertEquals("net.minecraft", components.get(0).path("uid").asString());
        assertEquals("1.21.4", components.get(0).path("version").asString());
        assertEquals("net.fabricmc.fabric-loader", components.get(1).path("uid").asString());
        assertEquals("0.19.5", components.get(1).path("version").asString());

        Path game = dir.resolve("minecraft");
        assertEquals("sodium", Files.readString(game.resolve("mods/modrinth-sodium.jar")));
        assertEquals("faithful", Files.readString(game.resolve("resourcepacks/modrinth-faithful.zip")));
        assertEquals("bsl", Files.readString(game.resolve("shaderpacks/modrinth-bsl.zip")));
        assertFalse(Files.exists(game.resolve("mods/modrinth-lithium.jar")), "server-only");
        assertFalse(Files.exists(game.resolve("mods/modrinth-iris.jar")), "optional, and no console to ask in");
        assertFalse(Files.exists(game.resolve("mods/modrinth-irislib.jar")), "only an unchosen entry needs it");
        assertTrue(out.contains("1 optional entries left out"), out);
        assertTrue(Files.readString(dir.resolve(".evoker/source.json")).contains("\"path\""));
    }

    @Test
    void clientOverridesGoIntoTheGameFolder() throws IOException {
        Files.createDirectories(pack().resolve("overrides/config"));
        Files.writeString(pack().resolve("overrides/config/a.toml"), "both");
        Files.createDirectories(pack().resolve("client-overrides"));
        Files.writeString(pack().resolve("client-overrides/options.txt"), "renderDistance:12");
        Files.createDirectories(pack().resolve("server-overrides"));
        Files.writeString(pack().resolve("server-overrides/server.properties"), "motd=x");
        fabricPack();

        Output.out(() -> assertEquals(0, run("install", "--local", "pack")));

        Path game = instance("My Pack").resolve("minecraft");
        assertEquals("both", Files.readString(game.resolve("config/a.toml")));
        assertEquals("renderDistance:12", Files.readString(game.resolve("options.txt")));
        assertFalse(Files.exists(game.resolve("server.properties")));
    }

    @Test
    void choosingAnOptionalEntryInstallsItAndItsDependencies() throws IOException {
        fabricPack();
        Output.out(() -> assertEquals(0, run("install", "--local", "pack")));
        var instance = new PrismInstance(instance("My Pack"), new Http());
        Pack pack = instance.installedPack();
        Path mods = instance("My Pack").resolve("minecraft/mods");

        instance.apply(pack, Set.of("modrinth:iris"));
        assertEquals("irislib", Files.readString(mods.resolve("modrinth-irislib.jar")));
        assertEquals(List.of("modrinth:iris"), instance.options().chosen());

        Files.writeString(mods.resolve("mine.jar"), "mine");
        Output.out(() -> instance.apply(pack, Set.of()));
        assertFalse(Files.exists(mods.resolve("modrinth-iris.jar")));
        assertFalse(Files.exists(mods.resolve("modrinth-irislib.jar")));
        assertEquals("mine", Files.readString(mods.resolve("mine.jar")), "files you add stay");
    }

    @Test
    void installingAgainIsAnErrorButAnotherPackWithTheSameNameGetsANumber() throws IOException {
        fabricPack();
        Output.out(() -> assertEquals(0, run("install", "--local", "pack")));

        String err = Output.err(() -> assertEquals(1, run("install", "--local", "pack")));
        assertTrue(err.contains("already installed"), err);

        Files.createDirectories(packs.resolve("other"));
        Files.copy(pack().resolve(Manifest.FILE), packs.resolve("other").resolve(Manifest.FILE));
        Files.copy(pack().resolve(Lock.FILE), packs.resolve("other").resolve(Lock.FILE));
        Output.out(() -> assertEquals(0, run("install", "client", "--local", "other")));
        assertTrue(Files.exists(instance("My Pack (2)").resolve("instance.cfg")));
    }

    @Test
    void serverPacksAreRefused() {
        writePack("Plugins", "server");
        String err = Output.err(() -> assertEquals(1, run("install", "--local", "pack")));
        assertTrue(err.contains("is a server pack"), err);
    }

    @Test
    void aMissingInstanceFolderSaysHowToSetIt() {
        writePack("P", "client");
        Config.user().set("instanceDir", prism.resolve("nowhere").toString());
        String err = Output.err(() -> assertEquals(1, run("install", "--local", "pack")));
        assertTrue(err.contains("evoker config instanceDir"), err);
    }

    @Test
    void optionsNeedsAConsole() {
        fabricPack();
        Output.out(() -> assertEquals(0, run("install", "--local", "pack")));
        String err = Output.err(() -> assertEquals(1, run("client", "options", "My Pack")));
        assertTrue(err.contains("needs a console"), err);
        err = Output.err(() -> assertEquals(1, run("client", "options", "Nope")));
        assertTrue(err.contains("no pack named Nope"), err);
    }

    private void installFabricPack() {
        fabricPack();
        Output.out(() -> assertEquals(0, run("install", "--local", "pack")));
    }

    private int update() {
        return run("client", "update", instance("My Pack").toString());
    }

    @Test
    void installSetsThePreLaunchCommand() {
        installFabricPack();

        var cfg = PrismInstance.readIni(instance("My Pack").resolve("instance.cfg"));

        assertEquals("true", cfg.get("OverrideCommands"));
        assertEquals("\"$INST_JAVA\" -jar \"" + Main.jar() + "\" client update \"$INST_DIR\"", cfg.get("PreLaunchCommand"));
    }

    @Test
    void updateInstallsWhatChangedAndLetsTheLaunchGoOn() throws IOException {
        installFabricPack();
        entry("modrinth:entity-culling", "mod", "client", null);
        content.remove("modrinth:bsl");
        writePack("My Pack", "both");

        String out = Output.out(() -> assertEquals(0, update()));

        Path game = instance("My Pack").resolve("minecraft");
        assertTrue(Files.exists(game.resolve("mods/modrinth-entity-culling.jar")));
        assertFalse(Files.exists(game.resolve("shaderpacks/modrinth-bsl.zip")));
        assertTrue(out.contains("modrinth:entity-culling: added"), out);
    }

    @Test
    void aNewGameVersionStopsTheLaunchOnce() throws IOException {
        installFabricPack();
        Path mmc = instance("My Pack").resolve("mmc-pack.json");
        // What Prism makes of it on the first launch: dependencies and cached fields added, plus a player's own component.
        Files.writeString(mmc, """
                {"components": [
                  {"uid": "org.lwjgl3", "version": "3.3.3", "dependencyOnly": true},
                  {"uid": "net.minecraft", "version": "1.21.4", "important": true, "cachedVersion": "1.21.4"},
                  {"uid": "net.fabricmc.intermediary", "version": "1.21.4", "dependencyOnly": true},
                  {"uid": "net.fabricmc.fabric-loader", "version": "0.19.5"},
                  {"uid": "custom.agent", "version": "1"}
                ], "formatVersion": 1}
                """);
        new Lock(Lock.VERSION, new Lock.Game("1.21.5", "fabric", "0.20.0"), null, content).write(pack());

        String out = Output.out(() -> assertEquals(1, update()));

        assertTrue(out.contains("My Pack was updated to Minecraft 1.21.5 (fabric 0.20.0). Press Launch again."), out);
        var components = Json.MAPPER.readTree(mmc.toFile()).path("components");
        assertEquals(3, components.size(), components.toString());
        assertEquals("1.21.5", components.get(0).path("version").asString());
        assertEquals("0.20.0", components.get(1).path("version").asString());
        assertEquals("custom.agent", components.get(2).path("uid").asString(), "the player's own component stays");

        assertEquals(0, update(), "the next launch goes ahead");

        // Prism saved its old copy over ours: the next update puts it right again.
        Files.writeString(mmc, Files.readString(mmc).replace("1.21.5", "1.21.4"));
        Output.out(() -> assertEquals(1, update()));
    }

    @Test
    void aDifferentLoaderReplacesTheOldOne() throws IOException {
        installFabricPack();
        new Lock(Lock.VERSION, new Lock.Game("1.21.4", "quilt", "0.29.2"), null, content).write(pack());

        Output.out(() -> assertEquals(1, update()));

        String mmc = Files.readString(instance("My Pack").resolve("mmc-pack.json"));
        assertTrue(mmc.contains("org.quiltmc.quilt-loader") && !mmc.contains("fabric-loader"), mmc);
    }

    @Test
    void anUnreachablePackDoesNotBlockTheLaunch() throws IOException {
        installFabricPack();
        Files.delete(pack().resolve(Lock.FILE));

        String err = Output.err(() -> assertEquals(0, update()));

        assertTrue(err.contains("cannot fetch the pack"), err);
        assertTrue(Files.exists(instance("My Pack").resolve("minecraft/mods/modrinth-sodium.jar")));
    }

    @Test
    void newOptionalEntriesAreLeftOutWithoutAConsoleAndEarlierAnswersStay() throws IOException {
        installFabricPack();
        var instance = new PrismInstance(instance("My Pack"), new Http());
        instance.apply(instance.installedPack(), Set.of("modrinth:iris"));
        entry("modrinth:distant-horizons", "mod", "client", "Distant Horizons");
        writePack("My Pack", "both");

        String out = Output.out(() -> assertEquals(0, update()));

        Path mods = instance("My Pack").resolve("minecraft/mods");
        assertTrue(Files.exists(mods.resolve("modrinth-iris.jar")), "chosen before");
        assertFalse(Files.exists(mods.resolve("modrinth-distant-horizons.jar")));
        assertTrue(out.contains("1 optional entries left out"), out);
    }

    @Test
    void updateRepairsThePreLaunchCommandAndKeepsTheRestOfInstanceCfg() throws IOException {
        installFabricPack();
        Path cfg = instance("My Pack").resolve("instance.cfg");
        Files.writeString(cfg, Files.readString(cfg).replaceAll("PreLaunchCommand=.*", "PreLaunchCommand=\"old\"")
                + "lastLaunchTime=123\n");

        String out = Output.out(() -> assertEquals(0, update()));

        var values = PrismInstance.readIni(cfg);
        assertEquals(PrismInstance.preLaunchCommand(), values.get("PreLaunchCommand"));
        assertEquals("123", values.get("lastLaunchTime"));
        assertTrue(out.contains("pointed the pre-launch command"), out);
        assertFalse(Output.out(() -> assertEquals(0, update())).contains("pointed"), "written only when it changed");
    }

    @Test
    void theHookSurvivesPrismRewritingInstanceCfg() throws IOException {
        installFabricPack();
        Path cfg = instance("My Pack").resolve("instance.cfg");
        // How Prism 11 saves it: unquoted, escapes kept.
        String prism = PrismInstance.preLaunchCommand().replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(cfg, Files.readString(cfg).replaceAll("PreLaunchCommand=.*",
                java.util.regex.Matcher.quoteReplacement("PreLaunchCommand=" + prism)));

        assertFalse(Output.out(() -> assertEquals(0, update())).contains("pointed"));
    }

    @Test
    void setIniAddsMissingKeysToGeneral() throws IOException {
        Path file = prism.resolve("x.cfg");
        Files.writeString(file, "[General]\na=1\n[UI]\nb=2\n");

        PrismInstance.setIni(file, Map.of("a", "3", "c", "4"));

        assertEquals("[General]\nc=4\na=3\n[UI]\nb=2\n", Files.readString(file).replace("\r\n", "\n"));
    }

    @Test
    void prismSettings() throws IOException {
        Path data = Files.createDirectories(prism.resolve("data"));
        assertEquals(new PrismInstance.Folders(data.resolve("instances"), data.resolve("icons")), PrismInstance.folders(data));

        Path elsewhere = prism.resolve("elsewhere").toAbsolutePath();
        Files.writeString(data.resolve("prismlauncher.cfg"), """
                [General]
                IconsDir=my icons
                InstanceDir=%s
                Quoted="D:\\\\Games\\\\Prism \\"x\\", 2"
                """.formatted(elsewhere.toString().replace('\\', '/')));
        var folders = PrismInstance.folders(data);
        assertEquals(data.resolve("my icons"), folders.icons());
        assertEquals(elsewhere, folders.instances(), "absolute paths stay absolute");
        assertEquals("D:\\Games\\Prism \"x\", 2", PrismInstance.readIni(data.resolve("prismlauncher.cfg")).get("Quoted"));
    }

    @Test
    void namesAndQuoting() {
        assertEquals("a-b- c", PrismInstance.folderName("a/b: c"));
        assertEquals("pack", PrismInstance.folderName(".."));
        assertEquals("\"Wheels\\\\ \\\"Pack\\\", 2\"", PrismInstance.quote("Wheels\\ \"Pack\", 2"));
        assertEquals("net.neoforged", PrismInstance.loaderUid("neoforge"));
        assertEquals(null, PrismInstance.loaderUid("paper"));
    }
}
