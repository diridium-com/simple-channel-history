// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

/**
 * @author Kiran Ayyagari (kayyagari@apache.org)
 */
public class RevisionInfo {
    private String hash;  // history ID (stored as String for API compatibility)
    private int revision; // channel/code template revision number
    private String committerName;
    private long time; // UTC, always

    public RevisionInfo() {
    }

    public String getHash() {
        return hash;
    }

    /** How the administrators label a revision: "Rev" and the item's revision number. */
    public String getShortHash() {
        return "Rev " + revision;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getCommitterName() {
        return committerName;
    }

    public void setCommitterName(String committerName) {
        this.committerName = committerName;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }
}
