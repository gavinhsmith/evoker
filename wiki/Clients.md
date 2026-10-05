# Clients

On the client, evoker installs a pack as a [Prism Launcher](https://prismlauncher.org) instance and keeps it up to date: every time you launch the instance, evoker checks the pack for updates first. Prism does everything else (accounts, downloading Minecraft, launching).

Prism is the only launcher supported for now.

## Setup

Install evoker (see [Getting Started](Getting-Started#1-install)) and Prism Launcher. evoker finds Prism's instance folder in its usual place:

| OS | Instance folder |
|---|---|
| Windows | `%APPDATA%\PrismLauncher\instances` |
| macOS | `~/Library/Application Support/PrismLauncher/instances` |
| Linux | `~/.local/share/PrismLauncher/instances`, or the Flatpak's `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances` |

If yours is somewhere else (portable Prism, a changed setting), tell evoker once:

```sh
evoker config instanceDir "D:\Games\Prism\instances"
```

## Install a pack

```sh
evoker install https://raw.githubusercontent.com/me/pack/main/
evoker install --local ./my-pack
```

1. Fetches `evoker.json`, `evoker.lock` and the icon (see [Pack URLs](Commands#pack-urls)). The pack's `side` must include `client`.
2. Creates a Prism instance named after the pack. An instance with that name already managed by evoker is an error (use `client update`); an unrelated one with the same name gets a number appended.
3. Sets the instance's Minecraft and loader versions to the pack's.
4. Downloads every entry whose `sides` include `client` into the instance, checking each file against its locked hash.
5. Asks about each optional entry, showing its title and description. Without a console to ask in, optional entries are left out.
6. Sets the instance's pre-launch command to `evoker client update`.

Then open Prism (or restart it, if it was running) and launch the instance.

## Updates on launch

Before every launch, Prism runs `evoker client update`:

1. Fetches the pack again from where it was installed from.
2. **Nothing changed:** the launch continues.
3. **Content changed:** downloads and deletes files to match, then the launch continues with the new content.
4. **New optional entries:** a window asks whether to install them. The answers are remembered.
5. **The game version or loader changed:** evoker updates the instance, then **stops the launch** and shows a message, e.g. *"My Pack was updated to Minecraft 1.21.5. Press Launch again."* Prism has already read the instance's versions by the time the update runs, so the new ones only take effect on the next launch.

**Launching is never blocked by updates:** if the pack can't be fetched (offline, the URL is gone) or a file can't be downloaded, evoker warns and the game launches with what's installed. A file that doesn't match its locked hash is never installed.

Run it yourself any time:

```sh
evoker client update "My Pack"
```

## Optional content

```sh
evoker client options "My Pack"
```

Asks about every optional entry again, and installs or removes files to match.

## Your own files

evoker only touches files it installed. Mods, resource packs and shaders you add to the instance yourself stay, and so do your options, keybinds, worlds and screenshots.

## What evoker writes

In the instance folder:

| File | |
|---|---|
| `instance.cfg` | Name, icon, and `PreLaunchCommand` (with `OverrideCommands=true`): `"$INST_JAVA" -jar "<path to evoker.jar>" client update "$INST_DIR"`. It runs with the instance's own Java, so nothing else is needed on `PATH`. |
| `mmc-pack.json` | The Minecraft and loader components (`net.minecraft`, plus `net.fabricmc.fabric-loader` and `net.fabricmc.intermediary`, `org.quiltmc.quilt-loader` and `net.fabricmc.intermediary`, or `net.neoforged`). |
| `minecraft/` | The game folder: `mods/`, `resourcepacks/`, `shaderpacks/`. |
| `.evoker/` | evoker's state: `source.json` (where the pack came from), `evoker.json` / `evoker.lock` (the pack as last installed), `options.json` (your optional choices), `installed.json` (files evoker installed). |

The pack icon is copied to Prism's `icons/` folder (next to `instances/`) as `evoker-<instance>.png`.

If evoker.jar moves (reinstalling evoker to a different folder), run `evoker client update <pack>` once to fix the pre-launch command.

## Where files go

| Type | Path in the instance |
|---|---|
| mod | `minecraft/mods/<source>-<projectId>.jar` |
| resource pack | `minecraft/resourcepacks/<source>-<projectId>.zip` |
| shader pack | `minecraft/shaderpacks/<source>-<projectId>.zip` |

Data packs and plugins are server-only.
