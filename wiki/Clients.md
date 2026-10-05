# Clients

On the client, evoker installs a pack as a [Prism Launcher](https://prismlauncher.org) instance and keeps it up to date: every time you launch the instance, evoker checks the pack for updates first. Prism does everything else (accounts, downloading Minecraft, launching).

Prism is the only launcher supported for now.

## Setup

Install evoker (see [Getting Started](Getting-Started#1-install)) and Prism Launcher. evoker finds Prism's data folder in its usual place, and reads Prism's own settings (`prismlauncher.cfg`: `InstanceDir`, `IconsDir`) for where instances and icons go:

| OS | Prism data folder |
|---|---|
| Windows | `%APPDATA%\PrismLauncher` |
| macOS | `~/Library/Application Support/PrismLauncher` |
| Linux | `~/.local/share/PrismLauncher`, or the Flatpak's `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher` |

If Prism isn't there (a portable install), tell evoker where its instances are once:

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

Then launch the instance from Prism. A running Prism picks the new instance up by itself.

## Updates on launch

Before every launch, Prism runs `evoker client update`:

1. Fetches the pack again from where it was installed from.
2. **Nothing changed:** the launch continues.
3. **Content changed:** downloads and deletes files to match, then the launch continues with the new content.
4. **New optional entries:** a window asks whether to install them. The answers are remembered.
5. **The game version or loader changed:** evoker updates the instance, then **stops the launch** (by exiting with an error, which Prism treats as "don't launch") and shows a message, e.g. *"My Pack was updated to Minecraft 1.21.5. Press Launch again."* Prism reads the instance's versions before it runs the pre-launch command, so the new ones only take effect on the next launch.

Every update compares the instance's versions with the pack, not only what changed upstream. Prism saves its copy of `mmc-pack.json` a few seconds after loading it, so it can occasionally overwrite a version change evoker just made; the next launch then corrects it again.

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
| `instance.cfg` | Name, icon, and `PreLaunchCommand` (with `OverrideCommands=true`): `"$INST_JAVA" -jar "<path to evoker.jar>" client update "$INST_DIR"`. Prism fills in `$INST_JAVA` (the instance's own Java, so nothing else is needed on `PATH`) and `$INST_DIR` itself, on every OS, and runs it in the instance's `minecraft/` folder. On Windows that Java is `javaw.exe`, which has no console: questions and messages during an update need a window. |
| `mmc-pack.json` | The Minecraft and loader components: `net.minecraft`, plus `net.fabricmc.fabric-loader`, `org.quiltmc.quilt-loader` or `net.neoforged`. Prism adds the rest (LWJGL, intermediary mappings) itself on the first launch. Later version changes edit the `version` fields in place. |
| `minecraft/` | The game folder: `mods/`, `resourcepacks/`, `shaderpacks/`. |
| `.evoker/` | evoker's state: `source.json` (where the pack came from), `evoker.json` / `evoker.lock` (the pack as last installed), `options.json` (your optional choices), `installed.json` (files evoker installed). |

The pack icon is copied to Prism's icons folder as `evoker-<instance>.png` (icon key `evoker-<instance>`).

If evoker.jar moves (reinstalling evoker to a different folder), run `evoker client update <pack>` once to fix the pre-launch command.

## Where files go

| Type | Path in the instance |
|---|---|
| mod | `minecraft/mods/<source>-<projectId>.jar` |
| resource pack | `minecraft/resourcepacks/<source>-<projectId>.zip` |
| shader pack | `minecraft/shaderpacks/<source>-<projectId>.zip` |

Data packs and plugins are server-only.
