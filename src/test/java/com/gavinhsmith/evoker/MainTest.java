package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End to end: real files, a local fake API, and a real child process. */
class MainTest {
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

    private void paperProject() throws IOException {
        byte[] jar = FakeServer.jar();
        api.bytes("/files/server.jar", jar)
                .json("/paper/v3/projects/paper/versions/1.21.4/builds", "paper-builds.json",
                        "sha256", FakeServer.hash("SHA-256", jar));
        Files.writeString(dir.resolve(Manifest.FILE), """
                {
                  "server": { "software": "paper", "version": "1.21.4" },
                  "eula": true,
                  "properties": { "motd": "evoker test" },
                  "evoker": { "java": "%s", "jvmArgs": ["-Xmx64M"] }
                }
                """.formatted(FakeServer.java().replace("\\", "\\\\")));
    }

    @Test
    void startInstallsThenRunsTheServerAndReturnsItsExitCode() throws IOException {
        paperProject();

        assertEquals(FakeServer.EXIT_CODE, run("start"));

        assertEquals("nogui", Files.readString(dir.resolve(FakeServer.MARKER)));
        assertTrue(Files.readString(dir.resolve("eula.txt")).contains("eula=true"));
        assertTrue(Files.readString(dir.resolve("server.properties")).contains("motd=evoker test"));
        Lock lock = Lock.read(dir);
        assertEquals("232", lock.server().build());
        assertEquals(Http.sha256(dir.resolve("server.jar")), lock.server().sha256());
    }

    @Test
    void secondInstallDownloadsNothing() throws IOException {
        paperProject();
        assertEquals(0, run("install"));
        String lockBefore = Files.readString(dir.resolve(Lock.FILE));
        api.hits.set(0);

        assertEquals(0, run("install"));

        assertEquals(0, api.hits.get());
        assertEquals(lockBefore, Files.readString(dir.resolve(Lock.FILE)));
    }

    @Test
    void changingTheVersionReinstalls() throws IOException {
        paperProject();
        assertEquals(0, run("install"));
        Files.writeString(dir.resolve(Manifest.FILE),
                Files.readString(dir.resolve(Manifest.FILE)).replace("\"software\": \"paper\", \"version\": \"1.21.4\"",
                        "\"software\": \"paper\", \"version\": \"1.21.4\", \"build\": \"231\""));
        byte[] older = "older".getBytes();
        api.bytes("/files/paper-231.jar", older)
                .json("/paper/v3/projects/paper/versions/1.21.4/builds/231", "paper-build-231.json",
                        "sha256", FakeServer.hash("SHA-256", older));

        assertEquals(0, run("install"));

        assertEquals("231", Lock.read(dir).server().build());
        assertEquals("older", Files.readString(dir.resolve("server.jar")));
    }

    @Test
    void errorsExitWithOne() {
        assertEquals(1, run("install"));
        assertEquals(1, run("bogus"));
        assertEquals(1, run());
    }
}
