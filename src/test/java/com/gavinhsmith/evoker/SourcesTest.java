package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hangar and URL entries end to end on a fake Paper server. */
class SourcesTest {
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

    @BeforeEach
    void paper() throws IOException {
        byte[] jar = FakeServer.jar();
        api.bytes("/files/server.jar", jar)
                .json("/paper/v3/projects/paper/versions/1.21.4/builds", "paper-builds.json",
                        "sha256", FakeServer.hash("SHA-256", jar));
        Files.writeString(dir.resolve(Manifest.FILE), """
                { "server": { "software": "paper", "version": "1.21.4" } }
                """);
    }

    @Test
    void hangarPluginWithItsDependency() throws IOException {
        byte[] via = "via".getBytes();
        api.json("/hangar/api/v1/projects/ViaVersion", "hangar-project-via.json")
                .json(HangarTest.RELEASES, "hangar-versions-via.json", "sha256", FakeServer.hash("SHA-256", via))
                .bytes("/files/via.jar", via)
                .json("/hangar/api/v1/projects/12", "hangar-project-backwards.json")
                .json("/hangar/api/v1/projects/ViaBackwards/versions?limit=1&platform=PAPER&platformVersion=1.21.4&channel=Release",
                        "hangar-versions-backwards.json")
                .bytes("/files/backwards.jar", "backwards".getBytes());

        Output.err(() -> assertEquals(0, run("add", "hangar:ViaVersion")));

        assertEquals("via", Files.readString(dir.resolve("plugins/hangar-31.jar")));
        assertEquals("backwards", Files.readString(dir.resolve("plugins/hangar-12.jar")));
        assertEquals(List.of("hangar:ViaVersion"), Lock.read(dir).content().get("hangar:ViaBackwards").requiredBy());
    }

    @Test
    void urlEntryIsPinnedByHashAndRefreshedByUpdate() throws IOException {
        api.bytes("/dl/Geyser-Spigot.jar", "v1".getBytes());

        assertEquals(0, run("add", api.base + "/dl/Geyser-Spigot.jar", "--type", "plugin"));

        Path file = dir.resolve("plugins/url-Geyser-Spigot.jar");
        assertEquals("v1", Files.readString(file));
        assertEquals(new Manifest.Content(null, api.base + "/dl/Geyser-Spigot.jar", "plugin"),
                Manifest.read(dir).content().get("url:Geyser-Spigot"));

        // Upstream changes: install refuses the new file and keeps the old one...
        api.bytes("/dl/Geyser-Spigot.jar", "v2".getBytes());
        Files.delete(file);
        String err = Output.err(() -> assertEquals(0, run("install")));
        assertTrue(err.contains("does not match evoker.lock"), err);
        assertTrue(Files.notExists(file), "a changed upstream file is not installed");

        // ...until update accepts it.
        assertEquals(0, run("update"));
        assertEquals("v2", Files.readString(file));
        assertEquals(Http.sha256(file), Lock.read(dir).content().get("url:Geyser-Spigot").sha256());
    }

    @Test
    void urlResourcePackIsHashedForServerProperties() throws IOException {
        byte[] pack = "pack".getBytes();
        api.bytes("/dl/pack.zip", pack);

        assertEquals(0, run("add", api.base + "/dl/pack.zip", "--type", "resourcepack", "--name", "mypack"));

        String props = Files.readString(dir.resolve("server.properties"));
        assertTrue(props.contains("resource-pack-sha1=" + FakeServer.hash("SHA-1", pack)), props);
        assertTrue(Files.notExists(dir.resolve("plugins")), "resource packs are not stored");
        api.hits.set(0);
        assertEquals(0, run("install"));
        assertEquals(0, api.hits.get(), "the locked hash is reused");
    }

    @Test
    void urlNeedsAType() {
        String err = Output.err(() -> assertEquals(1, run("add", api.base + "/dl/x.jar")));
        assertTrue(err.contains("needs --type"), err);
    }

    @Test
    void urlNames() {
        assertEquals("Geyser-Spigot", Main.urlName("https://example.com/a/Geyser-Spigot.jar?x=1"));
        assertEquals("my-pack", Main.urlName("https://example.com/my%20pack.zip"));
    }
}
