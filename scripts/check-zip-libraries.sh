#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 Diridium Technologies Inc.
#
# Checks that every library the zip's plugin.xml names is in the zip. The engine runs the server
# jar and the e2e tests exercise it, but nothing else loads the CLIENT libraries: only the Swing
# Administrator downloads them. A missing one, or a plugin.xml path that drifted from the jar's
# name, would pass every other check and break the Swing diff.
#
# Usage: scripts/check-zip-libraries.sh <plugin zip>

set -euo pipefail

zip="$1"
dir="simple-channel-history"
entries="$(unzip -Z1 "$zip")"
paths="$(unzip -p "$zip" "$dir/plugin.xml" | grep -o '<library [^>]*path="[^"]*"' | sed 's/.*path="\([^"]*\)"/\1/')"

if [ -z "$paths" ]; then
    echo "error: $dir/plugin.xml in $zip names no libraries" >&2
    exit 1
fi

missing=0
while IFS= read -r path; do
    if grep -qxF "$dir/$path" <<< "$entries"; then
        echo "ok: $path"
    else
        echo "error: plugin.xml names $path, but $zip has no $dir/$path" >&2
        missing=1
    fi
done <<< "$paths"
exit "$missing"
