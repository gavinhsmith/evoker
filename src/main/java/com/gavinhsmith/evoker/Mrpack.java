package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import tools.jackson.databind.JsonNode;

/** A Modrinth modpack (.mrpack, https://support.modrinth.com/en/articles/8802351): a zip with an index and overrides. */
record Mrpack(String name, String version, Manifest.ServerSpec server, List<PackFile> files, int clientOnly) {
    /** A file the pack downloads. */
    record PackFile(String path, String sha512, String url) {}

    /** Reads modrinth.index.json, keeping only files that run on a server. */
    static Mrpack read(Path zip) {
        JsonNode index;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry entry = z.getEntry("modrinth.index.json");
            if (entry == null) throw new EvokerException(zip.getFileName() + " is not a .mrpack (no modrinth.index.json)");
            try (InputStream in = z.getInputStream(entry)) {
                index = Json.MAPPER.readTree(in);
            }
        } catch (IOException e) {
            throw new EvokerException("cannot read " + zip.getFileName() + ": " + e.getMessage(), e);
        }
        if (index.path("formatVersion").asInt() != 1 || !index.path("game").asString().equals("minecraft")) {
            throw new EvokerException("unsupported .mrpack (formatVersion " + index.path("formatVersion").asInt() + ")");
        }

        JsonNode deps = index.path("dependencies");
        String game = deps.path("minecraft").asString(null);
        if (game == null) throw new EvokerException(".mrpack has no minecraft version");
        Manifest.ServerSpec server;
        if (deps.has("fabric-loader")) server = new Manifest.ServerSpec("fabric", game, deps.path("fabric-loader").asString());
        else if (deps.has("quilt-loader")) server = new Manifest.ServerSpec("quilt", game, deps.path("quilt-loader").asString());
        else if (deps.has("neoforge")) server = new Manifest.ServerSpec("neoforge", game, deps.path("neoforge").asString());
        else if (deps.has("forge")) throw new EvokerException("Forge packs aren't supported (evoker runs neoforge, not forge)");
        else server = new Manifest.ServerSpec("vanilla", game, null);

        var files = new ArrayList<PackFile>();
        int clientOnly = 0;
        for (JsonNode f : index.path("files")) {
            if (f.path("env").path("server").asString("required").equals("unsupported")) {
                clientOnly++;
                continue;
            }
            files.add(new PackFile(f.path("path").asString(), f.path("hashes").path("sha512").asString(),
                    f.path("downloads").path(0).asString()));
        }
        return new Mrpack(index.path("name").asString(), index.path("versionId").asString(), server, files, clientOnly);
    }

    /**
     * Copies overrides/ then server-overrides/ (which wins) into dir. Files that already exist in dir are left
     * alone, so importing over a configured server keeps its configs. Returns {written, kept}.
     */
    static int[] extractOverrides(Path zip, Path dir) {
        int written = 0, kept = 0;
        Path root = dir.toAbsolutePath().normalize();
        var fromPack = new HashSet<Path>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (String prefix : List.of("overrides/", "server-overrides/")) {
                for (var it = z.entries().asIterator(); it.hasNext(); ) {
                    ZipEntry e = it.next();
                    if (e.isDirectory() || !e.getName().startsWith(prefix)) continue;
                    Path target = root.resolve(e.getName().substring(prefix.length())).normalize();
                    if (!target.startsWith(root)) {
                        throw new EvokerException(".mrpack entry escapes the server folder: " + e.getName());
                    }
                    // server-overrides replace what overrides/ just wrote, but never a file that was already there
                    if (Files.exists(target) && !fromPack.contains(target)) {
                        kept++;
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (fromPack.add(target)) written++;
                }
            }
        } catch (IOException e) {
            throw new EvokerException("cannot extract " + zip.getFileName() + ": " + e.getMessage(), e);
        }
        return new int[] {written, kept};
    }
}
