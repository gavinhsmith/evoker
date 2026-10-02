package com.gavinhsmith.evoker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class Main {
    static final String VERSION =
            Objects.requireNonNullElse(Main.class.getPackage().getImplementationVersion(), "dev");

    private static final String USAGE = """
            usage: evoker <command>

              install   download whatever evoker.lock / evoker.json say is missing
              start     install, then run the server
              version   print the evoker version
            """;

    private final Path dir;
    private final Server server;
    private final Installer installer;

    Main(Path dir, Apis apis) {
        Http http = new Http();
        this.dir = dir;
        this.server = new Server(http, apis);
        this.installer = new Installer(dir, http);
    }

    public static void main(String[] args) {
        System.exit(run(Path.of("").toAbsolutePath(), Apis.DEFAULT, args));
    }

    /** Runs one command in dir and returns the exit code. */
    static int run(Path dir, Apis apis, String... args) {
        if (args.length == 0) {
            System.err.print(USAGE);
            return 1;
        }
        Main main = new Main(dir, apis);
        try {
            switch (args[0]) {
                case "install" -> main.install(Manifest.read(dir), false);
                case "start" -> {
                    return main.start();
                }
                case "version", "--version" -> System.out.println("evoker " + VERSION);
                case "help", "-h", "--help" -> System.out.print(USAGE);
                default -> {
                    System.err.print("unknown command: " + args[0] + "\n\n" + USAGE);
                    return 1;
                }
            }
            return 0;
        } catch (EvokerException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    static void log(String message) {
        System.out.println("evoker: " + message);
    }

    static void warn(String message) {
        System.err.println("evoker: warning: " + message);
    }

    /** Brings the disk in line with evoker.json and evoker.lock. */
    Lock install(Manifest manifest, boolean updateServer) {
        Lock before = Lock.read(dir);
        Lock lock = before.withServer(installServer(manifest.server(), before.server(), updateServer));
        if (!lock.equals(before)) lock.write(dir);
        installer.properties(manifest.properties());
        if (manifest.eula()) installer.acceptEula();
        return lock;
    }

    private Lock.Locked installServer(Manifest.ServerSpec want, Lock.Locked have, boolean update) {
        boolean stale = have == null
                || !want.software().equals(have.software())
                || !want.version().equals(have.version())
                || (!want.build().equals("latest") && !want.build().equals(have.build()));
        Lock.Locked locked = have;
        Server.Resolved resolved = null;
        if (stale || (update && want.build().equals("latest"))) {
            resolved = server.resolve(want);
            if (stale || !resolved.url().equals(have.url())) {
                locked = new Lock.Locked(want.software(), want.version(), resolved.build(), resolved.url(), null);
            } else {
                resolved = null;
            }
        }
        String label = locked.software() + " " + locked.version() + (locked.build() == null ? "" : " build " + locked.build());
        String sha256 = installer.fetch(label, locked.url(), dir.resolve(Server.jarName(locked.software())),
                locked.sha256(), resolved == null ? null : resolved.algo(), resolved == null ? null : resolved.hash());
        return locked.withSha256(sha256);
    }

    /** Installs, then runs the server as a child process and returns its exit code. */
    int start() {
        Manifest manifest = Manifest.read(dir);
        install(manifest, manifest.evoker().autoUpdateServer());
        var command = Server.command(manifest.server().software(), manifest.evoker());
        log("starting " + String.join(" ", command));
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        } catch (IOException e) {
            throw new EvokerException("cannot start the server: " + e.getMessage(), e);
        }
        // Ctrl+C reaches the server too (same console), so on shutdown just wait for it to save and exit.
        // On Unix also forward a plain SIGTERM (e.g. from systemd); Windows' destroy() is a hard kill, so skip it there.
        Thread hook = new Thread(() -> {
            if (!process.isAlive()) return;
            if (!System.getProperty("os.name").startsWith("Windows")) process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                // JVM is exiting anyway
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvokerException("interrupted", e);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // the hook is already running
            }
        }
    }
}
