# Commands

Run every command inside the server folder: `java -jar evoker.jar <command>`.

| Command | What it does |
|---|---|
| `install` | Brings the folder in line with `evoker.json` and `evoker.lock`: resolves anything new or changed in `evoker.json`, downloads whatever is missing, applies `properties` and `eula`. |
| `start` | `install` (plus the auto-updates enabled in the `evoker` block), then runs the server as a child process. The console is passed through; evoker exits with the server's exit code. |
| `version` | Prints the evoker version. |
| `help` | Prints the command list. |

Errors print `error: ...` and exit with code 1. Warnings print `evoker: warning: ...` and never stop the command.

## Stopping the server

Type `stop` in the console, or press Ctrl+C. Ctrl+C reaches the server too, so it saves before exiting; evoker waits for it.
