#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2025-2026 Diridium Technologies Inc.
#
# Installs the OIE engine jars this plugin builds against into the local
# Maven repository. The public repsy mirror does not yet carry 4.6.0, so we
# resolve from a local engine checkout instead.
#
# Usage:
#   ENGINE_DIR=/path/to/engine ./scripts/install-engine-jars.sh
# If ENGINE_DIR is unset, defaults to ../engine relative to this repo.

set -euo pipefail

VERSION="4.6.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENGINE_DIR="${ENGINE_DIR:-$(cd "$SCRIPT_DIR/../../engine" 2>/dev/null && pwd || true)}"

if [[ -z "${ENGINE_DIR}" || ! -d "${ENGINE_DIR}" ]]; then
    echo "error: engine directory not found." >&2
    echo "  set ENGINE_DIR to point at your OIE engine checkout, e.g.:" >&2
    echo "    ENGINE_DIR=/path/to/engine $0" >&2
    exit 1
fi

# Format: groupId:artifactId:relative-path-under-engine
declare -a JARS=(
    "com.mirth.connect:mirth-server:server/setup/server-lib/mirth-server.jar"
    "com.mirth.connect:donkey-server:donkey/setup/donkey-server.jar"
    "com.mirth.connect:mirth-client-core:server/setup/server-lib/mirth-client-core.jar"
    "com.mirth.connect:mirth-client:server/setup/client-lib/mirth-client.jar"
    "com.mirth.connect.connectors:vm-shared:client/lib/vm-shared.jar"
    "com.mirth.connect.connectors:js-shared:client/lib/js-shared.jar"
    "com.mirth.connect.connectors:tcp-shared:client/lib/tcp-shared.jar"
    "com.mirth.connect.plugins:mllpmode-shared:client/lib/mllpmode-shared.jar"
    "com.mirth.connect.plugins:http-shared:client/lib/http-shared.jar"
    "com.mirth.connect.plugins:javascriptstep-shared:client/lib/javascriptstep-shared.jar"
    "com.mirth.connect.plugins.datatypes:datatype-raw-shared:client/lib/datatype-raw-shared.jar"
    "com.mirth.connect.plugins.datatypes:datatype-hl7v2-shared:client/lib/datatype-hl7v2-shared.jar"
)

for entry in "${JARS[@]}"; do
    IFS=':' read -r _ _ rel_path <<< "${entry}"
    jar_path="${ENGINE_DIR}/${rel_path}"
    if [[ ! -f "${jar_path}" ]]; then
        echo "error: missing ${jar_path}" >&2
        echo "  build the engine first (ant in donkey/ and server/) so the setup jars exist." >&2
        exit 1
    fi
done

for entry in "${JARS[@]}"; do
    IFS=':' read -r group artifact rel_path <<< "${entry}"
    jar_path="${ENGINE_DIR}/${rel_path}"
    echo "installing ${group}:${artifact}:${VERSION} from ${rel_path}"
    mvn -q install:install-file \
        -Dfile="${jar_path}" \
        -DgroupId="${group}" \
        -DartifactId="${artifact}" \
        -Dversion="${VERSION}" \
        -Dpackaging=jar
done

echo "done. ${#JARS[@]} jars installed at version ${VERSION}."
