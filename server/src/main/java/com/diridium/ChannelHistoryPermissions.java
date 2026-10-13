// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.mirth.connect.client.core.Permissions;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;

/**
 * Registers each of the plugin's endpoints under the engine permission its {@code @MirthOperation}
 * declares, so an authorization plugin such as Role-Based Access Control enforces it. The engine
 * hands a plugin's operations to the authorization controller as {@code "<extension>#<operation>"},
 * and a controller only knows the permission for names a plugin registered. Unregistered, rbac
 * allows them for every role.
 *
 * <p>Each permission also carries the UI tasks that need it ({@link ChannelHistoryTasks}), which rbac
 * hands to the Administrators so they hide the actions a role can't use.</p>
 *
 * <p>The permissions are the engine's own, so a role that already grants view or manage channels or
 * code templates keeps its access. The stock engine ignores registered permissions.</p>
 */
public class ChannelHistoryPermissions implements ServicePlugin {

    static final String[] PERMISSIONS = {
            Permissions.CHANNELS_VIEW, Permissions.CHANNELS_MANAGE,
            Permissions.CODE_TEMPLATES_VIEW, Permissions.CODE_TEMPLATES_MANAGE
    };

    @Override
    public String getPluginPointName() {
        return ChannelHistoryServletInterface.PLUGIN_NAME;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void init(Properties properties) {
    }

    @Override
    public void update(Properties properties) {
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    /**
     * One entry per engine permission, holding the operations whose annotation names it. The names come
     * from the servlet interface itself, so an endpoint added later can't ship unregistered. The
     * extension name must be the one the servlet passes to MirthServlet, since it forms the operation
     * names the authorization controller looks up.
     */
    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        ExtensionPermission[] registered = new ExtensionPermission[PERMISSIONS.length];
        for (int i = 0; i < PERMISSIONS.length; i++) {
            registered[i] = new ExtensionPermission(ChannelHistoryServletInterface.PLUGIN_NAME, PERMISSIONS[i],
                    "Simple Channel History endpoints that need the engine's " + PERMISSIONS[i] + " permission.",
                    operationsNeeding(PERMISSIONS[i]), tasksNeeding(PERMISSIONS[i]));
        }
        return registered;
    }

    /** The plugin's UI tasks that need this permission, as "group/task". */
    static String[] tasksNeeding(String permission) {
        return ChannelHistoryTasks.permissions().entrySet().stream()
                .filter(entry -> entry.getValue().equals(permission))
                .map(Map.Entry::getKey)
                .toArray(String[]::new);
    }

    /**
     * The plugin's operations whose annotation names this permission. Not the engine's OperationUtil:
     * for a core permission it also returns every engine operation that needs it.
     */
    static String[] operationsNeeding(String permission) {
        List<String> names = new ArrayList<>();
        for (Method method : ChannelHistoryServletInterface.class.getMethods()) {
            MirthOperation operation = method.getAnnotation(MirthOperation.class);
            if (operation != null && operation.permission().equals(permission)) {
                names.add(operation.name());
            }
        }
        return names.toArray(new String[0]);
    }
}
