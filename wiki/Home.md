<p align="center"><img src="https://raw.githubusercontent.com/gavinhsmith/evoker/main/.github/logo.svg" alt="evoker" width="480"></p>

evoker is a package manager for Minecraft. A **pack** describes a complete environment: the game version, the loader, and the mods, plugins, data packs, resource packs and shaders. evoker builds that environment on a server, on players' clients, or both, and keeps it up to date.

- **[`evoker.json`](evoker-json)**: the pack author describes the pack.
- **[`evoker.lock`](evoker-lock)**: evoker records exactly which files it resolves to (versions, URLs, hashes). Publish both together, e.g. in a GitHub repo.
- **[Servers](Servers)**: `evoker install server <pack-url>` downloads the server and its content; `evoker server start` updates and runs it.
- **[Clients](Clients)**: `evoker install <pack-url>` creates a Prism Launcher instance; every launch checks the pack for updates first.

## Quick start

Install (Java 21+ required):

```sh
curl -fsSL https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.sh | bash   # Linux / macOS
```

```powershell
irm https://raw.githubusercontent.com/gavinhsmith/evoker/main/install.ps1 | iex           # Windows
```

Make a pack, in an empty folder:

```sh
evoker create "My Pack" fabric 1.21.4 --git
evoker add lithium
evoker add sodium
evoker add distant-horizons --optional
```

Push it to GitHub, then on a server:

```sh
evoker install server https://raw.githubusercontent.com/me/my-pack/main/
evoker server start
```

and on a player's computer:

```sh
evoker install https://raw.githubusercontent.com/me/my-pack/main/
```

See [Getting Started](Getting-Started) for the details.
