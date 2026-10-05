package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import tools.jackson.databind.JsonNode;

/** A Modrinth modpack (.mrpack, https://support.modrinth.com/en/articles/8802351): a zip with an index and overrides. */
record Mrpack(String name, String version, Manifest.Game game, List<PackFile> files, int overrides) {
    /** A file the pack downloads. client / server are its env: required, optional or unsupported. */
    record PackFile(String path, String sha512, String url, String client, String server) {}

    /** Reads modrinth.index.json, and counts the override files (which evoker doesn't use yet). */
    static Mrpack read(Path zip) {
        JsonNode index;
        int overrides = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry entry = z.getEntry("modrinth.index.json");
            if (entry == null) throw new EvokerException(zip.getFileName() + " is not a .mrpack (no modrinth.index.json)");
            try (InputStream in = z.getInputStream(entry)) {
                index = Json.MAPPER.readTree(in);
            }
            for (var it = z.entries().asIterator(); it.hasNext(); ) {
                ZipEntry e = it.next();
                if (!e.isDirectory() && e.getName().matches("(client-|server-)?overrides/.*")) overrides++;
            }
        } catch (IOException e) {
            throw new EvokerException("cannot read " + zip.getFileName() + ": " + e.getMessage(), e);
        }
        if (index.path("formatVersion").asInt() != 1 || !index.path("game").asString().equals("minecraft")) {
            throw new EvokerException("unsupported .mrpack (formatVersion " + index.path("formatVersion").asInt() + ")");
        }

        JsonNode deps = index.path("dependencies");
        String version = deps.path("minecraft").asString(null);
        if (version == null) throw new EvokerException(".mrpack has no minecraft version");
        Manifest.Game game;
        if (deps.has("fabric-loader")) game = new Manifest.Game(version, "fabric", deps.path("fabric-loader").asString());
        else if (deps.has("quilt-loader")) game = new Manifest.Game(version, "quilt", deps.path("quilt-loader").asString());
        else if (deps.has("neoforge")) game = new Manifest.Game(version, "neoforge", deps.path("neoforge").asString());
        else if (deps.has("forge")) throw new EvokerException("Forge packs aren't supported (evoker runs neoforge, not forge)");
        else game = new Manifest.Game(version, "vanilla", null);

        var files = new ArrayList<PackFile>();
        for (JsonNode f : index.path("files")) {
            files.add(new PackFile(f.path("path").asString(), f.path("hashes").path("sha512").asString(),
                    f.path("downloads").path(0).asString(), f.path("env").path("client").asString("required"),
                    f.path("env").path("server").asString("required")));
        }
        return new Mrpack(index.path("name").asString(), index.path("versionId").asString(), game, files, overrides);
    }
}
