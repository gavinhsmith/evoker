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

## 2. Make a pack

In an empty folder:

```sh
evoker create "My Pack" fabric 1.21.4 --git
```

This writes [`evoker.json`](evoker-json) and an empty [`evoker.lock`](evoker-lock). The side (`client`, `server` or `both`) defaults to `both` for mod loaders and `server` for Paper, Purpur and Spigot; give it after the game version to choose. `--git` also runs `git init`. Add an `icon.png` if you like.

Add content:

```sh
evoker add lithium                       # server-side performance mod
evoker add sodium                        # client-only: evoker works the sides out
evoker add distant-horizons --optional   # players choose
evoker add modrinth:terralith@2.5.8      # pinned to a version
evoker list
```

Nothing is installed in the pack folder: `add` only resolves versions and dependencies into `evoker.lock`. See [Content Sources](Content-Sources).

Keep it current with `evoker update` (newest versions for this game version) and `evoker upgrade` (a new game version). Both have a `list` form that only shows what would change.

## 3. Publish it

Commit `evoker.json`, `evoker.lock` and the icon, and push to GitHub (or put them anywhere that serves plain files). The folder's raw URL is the **pack URL**:

```
https://raw.githubusercontent.com/<you>/<pack>/main/
```

Every `update` / `upgrade` you push reaches servers on their next `server start`, and players on their next launch.

## 4. Run a server

In an empty folder on the server:

```sh
evoker install server https://raw.githubusercontent.com/<you>/<pack>/main/
evoker config jvmArgs '["-Xmx4G"]'
evoker server start
```

`install server` asks you to accept the Minecraft EULA. `server start` checks the pack for updates, then runs the server; type `stop` to shut it down. `server.properties` and the server's configs are yours to edit; evoker leaves them alone. See [Servers](Servers).

While writing a pack, test it with `evoker install server --local <pack folder>`.

## 5. Play

Players install evoker and [Prism Launcher](https://prismlauncher.org), then:

```sh
evoker install https://raw.githubusercontent.com/<you>/<pack>/main/
```

This creates a Prism instance with the pack's content and asks about the optional entries. Launch it from Prism; every launch checks the pack for updates first. See [Clients](Clients).
