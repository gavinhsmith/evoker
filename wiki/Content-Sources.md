# Content Sources

Content entries in `evoker.json` are keyed `source:slug`. With no prefix, the source is `modrinth`:

```json
"content": {
  "sodium": "latest",
  "modrinth:lithium": "mc1.21.4-0.15.3-fabric"
}
```

The value is `"latest"` or a pinned version: the version number Modrinth shows, or its version id.

## Modrinth

Mods, plugins, data packs and resource packs from [modrinth.com](https://modrinth.com). No account or API key needed.

### Which version is picked

Versions are filtered to your game `version`, then to what runs on your server software, in this order of preference:

1. **Mods or plugins** for the software:

   | `software` | Modrinth loaders accepted |
   |---|---|
   | fabric | fabric |
   | quilt | quilt, fabric |
   | neoforge | neoforge |
   | spigot | spigot, bukkit |
   | paper | paper, spigot, bukkit |
   | purpur | purpur, paper, spigot, bukkit |
   | vanilla | (none) |

2. **Data packs**
3. **Resource packs**

So a project published both as a mod and as a data pack installs as the mod on Fabric, and as the data pack on vanilla. `latest` takes the newest release, or the newest version of any kind if there is no release.

A pinned version that isn't marked compatible with your game version is still installed, with a warning.

Projects marked as not supported on servers (client-only mods like Sodium) install with a warning.

### Where files go

Files are named after the project id, never the upstream filename, so updating a project overwrites the same file:

| Type | Path |
|---|---|
| mod | `mods/modrinth-<projectId>.jar` |
| plugin | `plugins/modrinth-<projectId>.jar` (Hangar: `hangar-<projectId>.jar`, URL: `url-<name>.jar`) |
| data pack | `<level-name>/datapacks/modrinth-<projectId>.zip` (`level-name` from `server.properties`, default `world`) |
| resource pack | not downloaded; see below |

### Resource packs

A server doesn't host its resource pack: `server.properties` points players at a URL. evoker sets `resource-pack` to the pack's Modrinth download URL and `resource-pack-sha1` to its SHA-1. `server.properties` holds a single pack, so with more than one, evoker uses the first and warns. If `properties` in `evoker.json` sets `resource-pack` itself, that wins.

## Dependencies

Required dependencies are installed automatically and recursively. They go into `evoker.lock` only (with `requiredBy`), not into `evoker.json`.

| Modrinth dependency type | evoker |
|---|---|
| required | installed (recursively) |
| optional | skipped |
| embedded | skipped (already inside the jar) |
| incompatible | warning if both are installed |

- An entry you list in `evoker.json` always wins over a version a dependency asks for.
- If two entries need different exact versions of the same dependency, the newer one is used, with a warning.
- A dependency hosted outside Modrinth can't be installed automatically; evoker warns so you can add it yourself.
- `remove` deletes an entry and every dependency nothing else needs anymore.

## Hangar

Paper plugins from [hangar.papermc.io](https://hangar.papermc.io), keyed `hangar:<slug>` (the slug is case-sensitive, as shown on Hangar):

```sh
evoker add hangar:ViaVersion
```

- Only on `paper` and `purpur` (Hangar plugins may use Paper-only APIs).
- `latest` is the newest version in the **Release** channel for your game version, or the newest version of any channel (snapshots) if there is no release. A pinned value is the Hangar version name, e.g. `5.0.3`.
- Required plugin dependencies hosted on Hangar are installed automatically like Modrinth dependencies. Ones that only link elsewhere produce a warning; add them yourself.
- Some Hangar versions are only an external link with no checksum. evoker still hashes the file it downloads and pins it in the lock.
- Files: `plugins/hangar-<projectId>.jar`.

## URL

Any file from a URL, for things on neither Modrinth nor Hangar:

```sh
evoker add https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot --type plugin --name geyser
```

```json
"url:geyser": { "url": "https://…/spigot", "type": "plugin" }
```

- `--type` is **required**: `mod`, `plugin`, `datapack` or `resourcepack`.
- `--name` defaults to the file name in the URL. It becomes the key (`url:<name>`) and the file name (`url-<name>.jar`).
- No dependencies and no version checks. The lock pins the file by its SHA-256.
- `install` re-downloading a file that no longer matches the lock (the file at that URL changed) keeps your existing file and warns.
- `update` / `upgrade` re-download URL entries and accept the new file, so "latest" style links work. Links to a fixed version never change.
- A `resourcepack` URL is downloaded once to compute the SHA-1 for `server.properties`, then deleted.
- Plain `http://` works, with a warning.
