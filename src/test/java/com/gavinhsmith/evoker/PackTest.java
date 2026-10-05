package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** create, add, url and remove end to end: they write evoker.json and evoker.lock, and never install anything. */
class PackTest {
    @TempDir
    Path dir;

    final FakeApi api = new FakeApi();

    @AfterEach
    void close() {
        api.close();
    }

    private int run(String... args) {
        return Main.run(dir, api.apis(), args);
    }

    private void fabric() {
        api.json("/fabric/v2/versions/loader/1.21.4", "fabric-loaders.json")
                .json("/fabric/v2/versions/installer", "fabric-installers.json");
    }

    /** A fabric pack whose Modrinth has alpha 2.0 (server-side, requires beta) and beta 1.0 (both sides). */
    private void fabricPack(String side) {
        fabric();
        api.json("/modrinth/v2/project/alpha", "modrinth-project-alpha.json")
                .json(FakeApi.modrinthVersions("AAAA", "1.21.4"), "modrinth-versions-alpha.json", "sha512", "A512")
                .json("/modrinth/v2/project/beta", "modrinth-project-beta.json")
                .json("/modrinth/v2/project/BBBB", "modrinth-project-beta.json")
                .json(FakeApi.modrinthVersions("BBBB", "1.21.4"), "modrinth-versions-beta.json", "sha512", "B512")
                .json("/modrinth/v2/project/shiny", "modrinth-project-client.json")
                .json(FakeApi.modrinthVersions("SSSS", "1.21.4"), "modrinth-versions-mixed.json");
        assertEquals(0, run("create", "My Pack", "fabric", "1.21.4", side));
    }

    /** Everything in the pack folder except evoker.json and evoker.lock. */
    private List<String> otherFiles() throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> !n.equals(Manifest.FILE) && !n.equals(Lock.FILE)).toList();
        }
    }

    @Test
    void createWritesThePackAndLocksTheGame() throws IOException {
        fabric();

        assertEquals(0, run("create", "My Pack", "Fabric", "1.21.4"));

        Manifest m = Manifest.read(dir);
        assertEquals("My Pack", m.name());
        assertEquals("both", m.side());
        assertEquals(new Manifest.Game("1.21.4", "fabric", "latest"), m.game());
        Lock lock = Lock.read(dir);
        assertEquals(new Lock.Game("1.21.4", "fabric", "0.19.5"), lock.game());
        assertEquals(new Lock.Server(api.base + "/fabric/v2/versions/loader/1.21.4/0.19.5/1.1.2/server/jar", null),
                lock.server());
        assertEquals(List.of(), otherFiles());
    }

    @Test
    void updateRecordsTheOverrideFiles() throws IOException {
        fabric();
        assertEquals(0, run("create", "P", "fabric", "1.21.4"));
        Files.createDirectories(dir.resolve("overrides/config"));
        Files.writeString(dir.resolve("overrides/config/a.toml"), "x");

        assertEquals(0, run("update"));

        assertEquals("sha256:" + FakeServer.hash("SHA-256", "x".getBytes()),
                Lock.read(dir).overrideFiles().get("overrides/config/a.toml"));
    }

    @Test
    void paperDefaultsToAServerPackOnTheNewestRelease() {
        api.json("/mojang/mc/game/version_manifest_v2.json", "mojang-manifest.json")
                .json("/paper/v3/projects/paper/versions/1.21.4/builds", "paper-builds.json", "sha256", "abc");

        assertEquals(0, run("create", "Plugins", "paper"));

        assertEquals("server", Manifest.read(dir).side());
        assertEquals("1.21.4", Manifest.read(dir).game().version());
        assertEquals("sha256:abc", Lock.read(dir).server().hash());
    }

    @Test
    void aClientPackLocksNoServer() {
        fabric();

        assertEquals(0, run("create", "Visuals", "fabric", "client", "1.21.4"));

        assertEquals("client", Manifest.read(dir).side());
        assertEquals("0.19.5", Lock.read(dir).game().build());
        assertNull(Lock.read(dir).server());
    }

    @Test
    void createRefusesToOverwriteAndUnknownLoaders() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), "{}");
        String err = Output.err(() -> assertEquals(1, run("create", "x", "paper", "1.21.4")));
        assertTrue(err.contains("already exists"), err);
        assertEquals("{}", Files.readString(dir.resolve(Manifest.FILE)));

        Files.delete(dir.resolve(Manifest.FILE));
        err = Output.err(() -> assertEquals(1, run("create", "x", "forge", "1.21.4")));
        assertTrue(err.contains("unknown loader forge"), err);
    }

    @Test
    void createGitInitsARepository() {
        assumeTrue(gitAvailable(), "git is not installed");
        fabric();

        Output.err(() -> assertEquals(0, run("create", "x", "fabric", "1.21.4", "--git")));

        assertTrue(Files.isDirectory(dir.resolve(".git")));
    }

    @Test
    void addResolvesSidesAndDependenciesWithoutInstalling() throws IOException {
        fabricPack("both");

        Output.err(() -> assertEquals(0, run("add", "alpha")));

        assertEquals(Set.of("modrinth:alpha"), Manifest.read(dir).content().keySet());
        Lock lock = Lock.read(dir);
        Lock.Entry alpha = lock.content().get("modrinth:alpha"), beta = lock.content().get("modrinth:beta");
        assertEquals("2.0", alpha.version());
        assertEquals(List.of("server"), alpha.sides(), "server required, client optional");
        assertEquals("sha512:A512", alpha.hash());
        assertEquals(List.of("modrinth:alpha"), beta.requiredBy());
        assertEquals(List.of("server"), beta.sides(), "only alpha needs it, on the server");
        assertEquals(List.of(), otherFiles());
    }

    @Test
    void addPinsAndOptional() {
        fabricPack("both");

        assertEquals(0, run("add", "beta@1.0", "--optional"));

        assertEquals(new Manifest.Content("1.0", null, true, null, null), Manifest.read(dir).content().get("modrinth:beta"));
        Lock.Entry beta = Lock.read(dir).content().get("modrinth:beta");
        assertEquals(Boolean.TRUE, beta.optional());
        assertEquals(List.of("client", "server"), beta.sides());
    }

    @Test
    void addRefusesWhatCannotRunOnThePacksSides() throws IOException {
        fabricPack("server");
        String before = Files.readString(dir.resolve(Manifest.FILE));

        // shiny is only a resource pack on fabric: client-only
        String err = Output.err(() -> assertEquals(1, run("add", "shiny")));

        assertTrue(err.contains("runs only on the client, and this is a server pack"), err);
        assertEquals(before, Files.readString(dir.resolve(Manifest.FILE)));
    }

    @Test
    void addFailureLeavesThePackUntouched() throws IOException {
        fabricPack("both");
        String manifest = Files.readString(dir.resolve(Manifest.FILE)), lock = Files.readString(dir.resolve(Lock.FILE));

        Output.err(() -> assertEquals(1, run("add", "nope")));

        assertEquals(manifest, Files.readString(dir.resolve(Manifest.FILE)));
        assertEquals(lock, Files.readString(dir.resolve(Lock.FILE)));
    }

    @Test
    void urlEntriesAreHashedButNotInstalled() throws IOException {
        fabricPack("both");
        byte[] pack = "pack".getBytes();
        api.bytes("/dl/pack.zip", pack);

        assertEquals(0, run("url", "mypack", api.base + "/dl/pack.zip", "resourcepack", "--optional"));

        assertEquals(new Manifest.Content(null, null, true, "resourcepack", api.base + "/dl/pack.zip"),
                Manifest.read(dir).content().get("url:mypack"));
        Lock.Entry e = Lock.read(dir).content().get("url:mypack");
        assertEquals("sha256:" + FakeServer.hash("SHA-256", pack), e.hash());
        assertEquals(FakeServer.hash("SHA-1", pack), e.sha1());
        assertEquals(List.of("client"), e.sides());
        assertEquals(List.of(), otherFiles());
    }

    @Test
    void urlModsNeedASide() {
        fabricPack("both");
        String err = Output.err(() -> assertEquals(1, run("url", "x", api.base + "/dl/x.jar", "mod")));
        assertTrue(err.contains("needs --side"), err);
        assertEquals(1, run("url", "bad name", api.base + "/dl/x.jar", "plugin"));
    }

    @Test
    void addPointsUrlsAtTheUrlCommand() {
        fabricPack("both");
        String err = Output.err(() -> assertEquals(1, run("add", api.base + "/dl/x.jar")));
        assertTrue(err.contains("evoker url"), err);
    }

    @Test
    void removeDropsTheEntryAndItsOrphanedDependencies() {
        fabricPack("both");
        Output.err(() -> run("add", "alpha"));

        String err = Output.err(() -> assertEquals(1, run("remove", "beta")));
        assertTrue(err.contains("required by [modrinth:alpha]"), err);

        api.hits.set(0);
        assertEquals(0, run("remove", "alpha"));

        assertTrue(Lock.read(dir).content().isEmpty());
        assertTrue(Manifest.read(dir).content().isEmpty());
        assertEquals(0, api.hits.get(), "removing needs no network");
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
