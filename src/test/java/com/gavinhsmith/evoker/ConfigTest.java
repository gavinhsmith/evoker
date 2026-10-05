package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
    @TempDir
    Path dir;

    @TempDir
    Path user;

    @BeforeEach
    void userDir() {
        System.setProperty("evoker.configDir", user.toString());
    }

    @AfterEach
    void reset() {
        System.clearProperty("evoker.configDir");
    }

    private String run(String... args) {
        return Output.out(() -> assertEquals(0, Main.run(dir, Apis.DEFAULT, args))).strip();
    }

    @Test
    void serverSettingsHaveDefaultsAndTypes() {
        Config c = Config.server(dir);
        assertEquals("java", c.string("java"));
        assertEquals(List.of(), c.strings("jvmArgs"));
        assertTrue(c.bool("updateOnStart"));

        c.set("jvmArgs", "[\"-Xmx4G\"]");
        c.set("updateOnStart", "false");
        c.set("java", "C:\\Program Files\\Java\\bin\\java.exe");

        assertEquals(List.of("-Xmx4G"), c.strings("jvmArgs"));
        assertFalse(c.bool("updateOnStart"));
        assertEquals("C:\\Program Files\\Java\\bin\\java.exe", Config.server(dir).string("java"));
        assertTrue(assertThrows(EvokerException.class, () -> c.set("updateOnStart", "maybe")).getMessage()
                .contains("true or false"));
        assertTrue(assertThrows(EvokerException.class, () -> c.set("jvmArgs", "-Xmx4G")).getMessage()
                .contains("a list"));
        assertTrue(assertThrows(EvokerException.class, () -> c.get("instanceDir")).getMessage().contains("--user"));
    }

    @Test
    void theCommandPicksServerSettingsInAServerFolderAndUserSettingsElsewhere() throws IOException {
        assertEquals("instanceDir = \"D:\\\\Prism\\\\instances\"", run("config", "instanceDir", "D:\\Prism\\instances"));
        assertTrue(Files.exists(user.resolve("config.json")));

        Files.createDirectories(dir.resolve(ServerFolder.STATE));
        assertEquals("jvmArgs = [\"-Xmx2G\"]", run("config", "jvmArgs", "[\"-Xmx2G\"]"));
        assertTrue(run("config").contains("updateOnStart = true"));
        assertEquals("instanceDir = \"D:\\\\Prism\\\\instances\"", run("config", "instanceDir", "--user"));
        assertEquals(1, Main.run(dir, Apis.DEFAULT, "config", "instanceDir"));
    }
}
