# Getting Started

## 1. Install

evoker needs **Java 21 or newer** ([Adoptium](https://adoptium.net) is a good source).

**Linux / macOS:**

```sh
curl -fsSL https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.sh | bash
```

This puts `evoker.jar` and an `evoker` launcher in `~/.evoker` and tells you how to add it to your `PATH`. For another folder: `… | bash -s -- /opt/evoker` (or set `EVOKER_DIR`).

**Windows (PowerShell):**

```powershell
irm https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.ps1 | iex
```

This installs to `%LOCALAPPDATA%\evoker` (or `$env:EVOKER_DIR`) and adds it to your user `PATH`; open a new terminal afterwards. Set `$env:EVOKER_NO_MODIFY_PATH = 1` to leave `PATH` alone.

Both scripts install the latest release; set `EVOKER_VERSION` (e.g. `v0.1.0`) for a specific one. Re-run them to update evoker. The launcher uses `JAVA_HOME` if it is set, otherwise `java` from your `PATH`.

**By hand:** download `evoker.jar` from the [latest release](https://github.com/gavinhsmith/evoker/releases/latest) and use `java -jar evoker.jar <command>` wherever these pages say `evoker <command>`.

Run every command inside your server folder.

## 2. Create the server

In an empty folder:

```sh
evoker init paper 1.21.4 --git
```

This writes `evoker.json` (software and version default to `paper` and the newest Minecraft release). `--git` also runs `git init` and writes a `.gitignore` for a Minecraft server (see [below](#git)).

Edit `evoker.json`:

```json
{
  "server": { "software": "paper", "version": "1.21.4", "build": "latest" },
  "eula": true,
  "properties": { "motd": "My server", "max-players": 20 },
  "content": {},
  "evoker": { "jvmArgs": ["-Xmx4G"] }
}
```

`"eula": true` means you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA); evoker writes `eula.txt` for you.

See [evoker.json](evoker-json) for every option.

## 3. Add content

```sh
evoker add luckperms
evoker add hangar:ViaVersion
```

Dependencies come along automatically. See [Content Sources](Content-Sources).

## 4. Start it

```sh
evoker start
```

evoker downloads the server jar, writes [evoker.lock](evoker-lock), applies your `properties` to `server.properties`, and runs the server. The server console works as usual; type `stop` to shut it down. evoker exits with the server's exit code.

The next `start` downloads nothing: the lock says what is installed, and the files on disk match it.

## Git

Commit `evoker.json` **and** `evoker.lock`: any machine running `evoker start` then gets exactly the same server.

The `.gitignore` written by `init --git` ignores what evoker or the server can recreate, and keeps what you configure by hand:

| Ignored | Tracked |
|---|---|
| `server.jar`, `fabric-server-launch.jar`, `quilt-server-launch.jar`, `*-installer.jar`, `BuildTools.jar`, `run.sh`, `run.bat`, `.evoker-*` | `evoker.json`, `evoker.lock`, `user_jvm_args.txt` |
| evoker-managed jars: `mods/` and `plugins/` files named `modrinth-*`, `hangar-*`, `url-*` | jars you drop into `mods/` or `plugins/` yourself |
| `libraries/`, `versions/`, `cache/`, `.fabric/`, `.quilt/`, `plugins/.paper-remapped/` | `server.properties`, `config/`, `plugins/<plugin>/` config folders |
| `logs/`, `crash-reports/`, `debug/`, `usercache.json` | `ops.json`, `whitelist.json`, ban lists |
| `world/`, `world_nether/`, `world_the_end/` | |

Worlds are ignored because they're large and change constantly; back them up separately. If you change `level-name`, update the world folders in `.gitignore` to match.

An existing `.gitignore` is left alone.

**Secrets:** `server.properties` can contain `rcon.password`, and mod configs can hold tokens (a Discord bot token, for example). Keep those out of public repositories.
