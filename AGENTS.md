# AGENTS.md

Instructions for coding agents working on evoker.

## What this is

evoker is a Java 21 CLI package manager for Minecraft. A pack is `evoker.json` (author-written: name, side, game version, loader, content) plus `evoker.lock` (tool-written: exact versions, URLs, hashes, sides). Pack commands only edit those two files; servers and Prism clients install from a published pack (server and client installs are being rebuilt for the pack format on the `packs` branch). File formats, commands and behavior are documented in [`wiki/`](wiki/). Treat it as the spec, and update it when behavior changes.

## Build and test

```sh
./mvnw verify                  # build, unit + integration tests (offline, uses local fixtures)
./mvnw verify -Plive           # only the live tests: real APIs (Modrinth, Hangar, Mojang, PaperMC, ...)
```

- Output: `target/evoker.jar` (shaded, runnable with `java -jar`).
- `JAVA_HOME` must point at a JDK 21 folder, not its `bin`.
- In Git Bash on Windows, if `./mvnw` fails downloading Maven, an old `wget` is on PATH; use `mvnw.cmd` or remove it from PATH.

## Code layout

`src/main/java/com/gavinhsmith/evoker/`, kept flat on purpose:

- `Main`: CLI entry; a `switch` on the command, no CLI library. Also the pack commands and `lock()`: resolving a pack into its lock (online only for what the lock doesn't already satisfy)
- `Manifest`, `Lock`: `evoker.json` / `evoker.lock` records and JSON IO (`Json` holds the one mapper)
- `Source` + `Modrinth`, `Hangar`, `UrlSource`: content providers; `Mrpack`: reading .mrpack files for `import`
- `Server`: per-loader build resolution, installers (quilt, neoforge, spigot) and launch command
- `Resolver`: recursive dependency resolution, conflicts, pruning, keep-locked-on-failure, and each entry's sides
- `Installer`: file placement, lock-hash checks, `server.properties` / `eula.txt`
- `install.sh` / `install.ps1` (repo root): one-command installers; CI runs both against the built jar
- `Http`: JSON GETs and hashed downloads; `Apis`: every upstream base URL; `EvokerException`: user-facing errors

Tests live in `src/test/java/com/gavinhsmith/evoker/`. `FakeApi` serves fixtures over a local HTTP server; `FakeServer` / `FakeInstaller` are built into jars at test time so the real process launching is exercised.

## Behavior rules worth knowing

- Pack commands never install anything; they resolve into `evoker.lock` (hashes come from upstream, or a one-off temp download).
- If something can't be resolved but is locked, keep the locked version and warn (`update` / `upgrade` never fail on content; `upgrade` does fail without a loader build).
- Resolving goes online only when `evoker.json` asks for something the lock doesn't satisfy.
- A file whose download no longer matches its locked hash is never installed (warning); `update` is how a URL entry accepts a new file.

## Rules

- Standard library first (`java.net.http`, `MessageDigest`, `Properties`, `ProcessBuilder`). Jackson is the only runtime dependency; ask before adding another.
- No abstractions without a second implementation. Prefer deleting code to adding it.
- API clients take their base URL as a constructor argument so tests can point them at a local `com.sun.net.httpserver.HttpServer`.
- New logic needs a unit test. A new API client needs JSON fixtures in `src/test/resources/fixtures/` and an integration test. Tests that touch the real network are tagged `@Tag("live")`.
- Downloaded files are named `<source>-<projectId>.<ext>`; never use upstream filenames.
- Keep `README.md` (status table, Contributing) and `wiki/` in sync with behavior.

## Git

- Commit messages: `[tag] summary`.
- Never mention Claude, Claude Code, or any AI tool in commits or PRs (no `Co-Authored-By` or "Generated with" lines).
- Never push. The maintainer pushes and tags releases.
