// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The Deleted Items panel's Show and name filters, apart from the Swing panel so they can be tested. */
final class DeletedItemFilter {

    private DeletedItemFilter() {
    }

    /**
     * @param type   DeletedItemInfo.TYPE_CHANNEL or TYPE_CODE_TEMPLATE, or null for both
     * @param search text the name must contain, ignoring case; blank matches every item
     */
    static List<DeletedItemInfo> filter(List<DeletedItemInfo> items, String type, String search) {
        String text = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<DeletedItemInfo> filtered = new ArrayList<>();
        for (DeletedItemInfo info : items) {
            if (type != null && !type.equals(info.getType())) continue;
            String name = info.getName() == null ? "" : info.getName().toLowerCase(Locale.ROOT);
            if (!text.isEmpty() && !name.contains(text)) continue;
            filtered.add(info);
        }
        return filtered;
    }
}
