package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerTest {
    final FakeApi api = new FakeApi();
    final Server server = new Server(new Http(), api.apis());

    @AfterEach
    void close() {
        api.close();
    }

    private static Manifest.ServerSpec spec(String software, String build) {
        return new Manifest.ServerSpec(software, "1.21.4", build);
    }

    @Test
    void vanilla() {
        api.json("/mojang/mc/game/version_manifest_v2.json", "mojang-manifest.json")
                .json("/mojang/v1/packages/bbb/1.21.4.json", "mojang-1.21.4.json", "sha1", "abc");

        assertEquals(new Server.Resolved(null, api.base + "/files/server.jar", "SHA-1", "abc"),
                server.resolve(spec("vanilla", "latest")));
    }

    @Test
    void vanillaUnknownVersion() {
        api.json("/mojang/mc/game/version_manifest_v2.json", "mojang-manifest.json");
        var e = assertThrows(EvokerException.class,
                () -> server.resolve(new Manifest.ServerSpec("vanilla", "9.9", null)));
        assertTrue(e.getMessage().contains("unknown Minecraft version"), e.getMessage());
    }

    @Test
    void paperLatestSkipsUnstableBuilds() {
        api.json("/paper/v3/projects/paper/versions/1.21.4/builds", "paper-builds.json", "sha256", "abc");

        assertEquals(new Server.Resolved("232", api.base + "/files/server.jar", "SHA-256", "abc"),
                server.resolve(spec("paper", "latest")));
    }

    @Test
    void paperPinnedBuild() {
        api.json("/paper/v3/projects/paper/versions/1.21.4/builds/231", "paper-build-231.json", "sha256", "abc");

        assertEquals(new Server.Resolved("231", api.base + "/files/paper-231.jar", "SHA-256", "abc"),
                server.resolve(spec("paper", "231")));
    }

    @Test
    void paperUnknownVersion() {
        var e = assertThrows(EvokerException.class, () -> server.resolve(spec("paper", "latest")));
        assertTrue(e.getMessage().contains("paper has no builds"), e.getMessage());
    }

    @Test
    void purpurLatest() {
        api.json("/purpur/v2/purpur/1.21.4", "purpur-version.json")
                .json("/purpur/v2/purpur/1.21.4/2416", "purpur-build.json", "md5", "abc");

        assertEquals(new Server.Resolved("2416", api.base + "/purpur/v2/purpur/1.21.4/2416/download", "MD5", "abc"),
                server.resolve(spec("purpur", "latest")));
    }

    @Test
    void fabricLatestStableLoaderAndInstaller() {
        api.json("/fabric/v2/versions/loader/1.21.4", "fabric-loaders.json")
                .json("/fabric/v2/versions/installer", "fabric-installers.json");

        assertEquals(new Server.Resolved("0.19.5",
                        api.base + "/fabric/v2/versions/loader/1.21.4/0.19.5/1.1.2/server/jar", null, null),
                server.resolve(spec("fabric", "latest")));
    }

    @Test
    void notYetSupported() {
        var e = assertThrows(EvokerException.class, () -> server.resolve(spec("spigot", "latest")));
        assertTrue(e.getMessage().contains("not supported yet"), e.getMessage());
    }

    @Test
    void launchCommand() {
        var settings = new Manifest.Settings(false, false, "/opt/java", List.of("-Xmx4G"));
        assertEquals(List.of("/opt/java", "-Xmx4G", "-jar", "server.jar", "nogui"), Server.command("paper", settings));
        assertEquals("fabric-server-launch.jar", Server.command("fabric", settings).get(3));
    }
}
