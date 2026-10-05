package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI itself: options, errors, and the offline list command. */
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

    @Test
    void flagsTakeSpaceOrEqualsValues() {
        var positional = new ArrayList<String>();
        var flags = Main.flags(List.of("sodium", "--type", "mod", "--side=a=b", "--optional"), positional);

        assertEquals(List.of("sodium"), positional);
        assertEquals(Map.of("--type", "mod", "--side", "a=b", "--optional", ""), flags);
        assertEquals(1, run("upgrade", "--pinnd"));
        assertEquals(1, run("upgrade", "--pinned=yes"));
        assertEquals(1, run("add", "x", "--type"));
        assertEquals(1, run("upgrade", "--pinned", "--keep-pinned"));
    }

    @Test
    void outputJsonOnlyForListings() {
        String err = Output.err(() -> assertEquals(1, run("update", "--output=json")));
        assertTrue(err.contains("only works with list"), err);
        assertEquals(1, run("list", "--output=yaml"));
    }

    @Test
    void errorsExitWithOne() {
        assertEquals(1, run("add", "sodium")); // no evoker.json
        assertEquals(1, run("bogus"));
        assertEquals(1, run());
    }

    private void listPack() throws IOException {
        Files.writeString(dir.resolve(Manifest.FILE), """
                {
                  "name": "My Pack",
                  "side": "both",
                  "game": { "version": "1.21.4", "loader": "fabric" },
                  "content": {
                    "sodium": "latest",
                    "lithium": "0.14.3",
                    "distant-horizons": { "version": "latest", "optional": true },
                    "ferrite-core": "latest",
                    "url:terralith": { "url": "https://example.com/terralith.zip", "type": "datapack" }
                  }
                }
                """);
        Files.writeString(dir.resolve(Lock.FILE), """
                {
                  "lockVersion": 2,
                  "game": { "version": "1.21.4", "loader": "fabric", "build": "0.19.5" },
                  "content": {
                    "modrinth:sodium": { "type": "mod", "sides": ["client"], "projectId": "AANobbMI", "version": "0.6.5" },
                    "modrinth:lithium": { "type": "mod", "sides": ["server"], "projectId": "gvQqBUqZ", "version": "0.14.3" },
                    "modrinth:distant-horizons": { "type": "mod", "sides": ["client"], "optional": true,
                                                   "projectId": "uCdwusMi", "version": "2.3.0" },
                    "modrinth:fabric-api": { "type": "mod", "sides": ["client", "server"], "projectId": "P7dR8mSH",
                                             "version": "0.110.0", "requiredBy": ["modrinth:sodium"] },
                    "url:terralith": { "type": "datapack", "sides": ["server"], "projectId": "terralith" }
                  }
                }
                """);
    }

    @Test
    void listPrintsTheGameAndContentGroupedByType() throws IOException {
        listPack();
        String out = Output.out(() -> assertEquals(0, run("list")));

        assertEquals("""
                My Pack (both): fabric 1.21.4 build 0.19.5

                mods
                  modrinth:distant-horizons  2.3.0    client  latest, optional
                  modrinth:fabric-api        0.110.0  both    dependency of modrinth:sodium
                  modrinth:lithium           0.14.3   server  pinned
                  modrinth:sodium            0.6.5    client  latest

                datapacks
                  url:terralith              -        server  url

                not locked yet
                  modrinth:ferrite-core      latest   -       run evoker update
                """, out.replace("\r\n", "\n"));

        String mods = Output.out(() -> assertEquals(0, run("list", "datapacks")));
        assertFalse(mods.contains("sodium"), mods);
        assertTrue(mods.contains("terralith"), mods);
    }

    @Test
    void listAsJson() throws IOException {
        listPack();
        String out = Output.out(() -> assertEquals(0, run("list", "--output", "json")));

        var json = Json.MAPPER.readTree(out);
        assertEquals(2, json.path("format").asInt());
        assertEquals("0.19.5", json.path("game").path("build").asString());
        assertFalse(json.path("game").path("pinned").asBoolean());
        var content = json.path("content");
        assertEquals(6, content.size());
        var fabricApi = content.get(1);
        assertEquals("modrinth:fabric-api", fabricApi.path("key").asString());
        assertTrue(fabricApi.path("pinned").isNull());
        assertEquals("modrinth:sodium", fabricApi.path("requiredBy").get(0).asString());
        assertEquals("server", fabricApi.path("sides").get(1).asString());
        assertTrue(content.get(0).path("optional").asBoolean());
        var ferrite = content.get(2);
        assertTrue(ferrite.path("sides").isNull(), "not locked yet");
        assertTrue(content.get(3).path("pinned").asBoolean());
    }
}
