#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2025-2026 Diridium Technologies Inc.
#
# Installs the 12 OIE engine jars this plugin builds against into the local
# Maven repository, taken from the published OIE distribution for the POM's
# mc.version. CI builds from the same tarball (.github/workflows/build.yml).
# Run it once per engine version.
#
# Usage:
#   ./scripts/install-engine-jars.sh
#
# The tarball is downloaded into target/oie-dist/ (gitignored, reused until the
# next mvn clean) and checked against the release's own sha256sums before
# anything is installed. Works on Linux and macOS.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

MC_VERSION="$(mvn -q -N help:evaluate -Dexpression=mc.version -DforceStdout)"
if [[ -z "${MC_VERSION}" ]]; then
    echo "error: could not read mc.version from pom.xml" >&2
    exit 1
fi
TARBALL="oie_unix_${MC_VERSION//./_}.tar.gz"
BASE_URL="https://github.com/OpenIntegrationEngine/engine/releases/download/v${MC_VERSION}"
DIST_DIR="target/oie-dist"

# sha256sum on Linux, shasum on macOS.
sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

mkdir -p "${DIST_DIR}"
curl -fsSL -o "${DIST_DIR}/sha256sums" "${BASE_URL}/sha256sums"
# Lines read "<hash> *<file>" (binary mode) or "<hash>  <file>".
expected="$(awk -v f="${TARBALL}" '{ n = $2; sub(/^\*/, "", n) } n == f { print $1 }' "${DIST_DIR}/sha256sums")"
if [[ -z "${expected}" ]]; then
    echo "error: ${TARBALL} is not listed in ${BASE_URL}/sha256sums" >&2
    exit 1
fi

if [[ ! -f "${DIST_DIR}/${TARBALL}" || "$(sha256 "${DIST_DIR}/${TARBALL}")" != "${expected}" ]]; then
    echo "downloading ${TARBALL} from ${BASE_URL}"
    curl -fSL -o "${DIST_DIR}/${TARBALL}" "${BASE_URL}/${TARBALL}"
fi
actual="$(sha256 "${DIST_DIR}/${TARBALL}")"
if [[ "${actual}" != "${expected}" ]]; then
    echo "error: ${TARBALL} sha256 is ${actual}, the release lists ${expected}" >&2
    exit 1
fi

# groupId:artifactId:path inside the tarball
JARS=(
    "com.mirth.connect:mirth-server:oie/server-lib/mirth-server.jar"
    "com.mirth.connect:donkey-server:oie/server-lib/donkey/donkey-server.jar"
    "com.mirth.connect:mirth-client-core:oie/server-lib/mirth-client-core.jar"
    "com.mirth.connect:mirth-client:oie/client-lib/mirth-client.jar"
    "com.mirth.connect.connectors:vm-shared:oie/extensions/vm/vm-shared.jar"
    "com.mirth.connect.connectors:js-shared:oie/extensions/js/js-shared.jar"
    "com.mirth.connect.connectors:tcp-shared:oie/extensions/tcp/tcp-shared.jar"
    "com.mirth.connect.plugins:mllpmode-shared:oie/extensions/mllpmode/mllpmode-shared.jar"
    "com.mirth.connect.plugins:http-shared:oie/extensions/http/http-shared.jar"
    "com.mirth.connect.plugins:javascriptstep-shared:oie/extensions/javascriptstep/javascriptstep-shared.jar"
    "com.mirth.connect.plugins.datatypes:datatype-raw-shared:oie/extensions/datatype-raw/datatype-raw-shared.jar"
    "com.mirth.connect.plugins.datatypes:datatype-hl7v2-shared:oie/extensions/datatype-hl7v2/datatype-hl7v2-shared.jar"
)

members=()
for entry in "${JARS[@]}"; do
    members+=("${entry##*:}")
done
tar -xzf "${DIST_DIR}/${TARBALL}" -C "${DIST_DIR}" "${members[@]}"

for entry in "${JARS[@]}"; do
    IFS=':' read -r group artifact rel_path <<< "${entry}"
    echo "installing ${group}:${artifact}:${MC_VERSION}"
    mvn -q install:install-file \
        -Dfile="${DIST_DIR}/${rel_path}" \
        -DgroupId="${group}" \
        -DartifactId="${artifact}" \
        -Dversion="${MC_VERSION}" \
        -Dpackaging=jar
done

echo "done. ${#JARS[@]} jars installed at version ${MC_VERSION}."
