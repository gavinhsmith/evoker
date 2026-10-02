# evoker

evoker manages a Minecraft server's dependencies: the server jar, mods, plugins, data packs and resource packs.

- **`evoker.json`**: you describe the server (software, game version, content, `server.properties` overrides).
- **`evoker.lock`**: evoker records exactly what it installed (versions, URLs, hashes). Commit it.
- **`evoker start`**: evoker installs whatever is missing and runs the server as a child process.

## Quick start

```sh
java -jar evoker.jar <command>
```

evoker requires Java 21 or newer. Download `evoker.jar` from the [latest release](https://github.com/gavinhsmith/evoker/releases/latest).
