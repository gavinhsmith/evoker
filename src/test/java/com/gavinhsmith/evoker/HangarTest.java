package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HangarTest {
    final FakeApi api = new FakeApi();
    final Hangar hangar = new Hangar(new Http(), api.apis().hangar());

    static final Manifest.ServerSpec PAPER = new Manifest.ServerSpec("paper", "1.21.4", null);
    static final String VERSIONS = "/hangar/api/v1/projects/ViaVersion/versions?limit=1&platform=PAPER&platformVersion=1.21.4";
    static final String RELEASES = VERSIONS + "&channel=Release";

    @AfterEach
    void close() {
        api.close();
    }

    private Source.Resolution resolve(String ref, String version, Manifest.ServerSpec server) {
        return hangar.resolve(ref, new Manifest.Content(version, null, null), null, server);
    }

    private void via() {
        api.json("/hangar/api/v1/projects/ViaVersion", "hangar-project-via.json")
                .json(RELEASES, "hangar-versions-via.json", "sha256", "abc");
    }

    @Test
    void latestPrefersTheReleaseChannel() {
        via();
        String err = Output.err(() -> {
            Source.Resolution r = resolve("ViaVersion", "latest", PAPER);
            assertEquals("ViaVersion", r.slug());
            assertEquals(new Lock.Entry("plugin", "31", "500", "5.0.3", api.base + "/files/via.jar", null, null, null),
                    r.entry());
            assertEquals("SHA-256", r.algo());
            assertEquals("abc", r.hash());
            assertEquals(List.of(new Source.Dependency("12", null, false)), r.dependencies());
        });
        assertTrue(err.contains("ProtocolLib from outside Hangar"), err);
    }

    @Test
    void externalDownloadHasNoUpstreamHash() {
        api.json("/hangar/api/v1/projects/12", "hangar-project-backwards.json")
                .json("/hangar/api/v1/projects/ViaBackwards/versions?limit=1&platform=PAPER&platformVersion=1.21.4&channel=Release",
                        "hangar-versions-backwards.json");
        Source.Resolution r = resolve("12", "latest", PAPER);
        assertEquals(api.base + "/files/backwards.jar", r.entry().url());
        assertNull(r.algo());
    }

    @Test
    void pinnedVersionWarnsWhenNotMarkedCompatible() {
        api.json("/hangar/api/v1/projects/ViaVersion", "hangar-project-via.json")
                .bytes("/hangar/api/v1/projects/ViaVersion/versions/4.0.0", """
                        {"id": 400, "name": "4.0.0", "createdAt": "2025-01-01T00:00:00Z",
                         "downloads": {"PAPER": {"downloadUrl": "https://x/via4.jar", "fileInfo": {"sha256Hash": "0"}}},
                         "platformDependencies": {"PAPER": ["1.20.4"]}}
                        """.getBytes());
        String err = Output.err(() -> assertEquals("400", resolve("ViaVersion", "4.0.0", PAPER).entry().versionId()));
        assertTrue(err.contains("not marked compatible with paper 1.21.4"), err);
    }

    @Test
    void onlyOnPaperAndPurpur() {
        var e = assertThrows(EvokerException.class,
                () -> resolve("ViaVersion", "latest", new Manifest.ServerSpec("fabric", "1.21.4", null)));
        assertTrue(e.getMessage().contains("need paper or purpur"), e.getMessage());
    }

    @Test
    void noVersionForThisGameVersion() {
        api.json("/hangar/api/v1/projects/ViaVersion", "hangar-project-via.json")
                .bytes(RELEASES, "{\"result\": []}".getBytes())
                .bytes(VERSIONS, "{\"result\": []}".getBytes());
        var e = assertThrows(EvokerException.class, () -> resolve("ViaVersion", "latest", PAPER));
        assertTrue(e.getMessage().contains("hangar:ViaVersion has no version for paper 1.21.4"), e.getMessage());
    }

    @Test
    void fallsBackToSnapshotsWhenThereIsNoRelease() {
        api.json("/hangar/api/v1/projects/ViaVersion", "hangar-project-via.json")
                .bytes(RELEASES, "{\"result\": []}".getBytes())
                .bytes(VERSIONS, """
                        {"result": [{"id": 501, "name": "5.1.0-SNAPSHOT", "channel": {"name": "Snapshot"},
                          "downloads": {"PAPER": {"downloadUrl": "https://x/snap.jar"}}}]}
                        """.getBytes());
        assertEquals("5.1.0-SNAPSHOT", resolve("ViaVersion", "latest", PAPER).entry().version());
    }
}
