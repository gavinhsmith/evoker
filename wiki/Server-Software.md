# Server Software

| `software` | `build` means | Downloaded from | Upstream checksum | evoker downloads |
|---|---|---|---|---|
| `vanilla` | (unused) | Mojang version manifest | SHA-1 | `server.jar` |
| `paper` | Paper build number | PaperMC API (fill v3) | SHA-256 | `server.jar` |
| `purpur` | Purpur build number | Purpur API v2 | MD5 | `server.jar` |
| `fabric` | Fabric loader version | Fabric meta (server launcher) | none | `fabric-server-launch.jar` |
| `quilt` | Quilt loader version | Quilt meta + Quilt installer | none | `quilt-installer.jar`, then runs it |
| `neoforge` | NeoForge version (e.g. `21.4.158`) | NeoForged Maven installer | none | `neoforge-installer.jar`, then runs it |

Whatever the upstream publishes, evoker pins the file it downloaded by its own SHA-256 in [evoker.lock](evoker-lock).

## `build: "latest"`

- **paper**: the newest `STABLE`/`RECOMMENDED` build, or the newest build if none is stable.
- **purpur**: the newest build.
- **fabric**: the newest stable loader, with the newest stable installer.
- **quilt**: the newest non-beta loader available for the game version, or the newest beta if there is none.
- **neoforge**: the newest non-beta NeoForge for the game version, or the newest beta. NeoForge versions follow the game version: `1.21.4` → `21.4.x`, `1.21` → `21.0.x`, `26.1` → `26.1.0.x`.

## Installers (quilt, neoforge)

evoker downloads the installer and runs it in the server folder:

- quilt: `java -jar quilt-installer.jar install server <version> <build> --download-server --install-dir=.`
- neoforge: `java -jar neoforge-installer.jar --installServer .`

The installer's output goes to `.evoker-installer.log`. evoker records what it installed in `.evoker-installed` and only runs the installer again when the software, version or build changes. A failing installer is an error.

## How the server is launched

`<java> <jvmArgs…> <launch> nogui`, in the server folder, where `<launch>` is:

| `software` | Launch |
|---|---|
| vanilla, paper, purpur | `-jar server.jar` |
| fabric | `-jar fabric-server-launch.jar` |
| quilt | `-jar quilt-server-launch.jar` |
| neoforge | `@user_jvm_args.txt @libraries/net/neoforged/neoforge/<build>/win_args.txt` (`unix_args.txt` on Linux/macOS), same as NeoForge's own `run.bat` / `run.sh` |

## Notes

- **fabric / quilt**: the vanilla server ends up in `server.jar` and libraries in `libraries/`.
- **paper / purpur** keep the vanilla jar they patch in `cache/` and `libraries/`.
- **neoforge**: `user_jvm_args.txt` is passed through if it exists; evoker's `jvmArgs` work too.

## Planned

| `software` | How |
|---|---|
| `spigot` | Built locally with BuildTools (no official downloads) |
