# Getting Started

## 1. Install

evoker needs **Java 21 or newer**. Download `evoker.jar` from the [latest release](https://github.com/gavinhsmith/evoker/releases/latest) and put it somewhere handy. Every command below is `java -jar evoker.jar <command>`, run inside your server folder.

## 2. Create the server

In an empty folder:

```sh
java -jar evoker.jar init paper 1.21.4 --git
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
java -jar evoker.jar add luckperms
java -jar evoker.jar add hangar:ViaVersion
```

Dependencies come along automatically. See [Content Sources](Content-Sources).

## 4. Start it

```sh
java -jar evoker.jar start
```

evoker downloads the server jar, writes [evoker.lock](evoker-lock), applies your `properties` to `server.properties`, and runs the server. The server console works as usual; type `stop` to shut it down. evoker exits with the server's exit code.

The next `start` downloads nothing: the lock says what is installed, and the files on disk match it.

## Git

Commit `evoker.json` **and** `evoker.lock`: any machine running `evoker start` then gets exactly the same server.

The `.gitignore` written by `init --git` ignores what evoker or the server can recreate, and keeps what you configure by hand:

| Ignored | Tracked |
|---|---|
| `server.jar`, `fabric-server-launch.jar`, `quilt-server-launch.jar`, `*-installer.jar`, `run.sh`, `run.bat`, `.evoker-*` | `evoker.json`, `evoker.lock`, `user_jvm_args.txt` |
| evoker-managed jars: `mods/` and `plugins/` files named `modrinth-*`, `hangar-*`, `url-*` | jars you drop into `mods/` or `plugins/` yourself |
| `libraries/`, `versions/`, `cache/`, `.fabric/`, `.quilt/`, `plugins/.paper-remapped/` | `server.properties`, `config/`, `plugins/<plugin>/` config folders |
| `logs/`, `crash-reports/`, `debug/`, `usercache.json` | `ops.json`, `whitelist.json`, ban lists |
| `world/`, `world_nether/`, `world_the_end/` | |

Worlds are ignored because they're large and change constantly; back them up separately. If you change `level-name`, update the world folders in `.gitignore` to match.

An existing `.gitignore` is left alone.

**Secrets:** `server.properties` can contain `rcon.password`, and mod configs can hold tokens (a Discord bot token, for example). Keep those out of public repositories.
