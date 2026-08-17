/*
 * Simple Channel History — web administrator entry.
 *
 * Registers, against the host's extension points (the web equivalents of the
 * Swing ChannelHistoryPlugin / CodeTemplateHistoryPlugin / DeletedItemsSettingsPanelPlugin):
 *   - a "View History" channel action        (platform.registerChannelAction)
 *   - a "View History" code-template action   (platform.registerCodeTemplateAction)
 *   - a "Channel History: Deleted Items" tab   (platform.registerSettingsPanel)
 *
 * All UI is client-side; it talks only to the existing engine servlet at
 * /api/extensions/simple-channel-history (history, content, revert, prune,
 * deleted-items). Diff/decompose is computed in the browser, exactly as in Swing.
 */

import { makeApi } from './schi-core.js';
import { openHistory } from './history-dialog.js';
import { registerDeletedItems } from './deleted-items.jsx';

export function register(platform) {
    const api = makeApi(platform.api);

    platform.registerChannelAction({
        id: 'schi.channelHistory',
        label: 'View History',
        icon: 'clock',
        order: 50,
        onInvoke: (channel) => {
            if (!channel || !channel.id) return;
            openHistory(platform, api, { kind: 'channel', id: channel.id, name: channel.name || channel.id });
        }
    });

    platform.registerCodeTemplateAction({
        id: 'schi.codeTemplateHistory',
        label: 'View History',
        icon: 'clock',
        order: 50,
        onInvoke: (template) => {
            if (!template || !template.id) return;
            openHistory(platform, api, { kind: 'codeTemplate', id: template.id, name: template.name || template.id });
        }
    });

    registerDeletedItems(platform);
}
