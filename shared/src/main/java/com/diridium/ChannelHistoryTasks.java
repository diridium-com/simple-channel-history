// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mirth.connect.client.core.Permissions;
import com.mirth.connect.client.core.TaskConstants;

/**
 * Task names for the plugin's UI actions, each tied to the engine permission its endpoint needs. The
 * server registers them with the permissions (ChannelHistoryPermissions), so an authorization plugin
 * such as Role-Based Access Control can tell both Administrators which actions a role can use, and they
 * hide the rest. Without one, every task is allowed. The server checks the permissions either way.
 *
 * <p>The web administrator uses the same names, in webadmin/web/schi-core.js.</p>
 */
public final class ChannelHistoryTasks {

    /** The task group of the plugin's actions. A group's tasks are registered as "group/task". */
    public static final String GROUP = ChannelHistoryServletInterface.PLUGIN_NAME;

    public static final String REVERT_CHANNEL = "doRevertChannel";
    public static final String PRUNE_CHANNEL_HISTORY = "doPruneChannelHistory";
    public static final String VIEW_DELETED_CHANNELS = "doViewDeletedChannels";
    public static final String REVERT_CODE_TEMPLATE = "doRevertCodeTemplate";
    public static final String PRUNE_CODE_TEMPLATE_HISTORY = "doPruneCodeTemplateHistory";
    public static final String VIEW_DELETED_CODE_TEMPLATES = "doViewDeletedCodeTemplates";

    /** The Deleted Items settings tab. The Administrators name its task group after it. */
    public static final String DELETED_ITEMS_TAB = "Channel History: Deleted Items";

    private static final Map<String, String> PERMISSIONS = new LinkedHashMap<>();

    static {
        add(GROUP, REVERT_CHANNEL, Permissions.CHANNELS_MANAGE);
        add(GROUP, PRUNE_CHANNEL_HISTORY, Permissions.CHANNELS_MANAGE);
        add(GROUP, VIEW_DELETED_CHANNELS, Permissions.CHANNELS_MANAGE);
        add(GROUP, REVERT_CODE_TEMPLATE, Permissions.CODE_TEMPLATES_MANAGE);
        add(GROUP, PRUNE_CODE_TEMPLATE_HISTORY, Permissions.CODE_TEMPLATES_MANAGE);
        add(GROUP, VIEW_DELETED_CODE_TEMPLATES, Permissions.CODE_TEMPLATES_MANAGE);
        // The tab shows both lists, and a task has one permission, so any user who can see channels gets
        // the tab and it loads the lists their role allows. Without its own entry, rbac 1.1.3 requires the
        // Data Pruner's View Settings: the Data Pruner registers that for the bare refresh task every
        // settings tab shares.
        add(TaskConstants.SETTINGS_KEY_PREFIX + DELETED_ITEMS_TAB, TaskConstants.SETTINGS_REFRESH, Permissions.CHANNELS_VIEW);
    }

    private ChannelHistoryTasks() {
    }

    private static void add(String group, String task, String permission) {
        PERMISSIONS.put(group + "/" + task, permission);
    }

    /** "group/task" to the engine permission it needs, in registration order. */
    public static Map<String, String> permissions() {
        return Collections.unmodifiableMap(PERMISSIONS);
    }
}
