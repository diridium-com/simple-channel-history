# Simple Channel and Code Template History

[![GitHub release](https://img.shields.io/github/v/release/diridium-com/simple-channel-history)](https://github.com/diridium-com/simple-channel-history/releases/latest)
[![License: MPL-2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](https://www.mozilla.org/en-US/MPL/2.0/)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/projects/jdk/17/)
[![OIE 4.7.0](https://img.shields.io/badge/OIE-4.7.0-blue)](https://github.com/OpenIntegrationEngine/engine)

An Open Integration Engine plugin for tracking version history of Channels and Code Templates.

Running OIE 4.6.0? Use [simple-channel-history 2.8.1](https://github.com/diridium-com/simple-channel-history/releases/tag/v2.8.1).

## Features

- Automatic version history tracking when channels and code templates are saved
- Decomposed component diff view with navigable tree (scripts, connectors, filter/transformer steps, plugin properties)
- Side-by-side diff viewer with word-level inline highlighting
- Color-coded change indicators (added, removed, modified, unchanged)
- Revert to any previous version
- Prune older versions to manage storage
- Deleted items tracking: automatically saves a final XML snapshot when channels or code templates are deleted
- Database-backed storage (history travels with database backups)
- **Runs in both administrators**: the classic Swing Administrator and the OIE web administrator, with full feature parity

### Web administrator

The extension ships a web administrator UI in `webadmin/` (built to `webadmin/web/plugin.js` by the Maven build and packaged into the extension zip). Once installed, the OIE web admin discovers it automatically and adds:

- a **View History** action on the Channels view (right-click a channel, or the Channel Tasks pane) and on the Code Templates tree, and
- a **Channel History: Deleted Items** tab under Settings.

The diff uses the web admin's Monaco-backed side-by-side viewer; decomposition, change detection, and reorder detection run in the browser (the engine servlet is unchanged and serves the same raw revision XML to both UIs). Requires OIE web administrator 4.6+ (which provides the `registerChannelAction` / `registerCodeTemplateAction` extension points and `createDiffEditor`).

<img src="https://raw.githubusercontent.com/wiki/diridium-com/simple-channel-history/images/4.jpg" alt="History dialog showing revision table" width="500">

<img src="https://raw.githubusercontent.com/wiki/diridium-com/simple-channel-history/images/5.jpg" alt="Decomposed component diff view with tree navigation and side-by-side comparison" width="700">

<img width="500" height="691" alt="image" src="https://github.com/user-attachments/assets/65577e17-7929-4a13-bded-a1248e97ed3c" />


## Supported Databases

- PostgreSQL
- MySQL
- Oracle
- SQL Server
- Derby

## Building

Requires Java 17+ and OIE libraries in your Maven repository (local or remote).

```bash
./scripts/install-engine-jars.sh
```

The script installs the 4 engine jars this plugin builds against (`mirth-server`, `donkey-server`, `mirth-client-core` and `mirth-client`) into your local Maven repository, taken from the published OIE release matching the POM's `mc.version` and checked against that release's `sha256sums`. Run it once per engine version.

### Development Build (unsigned)

```bash
mvn clean package
```

### Release Build (signed with YubiKey)

Signed builds require a YubiKey with a code signing certificate and the OpenSC PKCS#11 library.

1. Copy `yubikey-pkcs11.cfg.example` to `yubikey-pkcs11.cfg` and update the library path for your system
2. Create `certchain.pem` containing your certificate chain (your cert + intermediate CA + root CA in PEM format)
3. Build with the signing profile. `read -s` takes the PIN without echoing it or leaving it in your shell history (bash or zsh). The variable isn't exported, so only Maven gets the PIN, not the processes the build starts (npm, and the install scripts it runs):

```bash
read -rs PIN
mvn clean package -Psigning -Dsigning.storepass="$PIN"
unset PIN
```

The plugin zip will be in `package/target/simple-channel-history-<version>.zip`.

### End-to-end tests

`e2e/run.py` installs a built zip into the released OIE engine through the engine's own installer and checks the plugin on a real database: a fresh install, an upgrade from each release in `e2e/upgrade-sources.txt` (a release built for an older engine runs on that engine first, from `e2e/engines.txt`, and the engine is upgraded under it), an uninstall, and an install alongside [Role-Based Access Control](https://github.com/diridium-com/role-based-access-control) (the release in `e2e/rbac-source.txt`), where a role limited to some channels must see only those channels' deleted snapshots. CI runs it on every push and pull request against Derby, PostgreSQL, MySQL, SQL Server (jTDS and Microsoft drivers) and Oracle. While a release targets an engine that isn't published yet, CI runs it on the POM's `mc.version` engine with `--label-as`, which installs copies of the zips whose `plugin.xml` names that engine instead.

Locally it needs Java 17 or later, Docker for every database but Derby, and the distribution tarball of the engine the zip is built for (the `mirthVersion` in its `plugin.xml`). When that is the POM's `mc.version`, `scripts/install-engine-jars.sh` leaves a checked copy in `target/oie-dist/`; a `mvn clean` of the whole project deletes it, so run the script again after one:

```bash
python3 e2e/run.py --db postgres --zip package/target/simple-channel-history-<version>.zip \
    --tarball target/oie-dist/oie_unix_4_6_0.tar.gz --work /tmp/sch-e2e-1
```

`--work` must not exist yet. Each engine and its logs stay there. For a zip built for an engine that isn't published yet, use the `mc.version` tarball and add `--label-as <mc.version>`, as CI does.

## Installation

Install using the Extensions manager in the OIE Administrator, or manually extract
to the `extensions` directory. A restart is required after installation.

The plugin will create the necessary database tables on first startup.

## Usage

### Viewing History
- Select a channel and click "View History" in the Channel Tasks panel
- Or right-click a channel and select "View History"

### Comparing Versions
- Double-click a version to compare it with the one before it
- Or select two versions (Ctrl/Cmd-click) and click "Show Diff"

### Reverting
- Select a version in the history table
- Click "Revert to Selected"

Reverting a channel restores it as saved in that revision: connectors, scripts, filters, transformers, and channel properties such as initial state and message storage. Its enabled or disabled state, message pruning settings and tags stay as they are now.

### Pruning
- Select a version in the history table
- Right-click and select "Prune older revisions"
- All versions older than the selected version will be deleted

## Design notes

Why the plugin works the way it does, what is deliberate, and what the tests cover: [docs/design-notes.md](docs/design-notes.md).

## License

Mozilla Public License 2.0 (MPL-2.0)

Based on the original [git-ext](https://github.com/kayyagari/git-ext) plugin by Kiran Ayyagari.
