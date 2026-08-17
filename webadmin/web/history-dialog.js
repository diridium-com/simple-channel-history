/*
 * Version History dialog — the web port of ChannelHistoryDialog /
 * CodeTemplateHistoryDialog. A revision table (Revision / User / Date), with:
 *   - double-click a row  → compare with the previous (older) revision
 *   - select two rows     → Show Diff
 *   - select one (non-latest) row → Revert to Selected
 *   - right-click a row   → Prune older revisions
 * Channels get the decomposed diff; code templates get a raw side-by-side diff.
 */

import { formatRevisionTime, formatDateTime } from './schi-core.js';
import { openDiff } from './diff-window.js';

function shortHash(rev) {
    if (rev.hash != null && /^\d+$/.test(String(rev.hash))) return `Rev ${rev.revision}`;
    const hs = rev.hash != null ? String(rev.hash) : '';
    return hs.length >= 8 ? hs.substring(0, 8) : hs;
}

// Diff header label: "Old - Rev 3 (user: admin, time: 2026-01-02 14:23)".
function diffLabel(side, rev) {
    const ts = formatDateTime(rev.time).slice(0, 16);   // yyyy-MM-dd HH:mm
    return `${side} - ${shortHash(rev)} (user: ${rev.committerName || 'Unknown'}, time: ${ts})`;
}

export function openHistory(platform, api, { kind, id, name }) {
    const { h, modal, toast, confirmDialog } = platform.ui;
    const isChannel = kind === 'channel';
    const noun = isChannel ? 'channel' : 'code template';

    const ops = isChannel
        ? {
            history: () => api.getHistory(id),
            content: (rev) => api.getContent(id, rev),
            revert: (rev) => api.revertChannel(id, rev),
            prune: (rev) => api.pruneChannelHistory(id, rev)
        }
        : {
            history: () => api.getCodeTemplateHistory(id),
            content: (rev) => api.getCodeTemplateContent(id, rev),
            revert: (rev) => api.revertCodeTemplate(id, rev),
            prune: (rev) => api.pruneCodeTemplateHistory(id, rev)
        };

    let rows = [];   // newest-first (as the server returns)

    const table = new platform.ui.DataTable([
        // Sorting disabled (Swing RevisionInfoTable.setSortable(false)) — the list
        // stays newest-first as the server returns it.
        { key: 'revision', label: 'Revision', width: 120, sortable: false, render: (r) => shortHash(r) },
        { key: 'committerName', label: 'User', sortable: false, render: (r) => r.committerName || 'Unknown' },
        { key: 'time', label: 'Date', sortable: false, render: (r) => formatRevisionTime(r.time) }
    ], {
        selectable: 'multi',
        rowKey: (r) => String(r.hash),
        emptyText: 'No history for this item yet.',
        onSelect: () => updateButtons(),
        onActivate: (r) => compareWithPrevious(r),
        onContextMenu: (r, e) => rowMenu(r, e)
    });
    table.el.style.cssText = 'flex:1; min-height:0; overflow:auto; border:1px solid var(--line); border-radius:6px';

    const help = h('div', { style: 'font-size:11.5px; color:var(--text-dim); line-height:1.6; padding:2px 2px 8px' },
        h('div', 'Double-click a revision to compare with previous'),
        h('div', 'Ctrl/Cmd-click to select two revisions, then Show Diff'),
        h('div', 'Right-click for prune option'));

    const showDiffBtn = h('button.btn', { onClick: () => showDiffSelected() }, 'Show Diff');
    const revertBtn = h('button.btn', { onClick: () => revertSelected() }, 'Revert to Selected');
    const buttonBar = h('div', { style: 'display:flex; gap:8px; justify-content:flex-end; padding-top:10px' }, showDiffBtn, revertBtn);

    const body = h('div', { style: 'display:flex; flex-direction:column; width:min(680px, calc(100vw - 60px)); height:min(440px, calc(100vh - 160px))' },
        table.el, help, buttonBar);

    function isLatest(rev) { return rows.length > 0 && String(rows[0].hash) === String(rev.hash); }
    function olderThan(rev) { return rows.filter((r) => r.time < rev.time || (r.time === rev.time && r.revision < rev.revision)); }

    function updateButtons() {
        const sel = table.selectedRows();
        showDiffBtn.disabled = sel.length !== 2;
        revertBtn.disabled = !(sel.length === 1 && !isLatest(sel[0]));
    }

    async function reload() {
        try {
            rows = await ops.history();
        } catch (e) {
            toast(e.message || 'Failed to load history.', 'error');
            rows = [];
        }
        table.setRows(rows);
        updateButtons();
    }

    // old = the earlier revision, new = the later one.
    async function showDiff(a, b) {
        const [older, newer] = a.time <= b.time ? [a, b] : [b, a];
        try {
            const [oldXml, newXml] = await Promise.all([ops.content(older.hash), ops.content(newer.hash)]);
            openDiff(platform, {
                title: `${isChannel ? 'Channel' : 'Code Template'} Diff - ${name}`,
                oldLabel: diffLabel('Old', older),
                newLabel: diffLabel('New', newer),
                oldXml, newXml,
                decompose: isChannel
            });
        } catch (e) {
            toast(e.message || 'Failed to load revision content.', 'error');
        }
    }

    function showDiffSelected() {
        const sel = table.selectedRows();
        if (sel.length === 2) showDiff(sel[0], sel[1]);
    }

    function compareWithPrevious(rev) {
        const older = olderThan(rev);
        if (!older.length) { toast('No previous revision to compare to.', 'info'); return; }
        // Nearest older revision = the newest among those older than this one.
        const prev = older.reduce((best, r) => (r.time > best.time ? r : best));
        showDiff(prev, rev);
    }

    async function revertSelected() {
        const sel = table.selectedRows();
        if (sel.length !== 1 || isLatest(sel[0])) return;
        const rev = sel[0];
        const ok = await confirmDialog('Confirm Revert',
            `Are you sure you want to revert to revision ${shortHash(rev)}?\nThis will overwrite the current ${noun}.`,
            { danger: true, okLabel: 'Revert' });
        if (!ok) return;
        try {
            const result = await ops.revert(rev.hash);
            if (result === false || result === 'false') { toast('Revert failed.', 'error'); return; }
            toast(`${isChannel ? 'Channel' : 'Code template'} reverted successfully.`, 'success');
            platform.events.emit(isChannel ? 'channels:changed' : 'codeTemplates:changed', { id });
            reload();
        } catch (e) {
            toast(e.message || 'Revert failed.', 'error');
        }
    }

    function rowMenu(rev, e) {
        const older = olderThan(rev);
        platform.ui.contextMenu(e.clientX, e.clientY, [
            {
                label: 'Prune older revisions', icon: 'trash', danger: true,
                onClick: () => pruneOlder(rev, older.length)
            }
        ]);
    }

    async function pruneOlder(rev, count) {
        if (!count) { toast('No older revisions to delete.', 'info'); return; }
        const ok = await confirmDialog('Confirm Prune',
            `Delete ${count} revision(s) older than ${shortHash(rev)}?\nThis action cannot be undone.`,
            { danger: true, okLabel: 'Delete' });
        if (!ok) return;
        try {
            const deleted = await ops.prune(rev.hash);
            const n = parseInt(deleted, 10);
            toast(`Deleted ${Number.isNaN(n) ? count : n} older revision(s).`, 'success');
            reload();
        } catch (e) {
            toast(e.message || 'Prune failed.', 'error');
        }
    }

    updateButtons();
    const handle = modal({ title: `Version History - ${name}`, body, size: 'wide', buttons: [{ label: 'Close', onClick: () => true }] });
    reload();
    return handle;
}
