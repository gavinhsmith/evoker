package com.gavinhsmith.evoker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Stands in for a Minecraft server: records its arguments and exits with code 3. */
public class FakeServer {
    static final int EXIT_CODE = 3;
    static final String MARKER = "fake-server-ran.txt";

    public static void main(String[] args) throws IOException {
        Files.writeString(Path.of(MARKER), String.join(" ", args));
        System.exit(EXIT_CODE);
    }

    /** A runnable jar containing this class. */
    static byte[] jar() {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeServer.class.getName());
        String entry = FakeServer.class.getName().replace('.', '/') + ".class";
        var bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes, manifest);
             InputStream in = FakeServer.class.getClassLoader().getResourceAsStream(entry)) {
            jar.putNextEntry(new JarEntry(entry));
            in.transferTo(jar);
            jar.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static String hash(String algo, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algo).digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
