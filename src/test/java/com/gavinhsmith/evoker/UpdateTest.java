package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** update and upgrade end to end against a fake Modrinth and Fabric. */
class UpdateTest {
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

    /** Publishes these versions of project "beta" (BBBB) for a game version, newest last. */
    private void betaVersions(String gameVersion, String... versions) {
        var json = new StringBuilder("[");
        for (int i = versions.length - 1; i >= 0; i--) {
            String v = versions[i];
            String version = """
                    {"id": "b%s", "project_id": "BBBB", "version_number": "%s", "version_type": "release",
                     "date_published": "2026-01-0%dT00:00:00Z", "loaders": ["fabric"],
                     "files": [{"url": "%s/files/beta-%s.jar", "primary": true, "hashes": {"sha512": "b%s"}}]}
                    """.formatted(v, v, i + 1, api.base, v, v);
            api.bytes("/modrinth/v2/version/b" + v, version.getBytes());
            json.append(version).append(i > 0 ? "," : "");
        }
        api.bytes(FakeApi.modrinthVersions("BBBB", gameVersion), json.append("]").toString().getBytes());
    }

    @BeforeEach
    void fabricPack() {
        api.json("/fabric/v2/versions/loader/1.21.4", "fabric-loaders.json")
                .json("/fabric/v2/versions/installer", "fabric-installers.json")
                .json("/modrinth/v2/project/beta", "modrinth-project-beta.json")
                .json("/modrinth/v2/project/BBBB", "modrinth-project-beta.json");
        assertEquals(0, run("create", "P", "fabric", "1.21.4"));
    }

    private String locked() {
        return Lock.read(dir).content().get("modrinth:beta").version();
    }

    private String wanted() {
        return Manifest.read(dir).content().get("modrinth:beta").version();
    }

    @Test
    void updateMovesLatestEntriesAndUpdateListOnlyShowsIt() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta"));
        betaVersions("1.21.4", "1.0", "2.0");
        String lock = Files.readString(dir.resolve(Lock.FILE));

        String out = Output.out(() -> assertEquals(0, run("update", "list", "--output=json")));
        var change = Json.MAPPER.readTree(out).path("content").get(0);
        assertEquals("updated", change.path("change").asString());
        assertEquals("1.0", change.path("from").asString());
        assertEquals("2.0", change.path("to").asString());
        assertEquals(false, change.path("pinned").asBoolean());
        assertEquals(lock, Files.readString(dir.resolve(Lock.FILE)));

        assertEquals(0, run("update"));
        assertEquals("2.0", locked());
        assertEquals("latest", wanted());
    }

    @Test
    void updateKeepsPinsUnlessNamed() {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta@1.0"));
        betaVersions("1.21.4", "1.0", "2.0");

        assertEquals(0, run("update"));
        assertEquals("1.0", locked());

        assertEquals(0, run("update", "beta"));
        assertEquals("2.0", locked());
        assertEquals("2.0", wanted(), "the pin moves");
    }

    @Test
    void upgradeMovesTheGameVersion() {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta"));
        api.json("/fabric/v2/versions/loader/1.21.5", "fabric-loaders.json");
        betaVersions("1.21.5", "3.0");

        Output.out(() -> assertEquals(0, run("upgrade", "1.21.5")));

        assertEquals("1.21.5", Manifest.read(dir).game().version());
        assertEquals("1.21.5", Lock.read(dir).game().version());
        assertEquals("3.0", locked());
    }

    @Test
    void upgradeAsksAboutPinsAndKeepsThemWithoutAConsole() {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta@1.0"));
        betaVersions("1.21.4", "1.0", "2.0");

        String err = Output.err(() -> assertEquals(0, run("upgrade", "1.21.4")));
        assertTrue(err.contains("modrinth:beta"), err);
        assertTrue(err.contains("--pinned"), err);
        assertEquals("1.0", wanted());

        assertEquals(0, run("upgrade", "1.21.4", "--pinned"));
        assertEquals("2.0", wanted());
        assertEquals("2.0", locked());
    }

    @Test
    void upgradeListMarksPinsAndChangesNothing() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta@1.0"));
        betaVersions("1.21.4", "1.0", "2.0");
        String manifest = Files.readString(dir.resolve(Manifest.FILE)), lock = Files.readString(dir.resolve(Lock.FILE));

        String all = Output.out(() -> assertEquals(0, run("upgrade", "list", "1.21.4", "--output=json")));
        String keep = Output.out(() -> assertEquals(0, run("upgrade", "list", "1.21.4", "--keep-pinned", "--output", "json")));

        var upgraded = Json.MAPPER.readTree(all).path("content").get(0);
        assertEquals("updated", upgraded.path("change").asString());
        assertTrue(upgraded.path("pinned").asBoolean());
        var kept = Json.MAPPER.readTree(keep).path("content").get(0);
        assertEquals("kept", kept.path("change").asString());
        assertTrue(Json.MAPPER.readTree(keep).path("game").isNull());
        assertEquals(manifest, Files.readString(dir.resolve(Manifest.FILE)));
        assertEquals(lock, Files.readString(dir.resolve(Lock.FILE)));
    }

    @Test
    void upgradeFailsWithoutALoaderBuildForTheNewVersion() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta"));
        api.bytes("/fabric/v2/versions/loader/1.21.5", "[]".getBytes());
        String manifest = Files.readString(dir.resolve(Manifest.FILE)), lock = Files.readString(dir.resolve(Lock.FILE));

        String err = Output.err(() -> assertEquals(1, run("upgrade", "1.21.5")));

        assertTrue(err.contains("fabric has no loader for 1.21.5"), err);
        assertEquals(manifest, Files.readString(dir.resolve(Manifest.FILE)));
        assertEquals(lock, Files.readString(dir.resolve(Lock.FILE)));
    }

    @Test
    void upgradeKeepsContentWithoutACompatibleVersion() {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta"));
        api.json("/fabric/v2/versions/loader/1.21.5", "fabric-loaders.json")
                .bytes(FakeApi.modrinthVersions("BBBB", "1.21.5"), "[]".getBytes());

        String err = Output.err(() -> assertEquals(0, run("upgrade", "1.21.5")));

        assertTrue(err.contains("modrinth:beta has no version for fabric 1.21.5; keeping modrinth:beta 1.0"), err);
        assertEquals("1.21.5", Manifest.read(dir).game().version());
        assertEquals("1.0", locked());
    }

    @Test
    void onlyUpdateAcceptsAChangedUrlFile() {
        betaVersions("1.21.4", "1.0");
        api.bytes("/dl/geyser.jar", "v1".getBytes());
        assertEquals(0, run("url", "geyser", api.base + "/dl/geyser.jar", "plugin"));
        String v1 = Lock.read(dir).content().get("url:geyser").hash();

        api.bytes("/dl/geyser.jar", "v2".getBytes());
        assertEquals(0, run("add", "beta"));
        assertEquals(v1, Lock.read(dir).content().get("url:geyser").hash(), "other commands keep the locked hash");

        assertEquals(0, run("update"));
        assertEquals("sha256:" + FakeServer.hash("SHA-256", "v2".getBytes()),
                Lock.read(dir).content().get("url:geyser").hash());
    }
}
