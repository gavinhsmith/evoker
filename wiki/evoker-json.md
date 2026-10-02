# evoker.json

Describes the server you want. You write it; evoker only rewrites it when a command changes it.

```json
{
  "server": { "software": "fabric", "version": "1.21.4", "build": "latest" },
  "eula": true,
  "properties": { "motd": "hi", "max-players": 20 },
  "content": { "modrinth:lithium": "latest" },
  "evoker": {
    "autoUpdateDeps": false,
    "autoUpdateServer": false,
    "java": "java",
    "jvmArgs": ["-Xmx4G"]
  }
}
```

## `server` (required)

| Key | Meaning |
|---|---|
| `software` | `vanilla`, `paper`, `purpur`, `spigot`, `fabric`, `quilt` or `neoforge`. See [Server Software](Server-Software). |
| `version` | Minecraft version, e.g. `1.21.4`. |
| `build` | `latest` (default) or a pinned build: Paper/Purpur build number, Fabric/Quilt loader version, NeoForge version, Spigot build number. Ignored for vanilla. |

Changing any of these makes the next `install`/`start` download the matching server.

## `eula`

`true` writes `eula=true` to `eula.txt`, meaning you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA). Default `false` (evoker leaves `eula.txt` alone).

## `properties`

Keys to set in `server.properties`. They are **enforced**: every `install`/`start` sets them again, so `evoker.json` stays the source of truth. Keys you don't list are left alone. Values can be strings, numbers or booleans.

## `content`

Mods, plugins, data packs and resource packs, keyed `source:slug` (no prefix means `modrinth`; sources are `modrinth`, `hangar` and `url`). The value is `"latest"` or a pinned version; `url` entries hold `{ "url", "type" }` instead. Usually managed with `evoker add` / `evoker remove`.

```json
"content": {
  "modrinth:lithium": "latest",
  "modrinth:terralith": "2.5.8",
  "hangar:ViaVersion": "5.0.3",
  "url:geyser": { "url": "https://…/Geyser-Spigot.jar", "type": "plugin" }
}
```

Only list what you want; required dependencies are added to `evoker.lock` automatically. See [Content Sources](Content-Sources).

## `evoker`

How evoker itself behaves. Every key is optional.

| Key | Default | Meaning |
|---|---|---|
| `autoUpdateServer` | `false` | On `start`, if `build` is `latest`, pick up the newest build for the current `version`. Never changes `version`. |
| `autoUpdateDeps` | `false` | On `start`, move `latest` content entries and their dependencies to their newest versions (like `evoker update`, without the server). |
| `java` | `java` | Java executable used to run the server. |
| `jvmArgs` | `[]` | Arguments for that JVM, e.g. `["-Xmx4G"]`. |
