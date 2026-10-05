# Commands

`evoker <command>` (or `java -jar evoker.jar <command>` without the launcher). Options take their value either way, `--type mod` or `--type=mod`; an unknown option is an error.

There are three groups: **pack** commands edit a pack (run them in the pack folder), **server** commands install and run a server (run them in the server folder), and **client** commands manage Prism instances (run them anywhere).

## Pack

Pack commands only change `evoker.json` and `evoker.lock`. They resolve versions and dependencies online, but **never install anything**; that's what `install` is for. (`url` downloads the file once, to a temporary location, to hash it.)

| Command | What it does |
|---|---|
| `create <name> <loader> [game_version] [client\|server\|both] [--git]` | Writes a new `evoker.json` and `evoker.lock` (with the game's exact loader build) in the current folder. Refuses if one exists. `game_version` defaults to the newest release. The side defaults to `server` for `paper`, `purpur` and `spigot`, and `both` otherwise. `--git` also runs `git init`. |
| `add <source:name>[@version] [--type <type>] [--side <side>] [--optional]` | Adds content: `latest` unless a version is given, which pins it. `sodium` means `modrinth:sodium`. `--type` only when the project is published as more than one type; `--side` only to override the [inferred side](Content-Sources#sides). `--optional` lets players choose. Adding an existing entry replaces its settings. |
| `url <name> <url> <type> [--side <side>] [--optional]` | Adds a file from a URL as `url:<name>` (the name: letters, digits, `.`, `_`, `-`). `--side` is required unless the type implies it (`plugin`, `datapack`: server; `shaderpack`: client). See [Content Sources](Content-Sources#url). |
| `remove <name>` | Removes the entry, and every dependency nothing else needs anymore. |
| `list [type] [--output=text\|json]` | Lists the game, loader and content (or one type of content): locked version, `latest` / `pinned`, side, `optional`, and what each dependency is required by. See [list --output=json](#list---outputjson). |
| `update [name]` | Moves `latest` entries (and the build, if `latest`) to their newest versions for the current game version. Pinned entries stay. With a name, updates only that entry, moving its pin if it has one. Re-downloads `url` entries to accept changed files. |
| `update list [--output=text\|json]` | Prints what `update` would change (`name: old -> new`), changing nothing. |
| `upgrade [game_version]` | Moves the pack to `game_version` (default: the newest release) and everything to its newest version for it. If there are pinned entries, asks whether to upgrade them too (`--pinned` / `--keep-pinned` answer without asking). |
| `upgrade list [game_version] [--output=text\|json]` | Prints what `upgrade` would change, changing nothing. Pinned entries are included as if upgraded, and marked; with `--keep-pinned` they show as `kept`. |
| `import <pack.mrpack \| url \| modrinth-slug \| pack.toml>` | Writes a new pack from a Modrinth modpack or a packwiz pack. See [Content Sources](Content-Sources#modpacks-mrpack). |

`--output=json` works only with `list`, `update list` and `upgrade list`. Errors print `error: ...` and exit with code 1; a failed command leaves `evoker.json` and `evoker.lock` unchanged. Warnings print `evoker: warning: ...` and never stop the command.

### Upgrades never fail on content

`update` and `upgrade` never fail because one entry has no newer (or no compatible) version: that entry keeps its locked version, with a warning. Making sure the pack works is up to you: remove the entry, wait for a release, or pin something else.

`upgrade` does fail if the loader has no build for the new game version: nothing is changed.

## Server

| Command | What it does |
|---|---|
| `install server <pack-url>` / `install server --local <path> [--accept-eula]` | Installs the pack as a server in the **current folder**: the server jar, every server-side entry, and the [`.evoker` folder](Servers#evoker-folder). Asks you to accept the Minecraft EULA unless `--accept-eula` is given. |
| `server update` | Fetches the pack again from where it was installed from, and applies the changes. |
| `server update list [--output=text\|json]` | Prints what `server update` would change, changing nothing. |
| `server start` | Runs `server update` (unless `updateOnStart` is off), then runs the server as a child process. The console is passed through; evoker exits with the server's exit code. |
| `server command` | Prints the command `server start` runs, on one line (arguments containing spaces are double-quoted). Offline. |

See [Servers](Servers).

## Client

| Command | What it does |
|---|---|
| `install <pack-url>` / `install --local <path>` | Installs the pack as a new Prism Launcher instance: client-side entries, asks about optional ones, and sets up updating before every launch. `install client …` is the same. |
| `client update <pack>` | Checks for a newer version of the pack and installs it. This is what Prism runs before every launch. |
| `client options <pack>` | Asks about the optional entries again and installs or removes them to match. |

`<pack>` is the pack's name or its instance folder. See [Clients](Clients).

## Everywhere

| Command | What it does |
|---|---|
| `config [path] [value]` | Shows or changes how evoker behaves. See [Configuration](Configuration). |
| `version` | Prints the evoker version. |
| `help` | Prints the command list. |

## Pack URLs

A pack URL is the folder that holds `evoker.json`, e.g. `https://raw.githubusercontent.com/me/pack/main/`. A URL ending in `/evoker.json` works too. evoker fetches `evoker.json`, `evoker.lock` and, if present, `icon.png` or `icon.jpg` from it. A pack without `evoker.lock` can't be installed.

`--local` takes the pack folder (or its `evoker.json`) on disk instead. Installs remember where they came from, so a local pack updates when its files change.

## list --output=json

Only the JSON goes to stdout. Content is a flat list sorted by `key`; every field is always present (`null` when it doesn't apply).

```json
{
  "format": 2,
  "game": { "version": "1.21.4", "loader": "fabric", "build": "0.16.10", "pinned": false },
  "content": [
    { "key": "modrinth:fabric-api", "type": "mod", "projectId": "P7dR8mSH", "version": "0.110.0",
      "pinned": null, "sides": ["client", "server"], "optional": false, "requiredBy": ["modrinth:sodium"] }
  ]
}
```

| Field | Meaning |
|---|---|
| `format` | Version of this output. Changes only when fields are removed or change meaning. |
| `game.pinned` | `build` in `evoker.json` is something other than `latest`. |
| `key`, `type`, `projectId`, `version`, `sides`, `optional` | As in [evoker.lock](evoker-lock). URL entries have no `version`. |
| `pinned` | `true` / `false` for entries in `evoker.json`; `null` for dependencies and URL entries. |
| `requiredBy` | Keys of the entries that need this one. Empty for entries only you asked for. |

## update list / upgrade list --output=json

```json
{
  "format": 2,
  "game": { "from": { "version": "1.21.4", "build": "0.16.10" }, "to": { "version": "1.21.5", "build": "0.16.14" } },
  "content": [
    { "key": "modrinth:sodium", "change": "updated", "from": "0.6.5", "to": "0.6.13", "pinned": false },
    { "key": "modrinth:fabric-api", "change": "added", "from": null, "to": "0.128.0", "pinned": null },
    { "key": "modrinth:terralith", "change": "kept", "from": "2.5.8", "to": "2.5.8", "pinned": true }
  ]
}
```

| Field | Meaning |
|---|---|
| `game` | `null` when the game version and build don't change. |
| `content` | Only entries that change, sorted by `key`. `change` is `added`, `removed`, `updated`, or `kept` (a pinned entry `upgrade list --keep-pinned` leaves alone). `from` / `to` are versions, `null` where there is none. An entry with no compatible version keeps its version and isn't listed; it's a warning on stderr. |

`server update list` prints the same shape, with `pinned` always `null`.
