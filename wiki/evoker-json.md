# evoker.json

Describes the server you want. You write it; evoker only rewrites it when a command changes it.

```json
{
  "server": { "software": "fabric", "version": "1.21.4", "build": "latest" },
  "eula": true,
  "properties": { "motd": "hi", "max-players": 20 },
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
| `software` | `vanilla`, `paper`, `purpur` or `fabric`. See [Server Software](Server-Software). |
| `version` | Minecraft version, e.g. `1.21.4`. |
| `build` | `latest` (default) or a pinned build: Paper/Purpur build number, Fabric loader version. Ignored for vanilla. |

Changing any of these makes the next `install`/`start` download the matching server.

## `eula`

`true` writes `eula=true` to `eula.txt`, meaning you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA). Default `false` (evoker leaves `eula.txt` alone).

## `properties`

Keys to set in `server.properties`. They are **enforced**: every `install`/`start` sets them again, so `evoker.json` stays the source of truth. Keys you don't list are left alone. Values can be strings, numbers or booleans.

## `evoker`

How evoker itself behaves. Every key is optional.

| Key | Default | Meaning |
|---|---|---|
| `autoUpdateServer` | `false` | On `start`, if `build` is `latest`, pick up the newest build for the current `version`. Never changes `version`. |
| `autoUpdateDeps` | `false` | On `start`, update content entries. (Content support is coming.) |
| `java` | `java` | Java executable used to run the server. |
| `jvmArgs` | `[]` | Arguments for that JVM, e.g. `["-Xmx4G"]`. |
