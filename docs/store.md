# Simple Channel History

Tracks **version history for channels and code templates** in Open Integration
Engine, and lets you diff, revert, prune, and recover deleted items — from both the
classic Swing Administrator and the OIE web administrator, with full feature parity.

- **Automatic history** — every channel and code-template save records a revision
  (user + timestamp), stored in the engine's own database so history travels with
  your database backups.
- **Decomposed component diff** — a navigable tree breaks a channel into its parts
  (channel scripts, source/destination connectors, filter and transformer steps,
  plugin properties, destination order) with color-coded change indicators, plus a
  side-by-side XML diff with inline highlighting. Toggle to a raw-XML diff any time.
- **Revert** — roll a channel or code template back to any previous revision (a
  revert-history note is appended to the description).
- **Prune** — delete revisions older than a chosen point to reclaim space.
- **Deleted items** — when a channel or code template is deleted, a final XML
  snapshot is captured. A *Channel History: Deleted Items* settings tab lists them
  with filter/search, and lets you view, diff, download, or purge a snapshot.

## Using it

- **Channels** — right-click a channel (or use the Channel Tasks pane) → **View
  History**.
- **Code templates** — right-click a code template (or the Code Template Tasks
  pane) → **View History**.
- **Deleted items** — **Settings → Channel History: Deleted Items**.

## Supported databases

PostgreSQL, MySQL, Oracle, SQL Server, and Derby.

## Compatibility

Requires Open Integration Engine **4.6.0+**. The web administrator UI requires a web
admin build that provides the channel/code-template action extension points and the
Monaco-backed diff viewer (4.6+). A restart is required after install. See the
[README](https://github.com/diridium-com/simple-channel-history#readme) for details.
