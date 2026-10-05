package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void commandPrintsTheLaunchLineForWhatIsLocked() throws IOException {
        paperProject();
        assertEquals(1, run("command"));

        assertEquals(0, run("install"));
        Files.writeString(dir.resolve(Manifest.FILE), Files.readString(dir.resolve(Manifest.FILE))
                .replace("\"-Xmx64M\"", "\"-Xmx64M\", \"-Dx=a b\""));
        String out = Output.out(() -> assertEquals(0, run("command")));

        assertTrue(out.strip().endsWith(" -Xmx64M \"-Dx=a b\" -jar server.jar nogui"), out);
    }

    @Test
    void flagsTakeSpaceOrEqualsValues() {
        var positional = new java.util.ArrayList<String>();
        var flags = Main.flags(java.util.List.of("https://x/a.jar", "--type", "mod", "--name=a=b", "--git"), positional);

        assertEquals(java.util.List.of("https://x/a.jar"), positional);
        assertEquals(java.util.Map.of("--type", "mod", "--name", "a=b", "--git", ""), flags);
        assertEquals(1, run("upgrade", "--dryrun"));
        assertEquals(1, run("upgrade", "--list=yes"));
        assertEquals(1, run("add", "x", "--type"));
    }

    private void listProject() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), """
                {
                  "server": { "software": "fabric", "version": "1.21.4" },
                  "content": {
                    "sodium": "latest",
                    "lithium": "0.14.3",
                    "ferrite-core": "latest",
                    "url:terralith": { "url": "https://example.com/terralith.zip", "type": "datapack" }
                  }
                }
                """);
        Files.writeString(dir.resolve(Lock.FILE), """
                {
                  "lockVersion": 1,
                  "server": { "software": "fabric", "version": "1.21.4", "build": "0.19.5" },
                  "content": {
                    "modrinth:sodium": { "type": "mod", "projectId": "AANobbMI", "version": "0.6.5" },
                    "modrinth:lithium": { "type": "mod", "projectId": "gvQqBUqZ", "version": "0.14.3" },
                    "modrinth:fabric-api": { "type": "mod", "projectId": "P7dR8mSH", "version": "0.110.0",
                                             "requiredBy": ["modrinth:sodium"] },
                    "url:terralith": { "type": "datapack", "projectId": "terralith" }
                  }
                }
                """);
    }

    @Test
    void listPrintsServerAndContentGroupedByType() throws IOException {
        listProject();
        String out = Output.out(() -> assertEquals(0, run("list")));

        assertEquals("""
                fabric 1.21.4 build 0.19.5 (latest)

                mods
                  modrinth:fabric-api    0.110.0  dependency of modrinth:sodium
                  modrinth:lithium       0.14.3   pinned
                  modrinth:sodium        0.6.5    latest

                datapacks
                  url:terralith          -        url

                not installed
                  modrinth:ferrite-core  latest   not installed
                """, out.replace("\r\n", "\n"));
    }

    @Test
    void listAsJson() throws IOException {
        listProject();
        String out = Output.out(() -> assertEquals(0, run("list", "--output", "json")));

        var json = Json.MAPPER.readTree(out);
        assertEquals(1, json.path("format").asInt());
        assertEquals("0.19.5", json.path("server").path("build").asString());
        assertFalse(json.path("server").path("pinned").asBoolean());
        var content = json.path("content");
        assertEquals(5, content.size());
        var fabricApi = content.get(0);
        assertEquals("modrinth:fabric-api", fabricApi.path("key").asString());
        assertTrue(fabricApi.path("pinned").isNull());
        assertEquals("modrinth:sodium", fabricApi.path("requiredBy").get(0).asString());
        var ferrite = content.get(1);
        assertEquals("modrinth:ferrite-core", ferrite.path("key").asString());
        assertFalse(ferrite.path("installed").asBoolean());
        assertTrue(ferrite.path("type").isNull());
        assertTrue(content.get(2).path("pinned").asBoolean());
        assertEquals(1, run("list", "--output=yaml"));
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

    /** A fabric server whose Modrinth has alpha 2.0 (requires beta) and beta 1.0. */
    private void fabricProject() throws IOException {
        byte[] alpha = "alpha-2".getBytes(), beta = "beta-1".getBytes();
        api.json("/fabric/v2/versions/loader/1.21.4", "fabric-loaders.json")
                .json("/fabric/v2/versions/installer", "fabric-installers.json")
                .bytes("/fabric/v2/versions/loader/1.21.4/0.19.5/1.1.2/server/jar", FakeServer.jar())
                .json("/modrinth/v2/project/alpha", "modrinth-project-alpha.json")
                .json(FakeApi.modrinthVersions("AAAA", "1.21.4"), "modrinth-versions-alpha.json",
                        "sha512", FakeServer.hash("SHA-512", alpha))
                .json("/modrinth/v2/project/BBBB", "modrinth-project-beta.json")
                .json(FakeApi.modrinthVersions("BBBB", "1.21.4"), "modrinth-versions-beta.json",
                        "sha512", FakeServer.hash("SHA-512", beta))
                .bytes("/files/alpha-2.jar", alpha)
                .bytes("/files/beta-1.jar", beta);
        Files.writeString(dir.resolve(Manifest.FILE), """
                { "server": { "software": "fabric", "version": "1.21.4" } }
                """);
    }

    @Test
    void addInstallsContentAndItsDependencies() throws IOException {
        fabricProject();

        Output.err(() -> assertEquals(0, run("add", "alpha")));

        assertEquals("alpha-2", Files.readString(dir.resolve("mods/modrinth-AAAA.jar")));
        assertEquals("beta-1", Files.readString(dir.resolve("mods/modrinth-BBBB.jar")));
        assertEquals(java.util.Set.of("modrinth:alpha"), Manifest.read(dir).content().keySet());
        Lock lock = Lock.read(dir);
        assertEquals("2.0", lock.content().get("modrinth:alpha").version());
        assertEquals(java.util.List.of("modrinth:alpha"), lock.content().get("modrinth:beta").requiredBy());
        assertEquals(Http.sha256(dir.resolve("mods/modrinth-BBBB.jar")), lock.content().get("modrinth:beta").sha256());

        api.hits.set(0);
        assertEquals(0, run("install"));
        assertEquals(0, api.hits.get(), "lock satisfied, nothing to resolve or download");
    }

    @Test
    void removeDeletesTheEntryAndItsOrphanedDependencies() throws IOException {
        fabricProject();
        Output.err(() -> run("add", "alpha"));

        assertEquals(0, run("remove", "alpha"));

        assertTrue(Files.notExists(dir.resolve("mods/modrinth-AAAA.jar")));
        assertTrue(Files.notExists(dir.resolve("mods/modrinth-BBBB.jar")));
        assertTrue(Lock.read(dir).content().isEmpty());
        assertTrue(Manifest.read(dir).content().isEmpty());
    }

    @Test
    void removingADependencyExplainsWhoNeedsIt() throws IOException {
        fabricProject();
        Output.err(() -> run("add", "alpha"));

        String err = Output.err(() -> assertEquals(1, run("remove", "beta")));

        assertTrue(err.contains("required by [modrinth:alpha]"), err);
    }

    @Test
    void addFailureLeavesEvokerJsonUntouched() throws IOException {
        fabricProject();
        String before = Files.readString(dir.resolve(Manifest.FILE));

        Output.err(() -> assertEquals(1, run("add", "nope")));

        assertEquals(before, Files.readString(dir.resolve(Manifest.FILE)));
    }

    @Test
    void dataPacksGoIntoTheWorldAndResourcePacksIntoServerProperties() throws IOException {
        byte[] zip = "alpha-datapack".getBytes();
        byte[] jar = FakeServer.jar();
        api.json("/mojang/mc/game/version_manifest_v2.json", "mojang-manifest.json")
                .json("/mojang/v1/packages/bbb/1.21.4.json", "mojang-1.21.4.json", "sha1", FakeServer.hash("SHA-1", jar))
                .bytes("/files/server.jar", jar)
                .json("/modrinth/v2/project/alpha", "modrinth-project-alpha.json")
                .json(FakeApi.modrinthVersions("AAAA", "1.21.4"), "modrinth-versions-alpha.json",
                        "sha512zip", FakeServer.hash("SHA-512", zip))
                .bytes("/files/alpha-2.zip", zip)
                .json("/modrinth/v2/project/shiny", "modrinth-project-client.json")
                .json(FakeApi.modrinthVersions("SSSS", "1.21.4"), "modrinth-versions-mixed.json");
        Files.writeString(dir.resolve(Manifest.FILE), """
                {
                  "server": { "software": "vanilla", "version": "1.21.4" },
                  "properties": { "level-name": "survival" },
                  "content": { "alpha": "latest", "shiny": "latest" }
                }
                """);

        Output.err(() -> assertEquals(0, run("install")));

        assertEquals("alpha-datapack", Files.readString(dir.resolve("survival/datapacks/modrinth-AAAA.zip")));
        String props = Files.readString(dir.resolve("server.properties"));
        assertTrue(props.contains("resource-pack=" + api.base.replace(":", "\\:") + "/files/pack.zip"), props);
        assertTrue(props.contains("resource-pack-sha1=packsha1"), props);
    }

    @Test
    void errorsExitWithOne() {
        assertEquals(1, run("install"));
        assertEquals(1, run("bogus"));
        assertEquals(1, run());
    }
}
