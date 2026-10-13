// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.mirth.connect.client.core.Permissions;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.model.ExtensionPermission;

public class ChannelHistoryPermissionsTest {

    /** operation name -> the permission it was registered under */
    private static Map<String, String> registered() {
        Map<String, String> byOperation = new HashMap<>();
        for (ExtensionPermission permission : new ChannelHistoryPermissions().getExtensionPermissions()) {
            assertEquals("the extension name forms the operation names the authorization controller looks up",
                    ChannelHistoryServletInterface.PLUGIN_NAME, permission.getExtensionName());
            for (String operation : permission.getOperationNames()) {
                String previous = byOperation.put(operation, permission.getDisplayName());
                assertNull(operation + " is registered under two permissions", previous);
            }
        }
        return byOperation;
    }

    @Test
    public void testEveryEndpointIsRegisteredUnderItsDeclaredPermission() {
        Map<String, String> byOperation = registered();
        int endpoints = 0;
        for (Method method : ChannelHistoryServletInterface.class.getMethods()) {
            MirthOperation operation = method.getAnnotation(MirthOperation.class);
            if (operation == null) {
                continue;
            }
            endpoints++;
            assertEquals(operation.name() + " must be registered under the permission it declares",
                    operation.permission(), byOperation.get(operation.name()));
        }
        assertEquals("every registered operation is an endpoint", endpoints, byOperation.size());
        assertTrue("the interface has endpoints", endpoints > 0);
    }

    @Test
    public void testEveryTaskIsRegisteredUnderItsPermission() {
        Map<String, String> byTask = new HashMap<>();
        for (ExtensionPermission permission : new ChannelHistoryPermissions().getExtensionPermissions()) {
            for (String task : permission.getTaskNames()) {
                assertTrue(task + " needs its group, as rbac keys a group's tasks \"group/task\"", task.contains("/"));
                assertNull(task + " is registered under two permissions", byTask.put(task, permission.getDisplayName()));
            }
        }
        assertEquals(ChannelHistoryTasks.permissions(), byTask);
    }

    @Test
    public void testDeletedItemsTabHasItsOwnRefreshEntry() {
        // Without it, rbac 1.1.3 hides the tab from every role without the Data Pruner's View Settings.
        assertEquals(Permissions.CHANNELS_VIEW,
                ChannelHistoryTasks.permissions().get("settings_Channel History: Deleted Items/doRefresh"));
    }

    @Test
    public void testWebAdministratorUsesTheSameTaskNames() throws Exception {
        String web = new String(Files.readAllBytes(Paths.get("..", "webadmin", "web", "schi-core.js")), StandardCharsets.UTF_8);
        assertTrue("task group", web.contains("'" + ChannelHistoryTasks.GROUP + "'"));
        assertTrue("Deleted Items tab", web.contains("'" + ChannelHistoryTasks.DELETED_ITEMS_TAB + "'"));
        for (String key : ChannelHistoryTasks.permissions().keySet()) {
            if (key.startsWith(ChannelHistoryTasks.GROUP + "/")) {
                String task = key.substring(ChannelHistoryTasks.GROUP.length() + 1);
                assertTrue(task + " is missing from schi-core.js", web.contains("'" + task + "'"));
            }
        }
    }

    @Test
    public void testOnlyThisPluginsOperationsAreRegistered() {
        // The engine's OperationUtil would also list every engine operation needing the same permission.
        Map<String, String> byOperation = registered();
        assertFalse(byOperation.containsKey("updateChannel"));
        assertFalse(byOperation.containsKey("getChannels"));
        assertTrue(byOperation.containsKey("revertChannel"));
        assertTrue(byOperation.containsKey("getDeletedChannels"));
    }
}
