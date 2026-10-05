# Server Software

| `loader` | `build` means | Downloaded from | Upstream checksum | evoker downloads |
|---|---|---|---|---|
| `vanilla` | (unused) | Mojang version manifest | SHA-1 | `server.jar` |
| `paper` | Paper build number | PaperMC API (fill v3) | SHA-256 | `server.jar` |
| `purpur` | Purpur build number | Purpur API v2 | MD5 | `server.jar` |
| `fabric` | Fabric loader version | Fabric meta (server launcher) | none | `fabric-server-launch.jar` |
| `quilt` | Quilt loader version | Quilt meta + Quilt installer | none | `quilt-installer.jar`, then runs it |
| `neoforge` | NeoForge version (e.g. `21.4.158`) | NeoForged Maven installer | none | `neoforge-installer.jar`, then runs it |
| `spigot` | Spigot build number (e.g. `4458`) | SpigotMC BuildTools (Jenkins) | none | `BuildTools.jar`, then builds Spigot locally |

The pack's [evoker.lock](evoker-lock) records the exact build and the upstream checksum. Where the upstream publishes none, each server pins the file it first downloaded by its own SHA-256 in [`.evoker/installed.json`](Servers#evoker-folder).

For `paper`, `purpur` and `spigot` packs, clients run vanilla Minecraft; for `fabric`, `quilt` and `neoforge`, clients run the same loader and `build` (see [Clients](Clients)).

## `build: "latest"`

- **paper**: the newest `STABLE`/`RECOMMENDED` build, or the newest build if none is stable.
- **purpur**: the newest build.
- **fabric**: the newest stable loader, with the newest stable installer.
- **quilt**: the newest non-beta loader available for the game version, or the newest beta if there is none.
- **neoforge**: the newest non-beta NeoForge for the game version, or the newest beta. NeoForge versions follow the game version: `1.21.4` → `21.4.x`, `1.21` → `21.0.x`, `26.1` → `26.1.0.x`.
- **spigot**: the current Spigot build for the game version (from `hub.spigotmc.org/versions/<version>.json`).

A newer installer, launcher or BuildTools on its own never counts as an update: a server is only re-downloaded or rebuilt when the locked `build` changes.

## Installers (quilt, neoforge, spigot)

evoker downloads the installer and runs it:

- quilt: `java -jar quilt-installer.jar install server <version> <build> --download-server --install-dir=.`
- neoforge: `java -jar neoforge-installer.jar --installServer .`
- spigot: `java -jar BuildTools.jar --rev <build> --compile spigot --output-dir <server folder> --final-name server.jar --nogui`, run inside `.evoker/buildtools/` (where BuildTools clones and compiles). Spigot publishes no server downloads, so it is built locally: this needs **git** and takes **several minutes**, but only once per build. Unless you specifically need Spigot, `paper` runs the same plugins and downloads in seconds.

The installer's output is shown in the console as it runs, and also saved to `.evoker/installer.log`. evoker records what it installed in `.evoker/installer.stamp` and only runs the installer again when the loader, version, build or installer changes. A failing installer is an error.

## How the server is launched

`<java> <jvmArgs…> <launch> nogui`, in the server folder (`evoker server command` prints it), where `<launch>` is the following and `java` / `jvmArgs` are [server settings](Configuration#server-settings):

| `loader` | Launch |
|---|---|
| vanilla, paper, purpur, spigot | `-jar server.jar` |
| fabric | `-jar fabric-server-launch.jar` |
| quilt | `-jar quilt-server-launch.jar` |
| neoforge | `@user_jvm_args.txt @libraries/net/neoforged/neoforge/<build>/win_args.txt` (`unix_args.txt` on Linux/macOS), same as NeoForge's own `run.bat` / `run.sh` |

## Notes

- **fabric / quilt**: the vanilla server ends up in `server.jar` and libraries in `libraries/`.
- **paper / purpur** keep the vanilla jar they patch in `cache/` and `libraries/`.
- **neoforge**: `user_jvm_args.txt` is passed through if it exists; evoker's `jvmArgs` setting works too.
- **spigot**: `.evoker/buildtools/` keeps BuildTools' clones so later builds are faster; it is safe to delete.
