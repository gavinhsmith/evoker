# Content Sources

Content entries in `evoker.json` are keyed `source:name`. With no prefix, the source is `modrinth`:

```sh
evoker add sodium                       # modrinth:sodium, latest
evoker add modrinth:terralith@2.5.8     # pinned
evoker add hangar:ViaVersion
evoker url geyser https://…/spigot plugin
```

A version is `latest` or pinned: the version number Modrinth or Hangar shows, or its version id. Each entry resolves to one version, used on every side it is installed on.

## Types

| Type | Server | Client |
|---|---|---|
| `mod` | `mods/` | `minecraft/mods/` |
| `plugin` | `plugins/` | – |
| `datapack` | `<level-name>/datapacks/` | – |
| `resourcepack` | `server.properties` (see [Resource packs](#resource-packs)) | `minecraft/resourcepacks/` |
| `shaderpack` | – | `minecraft/shaderpacks/` |

Usually the source says what a project is. When a Modrinth project is published as more than one type, evoker picks in this order: a mod or plugin for the pack's loader, then a data pack, then a resource pack. `--type` overrides that.

## Sides

Every entry is installed on the client, the server, or both. evoker works it out; `--side` overrides it.

| Type | Side |
|---|---|
| `plugin`, `datapack` | server |
| `resourcepack`, `shaderpack` | client |
| `mod` | from Modrinth's `client_side` / `server_side`, below |

| `client_side` | `server_side` | Side |
|---|---|---|
| required | required | both |
| optional | optional | both |
| required | optional | client |
| optional | required | server |
| required or optional | unsupported | client |
| unsupported | required or optional | server |

`unknown` counts as `optional`. Then the pack's own `side` applies: on a `server` pack a `both` mod is installed on the server only, and adding something that runs only on a side the pack doesn't have is an error.

Data packs have no client side: Minecraft loads them per world, and client worlds are the player's own.

## Optional entries

`--optional` (or `"optional": true`) lets players choose. Clients ask when the pack is installed, and when a new optional entry appears in an update (see [Clients](Clients#optional-content)). Servers always install optional entries that run on the server: a client that chose a mod that needs the server side can then still join.

An optional entry that isn't chosen brings none of its dependencies.

## Modrinth

Mods, plugins, data packs, resource packs and shaders from [modrinth.com](https://modrinth.com). No account or API key needed.

### Which version is picked

Versions are filtered to the pack's game version, then to what runs on its loader:

| `loader` | Modrinth loaders accepted (server) | (client) |
|---|---|---|
| fabric | fabric | fabric |
| quilt | quilt, fabric | quilt, fabric |
| neoforge | neoforge | neoforge |
| spigot | spigot, bukkit | – (vanilla client) |
| paper | paper, spigot, bukkit | – (vanilla client) |
| purpur | purpur, paper, spigot, bukkit | – (vanilla client) |
| vanilla | – | – |

Resource packs, data packs and shaders don't depend on the loader (shaders do need a shader mod such as Iris on the client: add it).

`latest` takes the newest release, or the newest version of any kind if there is no release. A pinned version that isn't marked compatible with the game version is still used, with a warning.

## Dependencies

Required dependencies are resolved automatically and recursively. They go into `evoker.lock` only (with `requiredBy`), not into `evoker.json`. A dependency is installed on the sides of the entries that need it, where it supports that side.

| Modrinth dependency type | evoker |
|---|---|
| required | added (recursively) |
| optional | skipped |
| embedded | skipped (already inside the jar) |
| incompatible | warning if both are in the pack |

- An entry you list in `evoker.json` always wins over a version a dependency asks for.
- If two entries need different exact versions of the same dependency, the newer one is used, with a warning.
- A dependency hosted outside Modrinth can't be added automatically; evoker warns so you can add it yourself.
- `remove` deletes an entry and every dependency nothing else needs anymore.

## Resource packs

Clients install resource packs into the instance. A **server** doesn't host one: `server.properties` points players at a URL. For a resource pack whose side includes `server`, `install server` and `server update` set `resource-pack` to its download URL and `resource-pack-sha1` to its SHA-1, and nothing else in `server.properties`. `server.properties` holds a single pack: with more than one, evoker uses the first and warns.

## Hangar

Paper plugins from [hangar.papermc.io](https://hangar.papermc.io), keyed `hangar:<slug>` (the slug is case-sensitive, as shown on Hangar). Always server-side.

- Only for `paper` and `purpur` packs (Hangar plugins may use Paper-only APIs).
- `latest` is the newest version in the **Release** channel for the game version, or the newest version of any channel if there is no release. A pinned value is the Hangar version name, e.g. `5.0.3`.
- Required plugin dependencies hosted on Hangar are added automatically. Ones that only link elsewhere produce a warning; add them yourself.
- Some Hangar versions are only an external link with no checksum. evoker downloads the file once when the entry is added or updated, and locks its SHA-256.

## URL

Any file from a URL, for things on neither Modrinth nor Hangar:

```sh
evoker url geyser https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot plugin
evoker url my-shader https://example.com/MyShader.zip shaderpack --optional
```

```json
"url:geyser": { "url": "https://…/spigot", "type": "plugin", "side": "server" }
```

- The type is required. `--side` is required for `mod` and `resourcepack`; the other types imply it.
- The name becomes the key (`url:<name>`) and the installed file name (`url-<name>.<ext>`).
- No dependencies and no version checks. `url` downloads the file once, to a temporary location, and locks its SHA-256.
- Installs that download a file no longer matching the lock (the file at that URL changed) keep what is installed and warn. The pack author's `update` re-downloads URL entries and accepts new files, so "latest" style links work.
- Plain `http://` works, with a warning.

## Modpacks (.mrpack)

`evoker import` writes a new pack from a [Modrinth modpack](https://modrinth.com/modpacks):

```sh
evoker import cobblemon-fabric        # a modpack's Modrinth slug: its newest release
evoker import ./MyPack-1.2.mrpack     # a local file
evoker import https://…/MyPack.mrpack # a URL
```

1. **Pack:** `name`, game version, `loader` and `build` come from the modpack (`fabric-loader`, `quilt-loader`, `neoforge`, or vanilla); `side` is `both`. Forge packs aren't supported.
2. **Files:** every file becomes an entry. Its side and `optional` come from the file's `env` (`client` / `server`: `required`, `optional` or `unsupported`); `optional` on the client makes the entry optional.
   - Files Modrinth recognizes (by hash) become `modrinth:<slug>` entries **pinned to the modpack's version**, so they upgrade like any other entry.
   - Other files become `url:<file name>` entries.
3. **Overrides** (`overrides/`, `client-overrides/`, `server-overrides/`: configs, mostly) aren't supported yet; evoker lists what it skipped.

Runs in an empty folder (it refuses if `evoker.json` exists). The import is one-time: afterwards the modpack isn't tracked.
