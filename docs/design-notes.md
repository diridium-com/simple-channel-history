# Design notes

Why the plugin works the way it does: what is deliberate, how it interacts with the engine, and what the tests cover. Written 2026-10-12, for 2.8.1. Later changes add to this page.

## What history keeps

Every save of a channel or code template stores its XML as a row in a history table in the engine's own database, so history travels with database backups. The plugin's four tables are the history and the deleted-item snapshots, each for channels and for code templates.

A channel's history row contains the channel's definition only. The engine keeps a channel's settings (enabled or disabled, message pruning) and its tags separately, and strips them from the channel before the plugin sees a save.

## Revert

A revert restores the channel as saved in that revision: connectors, scripts, filters, transformers, and channel properties such as initial state and message storage. A note is added to the channel's description.

The channel's enabled or disabled state, its message pruning settings and its tags stay as they are now. They aren't part of the revision, so the revert sends the current ones along with the reverted channel, as the channel editor does on every save. Before 2.8.1 a revert reset them to defaults: enabled, no pruning, no tags.

A revert counts as a change made now by the reverting user. If someone had the channel open in the channel editor, the engine refuses their next save as stale. The Swing Administrator then asks before overwriting, unless the editor's user is the one who reverted. In that case it overwrites without asking. That is the Swing Administrator's own rule for saves by the same user, and it applies to any change that user made elsewhere, not only to reverts.

Reverting to a revision that uses a connector or plugin which is no longer installed saves the channel as invalid, as importing that channel would. Install the connector again, or revert to a later revision.

## Prune and purge

Prune deletes every revision older than the one you pick. The cut-off must be one of the item's own revisions; any other number is refused and nothing is deleted.

Purge deletes one deleted-item snapshot. When the snapshot is already gone, it says so rather than reporting a purge.

## Deleted items

When a channel or code template is deleted, the plugin saves a final snapshot, then deletes the item's history. If the snapshot can't be saved, the history is still deleted. An insert failing there means the database itself is failing, and the plugin never blocks the engine's delete.

Channel snapshots and code template snapshots are numbered separately, both from 1.

## Permissions

Every endpoint declares the engine permission it needs: view or manage channels, or view or manage code templates. The plugin also registers each endpoint under that permission, so an authorization plugin such as Role-Based Access Control enforces it: a role needs view channels to see a channel's history, manage channels to revert or prune it or to use deleted channels, and the same for code templates. Before 2.8.1 the endpoints weren't registered, and rbac, which allows operations it has no permission for, let any role use them.

The channel history endpoints apply the engine's per-channel restrictions. A deleted channel's snapshot is looked up by its snapshot number, which the engine's check can't map to a channel, so the plugin applies the restrictions itself: the list leaves out channels outside them, and reading or purging one of those snapshots is refused.

Stock OIE gives every user every permission and has no channel restrictions. Both matter with an authorization plugin such as [Role-Based Access Control](https://github.com/diridium-com/role-based-access-control), and the e2e tests check the permissions and the channel restrictions with it installed.

Reverts, prunes and purges are written to the engine's event log with the user and address. Viewing history or a diff is not, so comparing revisions doesn't fill the log.

## Engine version

The engine loads a plugin only on an engine version listed in its `plugin.xml`, and the build lists exactly one: the version it was built against. Each OIE release therefore needs a plugin release, and `oie.json` names that one engine version as both the minimum and the maximum.

The engine's libraries are compile-time dependencies, at the versions the engine ships, and none are bundled. Unit tests can't tell when they drift from the engine; the e2e tests can, because they run the plugin inside the released engine.

## Diffs

The diff breaks a channel into components (scripts, connector settings, filter and transformer steps, plugin properties) and compares each one. Steps are compared by position: inserting a step at the top shows every later step as changed. When a group's steps are all the same and only their order changed, the group is labelled "(steps reordered)". The raw XML diff is always exact.

The web administrator's decomposition is a port of the Swing one. The two can differ slightly, for example on elements that contain only whitespace.

## Swing Administrator

The history dialogs and Deleted Items call the server from the Administrator's UI thread, so the Administrator pauses for each call. With normal channels on a local network the pause is short. Moving every call to a background thread touches every action in those screens, and was left for a later release.

## Tests

- **Unit tests** cover the client's diff logic: the decomposer, the diff view's alignment, change and reorder detection, time labels and the Deleted Items filter.
- **End-to-end tests** (`e2e/run.py`, on every pull request) install the zip into the released OIE engine through its own installer, on Derby, PostgreSQL, MySQL, SQL Server (jTDS and Microsoft driver) and Oracle. They cover a fresh install, upgrades from earlier releases, uninstall, and an install alongside Role-Based Access Control. They drive the plugin through its REST API, including the JSON the web administrator reads, and check the database and the engine's log. CI also checks the zip itself: the version inside it, and that every client library `plugin.xml` names is in it.
- **Not covered by automated tests:** the Swing and web screens themselves, which are checked by hand before each release, and the signed build, which is made and checked on the signing machine (see [RELEASING.md](../RELEASING.md)).

## Expected build warnings

A clean `mvn clean package` prints three warnings, all expected:

- `JAR will be empty - no content was marked for inclusion!` The `package` module only assembles the zip; the jar it also produces is empty and unused.
- `Parameter 'finalName' is read-only, must not be used in configuration`, from the assembly plugin in the `package` module. It names the zip and works.
- npm's `install-scripts` notice about `esbuild` (`postinstall: node install.js`). esbuild's install script checks that its binary for the build machine is in place.
