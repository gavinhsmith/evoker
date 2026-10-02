# Commands

Run every command inside the server folder: `java -jar evoker.jar <command>`.

| Command | What it does |
|---|---|
| `add <slug> [version]` | Adds content to `evoker.json` (`latest` unless you give a version), resolves it and its dependencies, and downloads them. `sodium` means `modrinth:sodium`. Adding an existing entry changes its version. |
| `remove <slug>` | Removes the entry from `evoker.json`, then deletes it and every dependency nothing else needs anymore. |
| `install` | Brings the folder in line with `evoker.json` and `evoker.lock`: resolves anything new or changed in `evoker.json`, downloads whatever is missing, deletes what was removed, applies `properties` and `eula`. |
| `update [slug]` | Moves `latest` entries to their newest compatible versions, and the server to its newest build if `build` is `latest`. Pinned entries stay. With a slug, updates only that entry (and not the server). Prints what changed. |
| `upgrade [--dry-run]` | Moves **everything** (pinned entries and a pinned server `build` included) to the newest versions for the current game version, and rewrites the pins in `evoker.json`. `--dry-run` prints the changes without touching anything. |
| `start` | `install` (plus the auto-updates enabled in the `evoker` block), then runs the server as a child process. The console is passed through; evoker exits with the server's exit code. |
| `version` | Prints the evoker version. |
| `help` | Prints the command list. |

Errors print `error: ...` and exit with code 1; a failed `add` leaves `evoker.json` unchanged. Warnings print `evoker: warning: ...` and never stop the command.

## When evoker goes online

`install` and `start` only contact Modrinth when `evoker.json` asks for something the lock doesn't have (a new entry, or a changed pin). Otherwise they use the lock as-is, and only download files that are missing or don't match their locked hash.

Resolving keeps the locked version of every entry you didn't touch: `add` doesn't update your other mods.

## Changing the game version

Edit `version` in `evoker.json`, then run `evoker upgrade` (try `--dry-run` first). Everything moves to its newest release for the new version.

Anything without a compatible version yet **keeps its current version** and prints a warning:

```
evoker: warning: modrinth:sodium has no version for fabric 1.21.5; keeping modrinth:sodium mc1.21.4-0.6.13-fabric
```

`start` is never blocked by this. Making sure the server actually works with what you installed is up to you: remove the entry, wait for a release, or pin something else.

The same rule applies whenever evoker can't resolve something it already has installed (an upstream project removed, for example): it keeps the installed version and warns.

## Stopping the server

Type `stop` in the console, or press Ctrl+C. Ctrl+C reaches the server too, so it saves before exiting; evoker waits for it.
