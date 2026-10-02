package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Quilt and NeoForge end to end: evoker downloads and runs the (fake) installer, then launches its output. */
class InstallerSoftwareTest {
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

    private void manifest(String software) throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), """
                {
                  "server": { "software": "%s", "version": "1.21.4" },
                  "evoker": { "java": "%s" }
                }
                """.formatted(software, FakeServer.java().replace("\\", "\\\\")));
    }

    @Test
    void quilt() throws IOException {
        byte[] installer = FakeServer.jar(FakeInstaller.class, Map.of());
        api.json("/quilt/v3/versions/loader/1.21.4", "quilt-loaders-1.21.4.json")
                .json("/quilt/v3/versions/loader", "quilt-loaders.json")
                .json("/quilt/v3/versions/installer", "quilt-installers.json", "sha256", FakeServer.hash("SHA-256", installer))
                .bytes("/files/quilt-installer.jar", installer);
        manifest("quilt");

        assertEquals(FakeServer.EXIT_CODE, run("start"));

        assertEquals("install server 1.21.4 0.29.2 --download-server --install-dir=.",
                Files.readString(dir.resolve(FakeInstaller.ARGS)));
        assertEquals("nogui", Files.readString(dir.resolve(FakeServer.MARKER)));

        // Same build: the installer doesn't run again.
        Files.delete(dir.resolve(FakeInstaller.ARGS));
        assertEquals(FakeServer.EXIT_CODE, run("start"));
        assertTrue(Files.notExists(dir.resolve(FakeInstaller.ARGS)));

        // New loader: it does.
        Files.writeString(dir.resolve(Manifest.FILE),
                Files.readString(dir.resolve(Manifest.FILE)).replace("\"1.21.4\" }", "\"1.21.4\", \"build\": \"0.28.0\" }"));
        assertEquals(0, run("install"));
        assertTrue(Files.readString(dir.resolve(FakeInstaller.ARGS)).contains("1.21.4 0.28.0"));
    }

    @Test
    void neoforge() throws IOException {
        byte[] installer = FakeServer.jar(FakeInstaller.class, Map.of("neoforge-build.txt", "21.4.158".getBytes()));
        api.json("/neoforge/api/maven/versions/releases/net/neoforged/neoforge", "neoforge-versions.json")
                .bytes("/neoforge/releases/net/neoforged/neoforge/21.4.158/neoforge-21.4.158-installer.jar", installer);
        manifest("neoforge");

        assertEquals(FakeServer.EXIT_CODE, run("start"));

        assertEquals("--installServer .", Files.readString(dir.resolve(FakeInstaller.ARGS)));
        assertEquals("nogui", Files.readString(dir.resolve(FakeServer.MARKER)));
        assertEquals("21.4.158", Lock.read(dir).server().build());
    }

    @Test
    void spigotIsBuiltWithBuildTools() throws IOException {
        byte[] buildTools = FakeServer.jar(FakeInstaller.class, Map.of());
        api.bytes("/spigot/versions/1.21.4.json", "{\"name\": \"4458\"}".getBytes())
                .bytes("/spigot/jenkins/job/BuildTools/lastSuccessfulBuild/api/json", "{\"number\": 201}".getBytes())
                .bytes("/spigot/jenkins/job/BuildTools/201/artifact/target/BuildTools.jar", buildTools);
        manifest("spigot");

        assertEquals(FakeServer.EXIT_CODE, run("start"));

        String args = Files.readString(dir.resolve(Server.BUILDTOOLS_DIR).resolve(FakeInstaller.ARGS));
        assertTrue(args.startsWith("--rev 4458 --compile spigot --output-dir "), args);
        assertTrue(args.endsWith("--final-name server.jar --nogui"), args);
        assertEquals("nogui", Files.readString(dir.resolve(FakeServer.MARKER)));
        assertEquals("4458", Lock.read(dir).server().build());

        // A newer BuildTools alone doesn't trigger a rebuild.
        api.bytes("/spigot/jenkins/job/BuildTools/lastSuccessfulBuild/api/json", "{\"number\": 202}".getBytes());
        assertEquals(0, run("update"));
        assertTrue(Lock.read(dir).server().url().endsWith("/201/artifact/target/BuildTools.jar"));
    }

    @Test
    void failingInstallerIsAnError() throws IOException {
        byte[] broken = "not a jar".getBytes();
        api.json("/neoforge/api/maven/versions/releases/net/neoforged/neoforge", "neoforge-versions.json")
                .bytes("/neoforge/releases/net/neoforged/neoforge/21.4.158/neoforge-21.4.158-installer.jar", broken);
        manifest("neoforge");

        String err = Stderr.capture(() -> assertEquals(1, run("install")));

        assertTrue(err.contains("neoforge installer failed"), err);
        assertTrue(Files.notExists(dir.resolve(Server.STAMP)));
    }
}
