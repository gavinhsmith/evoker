package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallerTest {
    @TempDir
    Path dir;

    final FakeApi api = new FakeApi();

    @AfterEach
    void close() {
        api.close();
    }

    private Installer installer() {
        return new Installer(dir, new Http());
    }

    @Test
    void fetchDownloadsAndReturnsSha256() throws IOException {
        byte[] data = "v1".getBytes(StandardCharsets.UTF_8);
        api.bytes("/files/a.jar", data);
        Path target = dir.resolve("mods/a.jar");

        String sha = installer().fetch("a", api.base + "/files/a.jar", target, null, "SHA-256",
                FakeServer.hash("SHA-256", data));

        assertEquals(FakeServer.hash("SHA-256", data), sha);
        assertEquals("v1", Files.readString(target));
    }

    @Test
    void fetchSkipsTheDownloadWhenTheFileMatchesTheLock() throws IOException {
        Path target = dir.resolve("a.jar");
        Files.writeString(target, "v1");
        String locked = FakeServer.hash("SHA-256", "v1".getBytes(StandardCharsets.UTF_8));

        assertEquals(locked, installer().fetch("a", api.base + "/files/a.jar", target, locked, null, null));
        assertEquals(0, api.hits.get());
    }

    @Test
    void fetchKeepsTheExistingFileWhenUpstreamChanged() throws IOException {
        Path target = dir.resolve("a.jar");
        Files.writeString(target, "tampered-locally");
        api.bytes("/files/a.jar", "v2".getBytes(StandardCharsets.UTF_8));
        String locked = FakeServer.hash("SHA-256", "v1".getBytes(StandardCharsets.UTF_8));

        assertEquals(locked, installer().fetch("a", api.base + "/files/a.jar", target, locked, null, null));
        assertEquals("tampered-locally", Files.readString(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "temp file cleaned up");
        }
    }

    @Test
    void fetchFailsOnAnUpstreamChecksumMismatch() {
        api.bytes("/files/a.jar", "v1".getBytes(StandardCharsets.UTF_8));
        Path target = dir.resolve("a.jar");

        assertThrows(EvokerException.class,
                () -> installer().fetch("a", api.base + "/files/a.jar", target, null, "SHA-256", "00"));
        assertFalse(Files.exists(target));
    }

    @Test
    void propertiesSetsOnlyListedKeys() throws IOException {
        Files.writeString(dir.resolve("server.properties"), "motd=old\nlevel-name=world\n");
        var wanted = new LinkedHashMap<String, Object>();
        wanted.put("motd", "new");
        wanted.put("max-players", 20);
        wanted.put("pvp", false);

        installer().properties(wanted);

        Properties p = load();
        assertEquals("new", p.getProperty("motd"));
        assertEquals("20", p.getProperty("max-players"));
        assertEquals("false", p.getProperty("pvp"));
        assertEquals("world", p.getProperty("level-name"));
    }

    @Test
    void propertiesLeavesTheFileAloneWhenNothingChanged() throws IOException {
        String original = "# hand written\nmotd=same\n";
        Files.writeString(dir.resolve("server.properties"), original);

        installer().properties(Map.of("motd", "same"));

        assertEquals(original, Files.readString(dir.resolve("server.properties")));
    }

    @Test
    void acceptEulaWritesEulaTxt() throws IOException {
        installer().acceptEula();
        assertTrue(Files.readString(dir.resolve("eula.txt")).contains("eula=true"));
    }

    private Properties load() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("server.properties"))) {
            p.load(in);
        }
        return p;
    }
}
