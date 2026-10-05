package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockTest {
    @TempDir
    Path dir;

    @Test
    void missingFileIsAnEmptyLock() {
        Lock lock = Lock.read(dir);
        assertEquals(Lock.VERSION, lock.lockVersion());
        assertNull(lock.game());
        assertTrue(lock.content().isEmpty());
    }

    @Test
    void roundTrips() {
        var lock = new Lock(Lock.VERSION, new Lock.Game("1.21.4", "paper", "232"),
                new Lock.Server("https://x/paper.jar", "sha256:abc"),
                Map.of("modrinth:sodium", new Lock.Entry("mod", List.of("client"), true, "Sodium", "Fast", "AANobbMI",
                        "Yp8wLY1P", "0.6.0", "https://x/s.jar", "sha512:def", null, List.of("modrinth:other"))));
        lock.write(dir);

        assertEquals(lock, Lock.read(dir));
    }

    @Test
    void entryHelpers() {
        var e = new Lock.Entry("mod", List.of("client"), null, "T", "D", "p", "v", "1", "u", null, null, null);
        assertNull(e.withOptional(false).title(), "only optional entries keep their title");
        assertEquals("T", e.withOptional(true).title());
        assertNull(e.withRequiredBy(List.of()).requiredBy());
    }

    @Test
    void refusesALockFromANewerEvoker() throws IOException {
        Files.writeString(dir.resolve(Lock.FILE), "{ \"lockVersion\": 99 }");
        var e = assertThrows(EvokerException.class, () -> Lock.read(dir));
        assertTrue(e.getMessage().contains("newer evoker"), e.getMessage());
    }

    @Test
    void refusesAnOldLock() throws IOException {
        Files.writeString(dir.resolve(Lock.FILE), "{ \"lockVersion\": 1, \"server\": { \"software\": \"paper\" } }");
        var e = assertThrows(EvokerException.class, () -> Lock.read(dir));
        assertTrue(e.getMessage().contains("evoker create"), e.getMessage());
    }
}
