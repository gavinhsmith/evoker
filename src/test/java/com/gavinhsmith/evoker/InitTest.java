package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitTest {
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

    @Test
    void writesAStarterManifest() {
        assertEquals(0, run("init", "Fabric", "1.21.1"));

        Manifest m = Manifest.read(dir);
        assertEquals(new Manifest.ServerSpec("fabric", "1.21.1", "latest"), m.server());
        assertFalse(m.eula());
        assertFalse(Files.exists(dir.resolve(".gitignore")));
    }

    @Test
    void defaultsToPaperOnTheLatestRelease() {
        api.json("/mojang/mc/game/version_manifest_v2.json", "mojang-manifest.json");

        assertEquals(0, run("init"));

        assertEquals(new Manifest.ServerSpec("paper", "1.21.4", "latest"), Manifest.read(dir).server());
    }

    @Test
    void refusesToOverwrite() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), "{}");
        String err = Output.err(() -> assertEquals(1, run("init", "paper", "1.21.4")));
        assertTrue(err.contains("already exists"), err);
        assertEquals("{}", Files.readString(dir.resolve(Manifest.FILE)));
    }

    @Test
    void rejectsUnknownSoftware() {
        String err = Output.err(() -> assertEquals(1, run("init", "bukkit", "1.21.4")));
        assertTrue(err.contains("unknown server software"), err);
    }

    @Test
    void gitSetsUpARepositoryAndIgnoresManagedFiles() throws IOException {
        assumeTrue(gitAvailable(), "git is not installed");

        String err = Output.err(() -> assertEquals(0, run("init", "paper", "1.21.4", "--git")));

        assertTrue(Files.isDirectory(dir.resolve(".git")));
        String ignore = Files.readString(dir.resolve(".gitignore"));
        assertTrue(ignore.contains("plugins/modrinth-*.jar"), ignore);
        assertFalse(ignore.lines().anyMatch(l -> l.startsWith("evoker.")), "evoker.json and evoker.lock must be committed");
        assertTrue(err.contains("rcon.password"), err);
    }

    @Test
    void gitKeepsAnExistingGitignore() throws IOException {
        assumeTrue(gitAvailable(), "git is not installed");
        Files.writeString(dir.resolve(".gitignore"), "mine\n");

        Output.err(() -> assertEquals(0, run("init", "paper", "1.21.4", "--git")));

        assertEquals("mine\n", Files.readString(dir.resolve(".gitignore")));
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
