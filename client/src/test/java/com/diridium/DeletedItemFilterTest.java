// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class DeletedItemFilterTest {

    private static DeletedItemInfo item(String type, String name) {
        DeletedItemInfo info = new DeletedItemInfo();
        info.setType(type);
        info.setName(name);
        return info;
    }

    private static final List<DeletedItemInfo> ITEMS = List.of(
            item(DeletedItemInfo.TYPE_CHANNEL, "ADT Inbound"),
            item(DeletedItemInfo.TYPE_CHANNEL, "Lab Results"),
            item(DeletedItemInfo.TYPE_CODE_TEMPLATE, "labHelpers"));

    private static List<String> names(List<DeletedItemInfo> items) {
        List<String> names = new ArrayList<>();
        for (DeletedItemInfo info : items) {
            names.add(info.getName());
        }
        return names;
    }

    @Test
    public void testAllWithNoSearch() {
        assertEquals(List.of("ADT Inbound", "Lab Results", "labHelpers"), names(DeletedItemFilter.filter(ITEMS, null, "")));
    }

    @Test
    public void testOneType() {
        assertEquals(List.of("ADT Inbound", "Lab Results"),
                names(DeletedItemFilter.filter(ITEMS, DeletedItemInfo.TYPE_CHANNEL, "")));
        assertEquals(List.of("labHelpers"),
                names(DeletedItemFilter.filter(ITEMS, DeletedItemInfo.TYPE_CODE_TEMPLATE, null)));
    }

    @Test
    public void testSearchIgnoresCaseAndSurroundingSpaces() {
        assertEquals(List.of("Lab Results", "labHelpers"), names(DeletedItemFilter.filter(ITEMS, null, "  LAB ")));
    }

    @Test
    public void testTypeAndSearchTogether() {
        assertEquals(List.of("labHelpers"),
                names(DeletedItemFilter.filter(ITEMS, DeletedItemInfo.TYPE_CODE_TEMPLATE, "lab")));
    }

    @Test
    public void testItemWithNoNameMatchesOnlyABlankSearch() {
        // The name column allows null. A search skips it rather than failing.
        List<DeletedItemInfo> items = List.of(item(DeletedItemInfo.TYPE_CHANNEL, null));
        assertEquals(1, DeletedItemFilter.filter(items, null, "").size());
        assertEquals(0, DeletedItemFilter.filter(items, null, "x").size());
    }
}
