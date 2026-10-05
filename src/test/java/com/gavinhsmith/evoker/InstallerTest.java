package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static String hash(String algo, String data) {
        return Http.hash(algo, FakeServer.hash(algo, data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fetchWithoutAHashInstallsAndReturnsTheSha256ToPin() throws IOException {
        api.bytes("/files/a.jar", "v1".getBytes());
        Path target = dir.resolve("mods/a.jar");

        assertEquals(hash("SHA-256", "v1"), installer().fetch("a", api.base + "/files/a.jar", target, null));
        assertEquals("v1", Files.readString(target));
    }

    @Test
    void fetchChecksTheLockHash() throws IOException {
        api.bytes("/files/a.jar", "v1".getBytes());
        Path target = dir.resolve("a.jar");

        installer().fetch("a", api.base + "/files/a.jar", target, hash("SHA-512", "v1"));

        assertEquals("v1", Files.readString(target));
    }

    @Test
    void fetchSkipsTheDownloadWhenTheFileMatches() throws IOException {
        Path target = dir.resolve("a.jar");
        Files.writeString(target, "v1");

        installer().fetch("a", api.base + "/files/a.jar", target, hash("SHA-512", "v1"));

        assertEquals(0, api.hits.get());
    }

    @Test
    void fetchNeverInstallsAMismatch() throws IOException {
        api.bytes("/files/a.jar", "v2".getBytes());
        Path kept = dir.resolve("kept.jar"), missing = dir.resolve("missing.jar");
        Files.writeString(kept, "mine");

        String err = Output.err(() -> {
            installer().fetch("a", api.base + "/files/a.jar", kept, hash("SHA-512", "v1"));
            installer().fetch("b", api.base + "/files/a.jar", missing, hash("SHA-512", "v1"));
        });

        assertEquals("mine", Files.readString(kept));
        assertFalse(Files.exists(missing));
        assertTrue(err.contains("keeping the existing file") && err.contains("not installing it"), err);
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "temp files cleaned up");
        }
    }

    @Test
    void propertiesSetsOnlyListedKeys() throws IOException {
        Files.writeString(dir.resolve("server.properties"), "motd=mine\nlevel-name=survival\n");
        var wanted = new LinkedHashMap<String, String>();
        wanted.put("resource-pack", "https://x/pack.zip");
        wanted.put("resource-pack-sha1", "abc");

        installer().properties(wanted);

        Properties p = load();
        assertEquals("https://x/pack.zip", p.getProperty("resource-pack"));
        assertEquals("mine", p.getProperty("motd"));
        assertEquals("survival", installer().levelName());
    }

    @Test
    void propertiesLeavesTheFileAloneWhenNothingChanged() throws IOException {
        String original = "# hand written\nresource-pack=same\n";
        Files.writeString(dir.resolve("server.properties"), original);

        installer().properties(Map.of("resource-pack", "same"));

        assertEquals(original, Files.readString(dir.resolve("server.properties")));
    }

    @Test
    void eula() throws IOException {
        assertFalse(installer().eulaAccepted());
        installer().acceptEula();
        assertTrue(installer().eulaAccepted());
        assertTrue(Files.readString(dir.resolve("eula.txt")).contains("remain responsible"));
    }

    private Properties load() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("server.properties"))) {
            p.load(in);
        }
        return p;
    }
}
