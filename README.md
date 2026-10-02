# evoker

[![CI](https://github.com/gavinhsmith/evoker/actions/workflows/ci.yml/badge.svg)](https://github.com/gavinhsmith/evoker/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/gavinhsmith/evoker)](https://github.com/gavinhsmith/evoker/releases/latest)
[![License](https://img.shields.io/github/license/gavinhsmith/evoker)](LICENSE.md)
![Java 21](https://img.shields.io/badge/java-21-blue)

Manage your Minecraft server dependencies (server jar, mods, plugins, data packs, resource packs) with ease.

You describe the server in `evoker.json`, evoker records exactly what it installed in `evoker.lock`, and it runs the server as a child process.

> evoker is in early development. Features land milestone by milestone; see [Status](#status).

## Install

Download `evoker.jar` from the [latest release](https://github.com/gavinhsmith/evoker/releases/latest) and run it with Java 21+:

```sh
java -jar evoker.jar <command>
```

Full documentation lives in the [wiki](https://github.com/gavinhsmith/evoker/wiki).

## Status

| Feature | Status |
|---|---|
| Build, CI, releases, wiki | ✅ |
| `install` / `start` (vanilla, paper, purpur, fabric) | planned |
| Modrinth content, dependency resolution | planned |
| `update` / `upgrade` | planned |
| Hangar + URL sources | planned |
| `init --git` | planned |
| quilt, neoforge | planned |
| spigot | planned |

## Contributing

**Prerequisites:** JDK 21 and git. Maven is not needed; use the wrapper.

```sh
./mvnw verify                  # build + unit and integration tests (offline)
./mvnw verify -Dgroups=live    # tests against the real Modrinth/Hangar/server APIs
java -jar target/evoker.jar    # run the build
```

On Windows use `mvnw.cmd`. `JAVA_HOME` must point at the JDK folder (not its `bin`).

- Branch from `main`, open a PR; CI must pass on Linux and Windows.
- Commit messages: `[tag] summary`, e.g. `[modrinth] resolve required dependencies`.
- New logic needs a unit test; a new API client needs fixtures and an integration test.
- Docs live in [`wiki/`](wiki/) and are synced to the GitHub Wiki on every push to `main`. Update them with behavior changes.
- Releases: a maintainer pushes a tag (`git tag v0.1.0 && git push origin v0.1.0`) and CI publishes `evoker.jar` to GitHub Releases.

Coding agents: see [AGENTS.md](AGENTS.md).

## License

[Apache 2.0](LICENSE.md)
