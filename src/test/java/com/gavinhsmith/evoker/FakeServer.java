package com.gavinhsmith.evoker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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

    /** A runnable jar of this class. */
    static byte[] jar() {
        return jar(FakeServer.class, Map.of());
    }

    /** A runnable jar with main as entry point, containing the fake classes and the given resources. */
    static byte[] jar(Class<?> main, Map<String, byte[]> resources) {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main.getName());
        var bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes, manifest)) {
            for (Class<?> c : new Class<?>[] {FakeServer.class, FakeInstaller.class}) {
                String entry = c.getName().replace('.', '/') + ".class";
                try (InputStream in = c.getClassLoader().getResourceAsStream(entry)) {
                    jar.putNextEntry(new JarEntry(entry));
                    in.transferTo(jar);
                    jar.closeEntry();
                }
            }
            for (var r : resources.entrySet()) {
                jar.putNextEntry(new JarEntry(r.getKey()));
                jar.write(r.getValue());
                jar.closeEntry();
            }
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
