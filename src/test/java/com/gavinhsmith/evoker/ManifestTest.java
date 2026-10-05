package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestTest {
    @TempDir
    Path dir;

    @Test
    void readsEveryField() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), """
                {
                  "name": "My Pack",
                  "side": "both",
                  "game": { "version": "1.21.1", "loader": "Fabric", "build": "0.16.5" },
                  "content": {
                    "sodium": "latest",
                    "modrinth:distant-horizons": { "version": "latest", "optional": true },
                    "hangar:ViaVersion": { "version": "5.0.3", "side": "server" },
                    "url:geyser": { "url": "https://example.com/Geyser.jar", "type": "plugin" }
                  }
                }
                """);
        Manifest m = Manifest.read(dir);

        assertEquals("My Pack", m.name());
        assertEquals(List.of("client", "server"), m.sides());
        assertEquals(new Manifest.Game("1.21.1", "fabric", "0.16.5"), m.game());
        assertEquals(List.of("modrinth:sodium", "modrinth:distant-horizons", "hangar:ViaVersion", "url:geyser"),
                List.copyOf(m.content().keySet()));
        assertEquals(Manifest.Content.of("latest"), m.content().get("modrinth:sodium"));
        assertTrue(m.content().get("modrinth:distant-horizons").isOptional());
        assertEquals(new Manifest.Content("5.0.3", "server", null, null, null), m.content().get("hangar:ViaVersion"));
        assertEquals(new Manifest.Content(null, null, null, "plugin", "https://example.com/Geyser.jar"),
                m.content().get("url:geyser"));
    }

    @Test
    void roundTripsWithTheShortFormForPlainVersions() throws IOException {
        var content = new LinkedHashMap<String, Manifest.Content>();
        content.put("modrinth:sodium", Manifest.Content.of("latest"));
        content.put("modrinth:iris", new Manifest.Content("1.8.0", "client", true, null, null));
        content.put("url:x", new Manifest.Content(null, "server", false, "datapack", "https://example.com/x.zip"));
        var m = new Manifest("Pack", "server", new Manifest.Game("1.21.4", "paper", null), content);
        m.write(dir);

        assertEquals(m, Manifest.read(dir));
        String json = Files.readString(dir.resolve(Manifest.FILE));
        assertTrue(json.contains("\"modrinth:sodium\": \"latest\""), json);
        assertTrue(json.contains("\"build\": \"latest\""), json);
        assertTrue(!json.contains("\"optional\": false"), json);
    }

    @Test
    void pins() {
        assertTrue(Manifest.Content.of("1.0").pinned());
        assertTrue(!Manifest.Content.of("latest").pinned());
        assertTrue(!new Manifest.Content(null, null, null, "mod", "https://x").pinned());
    }

    @Test
    void sides() {
        assertEquals(List.of("server"), Manifest.sides(List.of("client", "server"), List.of("server")));
        assertEquals(List.of("client", "server"), Manifest.sides(List.of("server", "client"), List.of("client", "server")));
        assertEquals(List.of("server"), Manifest.defaultSides("plugin"));
        assertEquals(List.of("client"), Manifest.defaultSides("shaderpack"));
        assertEquals(null, Manifest.defaultSides("mod"));
    }

    @Test
    void missingFileSuggestsCreate() {
        var e = assertThrows(EvokerException.class, () -> Manifest.read(dir));
        assertTrue(e.getMessage().contains("evoker create"), e.getMessage());
    }

    @Test
    void clearErrors() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), "{ \"name\": \"x\", \"side\": \"both\" }");
        assertTrue(assertThrows(EvokerException.class, () -> Manifest.read(dir)).getMessage().contains("\"game\""));

        Files.writeString(dir.resolve(Manifest.FILE), """
                { "name": "x", "side": "everywhere", "game": { "version": "1.21.4", "loader": "fabric" } }
                """);
        assertTrue(assertThrows(EvokerException.class, () -> Manifest.read(dir)).getMessage().contains("client, server or both"));

        Files.writeString(dir.resolve(Manifest.FILE), """
                { "name": "x", "side": "both", "game": { "version": "1.21.4", "loader": "forge" } }
                """);
        assertTrue(assertThrows(EvokerException.class, () -> Manifest.read(dir)).getMessage().contains("unknown loader"));
    }
}
