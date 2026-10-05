package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** install server, server update / start / command end to end: a pack on disk or at a URL, a fake upstream. */
class ServerFolderTest {
    @TempDir
    Path dir;

    @TempDir
    Path packs;

    final FakeApi api = new FakeApi();
    final Map<String, Lock.Entry> content = new TreeMap<>();

    @AfterEach
    void close() {
        api.close();
    }

    private int run(String... args) {
        return Main.run(dir, api.apis(), args);
    }

    private Path pack() {
        return packs.resolve("pack");
    }

    private static String sha256(byte[] data) {
        return "sha256:" + FakeServer.hash("SHA-256", data);
    }

    private static Lock.Entry entry(String type, String sides, String projectId, String version, String url, String hash) {
        return new Lock.Entry(type, Manifest.sides(sides), null, null, null, projectId, "v-" + version, version, url,
                hash, null, null);
    }

    /** Writes the pack (side, loader, its server download) with the entries in content. */
    private void writePack(String side, String loader, String build, String serverUrl, String serverHash) {
        new Manifest("Test Pack", side, new Manifest.Game("1.21.4", loader, null), null).write(pack());
        new Lock(Lock.VERSION, new Lock.Game("1.21.4", loader, build), new Lock.Server(serverUrl, serverHash), content,
                Overrides.scan(pack())).write(pack());
    }

    /** A Paper pack with a plugin, a data pack, a server resource pack and a client-only mod. */
    private void paperPack() {
        byte[] jar = FakeServer.jar(), via = "via".getBytes(), terra = "terra".getBytes();
        api.bytes("/files/server.jar", jar).bytes("/files/via.jar", via).bytes("/files/terra.zip", terra);
        content.put("hangar:ViaVersion", entry("plugin", "server", "31", "5.0.3", api.base + "/files/via.jar", sha256(via)));
        content.put("modrinth:terralith", entry("datapack", "server", "TTTT", "2.5", api.base + "/files/terra.zip", sha256(terra)));
        content.put("modrinth:sodium", entry("mod", "client", "SSSS", "0.6", api.base + "/files/sodium.jar", "sha512:00"));
        content.put("modrinth:faithful", new Lock.Entry("resourcepack", List.of("client", "server"), null, null, null, "FFFF",
                "f1", "1.0", "https://cdn/faithful.zip", "sha512:00", "packsha1", null));
        writePack("both", "paper", "232", api.base + "/files/server.jar", sha256(jar));
    }

    private void javaForTests() {
        Config.server(dir).set("java", FakeServer.java());
    }

    @Test
    void installPutsTheServerAndServerSideContentInPlace() throws IOException {
        paperPack();

        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));

        assertEquals("via", Files.readString(dir.resolve("plugins/hangar-31.jar")));
        assertEquals("terra", Files.readString(dir.resolve("world/datapacks/modrinth-TTTT.zip")));
        assertTrue(Files.exists(dir.resolve("server.jar")));
        assertFalse(Files.exists(dir.resolve("mods")), "client-only content stays off the server");
        String props = Files.readString(dir.resolve("server.properties"));
        assertTrue(props.contains("resource-pack-sha1=packsha1"), props);
        assertTrue(Files.readString(dir.resolve("eula.txt")).contains("eula=true"));
        assertTrue(Files.readString(dir.resolve(".evoker/source.json")).contains("\"path\""));
        assertEquals(Lock.read(pack()), Lock.read(dir.resolve(".evoker")));
        assertEquals(List.of("plugins/hangar-31.jar", "server.jar", "world/datapacks/modrinth-TTTT.zip"),
                Json.MAPPER.readValue(dir.resolve(".evoker/installed.json").toFile(), ServerFolder.Installed.class).files());
    }

    @Test
    void installFromAPackUrl() throws IOException {
        paperPack();
        api.bytes("/pack/evoker.json", Files.readAllBytes(pack().resolve(Manifest.FILE)));

        String err = Output.err(() -> assertEquals(1, run("install", "server", api.base + "/pack/")));
        assertTrue(err.contains("has no evoker.lock"), err);

        api.bytes("/pack/evoker.lock", Files.readAllBytes(pack().resolve(Lock.FILE)));
        assertEquals(0, run("install", "server", api.base + "/pack/evoker.json", "--accept-eula"));

        assertEquals("via", Files.readString(dir.resolve("plugins/hangar-31.jar")));
        assertTrue(Files.readString(dir.resolve(".evoker/source.json")).contains(api.base + "/pack/\""));
    }

    @Test
    void installRefusesClientPacksPackFoldersAndADifferentPack() throws IOException {
        writePack("client", "fabric", "0.19.5", "https://x", null);
        String err = Output.err(() -> assertEquals(1, run("install", "server", "--local", pack().toString())));
        assertTrue(err.contains("is a client pack"), err);

        Files.writeString(dir.resolve(Manifest.FILE), "{}");
        err = Output.err(() -> assertEquals(1, run("install", "server", "--local", pack().toString())));
        assertTrue(err.contains("is a pack folder"), err);
        Files.delete(dir.resolve(Manifest.FILE));

        paperPack();
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        err = Output.err(() -> assertEquals(1, run("install", "server", "--local", packs.toString())));
        assertTrue(err.contains("already has a pack installed from"), err);
    }

    @Test
    void withoutAConsoleTheEulaIsNotAccepted() {
        paperPack();

        String err = Output.err(() -> assertEquals(0, run("install", "server", "--local", pack().toString())));

        assertTrue(err.contains("--accept-eula"), err);
        assertFalse(Files.exists(dir.resolve("eula.txt")));
    }

    @Test
    void updateAppliesThePacksChangesAndLeavesYourFilesAlone() throws IOException {
        paperPack();
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        Files.writeString(dir.resolve("plugins/mine.jar"), "mine");
        byte[] via2 = "via2".getBytes();
        api.bytes("/files/via2.jar", via2);
        content.put("hangar:ViaVersion", entry("plugin", "server", "31", "5.1.0", api.base + "/files/via2.jar", sha256(via2)));
        content.remove("modrinth:terralith");
        writePack("both", "paper", "232", api.base + "/files/server.jar", sha256(FakeServer.jar()));

        String out = Output.out(() -> assertEquals(0, run("server", "update", "list", "--output=json")));
        var changes = Json.MAPPER.readTree(out).path("content");
        assertEquals("hangar:ViaVersion", changes.get(0).path("key").asString());
        assertEquals("5.1.0", changes.get(0).path("to").asString());
        assertEquals("removed", changes.get(1).path("change").asString());
        assertEquals("via", Files.readString(dir.resolve("plugins/hangar-31.jar")), "list changes nothing");

        assertEquals(0, run("server", "update"));

        assertEquals("via2", Files.readString(dir.resolve("plugins/hangar-31.jar")));
        assertFalse(Files.exists(dir.resolve("world/datapacks/modrinth-TTTT.zip")));
        assertEquals("mine", Files.readString(dir.resolve("plugins/mine.jar")));
    }

    private void override(String path, String text) throws IOException {
        Files.createDirectories(pack().resolve(path).getParent());
        Files.writeString(pack().resolve(path), text);
    }

    @Test
    void serverOverridesWinAndClientOverridesStayOff() throws IOException {
        override("overrides/config/a.toml", "both");
        override("server-overrides/config/a.toml", "server");
        override("client-overrides/options.txt", "client");
        paperPack();

        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));

        assertEquals("server", Files.readString(dir.resolve("config/a.toml")));
        assertFalse(Files.exists(dir.resolve("options.txt")));
    }

    @Test
    void overridesComeFromAPackUrlToo() throws IOException {
        override("overrides/config/my mod.toml", "configured");
        paperPack();
        api.bytes("/pack/evoker.json", Files.readAllBytes(pack().resolve(Manifest.FILE)))
                .bytes("/pack/evoker.lock", Files.readAllBytes(pack().resolve(Lock.FILE)))
                .bytes("/pack/overrides/config/my%20mod.toml", "configured".getBytes());

        assertEquals(0, run("install", "server", api.base + "/pack/", "--accept-eula"));

        assertEquals("configured", Files.readString(dir.resolve("config/my mod.toml")));
    }

    @Test
    void anUnchangedPackDownloadsNothing() {
        paperPack();
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        api.hits.set(0);

        assertEquals(0, run("server", "update"));

        assertEquals(0, api.hits.get());
    }

    @Test
    void anUnreachablePackKeepsWhatIsInstalled() throws IOException {
        paperPack();
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        Files.delete(pack().resolve(Lock.FILE));

        String err = Output.err(() -> assertEquals(0, run("server", "update")));

        assertTrue(err.contains("cannot fetch the pack"), err);
        assertEquals("via", Files.readString(dir.resolve("plugins/hangar-31.jar")));
    }

    @Test
    void aFileThatNoLongerMatchesTheLockIsNeverInstalled() {
        paperPack();
        content.put("hangar:ViaVersion", entry("plugin", "server", "31", "5.0.3", api.base + "/files/via.jar", "sha256:00"));
        writePack("both", "paper", "232", api.base + "/files/server.jar", sha256(FakeServer.jar()));

        String err = Output.err(() -> assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula")));

        assertTrue(err.contains("hangar:ViaVersion 5.0.3: download does not match evoker.lock"), err);
        assertFalse(Files.exists(dir.resolve("plugins/hangar-31.jar")));
    }

    @Test
    void aServerJarWithoutAnUpstreamHashIsPinnedOnFirstDownload() throws IOException {
        byte[] jar = FakeServer.jar();
        api.bytes("/files/fabric.jar", jar);
        writePack("server", "fabric", "0.19.5", api.base + "/files/fabric.jar", null);
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        assertTrue(Files.readString(dir.resolve(".evoker/installed.json")).contains(sha256(jar)));

        api.bytes("/files/fabric.jar", "swapped".getBytes());
        Files.delete(dir.resolve("fabric-server-launch.jar"));
        String err = Output.err(() -> assertEquals(0, run("server", "update")));

        assertTrue(err.contains("does not match"), err);
        assertFalse(Files.exists(dir.resolve("fabric-server-launch.jar")));
    }

    @Test
    void startUpdatesThenRunsTheServer() throws IOException {
        paperPack();
        javaForTests();
        Config.server(dir).set("jvmArgs", "[\"-Xmx64M\"]");
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        Files.delete(dir.resolve("plugins/hangar-31.jar"));

        assertEquals(FakeServer.EXIT_CODE, run("server", "start"));

        assertEquals("nogui", Files.readString(dir.resolve(FakeServer.MARKER)));
        assertTrue(Files.exists(dir.resolve("plugins/hangar-31.jar")), "start updated first");
    }

    @Test
    void startIsNotBlockedByAnUnreachablePack() throws IOException {
        paperPack();
        javaForTests();
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        Files.delete(pack().resolve(Lock.FILE));

        String err = Output.err(() -> assertEquals(FakeServer.EXIT_CODE, run("server", "start")));

        assertTrue(err.contains("cannot fetch the pack"), err);
    }

    @Test
    void commandPrintsTheLaunchLine() {
        paperPack();
        assertEquals(1, run("server", "command"));
        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        Config.server(dir).set("jvmArgs", "[\"-Xmx64M\", \"-Dx=a b\"]");

        String out = Output.out(() -> assertEquals(0, run("server", "command")));

        assertEquals("java -Xmx64M \"-Dx=a b\" -jar server.jar nogui", out.strip());
    }

    @Test
    void quiltRunsItsInstallerOncePerBuild() throws IOException {
        byte[] installer = FakeServer.jar(FakeInstaller.class, Map.of());
        api.bytes("/files/quilt-installer.jar", installer);
        javaForTests();
        writePack("server", "quilt", "0.29.2", api.base + "/files/quilt-installer.jar", sha256(installer));

        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));
        assertEquals("install server 1.21.4 0.29.2 --download-server --install-dir=.",
                Files.readString(dir.resolve(FakeInstaller.ARGS)));

        Files.delete(dir.resolve(FakeInstaller.ARGS));
        assertEquals(FakeServer.EXIT_CODE, run("server", "start"));
        assertFalse(Files.exists(dir.resolve(FakeInstaller.ARGS)), "same build: the installer doesn't run again");
        assertEquals("nogui", Files.readString(dir.resolve(FakeServer.MARKER)));

        writePack("server", "quilt", "0.28.0", api.base + "/files/quilt-installer.jar", sha256(installer));
        assertEquals(0, run("server", "update"));
        assertTrue(Files.readString(dir.resolve(FakeInstaller.ARGS)).contains("1.21.4 0.28.0"));
    }

    @Test
    void neoforgeShowsAndLogsItsInstaller() throws IOException {
        byte[] installer = FakeServer.jar(FakeInstaller.class, Map.of("neoforge-build.txt", "21.4.158".getBytes()));
        api.bytes("/files/neoforge-installer.jar", installer);
        javaForTests();
        writePack("server", "neoforge", "21.4.158", api.base + "/files/neoforge-installer.jar", null);

        String out = Output.out(() -> assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula")));
        assertTrue(out.contains(FakeInstaller.OUTPUT), "installer output is shown: " + out);
        assertTrue(Files.readString(dir.resolve(Server.INSTALLER_LOG)).contains(FakeInstaller.OUTPUT), "and logged");

        assertEquals(FakeServer.EXIT_CODE, run("server", "start"));
        assertEquals("nogui", Files.readString(dir.resolve(FakeServer.MARKER)));
    }

    @Test
    void spigotIsBuiltWithBuildTools() throws IOException {
        byte[] buildTools = FakeServer.jar(FakeInstaller.class, Map.of());
        api.bytes("/files/BuildTools.jar", buildTools);
        javaForTests();
        writePack("server", "spigot", "4458", api.base + "/files/BuildTools.jar", null);

        assertEquals(0, run("install", "server", "--local", pack().toString(), "--accept-eula"));

        String args = Files.readString(dir.resolve(Server.BUILDTOOLS_DIR).resolve(FakeInstaller.ARGS));
        assertTrue(args.startsWith("--rev 4458 --compile spigot --output-dir "), args);
        assertEquals(FakeServer.EXIT_CODE, run("server", "start"));
    }

    @Test
    void aFailingInstallerIsAnError() {
        api.bytes("/files/neoforge-installer.jar", "not a jar".getBytes());
        javaForTests();
        writePack("server", "neoforge", "21.4.158", api.base + "/files/neoforge-installer.jar", null);

        String err = Output.err(() -> assertEquals(1, run("install", "server", "--local", pack().toString())));

        assertTrue(err.contains("neoforge installer failed"), err);
        assertFalse(Files.exists(dir.resolve(Server.STAMP)));
    }
}
