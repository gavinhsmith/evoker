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

    private static Manifest.ServerSpec on(String software) {
        return new Manifest.ServerSpec(software, "1.21.4", null);
    }

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
        Source.Resolution r = modrinth.resolve("alpha", new Manifest.Content("latest", null, null), null, on("fabric"));

        assertEquals("alpha", r.slug());
        assertEquals(new Lock.Entry("mod", "AAAA", "a2", "2.0", api.base + "/files/alpha-2.jar", null, "a2sha1", null),
                r.entry());
        assertEquals("SHA-512", r.algo());
        assertEquals("S", r.hash());
    }

    @Test
    void keepsRequiredAndIncompatibleDependenciesOnly() {
        alpha();
        String err = Stderr.capture(() -> {
            var r = modrinth.resolve("alpha", new Manifest.Content("latest", null, null), null, on("fabric"));
            assertEquals(List.of(new Source.Dependency("BBBB", null, false), new Source.Dependency("DDDD", null, true)),
                    r.dependencies());
        });
        assertTrue(err.contains("external.jar"), err);
    }

    @Test
    void quiltAlsoAcceptsFabricMods() {
        alpha();
        assertEquals("a2", modrinth.resolve("alpha", new Manifest.Content("latest", null, null), null, on("quilt")).entry().versionId());
    }

    @Test
    void vanillaGetsTheDataPack() {
        alpha();
        Lock.Entry e = modrinth.resolve("alpha", new Manifest.Content("latest", null, null), null, on("vanilla")).entry();
        assertEquals("datapack", e.type());
        assertEquals("a2d", e.versionId());
    }

    @Test
    void pinnedVersion() {
        alpha();
        assertEquals("a1", modrinth.resolve("alpha", new Manifest.Content("1.0", null, null), null, on("fabric")).entry().versionId());
    }

    @Test
    void exactVersionId() {
        api.json("/modrinth/v2/project/beta", "modrinth-project-beta.json")
                .bytes("/modrinth/v2/version/b1", """
                        {"id": "b1", "project_id": "BBBB", "version_number": "1.0", "date_published": "x",
                         "loaders": ["fabric"], "files": [{"url": "u", "primary": true, "hashes": {}}]}
                        """.getBytes());
        assertEquals("1.0", modrinth.resolve("beta", new Manifest.Content("latest", null, null), "b1", on("fabric")).entry().version());
    }

    @Test
    void pluginOnPaperAndClientOnlyWarning() {
        mixed();
        String err = Stderr.capture(() ->
                assertEquals("plugin", modrinth.resolve("shiny", new Manifest.Content("latest", null, null), null, on("purpur")).entry().type()));
        assertTrue(err.contains("client-side only"), err);
    }

    @Test
    void resourcePackWhenNothingElseFits() {
        mixed();
        Lock.Entry e = modrinth.resolve("shiny", new Manifest.Content("latest", null, null), null, on("fabric")).entry();
        assertEquals("resourcepack", e.type());
        assertEquals("packsha1", e.sha1());
    }

    @Test
    void noCompatibleVersion() {
        api.json("/modrinth/v2/project/beta", "modrinth-project-beta.json")
                .bytes(modrinthVersions("BBBB", "1.21.4"), "[]".getBytes());
        var e = assertThrows(EvokerException.class, () -> modrinth.resolve("beta", new Manifest.Content("latest", null, null), null, on("paper")));
        assertTrue(e.getMessage().contains("has no version for paper 1.21.4"), e.getMessage());
    }

    @Test
    void unknownProject() {
        var e = assertThrows(EvokerException.class, () -> modrinth.resolve("nope", new Manifest.Content("latest", null, null), null, on("fabric")));
        assertTrue(e.getMessage().contains("no Modrinth project"), e.getMessage());
    }
}
