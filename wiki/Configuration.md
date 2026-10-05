# Configuration

`evoker config` changes how **evoker itself** behaves. It never touches a pack, a server's own settings (`server.properties`, configs) or a Prism instance's game settings.

```sh
evoker config                      # every setting and its value
evoker config jvmArgs              # one setting
evoker config jvmArgs '["-Xmx6G"]' # change it
evoker config updateOnStart false
```

List and true/false settings take JSON (`["-Xmx6G"]`, `false`); the others take the text as typed (`D:\Games\Prism\instances`). Unknown settings and values of the wrong kind are errors.

## Which settings

It depends on where you run it:

- **In a server folder** (one with a [`.evoker` folder](Servers#evoker-folder)): that server's settings, stored in `.evoker/config.json`.
- **Anywhere else:** your user settings, stored in `%APPDATA%\evoker\config.json` (Windows), `~/Library/Application Support/evoker/config.json` (macOS) or `~/.config/evoker/config.json` (Linux, or `$XDG_CONFIG_HOME/evoker`).

`--user` uses your user settings even inside a server folder.

## Server settings

| Key | Default | Meaning |
|---|---|---|
| `java` | `java` | Java executable used to run the server. |
| `jvmArgs` | `[]` | Arguments for that JVM, e.g. `["-Xmx4G"]`. |
| `updateOnStart` | `true` | `server start` runs `server update` first. |

## User settings

| Key | Default | Meaning |
|---|---|---|
| `instanceDir` | empty: Prism's usual folder (see [Clients](Clients#setup)) | Where Prism Launcher keeps its instances. |
