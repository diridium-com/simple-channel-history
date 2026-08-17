/*
 * "Channel History: Deleted Items" settings tab — the web port of
 * DeletedItemsSettingsPanel. Lists the final XML snapshots captured when a
 * channel or code template was deleted, with a Show filter (All / Channels /
 * Code Templates), a live name search, and per-selection actions: View XML,
 * Show Diff (two same-type items), Download XML, and Purge.
 */

import { makeApi, formatDateTime } from './schi-core.js';
import { openView, openDiff } from './diff-window.js';

const TYPE_CHANNEL = 'Channel';
const TYPE_CODE_TEMPLATE = 'Code Template';

export function registerDeletedItems(platform) {
    const React = platform.React;
    const { toast, confirmDialog, saveFile, taskButton } = platform.ui;
    const api = makeApi(platform.api);

    const contentOf = (item) => item.type === TYPE_CHANNEL
        ? api.getDeletedChannelContent(item.id)
        : api.getDeletedCodeTemplateContent(item.id);

    function DeletedItemsPanel({ setTasks }) {
        const [items, setItems] = React.useState([]);
        const [loading, setLoading] = React.useState(true);
        const [error, setError] = React.useState(null);
        const [typeFilter, setTypeFilter] = React.useState('All');
        const [search, setSearch] = React.useState('');
        const [selected, setSelected] = React.useState(() => new Set());
        const [sort, setSort] = React.useState({ key: 'dateDeleted', dir: -1 });

        const load = React.useCallback(async () => {
            setLoading(true);
            try {
                const [chs, cts] = await Promise.all([api.getDeletedChannels(), api.getDeletedCodeTemplates()]);
                const merged = [
                    ...chs.map((x) => ({ ...x, type: TYPE_CHANNEL })),
                    ...cts.map((x) => ({ ...x, type: TYPE_CODE_TEMPLATE }))
                ];
                setItems(merged);
                setSelected(new Set());
                setError(null);
            } catch (e) {
                setError(e.message || 'Failed to load deleted items.');
                setItems([]);
            } finally {
                setLoading(false);
            }
        }, []);

        React.useEffect(() => {
            load();
            setTasks('Deleted Items Tasks', [taskButton('Refresh', 'refresh', () => load())]);
        }, [load, setTasks]);

        const visible = React.useMemo(() => {
            let list = items;
            if (typeFilter === 'Channels') list = list.filter((i) => i.type === TYPE_CHANNEL);
            else if (typeFilter === 'Code Templates') list = list.filter((i) => i.type === TYPE_CODE_TEMPLATE);
            const q = search.trim().toLowerCase();
            if (q) list = list.filter((i) => String(i.name || '').toLowerCase().includes(q));
            const col = sort.key;
            return [...list].sort((a, b) => {
                const va = a[col], vb = b[col];
                if (va === vb) return 0;
                if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * sort.dir;
                return String(va).localeCompare(String(vb)) * sort.dir;
            });
        }, [items, typeFilter, search, sort]);

        const selectedItems = visible.filter((i) => selected.has(i.id));
        const one = selectedItems.length === 1 ? selectedItems[0] : null;
        const two = selectedItems.length === 2 ? selectedItems : null;
        const sameTypePair = two && two[0].type === two[1].type ? two : null;

        const onRowClick = (item, e) => {
            setSelected((prev) => {
                const next = new Set(prev);
                if (e.metaKey || e.ctrlKey) { next.has(item.id) ? next.delete(item.id) : next.add(item.id); }
                else { next.clear(); next.add(item.id); }
                return next;
            });
        };

        const toggleSort = (key) => setSort((s) => (s.key === key ? { key, dir: -s.dir } : { key, dir: 1 }));

        async function viewXml(item) {
            try {
                const xml = await contentOf(item);
                openView(platform, { title: `Deleted ${item.type} - ${item.name}`, xml, decompose: item.type === TYPE_CHANNEL });
            } catch (e) { toast(e.message || 'Failed to load snapshot.', 'error'); }
        }

        async function showDiff() {
            if (!sameTypePair) return;
            const [older, newer] = sameTypePair[0].dateDeleted <= sameTypePair[1].dateDeleted
                ? [sameTypePair[0], sameTypePair[1]] : [sameTypePair[1], sameTypePair[0]];
            try {
                const [oldXml, newXml] = await Promise.all([contentOf(older), contentOf(newer)]);
                openDiff(platform, {
                    title: `Deleted ${older.type} Diff`,
                    oldLabel: `Old - ${older.name} (deleted ${formatDateTime(older.dateDeleted)})`,
                    newLabel: `New - ${newer.name} (deleted ${formatDateTime(newer.dateDeleted)})`,
                    oldXml, newXml,
                    decompose: older.type === TYPE_CHANNEL
                });
            } catch (e) { toast(e.message || 'Failed to load snapshots.', 'error'); }
        }

        function downloadXml(item) {
            // The picker must open inside the click gesture; saveFile fetches after.
            saveFile(`${item.name}.xml`, 'application/xml', () => contentOf(item))
                .then(() => toast(`Saved ${item.name}.xml`, 'success'))
                .catch((e) => toast(e.message || 'Download failed.', 'error'));
        }

        async function purge(item) {
            const ok = await confirmDialog('Confirm Purge',
                `Permanently delete the snapshot for "${item.name}"?\nThis action cannot be undone.`,
                { danger: true, okLabel: 'Purge' });
            if (!ok) return;
            try {
                const res = item.type === TYPE_CHANNEL
                    ? await api.purgeDeletedChannel(item.id)
                    : await api.purgeDeletedCodeTemplate(item.id);
                if (res === false || res === 'false') { toast('Purge failed.', 'error'); return; }
                toast(`Purged the snapshot for "${item.name}".`, 'success');
                load();
            } catch (e) { toast(e.message || 'Purge failed.', 'error'); }
        }

        const sortArrow = (key) => (sort.key === key ? (sort.dir > 0 ? ' ▲' : ' ▼') : '');
        const Th = ({ col, label, width }) => (
            <th className="sortable" style={width ? { width } : null} onClick={() => toggleSort(col)}>{label}{sortArrow(col)}</th>
        );

        return (
            <div className="p-4" style={{ display: 'flex', flexDirection: 'column', gap: 10, height: '100%', minHeight: 0 }}>
                <div className="text-text-dim" style={{ fontSize: 12 }}>
                    Final XML snapshots captured when a channel or code template was deleted. Select one to view,
                    download, or purge it; select two of the same type to compare them.
                </div>
                <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                    <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center', fontSize: 12 }}>
                        Show
                        <select value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)}>
                            <option>All</option>
                            <option>Channels</option>
                            <option>Code Templates</option>
                        </select>
                    </label>
                    <input type="text" placeholder="Filter by name…" value={search}
                        onChange={(e) => setSearch(e.target.value)} style={{ flex: '0 1 240px' }} />
                    <div style={{ flex: 1 }} />
                    <span className="text-text-faint" style={{ fontSize: 12 }}>{visible.length} item(s)</span>
                </div>

                {loading ? (
                    <div className="text-text-faint">Loading…</div>
                ) : error ? (
                    <div style={{ color: 'var(--err)' }}>{error}</div>
                ) : (
                    <div style={{ flex: 1, minHeight: 0, overflow: 'auto', border: '1px solid var(--line)', borderRadius: 6 }}>
                        <table className="dt" style={{ width: '100%' }}>
                            <thead>
                                <tr>
                                    <Th col="type" label="Type" width={120} />
                                    <Th col="name" label="Name" />
                                    <Th col="itemId" label="Item ID" width={240} />
                                    <Th col="deletedBy" label="Deleted By" width={130} />
                                    <Th col="dateDeleted" label="Date Deleted" width={170} />
                                </tr>
                            </thead>
                            <tbody>
                                {visible.length === 0 ? (
                                    <tr><td colSpan={5} className="text-text-faint" style={{ padding: 12 }}>No deleted items.</td></tr>
                                ) : visible.map((item) => (
                                    <tr key={item.id} className={selected.has(item.id) ? 'selected' : ''}
                                        style={{ cursor: 'pointer' }}
                                        onClick={(e) => onRowClick(item, e)}
                                        onDoubleClick={() => viewXml(item)}>
                                        <td>{item.type}</td>
                                        <td>{item.name}</td>
                                        <td className="mono" style={{ fontSize: 11 }}>{item.itemId}</td>
                                        <td>{item.deletedBy || 'Unknown'}</td>
                                        <td>{formatDateTime(item.dateDeleted)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}

                <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                    <button className="btn" disabled={!one} onClick={() => one && viewXml(one)}>View XML</button>
                    <button className="btn" disabled={!sameTypePair} onClick={() => showDiff()}>Show Diff</button>
                    <button className="btn" disabled={!one} onClick={() => one && downloadXml(one)}>Download XML</button>
                    <button className="btn btn-danger" disabled={!one} onClick={() => one && purge(one)}>Purge</button>
                </div>
            </div>
        );
    }

    platform.registerSettingsPanel({ label: 'Channel History: Deleted Items', component: DeletedItemsPanel });
}
