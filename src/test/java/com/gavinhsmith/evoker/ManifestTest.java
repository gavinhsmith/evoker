package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                  "server": { "software": "Fabric", "version": "1.21.1", "build": "0.16.5" },
                  "eula": true,
                  "properties": { "motd": "hi", "max-players": 20 },
                  "content": {
                    "sodium": "latest",
                    "hangar:ViaVersion": "5.0.3",
                    "url:geyser": { "url": "https://example.com/Geyser.jar", "type": "plugin" }
                  },
                  "evoker": { "autoUpdateDeps": true, "jvmArgs": ["-Xmx4G"] }
                }
                """);
        Manifest m = Manifest.read(dir);

        assertEquals(new Manifest.ServerSpec("fabric", "1.21.1", "0.16.5"), m.server());
        assertTrue(m.eula());
        assertEquals(20, m.properties().get("max-players"));
        assertEquals(List.of("modrinth:sodium", "hangar:ViaVersion", "url:geyser"), List.copyOf(m.content().keySet()));
        assertEquals(new Manifest.Content("latest", null, null), m.content().get("modrinth:sodium"));
        assertEquals(new Manifest.Content(null, "https://example.com/Geyser.jar", "plugin"), m.content().get("url:geyser"));
        assertTrue(m.evoker().autoUpdateDeps());
        assertFalse(m.evoker().autoUpdateServer());
        assertEquals("java", m.evoker().java());
        assertEquals(List.of("-Xmx4G"), m.evoker().jvmArgs());
    }

    @Test
    void defaultsForAMinimalFile() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), """
                { "server": { "software": "vanilla", "version": "1.21.4" } }
                """);
        Manifest m = Manifest.read(dir);

        assertEquals("latest", m.server().build());
        assertFalse(m.eula());
        assertTrue(m.properties().isEmpty());
        assertTrue(m.content().isEmpty());
        assertEquals(new Manifest.Settings(false, false, "java", List.of()), m.evoker());
    }

    @Test
    void roundTrips() {
        var content = new java.util.LinkedHashMap<String, Manifest.Content>();
        content.put("modrinth:sodium", new Manifest.Content("latest", null, null));
        content.put("url:x", new Manifest.Content(null, "https://example.com/x.zip", "datapack"));
        var m = new Manifest(new Manifest.ServerSpec("paper", "1.21.4", "latest"), true,
                java.util.Map.of("motd", "hi"), content, null);
        m.write(dir);

        assertEquals(m, Manifest.read(dir));
    }

    @Test
    void missingFileSuggestsInit() {
        var e = assertThrows(EvokerException.class, () -> Manifest.read(dir));
        assertTrue(e.getMessage().contains("evoker init"), e.getMessage());
    }

    @Test
    void missingServerIsAClearError() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), "{ \"eula\": true }");
        var e = assertThrows(EvokerException.class, () -> Manifest.read(dir));
        assertTrue(e.getMessage().contains("\"server\""), e.getMessage());
    }
}
