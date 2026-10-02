# Getting Started

## 1. Install

evoker needs **Java 21 or newer**. Download `evoker.jar` from the [latest release](https://github.com/gavinhsmith/evoker/releases/latest) and put it somewhere handy. Every command below is `java -jar evoker.jar <command>`, run inside your server folder.

## 2. Describe your server

Create `evoker.json` in an empty folder:

```json
{
  "server": { "software": "paper", "version": "1.21.4" },
  "eula": true,
  "properties": { "motd": "My server", "max-players": 20 },
  "evoker": { "jvmArgs": ["-Xmx4G"] }
}
```

`"eula": true` means you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA); evoker writes `eula.txt` for you.

See [evoker.json](evoker-json) for every option.

## 3. Start it

```sh
java -jar evoker.jar start
```

evoker downloads the server jar, writes [evoker.lock](evoker-lock), applies your `properties` to `server.properties`, and runs the server. The server console works as usual; type `stop` to shut it down. evoker exits with the server's exit code.

The next `start` downloads nothing: the lock says what is installed, and the file on disk matches it.

## 4. Commit it

Put the folder in git with `evoker.json` **and** `evoker.lock` so every machine gets exactly the same server.
