package com.gavinhsmith.evoker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for the Quilt and NeoForge installers: records its arguments and writes what the real ones
 * produce, with FakeServer as the server.
 */
public class FakeInstaller {
    static final String ARGS = "installer-args.txt";

    public static void main(String[] args) throws IOException {
        Files.writeString(Path.of(ARGS), String.join(" ", args));
        if (args[0].equals("install")) {
            Files.write(Path.of("quilt-server-launch.jar"), FakeServer.jar());
        } else if (args[0].equals("--rev")) {
            // BuildTools: --rev <build> --compile spigot --output-dir <dir> --final-name <name> --nogui
            Files.write(Path.of(args[5], args[7]), FakeServer.jar());
        } else {
            String build;
            try (InputStream in = FakeInstaller.class.getResourceAsStream("/neoforge-build.txt")) {
                build = new String(in.readAllBytes());
            }
            Path libs = Files.createDirectories(Path.of("libraries/net/neoforged/neoforge/" + build));
            Files.write(Path.of("neoforge-fake-server.jar"), FakeServer.jar());
            Files.writeString(libs.resolve("win_args.txt"), "-jar neoforge-fake-server.jar");
            Files.writeString(libs.resolve("unix_args.txt"), "-jar neoforge-fake-server.jar");
            Files.writeString(Path.of("user_jvm_args.txt"), "# Xmx and Xms go here\n-Xms16M\n");
        }
    }
}
