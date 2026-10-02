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

/** update, upgrade and the auto-update settings, end to end against a fake Modrinth and Fabric. */
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

    /** Publishes these versions of project "beta" (BBBB), newest last, each file containing its version number. */
    private void betaVersions(String gameVersion, String... versions) {
        var json = new StringBuilder("[");
        for (int i = versions.length - 1; i >= 0; i--) {
            String v = versions[i];
            byte[] file = ("beta-" + v).getBytes();
            api.bytes("/files/beta-" + v + ".jar", file);
            String version = """
                    {"id": "b%s", "project_id": "BBBB", "version_number": "%s", "version_type": "release",
                     "date_published": "2026-01-0%dT00:00:00Z", "loaders": ["fabric"],
                     "files": [{"url": "%s/files/beta-%s.jar", "primary": true, "hashes": {"sha512": "%s"}}]}
                    """.formatted(v, v, i + 1, api.base, v, FakeServer.hash("SHA-512", file));
            api.bytes("/modrinth/v2/version/b" + v, version.getBytes());
            json.append(version).append(i > 0 ? "," : "");
        }
        api.bytes(FakeApi.modrinthVersions("BBBB", gameVersion), json.append("]").toString().getBytes());
    }

    @BeforeEach
    void fabric() throws IOException {
        api.json("/fabric/v2/versions/loader/1.21.4", "fabric-loaders.json")
                .json("/fabric/v2/versions/installer", "fabric-installers.json")
                .bytes("/fabric/v2/versions/loader/1.21.4/0.19.5/1.1.2/server/jar", FakeServer.jar())
                .json("/modrinth/v2/project/beta", "modrinth-project-beta.json")
                .json("/modrinth/v2/project/BBBB", "modrinth-project-beta.json");
        Files.writeString(dir.resolve(Manifest.FILE), """
                { "server": { "software": "fabric", "version": "1.21.4" } }
                """);
    }

    private String jar() throws IOException {
        return Files.readString(dir.resolve("mods/modrinth-BBBB.jar"));
    }

    @Test
    void installKeepsTheLockedVersionAndUpdateMovesLatestEntries() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta"));
        betaVersions("1.21.4", "1.0", "2.0");

        assertEquals(0, run("install"));
        assertEquals("beta-1.0", jar());

        assertEquals(0, run("update"));
        assertEquals("beta-2.0", jar());
        assertEquals("2.0", Lock.read(dir).content().get("modrinth:beta").version());
        assertEquals("latest", Manifest.read(dir).content().get("modrinth:beta").version());
    }

    @Test
    void updateKeepsPins() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta", "1.0"));
        betaVersions("1.21.4", "1.0", "2.0");

        assertEquals(0, run("update", "beta"));

        assertEquals("beta-1.0", jar());
    }

    @Test
    void upgradeRewritesPins() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta", "1.0"));
        betaVersions("1.21.4", "1.0", "2.0");

        assertEquals(0, run("upgrade"));

        assertEquals("beta-2.0", jar());
        assertEquals("2.0", Manifest.read(dir).content().get("modrinth:beta").version());
    }

    @Test
    void upgradeDryRunChangesNothing() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta", "1.0"));
        betaVersions("1.21.4", "1.0", "2.0");
        String manifest = Files.readString(dir.resolve(Manifest.FILE));
        String lock = Files.readString(dir.resolve(Lock.FILE));

        assertEquals(0, run("upgrade", "--dry-run"));

        assertEquals(manifest, Files.readString(dir.resolve(Manifest.FILE)));
        assertEquals(lock, Files.readString(dir.resolve(Lock.FILE)));
        assertEquals("beta-1.0", jar());
    }

    @Test
    void upgradeKeepsWhatHasNoCompatibleVersionAndDoesNotBlockStart() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta", "1.0"));
        String lock = Files.readString(dir.resolve(Lock.FILE));
        // Move to a game version nothing supports yet: no fabric loader, no beta build.
        Files.writeString(dir.resolve(Manifest.FILE),
                Files.readString(dir.resolve(Manifest.FILE)).replace("1.21.4", "1.21.5"));
        api.bytes("/fabric/v2/versions/loader/1.21.5", "[]".getBytes())
                .bytes(FakeApi.modrinthVersions("BBBB", "1.21.5"), "[]".getBytes());

        String err = Stderr.capture(() -> assertEquals(0, run("upgrade")));

        assertTrue(err.contains("fabric has no loader for 1.21.5; keeping fabric 1.21.4"), err);
        assertTrue(err.contains("modrinth:beta has no version for fabric 1.21.5; keeping modrinth:beta 1.0"), err);
        assertEquals(lock, Files.readString(dir.resolve(Lock.FILE)));
        assertEquals("1.0", Manifest.read(dir).content().get("modrinth:beta").version());
    }

    @Test
    void autoUpdateDepsOnStart() throws IOException {
        betaVersions("1.21.4", "1.0");
        assertEquals(0, run("add", "beta"));
        betaVersions("1.21.4", "1.0", "2.0");
        Files.writeString(dir.resolve(Manifest.FILE), """
                {
                  "server": { "software": "fabric", "version": "1.21.4" },
                  "content": { "modrinth:beta": "latest" },
                  "evoker": { "autoUpdateDeps": true, "java": "%s" }
                }
                """.formatted(FakeServer.java().replace("\\", "\\\\")));

        assertEquals(FakeServer.EXIT_CODE, run("start"));

        assertEquals("beta-2.0", jar());
    }
}
