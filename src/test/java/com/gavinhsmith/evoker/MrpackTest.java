package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MrpackTest {
    @TempDir
    Path dir;

    static final String INDEX = """
            {
              "formatVersion": 1, "game": "minecraft", "versionId": "1.2.0", "name": "Test Pack",
              "files": [
                {"path": "mods/alpha.jar", "hashes": {"sha512": "A512"}, "downloads": ["%1$s/files/alpha-2.jar"],
                 "env": {"client": "required", "server": "required"}},
                {"path": "mods/External Mod.jar", "hashes": {"sha512": "X512"}, "downloads": ["%1$s/files/external.jar"],
                 "env": {"client": "required", "server": "optional"}},
                {"path": "mods/shiny-client.jar", "hashes": {"sha512": "C512"}, "downloads": ["%1$s/files/client.jar"],
                 "env": {"client": "optional", "server": "unsupported"}},
                {"path": "shaderpacks/pretty.zip", "hashes": {"sha512": "S512"}, "downloads": ["%1$s/files/shader.zip"]},
                {"path": "config/alpha.toml", "hashes": {"sha512": "T512"}, "downloads": ["%1$s/files/alpha.toml"]}
              ],
              "dependencies": {"minecraft": "1.21.4", "fabric-loader": "0.19.5"}
            }
            """;

    /** Writes a .mrpack with the given index and extra entries. */
    static Path pack(Path file, String index, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); var zip = new ZipOutputStream(out)) {
            var all = new LinkedHashMap<String, String>();
            all.put("modrinth.index.json", index);
            all.putAll(entries);
            for (var e : all.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes());
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void readsTheGameAndEveryFileWithItsEnv() throws IOException {
        Mrpack p = Mrpack.read(pack(dir.resolve("p.mrpack"), INDEX.formatted("https://x"), Map.of(
                "overrides/config/a.toml", "a", "client-overrides/options.txt", "b", "README.md", "c")));

        assertEquals("Test Pack", p.name());
        assertEquals(new Manifest.Game("1.21.4", "fabric", "0.19.5"), p.game());
        assertEquals(5, p.files().size());
        assertEquals(new Mrpack.PackFile("mods/shiny-client.jar", "C512", "https://x/files/client.jar", "optional",
                "unsupported"), p.files().get(2));
        assertEquals(List.of("required", "required"), List.of(p.files().get(3).client(), p.files().get(3).server()),
                "no env means required");
        assertEquals(2, p.overrides());
    }

    @Test
    void forgeIsRejected() throws IOException {
        Path zip = pack(dir.resolve("p.mrpack"),
                "{\"formatVersion\":1,\"game\":\"minecraft\",\"dependencies\":{\"minecraft\":\"1.20.1\",\"forge\":\"47.1.0\"}}",
                Map.of());
        var e = assertThrows(EvokerException.class, () -> Mrpack.read(zip));
        assertTrue(e.getMessage().contains("Forge"), e.getMessage());
    }

    @Test
    void notAPack() throws IOException {
        Path zip = dir.resolve("x.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
        }
        assertThrows(EvokerException.class, () -> Mrpack.read(zip));
    }
}
