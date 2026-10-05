# evoker.lock

Exactly which files a pack resolves to. **Written only by evoker; don't edit it. Publish it with `evoker.json`.**

Authoring commands (`add`, `update`, `upgrade`, ...) resolve and write the lock without installing anything. Servers and clients never resolve: they install exactly what the lock says, so every install of a pack gets the same files.

```json
{
  "lockVersion": 2,
  "game": { "version": "1.21.4", "loader": "fabric", "build": "0.16.10" },
  "server": {
    "url": "https://meta.fabricmc.net/v2/versions/loader/1.21.4/0.16.10/1.0.1/server/jar",
    "hash": null
  },
  "content": {
    "modrinth:sodium": {
      "type": "mod",
      "sides": ["client"],
      "projectId": "AANobbMI",
      "versionId": "…",
      "version": "mc1.21.4-0.6.5-fabric",
      "url": "https://cdn.modrinth.com/…",
      "hash": "sha512:…"
    },
    "modrinth:distant-horizons": {
      "type": "mod",
      "sides": ["client"],
      "optional": true,
      "title": "Distant Horizons",
      "description": "See farther without lag.",
      "projectId": "uCdwusMi",
      "…": "…"
    },
    "modrinth:fabric-api": {
      "type": "mod",
      "sides": ["client", "server"],
      "requiredBy": ["modrinth:sodium", "modrinth:lithium"],
      "…": "…"
    }
  }
}
```

## `game`

The resolved game version, loader and exact build. Clients set these in the Prism instance; servers download the matching server.

## `server`

Present when the pack's `side` includes `server`: where the server jar or installer comes from, and its upstream hash. Some upstreams publish no checksum (Fabric, Quilt, NeoForge, Spigot's BuildTools); then `hash` is `null` and each server pins the file it first downloads in its own state (see [Servers](Servers#evoker-folder)).

## Content entries

| Field | Meaning |
|---|---|
| `type` | `mod`, `plugin`, `datapack`, `resourcepack` or `shaderpack`; decides where the file goes |
| `sides` | Where it is installed: `client`, `server` or both |
| `optional` | Players choose whether to install it (clients only; servers always install it) |
| `title`, `description` | Optional entries only: shown when players are asked |
| `projectId` | Stable upstream id; installed files are named `<source>-<projectId>.<ext>` |
| `versionId`, `version` | Exact upstream version (id and readable number) |
| `url` | Where the file is downloaded from |
| `hash` | `<algorithm>:<hex>`: the upstream hash (Modrinth `sha512`, Hangar `sha256`), or for `url` entries the `sha256` evoker computed when the entry was added or updated |
| `sha1` | Resource packs on servers only: for `resource-pack-sha1` in `server.properties` |
| `requiredBy` | Present only on dependencies: the entries that need it. A dependency is installed on the sides of the entries that need it, and only when they are installed (an unchosen optional entry brings no dependencies). Once nothing needs it, it is removed. |

## `overrides`

The pack's [override files](Overrides): pack path (e.g. `overrides/config/sodium-options.json`) → `sha256:<hex>`. Every pack command scans the override folders again, so run one (`evoker update`, for example) after changing a file there.

## Hash mismatch

A download that doesn't match `hash` is **never installed**: the file at that URL changed after it was locked. evoker keeps whatever is there, warns, and carries on. For a `url` entry, the pack author's `evoker update` accepts the new file.

## `lockVersion`

The lock format version: `2` for packs. An evoker that finds a newer `lockVersion` than it understands refuses to use the lock. Version `1` locks (evoker 0.3 and older) aren't read; start the pack again with `evoker create`.
