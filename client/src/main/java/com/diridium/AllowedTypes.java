// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.util.Arrays;
import java.util.Collections;

import com.mirth.connect.model.converters.ObjectXMLSerializer;

/**
 * Lets the plugin's two model classes through the engine's XStream allowlist, so the Administrator can
 * read the server's responses. Named exactly rather than by package, and registered once: the engine's
 * allowTypes adds to its list on every call.
 */
final class AllowedTypes {

    private static boolean registered;

    private AllowedTypes() {
    }

    static synchronized void register() {
        if (!registered) {
            ObjectXMLSerializer.getInstance().allowTypes(
                    Arrays.asList(RevisionInfo.class.getName(), DeletedItemInfo.class.getName()),
                    Collections.emptyList(), Collections.emptyList());
            registered = true;
        }
    }
}
