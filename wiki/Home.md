<p align="center"><img src="https://raw.githubusercontent.com/gavinhsmith/evoker/main/.github/logo.svg" alt="evoker" width="480"></p>

evoker manages a Minecraft server's dependencies: the server jar, mods, plugins, data packs and resource packs.

- **`evoker.json`**: you describe the server (software, game version, content, `server.properties` overrides).
- **`evoker.lock`**: evoker records exactly what it installed (versions, URLs, hashes). Commit it.
- **`evoker start`**: evoker installs whatever is missing and runs the server as a child process.

## Quick start

Install (Java 21+ required):

```sh
curl -fsSL https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.sh | bash   # Linux / macOS
```

```powershell
irm https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.ps1 | iex           # Windows
```

Then, in an empty folder:

```sh
evoker init paper 1.21.4 --git
evoker add luckperms
evoker start
```

See [Getting Started](Getting-Started) for the details.
