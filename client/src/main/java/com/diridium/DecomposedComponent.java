// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

public class DecomposedComponent {

    private final String key;
    private final String displayName;
    private final String content;
    private final String parentGroup;

    public DecomposedComponent(String key, String displayName, String content, String parentGroup) {
        this.key = key;
        this.displayName = displayName;
        this.content = content;
        this.parentGroup = parentGroup;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getContent() {
        return content;
    }

    public String getParentGroup() {
        return parentGroup;
    }
}
