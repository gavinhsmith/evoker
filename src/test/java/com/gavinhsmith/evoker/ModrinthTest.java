package com.gavinhsmith.evoker;

import static com.gavinhsmith.evoker.FakeApi.modrinthVersions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModrinthTest {
    final FakeApi api = new FakeApi();
    final Modrinth modrinth = new Modrinth(new Http(), api.apis().modrinth());

    @AfterEach
    void close() {
        api.close();
    }

    private static Manifest.Game on(String loader) {
        return new Manifest.Game("1.21.4", loader, null);
    }

    private static final Manifest.Content LATEST = Manifest.Content.of("latest");

    private void alpha() {
        api.json("/modrinth/v2/project/alpha", "modrinth-project-alpha.json")
                .json(modrinthVersions("AAAA", "1.21.4"), "modrinth-versions-alpha.json", "sha512", "S", "sha512zip", "Z");
    }

    private void mixed() {
        api.json("/modrinth/v2/project/shiny", "modrinth-project-client.json")
                .json(modrinthVersions("SSSS", "1.21.4"), "modrinth-versions-mixed.json");
    }

    @Test
    void latestPrefersReleasesAndTheSoftwaresOwnLoader() {
        alpha();
        Source.Resolution r = modrinth.resolve("alpha", LATEST, null, on("fabric"));

        assertEquals("alpha", r.slug());
        assertEquals(new Lock.Entry("mod", List.of("server"), null, null, null, "AAAA", "a2", "2.0",
                api.base + "/files/alpha-2.jar", "sha512:S", null, null), r.entry());
    }

    @Test
    void keepsRequiredAndIncompatibleDependenciesOnly() {
        alpha();
        String err = Output.err(() -> {
            var r = modrinth.resolve("alpha", LATEST, null, on("fabric"));
            assertEquals(List.of(new Source.Dependency("BBBB", null, false), new Source.Dependency("DDDD", null, true)),
                    r.dependencies());
        });
        assertTrue(err.contains("external.jar"), err);
    }

    @Test
    void quiltAlsoAcceptsFabricMods() {
        alpha();
        assertEquals("a2", modrinth.resolve("alpha", LATEST, null, on("quilt")).entry().versionId());
    }

    @Test
    void vanillaGetsTheDataPack() {
        alpha();
        Lock.Entry e = modrinth.resolve("alpha", LATEST, null, on("vanilla")).entry();
        assertEquals("datapack", e.type());
        assertEquals("a2d", e.versionId());
    }

    @Test
    void pinnedVersion() {
        alpha();
        assertEquals("a1", modrinth.resolve("alpha", Manifest.Content.of("1.0"), null, on("fabric")).entry().versionId());
    }

    @Test
    void exactVersionId() {
        api.json("/modrinth/v2/project/beta", "modrinth-project-beta.json")
                .bytes("/modrinth/v2/version/b1", """
                        {"id": "b1", "project_id": "BBBB", "version_number": "1.0", "date_published": "x",
                         "loaders": ["fabric"], "files": [{"url": "u", "primary": true, "hashes": {}}]}
                        """.getBytes());
        assertEquals("1.0", modrinth.resolve("beta", LATEST, "b1", on("fabric")).entry().version());
    }

    @Test
    void pluginOnPaper() {
        mixed();
        Lock.Entry e = modrinth.resolve("shiny", LATEST, null, on("purpur")).entry();
        assertEquals("plugin", e.type());
        assertEquals(List.of("server"), e.sides(), "plugins run on the server, whatever the project says");
    }

    @Test
    void resourcePackWhenNothingElseFits() {
        mixed();
        Lock.Entry e = modrinth.resolve("shiny", LATEST, null, on("fabric")).entry();
        assertEquals("resourcepack", e.type());
        assertEquals("packsha1", e.sha1());
        assertEquals(List.of("client"), e.sides());
    }

    @Test
    void aWantedTypeWins() {
        alpha();
        Lock.Entry e = modrinth.resolve("alpha", new Manifest.Content("latest", null, null, "datapack", null), null,
                on("fabric")).entry();
        assertEquals("datapack", e.type());
        assertEquals("a2d", e.versionId());
    }

    @Test
    void shaders() {
        api.json("/modrinth/v2/project/shiny", "modrinth-project-client.json")
                .bytes(modrinthVersions("SSSS", "1.21.4"), """
                        [{"id": "s1", "project_id": "SSSS", "version_number": "1.0", "version_type": "release",
                          "date_published": "x", "loaders": ["iris", "optifine"],
                          "files": [{"url": "u", "primary": true, "hashes": {"sha512": "0"}}]}]
                        """.getBytes());
        Lock.Entry e = modrinth.resolve("shiny", LATEST, null, on("fabric")).entry();
        assertEquals("shaderpack", e.type());
        assertEquals(List.of("client"), e.sides());
    }

    @Test
    void modSides() {
        assertEquals(List.of("client", "server"), Modrinth.sides("mod", "required", "required"));
        assertEquals(List.of("client", "server"), Modrinth.sides("mod", "optional", "unknown"));
        assertEquals(List.of("client"), Modrinth.sides("mod", "required", "optional"));
        assertEquals(List.of("server"), Modrinth.sides("mod", "optional", "required"));
        assertEquals(List.of("client"), Modrinth.sides("mod", "optional", "unsupported"));
        assertEquals(List.of("server"), Modrinth.sides("mod", "unsupported", "required"));
        assertEquals(List.of("server"), Modrinth.sides("datapack", "required", "required"));
    }

    @Test
    void noCompatibleVersion() {
        api.json("/modrinth/v2/project/beta", "modrinth-project-beta.json")
                .bytes(modrinthVersions("BBBB", "1.21.4"), "[]".getBytes());
        var e = assertThrows(EvokerException.class, () -> modrinth.resolve("beta", LATEST, null, on("paper")));
        assertTrue(e.getMessage().contains("has no version for paper 1.21.4"), e.getMessage());
    }

    @Test
    void unknownProject() {
        var e = assertThrows(EvokerException.class, () -> modrinth.resolve("nope", LATEST, null, on("fabric")));
        assertTrue(e.getMessage().contains("no Modrinth project"), e.getMessage());
    }
}
