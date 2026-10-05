# Servers

A server is a folder that a pack was installed into. The pack decides what is installed; the server folder belongs to whoever runs it. evoker never changes the server's own configuration (`server.properties`, configs, worlds), with the two exceptions below.

## Install

In an empty folder:

```sh
evoker install server https://raw.githubusercontent.com/me/pack/main/
evoker install server --local ../my-pack          # a pack on disk, e.g. while writing it
```

1. Fetches `evoker.json` and `evoker.lock` (see [Pack URLs](Commands#pack-urls)). The pack's `side` must include `server`.
2. Downloads the server jar, or runs its installer. See [Server Software](Server-Software).
3. Downloads every entry whose `sides` include `server`, optional ones included, and the pack's server [overrides](Overrides). Each file is checked against its locked hash. See [where files go](Content-Sources#types).
4. Asks whether you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) (`--accept-eula` answers yes). Yes writes `eula.txt`; no installs everything else, and the server won't start until you accept (edit `eula.txt`, or install again with `--accept-eula`).
5. Writes the [`.evoker` folder](#evoker-folder).

Installing into a folder that already has a pack from a different source is an error. Installing the same pack again is the same as `evoker server update`.

## What evoker writes outside the pack's files

- **`eula.txt`**, only when you accept the EULA during install. It notes that you, the server owner, remain responsible for following the Minecraft EULA and the [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).
- **`resource-pack` and `resource-pack-sha1`** in `server.properties`, when the pack has a server-side resource pack (see [Resource packs](Content-Sources#resource-packs)). Every other key is yours.

## Update

```sh
evoker server update list    # what would change
evoker server update
```

Fetches the pack again from where it was installed from and applies the difference: new and changed files are downloaded, removed entries' files are deleted. evoker only ever deletes files it installed; jars you put in `mods/` or `plugins/` yourself are left alone.

If the pack can't be fetched (offline, the URL is gone), evoker warns and keeps what is installed.

## Start

```sh
evoker server start
```

Runs `server update` first (turn that off with `evoker config updateOnStart false`), then runs the server as a child process. The console works as usual; type `stop` to shut it down. Ctrl+C reaches the server too, and on Linux/macOS a SIGTERM (e.g. from systemd) is passed on so it saves and exits. evoker exits with the server's exit code.

**`server start` is never blocked by updates:** if the pack can't be fetched, or something in it can't be downloaded, the installed files are used and evoker warns.

## Running it yourself

To run the server from systemd, Docker, a hosting panel or your own script:

```sh
evoker server update && eval "exec $(evoker server command)"
```

Ask evoker for the command each time rather than copying it once: for NeoForge it contains the build number, which changes with the pack.

## Configuration

Settings for this server, changed with `evoker config` inside the server folder (see [Configuration](Configuration)):

| Key | Default | Meaning |
|---|---|---|
| `java` | `java` | Java executable used to run the server. |
| `jvmArgs` | `[]` | Arguments for that JVM, e.g. `["-Xmx4G"]`. |
| `updateOnStart` | `true` | `server start` runs `server update` first. |

## `.evoker` folder

evoker's state for this server. Don't edit it; deleting it makes evoker forget the install (the server files stay).

| File | |
|---|---|
| `config.json` | The [configuration](#configuration) above. |
| `source.json` | Where the pack was installed from (URL or local path). |
| `evoker.json`, `evoker.lock` | The pack as last installed. `server update` compares the fetched pack with these. |
| `installed.json` | Every file evoker installed, with its hash. Also pins the server jar or installer when its upstream publishes no checksum. |
| `installer.log` | Output of the last installer run (Quilt, NeoForge, Spigot). |
| `installer.stamp` | Which loader, version, build and installer last ran, so the installer only runs again when one of them changes. |
| `buildtools/` | Spigot only: BuildTools' clones, so later builds are faster. Safe to delete. |
