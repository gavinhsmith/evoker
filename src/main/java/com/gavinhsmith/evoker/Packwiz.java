package com.gavinhsmith.evoker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * A packwiz pack (https://packwiz.infra.link/reference/pack-format/): pack.toml, index.toml, a .pw.toml metafile per
 * mod, and plain files (configs). Read into the same shape as a .mrpack, so import treats both alike.
 */
final class Packwiz {
    private static final TomlMapper TOML = new TomlMapper();

    /** A plain file of the pack, with its index hash. */
    private record Plain(String path, String algo, String hash) {}

    private final Http http;
    private final String url;
    private final Path dir;
    private final List<Plain> plain = new ArrayList<>();

    /** A pack.toml at a URL (url, its folder ending in /) or on disk (dir, the folder holding it). */
    private Packwiz(Http http, String url, Path dir) {
        this.http = http;
        this.url = url;
        this.dir = dir;
    }

    /** Is ref a packwiz pack: a pack.toml (URL or file), or a folder with one? */
    static boolean is(String ref, Path cwd) {
        return ref.endsWith("pack.toml") || Files.isRegularFile(cwd.resolve(ref).resolve("pack.toml"));
    }

    static Packwiz of(Http http, String ref, Path cwd) {
        if (ref.startsWith("https://") || ref.startsWith("http://")) {
            return new Packwiz(http, ref.substring(0, ref.length() - "pack.toml".length()), null);
        }
        Path p = cwd.resolve(ref).toAbsolutePath().normalize();
        return new Packwiz(http, null, Files.isDirectory(p) ? p : p.getParent());
    }

    /**
     * Reads pack.toml, index.toml and the metafiles. Mods, resource packs and shaders become pack files (packwiz's
     * side and option.optional as env); CurseForge-only files (no download URL) are skipped; plain files are kept for
     * {@link #copyOverrides}.
     */
    Mrpack read() {
        JsonNode pack = toml("pack.toml");
        JsonNode versions = pack.path("versions");
        String minecraft = versions.path("minecraft").asString(null);
        if (minecraft == null) throw new EvokerException("pack.toml has no minecraft version");
        Manifest.Game game;
        if (versions.has("fabric")) game = new Manifest.Game(minecraft, "fabric", versions.path("fabric").asString());
        else if (versions.has("quilt")) game = new Manifest.Game(minecraft, "quilt", versions.path("quilt").asString());
        else if (versions.has("neoforge")) game = new Manifest.Game(minecraft, "neoforge", versions.path("neoforge").asString());
        else if (versions.has("forge")) throw new EvokerException("Forge packs aren't supported (evoker runs neoforge, not forge)");
        else game = new Manifest.Game(minecraft, "vanilla", null);

        JsonNode index = toml(pack.path("index").path("file").asString("index.toml"));
        String indexAlgo = index.path("hash-format").asString("sha256");
        var files = new ArrayList<Mrpack.PackFile>();
        var curseforge = new ArrayList<String>();
        for (JsonNode f : index.path("files")) {
            String path = f.path("file").asString();
            if (!f.path("metafile").asBoolean(false)) {
                plain.add(new Plain(path, f.path("hash-format").asString(indexAlgo), f.path("hash").asString()));
                continue;
            }
            JsonNode meta = toml(path);
            JsonNode download = meta.path("download");
            String fileUrl = download.path("url").asString(null);
            if (fileUrl == null) {
                curseforge.add(meta.path("name").asString(path));
                continue;
            }
            String side = meta.path("side").asString("both");
            boolean optional = meta.path("option").path("optional").asBoolean(false);
            String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
            files.add(new Mrpack.PackFile(folder + meta.path("filename").asString(),
                    download.path("hash-format").asString("").equals("sha512") ? download.path("hash").asString() : null,
                    // "both" is packwiz's default, so it says nothing: env "unknown" lets evoker work the side out.
                    fileUrl, side.equals("server") ? "unsupported" : optional ? "optional" : side.equals("both") ? "unknown" : "required",
                    side.equals("client") ? "unsupported" : side.equals("both") ? "unknown" : "required"));
        }
        if (!curseforge.isEmpty()) {
            Main.warn("skipping " + curseforge.size() + " CurseForge files (evoker doesn't use CurseForge): " + curseforge);
        }
        return new Mrpack(pack.path("name").asString(), pack.path("version").asString(""), game, files, plain.size());
    }

    /** Copies the pack's plain files (configs, mostly) into the new pack's overrides/, checking each against the index. */
    void copyOverrides(Path pack) {
        for (Plain p : plain) {
            byte[] data = bytes(p.path());
            String hex = hex(p.algo(), data);
            if (hex != null && !hex.equalsIgnoreCase(p.hash())) {
                Main.warn(p.path() + " doesn't match index.toml; skipping it");
                continue;
            }
            Path target = Overrides.inside(pack, "overrides/" + p.path());
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, data);
            } catch (IOException e) {
                throw new EvokerException("cannot write " + target + ": " + e.getMessage(), e);
            }
        }
    }

    private JsonNode toml(String path) {
        try {
            return TOML.readTree(bytes(path));
        } catch (JacksonException e) {
            throw new EvokerException("invalid " + path + ": " + e.getOriginalMessage(), e);
        }
    }

    /** A file of the pack, relative to pack.toml. */
    private byte[] bytes(String path) {
        if (url != null) {
            byte[] data = http.bytesOrNull(url + Overrides.encode(path));
            if (data == null) throw new EvokerException("the pack has no " + path + " at " + url);
            return data;
        }
        Path file = Overrides.inside(dir, path);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new EvokerException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** The hex digest for a packwiz hash format, or null for one evoker can't check (murmur2). */
    private static String hex(String algo, byte[] data) {
        String name = switch (algo) {
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            case "md5" -> "MD5";
            default -> null;
        };
        if (name == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(name).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
