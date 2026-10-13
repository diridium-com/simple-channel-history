// SPDX-FileCopyrightText: Copyright 2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.awt.Component;

import com.mirth.connect.client.core.ForbiddenException;
import com.mirth.connect.client.ui.AuthorizationControllerFactory;
import com.mirth.connect.client.ui.PlatformUI;

/**
 * What the user's role allows, so the UI can hide actions the server would refuse, and say so plainly
 * when it refuses one anyway (a role changed while the Administrator was open).
 */
final class RoleAccess {

    private RoleAccess() {
    }

    /**
     * Whether the user may use one of the plugin's tasks. Asks the Administrator's authorization
     * controller, which with Role-Based Access Control resolves the names in {@link ChannelHistoryTasks}.
     * Without one, every task is allowed.
     */
    static boolean allows(String task) {
        try {
            return AuthorizationControllerFactory.getAuthorizationController().checkTask(ChannelHistoryTasks.GROUP, task);
        } catch (RuntimeException e) {
            // Hiding is only a convenience: show the action and let the server decide, as the engine's
            // default controller does.
            return true;
        }
    }

    /** Whether a failed request was refused for want of a permission. */
    static boolean isRefusal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ForbiddenException) {
                return true;
            }
        }
        return false;
    }

    /** A refused request as the given sentence, anything else as the engine's error dialog. */
    static void alertFailure(Component parent, Throwable e, String refusal) {
        if (isRefusal(e)) {
            PlatformUI.MIRTH_FRAME.alertWarning(parent, refusal);
        } else {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }
}
