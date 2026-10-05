# evoker.json

Describes a pack: the game, the loader, and the content. The pack author writes it (mostly through [commands](Commands)); servers and clients only read it.

A pack is a folder:

| File | |
|---|---|
| `evoker.json` | This file. |
| `evoker.lock` | Exactly which files the pack resolves to. Written by evoker. See [evoker.lock](evoker-lock). |
| `icon.png` or `icon.jpg` | Optional. Used as the Prism instance icon. |
| `overrides/`, `client-overrides/`, `server-overrides/` | Optional. Files installed as they are, mostly configs. See [Overrides](Overrides). |

Publish the folder anywhere that serves plain files (a GitHub repo works: `https://raw.githubusercontent.com/<you>/<pack>/main/`). That folder URL is the **pack URL**.

```json
{
  "name": "My Pack",
  "side": "both",
  "game": { "version": "1.21.4", "loader": "fabric", "build": "latest" },
  "content": {
    "modrinth:lithium": "latest",
    "modrinth:sodium": "latest",
    "modrinth:terralith": "2.5.8",
    "modrinth:distant-horizons": { "version": "latest", "optional": true },
    "modrinth:some-mod": { "version": "latest", "side": "client" },
    "url:geyser": { "url": "https://…/Geyser-Spigot.jar", "type": "plugin", "side": "server" }
  }
}
```

## `name` (required)

The pack's display name. It names the Prism instance and is how `evoker client` commands refer to the pack.

## `side` (required)

What the pack is for: `server`, `client` or `both`. Set by `evoker create`.

- A `server` pack can't be installed as a client, and the other way round.
- Content that only runs on a side the pack doesn't have is refused by `add`.

## `game` (required)

| Key | Meaning |
|---|---|
| `version` | Minecraft version, e.g. `1.21.4`. Changed by `evoker upgrade`. |
| `loader` | `vanilla`, `fabric`, `quilt`, `neoforge`, `paper`, `purpur` or `spigot`. See [Server Software](Server-Software). |
| `build` | `latest` (default) or a pinned build: loader version for Fabric/Quilt/NeoForge, build number for Paper/Purpur/Spigot. Ignored for vanilla. |

The client side runs the same loader for `fabric`, `quilt` and `neoforge`. For `paper`, `purpur`, `spigot` and `vanilla` the client is vanilla Minecraft: plugins go to the server only, and players get resource packs and shaders.

## `content`

Keyed `source:name`. With no prefix the source is `modrinth`; the others are `hangar` and `url`. See [Content Sources](Content-Sources).

The short form is just the version: `"latest"`, or a pinned version (the version number Modrinth or Hangar shows). The long form is an object:

| Key | Meaning |
|---|---|
| `version` | `latest` or a pinned version. Not used by `url` entries. |
| `side` | `client`, `server` or `both`. Optional: evoker works it out from the source (see [Sides](Content-Sources#sides)); set it only to override. Required for `url` entries. |
| `optional` | `true` lets players choose whether to install it. Servers always install it if it runs there. Default `false`. |
| `type` | `mod`, `plugin`, `datapack`, `resourcepack` or `shaderpack`. Required for `url` entries; for other sources only when the project is published as more than one type. |
| `url` | `url` entries only: where the file is downloaded from. |

Only list what you want; dependencies are resolved into `evoker.lock` automatically.

## What isn't here

`evoker.json` describes content, never how a particular server or client runs it. Memory, Java, `server.properties`, `eula.txt` and similar belong to whoever runs the server; see [Servers](Servers) and [Configuration](Configuration).
