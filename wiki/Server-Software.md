# Server Software

| `software` | `build` means | Downloaded from | Upstream checksum | Jar |
|---|---|---|---|---|
| `vanilla` | (unused) | Mojang version manifest | SHA-1 | `server.jar` |
| `paper` | Paper build number | PaperMC API (fill v3) | SHA-256 | `server.jar` |
| `purpur` | Purpur build number | Purpur API v2 | MD5 | `server.jar` |
| `fabric` | Fabric loader version | Fabric meta (server launcher) | none | `fabric-server-launch.jar` |

`build: "latest"` picks:

- **paper**: the newest `STABLE`/`RECOMMENDED` build, or the newest build if none is stable.
- **purpur**: the newest build.
- **fabric**: the newest stable loader, with the newest stable installer.

The server runs as `<java> <jvmArgs…> -jar <jar> nogui` in the server folder.

## Notes

- **fabric**: the launcher downloads the vanilla server into `server.jar` and its libraries into `libraries/` on first start.
- **paper / purpur** keep the vanilla jar they patch in `cache/` and `libraries/`.

## Planned

| `software` | How |
|---|---|
| `quilt` | Runs the Quilt installer |
| `neoforge` | Runs the NeoForge installer; launched through its argument files |
| `spigot` | Built locally with BuildTools (no official downloads) |
