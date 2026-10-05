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

    private static Manifest.Game spec(String loader, String build) {
        return new Manifest.Game("1.21.4", loader, build);
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
                () -> server.resolve(new Manifest.Game("9.9", "vanilla", null)));
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
    void quiltLatestStableLoaderInNewestFirstOrder() {
        api.json("/quilt/v3/versions/loader/1.21.4", "quilt-loaders-1.21.4.json")
                .json("/quilt/v3/versions/loader", "quilt-loaders.json")
                .json("/quilt/v3/versions/installer", "quilt-installers.json", "sha256", "abc");

        assertEquals(new Server.Resolved("0.29.2", api.base + "/files/quilt-installer.jar", null, null),
                server.resolve(spec("quilt", "latest")));
    }

    @Test
    void neoforgeLatestStableForTheGameVersion() {
        api.json("/neoforge/api/maven/versions/releases/net/neoforged/neoforge", "neoforge-versions.json");

        assertEquals(new Server.Resolved("21.4.158",
                        api.base + "/neoforge/releases/net/neoforged/neoforge/21.4.158/neoforge-21.4.158-installer.jar",
                        null, null),
                server.resolve(spec("neoforge", "latest")));
        assertEquals("21.5.1-beta",
                server.resolve(new Manifest.Game("1.21.5", "neoforge", null)).build(), "beta when nothing else");
        assertThrows(EvokerException.class, () -> server.resolve(new Manifest.Game("1.20", "neoforge", null)));
    }

    @Test
    void neoforgeVersionPrefixes() {
        assertEquals("21.4", Server.neoforgePrefix("1.21.4"));
        assertEquals("21.0", Server.neoforgePrefix("1.21"));
        assertEquals("26.3.0", Server.neoforgePrefix("26.3"));
        assertEquals("26.1.2", Server.neoforgePrefix("26.1.2"));
    }

    @Test
    void spigotUnknownVersion() {
        var e = assertThrows(EvokerException.class, () -> server.resolve(spec("spigot", "latest")));
        assertTrue(e.getMessage().contains("spigot has no build for 1.21.4"), e.getMessage());
    }

    @Test
    void launchCommands(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        List<String> jvm = List.of("-Xmx4G");
        assertEquals(List.of("/opt/java", "-Xmx4G", "-jar", "server.jar", "nogui"),
                Server.command("paper", "232", "/opt/java", jvm, dir));
        assertEquals("fabric-server-launch.jar", Server.command("fabric", "0.19.5", "/opt/java", jvm, dir).get(3));
        assertEquals("quilt-server-launch.jar", Server.command("quilt", "0.29.2", "/opt/java", jvm, dir).get(3));
        String args = System.getProperty("os.name").startsWith("Windows") ? "win_args.txt" : "unix_args.txt";
        assertEquals(List.of("/opt/java", "-Xmx4G", "@libraries/net/neoforged/neoforge/21.4.158/" + args, "nogui"),
                Server.command("neoforge", "21.4.158", "/opt/java", jvm, dir));
    }
}
