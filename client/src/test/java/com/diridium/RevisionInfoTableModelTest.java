// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import static org.junit.Assert.*;

import org.junit.Test;

public class RevisionInfoTableModelTest {

    private static final String DATE_TIME = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}";

    @Test
    public void testSecondsAgo() {
        // 30 to 59 seconds back, so the run's own time can't push it to a minute or down to one second.
        String label = RevisionInfoTableModel.formatTime(System.currentTimeMillis() - 30_000);
        assertTrue(label, label.matches("[3-5]\\d seconds ago"));
    }

    @Test
    public void testOneMinuteIsSingular() {
        String label = RevisionInfoTableModel.formatTime(System.currentTimeMillis() - 90_000);
        assertEquals("1 minute ago", label);
    }

    @Test
    public void testMinutesAgo() {
        String label = RevisionInfoTableModel.formatTime(System.currentTimeMillis() - 5 * 60_000 - 10_000);
        assertEquals("5 minutes ago", label);
    }

    @Test
    public void testAnHourOrMoreIsADate() {
        String label = RevisionInfoTableModel.formatTime(System.currentTimeMillis() - 2 * 3_600_000);
        assertTrue(label, label.matches(DATE_TIME));
    }

    @Test
    public void testFutureTimeIsADate() {
        // A server clock ahead of the workstation's: shown as the date, not a negative age.
        String label = RevisionInfoTableModel.formatTime(System.currentTimeMillis() + 3_600_000);
        assertTrue(label, label.matches(DATE_TIME));
    }
}
