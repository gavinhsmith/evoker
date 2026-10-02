# AGENTS.md

Instructions for coding agents working on evoker.

## What this is

evoker is a Java 21 CLI that manages a Minecraft server's dependencies. `evoker.json` (user-written) declares the server software, game version and content; `evoker.lock` (tool-written) records exactly what is installed; evoker launches the server as a child process. File formats, commands and behavior are documented in [`wiki/`](wiki/). Treat it as the spec, and update it when behavior changes.

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

- `Main`: CLI entry; a `switch` on the command, no CLI library. Also the install flow: `plan` (resolve, no side effects; used by `upgrade --dry-run`) then apply (download, delete, write lock)
- `Manifest`, `Lock`: `evoker.json` / `evoker.lock` records and JSON IO (`Json` holds the one mapper)
- `Source` + `Modrinth`, `Hangar`, `UrlSource`: content providers; `Mrpack`: reading .mrpack files for `import`
- `Server`: per-software resolution, installers (quilt, neoforge, spigot) and launch command
- `Resolver`: recursive dependency resolution, conflicts, pruning, keep-locked-on-failure
- `Installer`: file placement, lock-hash checks, `server.properties` / `eula.txt`
- `Http`: JSON GETs and hashed downloads; `Apis`: every upstream base URL; `EvokerException`: user-facing errors

Tests live in `src/test/java/com/gavinhsmith/evoker/`. `FakeApi` serves fixtures over a local HTTP server; `FakeServer` / `FakeInstaller` are built into jars at test time so the real process launching is exercised.

## Behavior rules worth knowing

- Never block `start`: if something can't be resolved but is locked, keep the locked version and warn.
- `install` / `start` go online only when `evoker.json` asks for something the lock doesn't satisfy.
- A file whose download no longer matches its locked sha256 is never installed (warning); `update` is how a URL entry accepts a new file.

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
