# evoker.lock

Records exactly what evoker installed. **Written only by evoker; don't edit it. Do commit it.**

```json
{
  "lockVersion": 1,
  "server": {
    "software": "paper",
    "version": "1.21.4",
    "build": "232",
    "url": "https://fill-data.papermc.io/v1/objects/…/paper-1.21.4-232.jar",
    "sha256": "5ee4f542…"
  },
  "content": {}
}
```

## How it is used

- **`install` / `start`** compare the lock with `evoker.json`. If they still agree, the locked file is used as-is: evoker hashes the file on disk and skips the download when it matches `sha256`.
- If the file is missing or different, evoker downloads it from `url` again.
- `sha256` is always computed by evoker itself. Upstream checksums (Mojang SHA-1, Paper SHA-256, Purpur MD5) are also checked during the first download.

## Hash mismatch

If a re-download no longer matches `sha256`, the file at that URL changed after you locked it. evoker **keeps the existing file**, leaves the lock alone, and prints a warning. Nothing is blocked: investigate, then change `evoker.json` (for example the build) to accept a new file.

## `lockVersion`

The lock format version. An evoker that finds a newer `lockVersion` than it understands refuses to touch the lock.
