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
        assertNull(lock.server());
        assertTrue(lock.content().isEmpty());
    }

    @Test
    void roundTrips() {
        var lock = new Lock(Lock.VERSION, new Lock.Locked("paper", "1.21.4", "232", "https://x/paper.jar", "abc"),
                Map.of("modrinth:sodium", new Lock.Entry("mod", "AANobbMI", "Yp8wLY1P", "0.6.0", "https://x/s.jar",
                        "def", null, List.of("modrinth:other"))));
        lock.write(dir);

        assertEquals(lock, Lock.read(dir));
    }

    @Test
    void refusesALockFromANewerEvoker() throws IOException {
        Files.writeString(dir.resolve(Lock.FILE), "{ \"lockVersion\": 99 }");
        var e = assertThrows(EvokerException.class, () -> Lock.read(dir));
        assertTrue(e.getMessage().contains("newer evoker"), e.getMessage());
    }
}
