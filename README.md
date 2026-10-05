<div align="center">

<h1><img src=".github/logo.svg" alt="evoker" width="480"></h1>

**A package manager for Minecraft.**

Describe a pack once: the game version, the loader, the mods, plugins, resource packs, shaders and configs.<br>
evoker locks the exact versions and builds it on your server and on your players' Prism Launcher, and keeps both up to date.

[![Release](https://img.shields.io/github/v/release/gavinhsmith/evoker?label=release)](https://github.com/gavinhsmith/evoker/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/gavinhsmith/evoker/total)](https://github.com/gavinhsmith/evoker/releases)
[![CI](https://github.com/gavinhsmith/evoker/actions/workflows/ci.yml/badge.svg)](https://github.com/gavinhsmith/evoker/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/gavinhsmith/evoker)](LICENSE.md)
![Java 21+](https://img.shields.io/badge/java-21%2B-blue)

[Install](#install) · [Quick start](#quick-start) · [Documentation](https://github.com/gavinhsmith/evoker/wiki) · [Report a bug](https://github.com/gavinhsmith/evoker/issues)

</div>

```sh
evoker create "My Pack" fabric 1.21.4      # write the pack
evoker add lithium                         # content from Modrinth or Hangar, dependencies included
evoker install server <pack-url>           # on the server
evoker install <pack-url>                  # on a player's computer: a Prism Launcher instance
```

## Why evoker

Running a modded server usually means hunting down jars, matching versions by hand, and sending every player a zip that is out of date by next week. evoker treats the whole setup as one project:

- **One pack, both sides.** `evoker.json` lists the game, the loader and the content. evoker knows what runs where (Modrinth says which mods are client-only, server-only or both), so the server gets the server's files and players get theirs.
- **Reproducible.** `evoker.lock` pins every file to an exact version and hash, like `package-lock.json` or `Cargo.lock`. Every server and every player ends up with the same files.
- **Updates reach everyone.** Push a change to the pack's repo: servers pick it up on their next `evoker server start`, and players on their next launch, because evoker runs before Prism starts the game. A new game version stops that launch once, with a message, and the next one plays.
- **Dependencies handled.** Required dependencies are resolved recursively and dropped again when nothing needs them.
- **Optional content.** Mark something optional and players choose whether they want it.
- **Configs that stay yours.** Packs can ship config files. Once a server owner or player changes one, evoker never overwrites it.
- **Safe upgrades.** `evoker upgrade list 1.21.5` shows what a new game version would change. Anything without a compatible release keeps its current version instead of breaking the pack.
- **Modpacks in one command.** `evoker import <modpack>` turns a Modrinth `.mrpack` into an evoker pack, configs included.

## Supported

**Loaders:** Vanilla · Fabric · Quilt · NeoForge · Paper · Purpur · Spigot (built with BuildTools). Plugin servers pair with vanilla clients.

**Content:** [Modrinth](https://modrinth.com) (mods, plugins, data packs, resource packs, shaders, modpacks) · [Hangar](https://hangar.papermc.io) (Paper plugins) · any URL

**Clients:** [Prism Launcher](https://prismlauncher.org)

Runs on Windows, Linux and macOS with Java 21 or newer.

## Install

```sh
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.sh | bash
```

```powershell
# Windows (PowerShell)
irm https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.ps1 | iex
```

This installs the latest release and an `evoker` command. You can also download `evoker.jar` from the [releases](https://github.com/gavinhsmith/evoker/releases/latest) and run `java -jar evoker.jar`. More options: [Getting Started](https://github.com/gavinhsmith/evoker/wiki/Getting-Started).

## Quick start

**Make a pack**, in an empty folder:

```sh
evoker create "My Pack" fabric 1.21.4 --git
evoker add lithium                       # server-side
evoker add sodium                        # client-side: evoker works the sides out
evoker add distant-horizons --optional   # players choose
evoker list
```

Commit `evoker.json` and `evoker.lock` (and an `icon.png`, and any configs in `overrides/`) and push them to GitHub. The folder's raw URL is the pack URL, e.g. `https://raw.githubusercontent.com/you/my-pack/main/`.

**Run the server**, in an empty folder:

```sh
evoker install server https://raw.githubusercontent.com/you/my-pack/main/
evoker config jvmArgs '["-Xmx4G"]'
evoker server start
```

**Play:** players install evoker and Prism Launcher, then

```sh
evoker install https://raw.githubusercontent.com/you/my-pack/main/
```

and launch the new instance from Prism.

## Commands

| Pack (in the pack folder) | |
|---|---|
| `evoker create <name> <loader> [version] [client\|server\|both]` | Start a pack |
| `evoker add <source:name>[@version] [--optional]` | Add content with its dependencies |
| `evoker url <name> <url> <type>` | Add a file from a URL |
| `evoker remove <name>` | Remove it and whatever only it needed |
| `evoker list [type] [--output=json]` | Show versions, pins, sides and dependencies |
| `evoker update [name \| list]` | Move `latest` entries to their newest versions |
| `evoker upgrade [version \| list]` | Move the pack to a new game version |
| `evoker import <modpack>` | Start a pack from a Modrinth modpack |

| Server (in the server folder) | |
|---|---|
| `evoker install server <pack-url>` | Install the pack as a server here |
| `evoker server start` | Update, then run the server |
| `evoker server update [list]` | Apply the pack's latest changes |
| `evoker server command` | Print the launch command, for systemd, Docker or a panel |

| Client | |
|---|---|
| `evoker install <pack-url>` | Install the pack as a Prism Launcher instance |
| `evoker client update <pack>` | Update the instance (Prism runs this before every launch) |
| `evoker client options <pack>` | Choose optional content again |

`evoker config [setting] [value]` changes evoker's own settings. Full reference: [Commands](https://github.com/gavinhsmith/evoker/wiki/Commands) · [evoker.json](https://github.com/gavinhsmith/evoker/wiki/evoker-json) · [Servers](https://github.com/gavinhsmith/evoker/wiki/Servers) · [Clients](https://github.com/gavinhsmith/evoker/wiki/Clients) · [Overrides](https://github.com/gavinhsmith/evoker/wiki/Overrides) · [Content Sources](https://github.com/gavinhsmith/evoker/wiki/Content-Sources)

## Feedback

evoker is young (0.x). If something doesn't work with your pack, server, a mod, or a plugin, please [open an issue](https://github.com/gavinhsmith/evoker/issues). Ideas and questions are welcome in [Discussions](https://github.com/gavinhsmith/evoker/discussions). A ⭐ helps others find it.

## Contributing

**Prerequisites:** JDK 21 and git. Maven is not needed; use the wrapper.

```sh
./mvnw verify                  # build + unit and integration tests (offline)
./mvnw verify -Plive           # only the tests against the real Modrinth/Hangar/server APIs
java -jar target/evoker.jar    # run the build
```

On Windows use `mvnw.cmd`. `JAVA_HOME` must point at the JDK folder (not its `bin`).

- Branch from `main`, open a PR; CI must pass on Linux and Windows.
- Commit messages: `[tag] summary`, e.g. `[modrinth] resolve required dependencies`.
- New logic needs a unit test; a new API client needs fixtures and an integration test.
- Docs live in [`wiki/`](wiki/) and are synced to the GitHub Wiki on every push to `main`. Update them with behavior changes.
- Releases: a maintainer pushes a tag (`git tag v0.3.0 && git push origin v0.3.0`) and CI publishes `evoker.jar` to GitHub Releases.

Coding agents: see [AGENTS.md](AGENTS.md).

## License

[Apache 2.0](LICENSE.md). evoker is not affiliated with Mojang, Microsoft, Modrinth or PaperMC.
