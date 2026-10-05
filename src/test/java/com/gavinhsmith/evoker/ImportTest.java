package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** evoker import end to end: a pack whose alpha mod is on Modrinth and whose other files are not. */
class ImportTest {
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
    void apis() {
        api.json("/fabric/v2/versions/installer", "fabric-installers.json")
                // the pack's hashes: only alpha is known to Modrinth
                .bytes("/modrinth/v2/version_files", """
                        {"A512": {"id": "a2", "project_id": "AAAA", "version_number": "2.0"}}
                        """.getBytes())
                .bytes("/modrinth/v2/projects?ids=" + URLEncoder.encode("[\"AAAA\"]", StandardCharsets.UTF_8),
                        "[{\"id\": \"AAAA\", \"slug\": \"alpha\"}]".getBytes())
                // what the lock then resolves: alpha 2.0 (requires beta)
                .json("/modrinth/v2/project/alpha", "modrinth-project-alpha.json")
                .json(FakeApi.modrinthVersions("AAAA", "1.21.4"), "modrinth-versions-alpha.json", "sha512", "A512")
                .json("/modrinth/v2/project/BBBB", "modrinth-project-beta.json")
                .json(FakeApi.modrinthVersions("BBBB", "1.21.4"), "modrinth-versions-beta.json", "sha512", "B512")
                // url entries are hashed once
                .bytes("/files/external.jar", "external".getBytes())
                .bytes("/files/client.jar", "client".getBytes())
                .bytes("/files/shader.zip", "shader".getBytes());
    }

    private Path pack() throws IOException {
        return MrpackTest.pack(dir.resolve("test.mrpack"), MrpackTest.INDEX.formatted(api.base),
                Map.of("overrides/config/alpha.toml", "configured"));
    }

    @Test
    void importsAFile() throws IOException {
        pack();

        String err = Output.err(() -> assertEquals(0, run("import", "test.mrpack")));

        Manifest m = Manifest.read(dir);
        assertEquals("Test Pack", m.name());
        assertEquals("both", m.side());
        assertEquals(new Manifest.Game("1.21.4", "fabric", "0.19.5"), m.game());
        assertEquals(new Manifest.Content("2.0", "both", null, "mod", null), m.content().get("modrinth:alpha"));
        assertEquals(new Manifest.Content(null, "both", null, "mod", api.base + "/files/external.jar"),
                m.content().get("url:External-Mod"));
        assertEquals(new Manifest.Content(null, "client", true, "mod", api.base + "/files/client.jar"),
                m.content().get("url:shiny-client"));
        assertEquals(new Manifest.Content(null, null, null, "shaderpack", api.base + "/files/shader.zip"),
                m.content().get("url:pretty"));
        assertEquals(4, m.content().size());
        assertTrue(err.contains("config/alpha.toml"), "files outside mods/, resourcepacks/, shaderpacks/: " + err);
        assertEquals("configured", Files.readString(dir.resolve("overrides/config/alpha.toml")), "overrides are copied");

        Lock lock = Lock.read(dir);
        assertEquals("0.19.5", lock.game().build());
        assertEquals(List.of("client", "server"), lock.content().get("modrinth:alpha").sides(), "the pack's env wins");
        assertEquals(List.of("modrinth:alpha"), lock.content().get("modrinth:beta").requiredBy(), "dependencies resolve");
        assertEquals(List.of("client"), lock.content().get("url:pretty").sides());
        assertEquals(Boolean.TRUE, lock.content().get("url:shiny-client").optional());
        assertTrue(lock.overrideFiles().containsKey("overrides/config/alpha.toml"));
    }

    @Test
    void importsAModrinthModpackBySlug() throws IOException {
        byte[] zip = Files.readAllBytes(pack());
        Files.delete(dir.resolve("test.mrpack"));
        api.bytes("/modrinth/v2/project/testpack/version", """
                        [{"version_number": "1.2.0", "version_type": "release",
                          "files": [{"url": "%s/files/test.mrpack", "primary": true}]}]
                        """.formatted(api.base).getBytes())
                .bytes("/files/test.mrpack", zip);

        Output.err(() -> assertEquals(0, run("import", "testpack")));

        assertTrue(Manifest.read(dir).content().containsKey("modrinth:alpha"));
        try (var files = Files.list(dir)) {
            assertEquals(List.of(Manifest.FILE, Lock.FILE, "overrides"),
                    files.map(f -> f.getFileName().toString()).sorted().toList(), "nothing else is written");
        }
    }

    /** A packwiz pack: alpha (Modrinth, both sides), an optional client mod from a URL, a CurseForge-only mod, a config. */
    private Map<String, String> packwiz() {
        String config = "renderDistance = 12\n";
        return Map.of(
                "pack.toml", """
                        name = "Wiz Pack"
                        version = "2.0.0"
                        pack-format = "packwiz:1.1.0"

                        [index]
                        file = "index.toml"
                        hash-format = "sha256"
                        hash = "ignored"

                        [versions]
                        minecraft = "1.21.4"
                        fabric = "0.19.5"
                        """,
                "index.toml", """
                        hash-format = "sha256"

                        [[files]]
                        file = "config/my mod.toml"
                        hash = "%s"

                        [[files]]
                        file = "mods/alpha.pw.toml"
                        hash = "x"
                        metafile = true

                        [[files]]
                        file = "mods/zoom.pw.toml"
                        hash = "x"
                        metafile = true

                        [[files]]
                        file = "mods/cursed.pw.toml"
                        hash = "x"
                        metafile = true
                        """.formatted(FakeServer.hash("SHA-256", config.getBytes())),
                "config/my mod.toml", config,
                "mods/alpha.pw.toml", """
                        name = "Alpha"
                        filename = "alpha-2.jar"
                        side = "both"

                        [download]
                        url = "%s/files/alpha-2.jar"
                        hash-format = "sha512"
                        hash = "A512"

                        [update.modrinth]
                        mod-id = "AAAA"
                        version = "a2"
                        """.formatted(api.base),
                "mods/zoom.pw.toml", """
                        name = "Zoom"
                        filename = "zoom.jar"
                        side = "client"

                        [download]
                        url = "%s/files/client.jar"
                        hash-format = "sha1"
                        hash = "abc"

                        [option]
                        optional = true
                        description = "Zoom in"
                        """.formatted(api.base),
                "mods/cursed.pw.toml", """
                        name = "Cursed"
                        filename = "cursed.jar"
                        side = "both"

                        [download]
                        hash-format = "sha1"
                        hash = "abc"
                        mode = "metadata:curseforge"

                        [update.curseforge]
                        file-id = 1
                        project-id = 2
                        """);
    }

    private void checkWizPack(String err) throws IOException {
        Manifest m = Manifest.read(dir);
        assertEquals("Wiz Pack", m.name());
        assertEquals(new Manifest.Game("1.21.4", "fabric", "0.19.5"), m.game());
        assertEquals(new Manifest.Content("2.0", null, null, "mod", null), m.content().get("modrinth:alpha"),
                "packwiz's default side says nothing: evoker works it out");
        assertEquals(List.of("server"), Lock.read(dir).content().get("modrinth:alpha").sides(), "from Modrinth");
        assertEquals(new Manifest.Content(null, "client", true, "mod", api.base + "/files/client.jar"),
                m.content().get("url:zoom"));
        assertEquals(2, m.content().size());
        assertTrue(err.contains("skipping 1 CurseForge files") && err.contains("Cursed"), err);
        assertEquals("renderDistance = 12\n", Files.readString(dir.resolve("overrides/config/my mod.toml")));
        assertTrue(Lock.read(dir).overrideFiles().containsKey("overrides/config/my mod.toml"));
        assertEquals(List.of("modrinth:alpha"), Lock.read(dir).content().get("modrinth:beta").requiredBy());
    }

    @Test
    void importsAPackwizFolder() throws IOException {
        Path wiz = dir.resolve("wiz");
        for (var f : packwiz().entrySet()) {
            Files.createDirectories(wiz.resolve(f.getKey()).getParent());
            Files.writeString(wiz.resolve(f.getKey()), f.getValue());
        }

        String err = Output.err(() -> assertEquals(0, run("import", "wiz")));

        checkWizPack(err);
    }

    @Test
    void importsAPackwizPackFromAUrl() throws IOException {
        packwiz().forEach((path, text) -> api.bytes("/wiz/" + path.replace(" ", "%20"), text.getBytes()));

        String err = Output.err(() -> assertEquals(0, run("import", api.base + "/wiz/pack.toml")));

        checkWizPack(err);
    }

    @Test
    void refusesToImportIntoAnExistingPack() throws IOException {
        pack();
        Files.writeString(dir.resolve(Manifest.FILE), "{}");

        String err = Output.err(() -> assertEquals(1, run("import", "test.mrpack")));

        assertTrue(err.contains("already exists"), err);
    }
}
