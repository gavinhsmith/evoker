package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverridesTest {
    @TempDir
    Path pack;

    @TempDir
    Path root;

    private void file(Path dir, String path, String text) throws IOException {
        Files.createDirectories(dir.resolve(path).getParent());
        Files.writeString(dir.resolve(path), text);
    }

    private static String sha(String text) {
        return Http.hash("SHA-256", FakeServer.hash("SHA-256", text.getBytes()));
    }

    private Map<String, String> apply(Map<String, String> before) {
        return Overrides.apply(root, "server", Overrides.scan(pack), before,
                Pack.Source.of(null, pack.toString()), new Http());
    }

    @Test
    void scanAndTheSidesOwnFolderWins() throws IOException {
        file(pack, "overrides/config/a.toml", "both");
        file(pack, "server-overrides/config/a.toml", "server");
        file(pack, "client-overrides/options.txt", "client");
        file(pack, "mods/ignored.jar", "x");

        Map<String, String> scanned = Overrides.scan(pack);

        assertEquals(sha("both"), scanned.get("overrides/config/a.toml"));
        assertEquals(3, scanned.size());
        assertEquals(Map.of("config/a.toml", "server-overrides/config/a.toml"), Overrides.forSide(scanned, "server"));
        assertEquals(Map.of("config/a.toml", "overrides/config/a.toml", "options.txt", "client-overrides/options.txt"),
                Overrides.forSide(scanned, "client"));
    }

    @Test
    void installsMissingFilesAndFollowsThePackUntilAFileIsChanged() throws IOException {
        file(pack, "overrides/a.toml", "v1");
        file(pack, "overrides/b.toml", "v1");
        Map<String, String> installed = apply(Map.of());
        assertEquals("v1", Files.readString(root.resolve("a.toml")));

        file(root, "b.toml", "mine");
        file(pack, "overrides/a.toml", "v2");
        file(pack, "overrides/b.toml", "v2");
        String err = Output.err(() -> assertEquals(sha("v1"), apply(installed).get("b.toml")));

        assertEquals("v2", Files.readString(root.resolve("a.toml")), "untouched: follows the pack");
        assertEquals("mine", Files.readString(root.resolve("b.toml")), "changed: yours");
        assertTrue(err.contains("keeping your changed b.toml"), err);
    }

    @Test
    void neverTouchesAFileThatWasThereBefore() throws IOException {
        file(root, "server.properties", "mine");
        file(pack, "overrides/server.properties", "pack");

        var installed = new java.util.HashMap<String, String>();
        String out = Output.out(() -> installed.putAll(apply(Map.of())));

        assertEquals("mine", Files.readString(root.resolve("server.properties")));
        assertFalse(installed.containsKey("server.properties"));
        assertTrue(out.contains("it was there before the pack"), out);
    }

    @Test
    void droppedFilesAreDeletedUnlessChanged() throws IOException {
        file(pack, "overrides/a.toml", "v1");
        file(pack, "overrides/b.toml", "v1");
        Map<String, String> installed = apply(Map.of());
        file(root, "b.toml", "mine");
        Files.delete(pack.resolve("overrides/a.toml"));
        Files.delete(pack.resolve("overrides/b.toml"));

        Output.err(() -> assertTrue(apply(installed).isEmpty()));

        assertFalse(Files.exists(root.resolve("a.toml")));
        assertEquals("mine", Files.readString(root.resolve("b.toml")));
    }

    @Test
    void pathsCannotLeaveTheirFolder() {
        assertThrows(EvokerException.class, () -> Overrides.inside(root, "../evil.txt"));
        assertThrows(EvokerException.class, () -> Overrides.inside(root, "."));
        assertEquals(root.toAbsolutePath().normalize().resolve("config/a.toml"), Overrides.inside(root, "config/a.toml"));
    }
}
