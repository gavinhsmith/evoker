package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Hits the real upstream APIs to catch drift. Run with: ./mvnw verify -Plive */
@Tag("live")
class LiveTest {
    final Server server = new Server(new Http(), Apis.DEFAULT);

    @ParameterizedTest
    @ValueSource(strings = {"vanilla", "paper", "purpur", "fabric", "quilt", "neoforge", "spigot"})
    void resolvesEveryLoader(String loader) {
        var resolved = server.resolve(new Manifest.Game("1.21.4", loader, null));
        assertTrue(resolved.url().startsWith("https://"), resolved.url());
        if (!loader.equals("vanilla")) assertNotNull(resolved.build());
    }

    final Modrinth modrinth = new Modrinth(new Http(), Apis.DEFAULT.modrinth());

    @Test
    void modrinthModWithRequiredDependency() {
        // Mod Menu requires Fabric API (P7dR8mSH)
        var r = modrinth.resolve("modmenu", Manifest.Content.of("latest"), null, new Manifest.Game("1.21.4", "fabric", null));
        assertEquals("mod", r.entry().type());
        assertTrue(r.dependencies().contains(new Source.Dependency("P7dR8mSH", null, false)), r.dependencies().toString());
    }

    @Test
    void hangarPlugin() {
        var r = new Hangar(new Http(), Apis.DEFAULT.hangar()).resolve("ViaVersion",
                Manifest.Content.of("latest"), null, new Manifest.Game("1.21.4", "paper", null));
        assertEquals("plugin", r.entry().type());
        assertTrue(r.entry().url().startsWith("https://"), r.entry().url());
    }

    @Test
    void modrinthPluginAndResourcePack() {
        var paper = new Manifest.Game("1.21.4", "paper", null);
        assertEquals("plugin", modrinth.resolve("luckperms", Manifest.Content.of("latest"), null, paper).entry().type());
        assertEquals("resourcepack", modrinth.resolve("faithful-32x", Manifest.Content.of("latest"), null, paper).entry().type());
    }
}
