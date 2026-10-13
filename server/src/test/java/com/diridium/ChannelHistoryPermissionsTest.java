// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

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
    public void testOnlyThisPluginsOperationsAreRegistered() {
        // The engine's OperationUtil would also list every engine operation needing the same permission.
        Map<String, String> byOperation = registered();
        assertFalse(byOperation.containsKey("updateChannel"));
        assertFalse(byOperation.containsKey("getChannels"));
        assertTrue(byOperation.containsKey("revertChannel"));
        assertTrue(byOperation.containsKey("getDeletedChannels"));
    }
}
