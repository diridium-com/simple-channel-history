<!-- SPDX-License-Identifier: MPL-2.0 -->
<!-- Copyright (c) 2026 Diridium Technologies Inc. -->

# Releasing

Every release is signed. CI builds an unsigned draft, the signed build is made where the YubiKey is, and it replaces the draft's assets before anyone publishes.

## 1. Bump the version

Change all of these in the commit you tag:

- `<revision>` in the parent `pom.xml`
- `version` in `oie.json` (the release workflow fails if it does not match the tag)
- `version` in `webadmin/plugin.json`
- `version` in `webadmin/package.json`, and the two root entries in `webadmin/package-lock.json`

CI stamps the tag into the build on its own, but the signed build is made from the committed files, so they have to agree.

## 2. Tag

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
```

`.github/workflows/release.yml` builds against the OIE distribution named by `OIE_TAG` and stages a **draft** release holding the unsigned `simple-channel-history-X.Y.Z.zip` and its `.sha256` sidecar. CI never publishes it.

## 3. Build the signed zip

Check out the tag on the machine with the YubiKey. If its local Maven repository does not have the engine jars for this `mc.version` yet, run `./scripts/install-engine-jars.sh` first. The `signing` profile needs `yubikey-pkcs11.cfg` (start from `yubikey-pkcs11.cfg.example`) and `certchain.pem` in the repo root. Both are gitignored. From the repo root:

```bash
mvn clean package -Psigning -Dsigning.storepass=<yubikey-pin>
```

Copy `package/target/simple-channel-history-X.Y.Z.zip` somewhere safe right away. A later `mvn clean` deletes it, and it cannot be rebuilt without the key.

Check every jar before it goes anywhere. Each must print `jar verified.` and exit 0:

```bash
unzip -q simple-channel-history-X.Y.Z.zip -d check
for j in check/simple-channel-history/*.jar; do jarsigner -verify -strict "$j" || echo "NOT SIGNED: $j"; done
```

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
