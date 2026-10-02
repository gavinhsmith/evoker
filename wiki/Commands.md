# Commands

Run every command inside the server folder: `java -jar evoker.jar <command>`.

| Command | What it does |
|---|---|
| `add <slug> [version]` | Adds content to `evoker.json` (`latest` unless you give a version), resolves it and its dependencies, and downloads them. `sodium` means `modrinth:sodium`. Adding an existing entry changes its version. |
| `remove <slug>` | Removes the entry from `evoker.json`, then deletes it and every dependency nothing else needs anymore. |
| `install` | Brings the folder in line with `evoker.json` and `evoker.lock`: resolves anything new or changed in `evoker.json`, downloads whatever is missing, deletes what was removed, applies `properties` and `eula`. |
| `start` | `install` (plus the auto-updates enabled in the `evoker` block), then runs the server as a child process. The console is passed through; evoker exits with the server's exit code. |
| `version` | Prints the evoker version. |
| `help` | Prints the command list. |

Errors print `error: ...` and exit with code 1; a failed `add` leaves `evoker.json` unchanged. Warnings print `evoker: warning: ...` and never stop the command.

## When evoker goes online

`install` and `start` only contact Modrinth when `evoker.json` asks for something the lock doesn't have (a new entry, or a changed pin). Otherwise they use the lock as-is, and only download files that are missing or don't match their locked hash.

Resolving keeps the locked version of every entry you didn't touch: `add` doesn't update your other mods.

## Stopping the server

Type `stop` in the console, or press Ctrl+C. Ctrl+C reaches the server too, so it saves before exiting; evoker waits for it.
