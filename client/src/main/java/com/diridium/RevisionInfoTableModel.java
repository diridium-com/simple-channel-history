// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.table.AbstractTableModel;

/**
 * @author Kiran Ayyagari (kayyagari@apache.org)
 */
public class RevisionInfoTableModel extends AbstractTableModel {

    private final List<RevisionInfo> revisions;

    private static final DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final String[] columnNames = {"Revision", "User", "Date"};

    public RevisionInfoTableModel(List<RevisionInfo> revisions) {
        this.revisions = revisions;
    }

    @Override
    public int getRowCount() {
        return revisions.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        RevisionInfo r = revisions.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> r.getShortHash();
            case 1 -> r.getCommitterName();
            case 2 -> formatTime(r.getTime());
            default -> throw new IllegalArgumentException("unknown column number " + columnIndex);
        };
    }

    public RevisionInfo getRevisionAt(int row) {
        return revisions.get(row);
    }

    private String formatTime(long t) {
        long elapsed = System.currentTimeMillis() - t;
        if (elapsed < 0) {
            return df.format(new Date(t));
        }

        long seconds = elapsed / 1000;
        long minutes = seconds / 60;

        if (minutes >= 60) {
            return df.format(new Date(t));
        } else if (minutes > 0) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        } else {
            return seconds + (seconds == 1 ? " second ago" : " seconds ago");
        }
    }
}
