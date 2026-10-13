<!-- SPDX-License-Identifier: MPL-2.0 -->
<!-- Copyright (c) 2026 Diridium Technologies Inc. -->

# Releasing

Every release is signed. CI builds an unsigned draft, the signed build is made where the YubiKey is, and it replaces the draft's assets before anyone publishes.

## 1. Bump the version

Change both of these in the commit you tag. The release workflow fails, naming each one, if either does not match the tag:

- `<revision>` in the parent `pom.xml`
- `version` in `oie.json`

Everything else takes its version from `<revision>` when the zip is built: `plugin.xml`, the jar names and `webadmin/plugin.json`. The release workflow checks the version inside the built zip as well. `oie.json` stays separate because the store tooling reads it straight from the repo at the tag.

When the engine version (`mc.version` in `pom.xml`) changes, set `minEngineVersion` and `maxEngineVersion` in `oie.json` to it as well. The engine loads a plugin only on the exact version it was built for, and the release workflow fails if `oie.json` says otherwise.

CI builds the committed files as they are, the same way the signed build does.

## 2. Tag

Tag a commit on `main` whose CI run passed, the e2e jobs included. The release job runs the unit tests only, so a commit that never passed CI would ship without having run in a real engine.

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
```

`.github/workflows/release.yml` builds and tests against the OIE release named by the POM's `mc.version` and stages a **draft** release holding the unsigned `simple-channel-history-X.Y.Z.zip` and its `.sha256` sidecar. CI never publishes it. It refuses a tag whose release is already published, so a re-run cannot put the unsigned zip over the signed one.

If the release job fails, or the draft turns out wrong before anyone publishes it, delete the draft and the tag and tag again. Nobody has an unpublished version, so it keeps its number:

```bash
gh release delete vX.Y.Z --yes          # the draft only; never a published release
git push origin :refs/tags/vX.Y.Z
git tag -d vX.Y.Z
# fix on main, wait for its CI, then tag again
```

A clone that already fetched the old tag keeps it, so on the signing machine fetch with `git fetch --tags --force` and check that `git rev-parse vX.Y.Z` is the new commit.

## 3. Build the signed zip

Check out the tag on the machine with the YubiKey, and make sure the checkout is exactly the tag, with no local changes, so the signed zip is built from what CI tested:

```bash
git fetch --tags --force && git checkout vX.Y.Z
git status --porcelain                 # must print nothing
git describe --exact-match --tags      # must print vX.Y.Z
```

If its local Maven repository does not have the engine jars for this `mc.version` yet, run `./scripts/install-engine-jars.sh` first. The `signing` profile needs `yubikey-pkcs11.cfg` (start from `yubikey-pkcs11.cfg.example`) and `certchain.pem` in the repo root. Both are gitignored. From the repo root:

```bash
read -rs PIN
mvn clean package -Psigning -Dsigning.storepass="$PIN"
unset PIN
```

`read -s` takes the PIN without echoing it or leaving it in your shell history. The variable isn't exported, so only Maven gets the PIN, not the processes the build starts (npm, and the install scripts it runs).

Copy `package/target/simple-channel-history-X.Y.Z.zip` somewhere safe right away. A later `mvn clean` deletes it, and it cannot be rebuilt without the key.

Check every jar before it goes anywhere. Each must print `jar verified.` and exit 0:

```bash
mkdir check-X.Y.Z && unzip -q simple-channel-history-X.Y.Z.zip -d check-X.Y.Z
for j in check-X.Y.Z/simple-channel-history/*.jar; do jarsigner -verify -strict "$j" || echo "NOT SIGNED: $j"; done
```

The `mkdir` fails if the folder already exists, so an earlier release's jars can't get mixed in.

`jarsigner -verify -verbose -certs` also prints when the signing certificate expires. The DigiCert timestamp keeps already-signed jars valid after that, but new releases need a current certificate.

## 4. Replace the draft's assets and publish

Regenerate the sidecar from the signed zip and replace both assets on the draft:

```bash
sha256sum simple-channel-history-X.Y.Z.zip > simple-channel-history-X.Y.Z.zip.sha256
gh release upload vX.Y.Z simple-channel-history-X.Y.Z.zip simple-channel-history-X.Y.Z.zip.sha256 --clobber
```

Review the draft, then publish it by hand. Never upload over a published release's assets; cut a new version instead.

## 5. Submit to the OIE Community Store

The store lists packages from the [community catalog](https://github.com/gibson9583/oie-community-catalog). Open a PR there, from a fork, adding `manifests/plugins/simple-channel-history/X.Y.Z.json` and refreshing `meta.json` from `oie.json`. The catalog README describes both files. The `sha256` must be computed from the **published, signed** zip, never from the CI build. The catalog's CI downloads the zip and checks that digest before the PR can merge, and the index is rebuilt on merge.

On the Diridium dev VM, the `publish-to-oie-store` Claude Code skill does this end to end. It refuses a release with unsigned jars or a stale sidecar.

## 6. Test upgrades from it

Add the published release to `e2e/upgrade-sources.txt`: its version, the SHA-256 of the published zip, and its download URL. From then on the e2e tests install the signed zip and upgrade from it, the path every existing user takes to the next release.
