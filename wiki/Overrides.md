# Overrides

Files a pack ships as they are, mostly configs: mod settings, `options.txt` defaults, a server's plugin configs. They live in three folders next to `evoker.json`:

| Folder | Installed on |
|---|---|
| `overrides/` | servers and clients |
| `server-overrides/` | servers; wins over `overrides/` for the same file |
| `client-overrides/` | clients; wins over `overrides/` for the same file |

A file goes to the same path inside the server folder, or inside the Prism instance's `minecraft/` folder: `overrides/config/sodium-options.json` becomes `config/sodium-options.json`.

```
my-pack/
  evoker.json
  evoker.lock
  overrides/config/sodium-options.json
  client-overrides/options.txt
  server-overrides/config/paper-global.yml
```

## For pack authors

Every pack command scans the three folders and records each file's hash in [`evoker.lock`](evoker-lock#overrides). After changing a file there, run a pack command (`evoker update` works) and publish both. A file changed without that doesn't match the lock, so servers and clients won't install it.

`evoker import` copies a modpack's override folders into the new pack as they are.

## On servers and clients

Overrides are defaults: once someone changes a file, it is theirs.

| The file on disk | What evoker does |
|---|---|
| missing | installs the pack's version |
| installed by evoker, unchanged since | follows the pack: updated when the pack's version changes, deleted when the pack drops it |
| installed by evoker, changed since | keeps it, with a warning when the pack has a different version |
| there before evoker installed the pack | never touches it |

So a server owner's own `server.properties`, or a player's own `options.txt`, is never overwritten. To get the pack's version back, delete the file; the next update installs it.

Paths that would leave their folder (`../`) are refused.
