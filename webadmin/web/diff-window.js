/*
 * The diff / view windows — the web port of DecomposedDiffWindow + DiffWindow +
 * ComponentTreePanel + SimpleDiffPanel. Side-by-side text diff is rendered by the
 * host's Monaco (platform.createDiffEditor); the semantic component tree,
 * change-dots, reorder labels, and Show-Changed-Only / Show-Labels toggles are
 * ported here. Channels get the decomposed view (with a "Show Raw XML" toggle);
 * code templates and unparseable channels fall back to a raw side-by-side diff.
 */

import {
    decomposeChannel, computeChangeTypes, computeReorderedSubGroups, buildTree, countChanges,
    CHANGE, CHANGE_COLOR, CHANGE_LABEL
} from './schi-core.js';

// Scripts diff as JavaScript; everything else as XML/text.
function languageFor(key) {
    if (/\/Script$/.test(key) || /Channel Scripts\//.test(key)) return 'javascript';
    if (key === 'Destination Order') return 'plaintext';
    return 'xml';
}

function dot(h, changeType) {
    return h('span', { style: `display:inline-block; width:7px; height:7px; border-radius:50%; margin-right:7px; flex:none; background:${CHANGE_COLOR[changeType]}` });
}

function nodeLabel(h, text, changeType, showLabels) {
    const changed = changeType !== CHANGE.UNCHANGED;
    const color = changed ? `color:${CHANGE_COLOR[changeType]}; font-weight:600;` : '';
    return h('span', { style: color }, text + (showLabels ? CHANGE_LABEL[changeType] : ''));
}

/* Renders the component tree with collapsible groups (Swing uses a JTree, auto-
   expanded). Group rows carry a twisty and start expanded; clicking a group row
   toggles its children. Returns { el, select(key), hasKey(key) }. */
function renderTree(h, treeData, { showLabels, onSelect }) {
    const el = h('div', { style: 'flex:1; min-height:0; overflow:auto; padding:4px 0' });
    const rowByKey = new Map();
    let selected = null;
    const select = (key) => {
        if (selected && rowByKey.get(selected)) rowByKey.get(selected).style.background = '';
        selected = key;
        const row = rowByKey.get(key);
        if (row) row.style.background = 'var(--sel, rgba(120,150,255,0.18))';
        onSelect(key);
    };
    const walk = (nodes, depth, container) => {
        for (const node of nodes) {
            const pad = 8 + depth * 15;
            if (node.type === 'group') {
                const kids = h('div');
                let expanded = true;
                const twisty = h('span', { style: 'display:inline-block; width:12px; text-align:center; cursor:pointer; user-select:none; color:var(--text-dim)' }, '▾');
                const row = h('div', {
                    style: `display:flex; align-items:center; padding:2px 6px 2px ${pad}px; cursor:pointer`,
                    onClick: () => { expanded = !expanded; twisty.textContent = expanded ? '▾' : '▸'; kids.style.display = expanded ? '' : 'none'; }
                }, twisty, dot(h, node.changeType), nodeLabel(h, node.label, node.changeType, showLabels));
                container.appendChild(row);
                container.appendChild(kids);
                walk(node.children, depth + 1, kids);
            } else {
                // +12px so leaf rows align past the group twisty column.
                const row = h('div', {
                    style: `display:flex; align-items:center; padding:2px 6px 2px ${pad + 12}px; cursor:pointer; border-radius:4px`,
                    onmouseenter: (e) => { if (node.key !== selected) e.currentTarget.style.background = 'var(--hover, rgba(128,128,128,0.10))'; },
                    onmouseleave: (e) => { if (node.key !== selected) e.currentTarget.style.background = ''; },
                    onClick: () => select(node.key)
                }, dot(h, node.changeType), nodeLabel(h, node.label, node.changeType, showLabels));
                rowByKey.set(node.key, row);
                container.appendChild(row);
            }
        }
    };
    walk(treeData, 0, el);
    return { el, select, hasKey: (k) => rowByKey.has(k) };
}

/* Two bold, centered revision labels sitting directly over the two diff panes
   (Old | New), matching Swing's GridLayout(1,2) header above the diff. */
function paneHeader(h, oldLabel, newLabel) {
    const cell = (text) => h('div', { style: 'flex:1 1 50%; min-width:0; text-align:center; font-weight:600; font-size:14px; padding:4px 8px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap' }, text);
    return h('div', { style: 'display:flex; flex:none' }, cell(oldLabel), cell(newLabel));
}

/* Decomposed diff between two channel revisions, with a raw-XML toggle. */
export function openDiff(platform, { title, oldLabel, newLabel, oldXml, newXml, decompose }) {
    const { h, modal } = platform.ui;

    let model = null;   // decomposition + change data, when decompose succeeds
    if (decompose) {
        try {
            const left = decomposeChannel(oldXml);
            const right = decomposeChannel(newXml);
            const changeTypes = computeChangeTypes(left.components, right.components);
            const reordered = computeReorderedSubGroups(left.components, right.components);
            const groupNames = new Map([...left.groupDisplayNames, ...right.groupDisplayNames]);
            model = { left, right, changeTypes, reordered, groupNames };
        } catch { model = null; }
    }

    // Top bar with the Raw-XML toggle, centered (Swing places it at the TOP of the
    // dialog, not in the footer). Populated below only when a decomposed view exists.
    const topBar = h('div', { style: 'display:flex; justify-content:center; padding:0 0 8px 0; flex:none' });

    const contentArea = h('div', { style: 'flex:1; min-height:0; display:flex; flex-direction:column' });
    const body = h('div', { style: 'display:flex; flex-direction:column; height:calc(100vh - 230px); min-height:360px; width:100%' },
        topBar, contentArea);

    let componentDiff = null;       // the reusable component diff editor (right pane)
    let rawDiff = null;             // lazily created on first raw toggle (decomposed view)
    const editors = [];
    let decomposedSplitEl = null;   // the tree|diff split, captured for the raw toggle

    if (!model) {
        // Raw-only (code templates, or a channel we couldn't decompose).
        rawDiff = platform.createDiffEditor({ original: oldXml, modified: newXml, language: 'xml' });
        editors.push(rawDiff);
        rawDiff.el.style.cssText = 'flex:1; min-height:0; border:1px solid var(--line); border-radius:6px; overflow:hidden';
        contentArea.appendChild(h('div', { style: 'flex:1; min-height:0; display:flex; flex-direction:column' },
            paneHeader(h, oldLabel, newLabel), rawDiff.el));
        setTimeout(() => rawDiff.layout(), 50);
    } else {
        componentDiff = platform.createDiffEditor({ language: 'xml' });
        editors.push(componentDiff);
        buildDecomposedView();
    }

    function buildDecomposedView() {
        const { left, right, changeTypes, reordered, groupNames } = model;
        const { changed, total } = countChanges(changeTypes);

        // Left: summary + tree + checkboxes. Right: the component diff.
        const summary = h('div', { style: 'padding:2px 8px 6px; font-size:12px; color:var(--text-dim)' },
            `${changed} of ${total} components changed`);
        const treeHost = h('div', { style: 'flex:1; min-height:0; display:flex; flex-direction:column' });

        let showChangedOnly = false;
        let showLabels = true;
        let currentKey = null;

        const showComponent = (key) => {
            currentKey = key;
            const l = left.components.get(key);
            const r = right.components.get(key);
            componentDiff.setModels({
                original: l ? l.content : '',
                modified: r ? r.content : '',
                language: languageFor(key)
            });
            setTimeout(() => componentDiff.layout(), 30);
        };

        const rebuild = () => {
            const { tree, firstChanged } = buildTree(left.components, right.components, groupNames,
                changeTypes, reordered, { changedOnly: showChangedOnly });
            const rendered = renderTree(h, tree, { showLabels, onSelect: showComponent });
            treeHost.replaceChildren(rendered.el);
            // Keep the current selection if still visible, else auto-select first changed.
            const target = (currentKey && rendered.hasKey(currentKey)) ? currentKey : firstChanged;
            if (target) rendered.select(target);
            else componentDiff.setModels({ original: '', modified: '', language: 'xml' });
        };

        const changedOnlyCb = h('label', { style: 'display:inline-flex; align-items:center; gap:5px; font-size:12px; cursor:pointer' },
            h('input', { type: 'checkbox', onChange: (e) => { showChangedOnly = e.target.checked; rebuild(); } }), 'Show Changed Only');
        const labelsCb = h('label', { style: 'display:inline-flex; align-items:center; gap:5px; font-size:12px; cursor:pointer' },
            h('input', { type: 'checkbox', checked: true, onChange: (e) => { showLabels = e.target.checked; rebuild(); } }), 'Show Labels');
        const checks = h('div', { style: 'display:flex; gap:14px; padding:6px 8px; border-top:1px solid var(--line)' }, changedOnlyCb, labelsCb);

        const leftPane = h('div', { style: 'width:280px; flex:none; display:flex; flex-direction:column; min-height:0; border:1px solid var(--line); border-radius:6px; overflow:hidden' },
            summary, treeHost, checks);
        componentDiff.el.style.cssText = 'flex:1; min-height:0; border:1px solid var(--line); border-radius:6px; overflow:hidden';
        // Right pane: the Old|New headers sitting directly over the two diff panes.
        const rightPane = h('div', { style: 'flex:1; min-height:0; display:flex; flex-direction:column' },
            paneHeader(h, oldLabel, newLabel), componentDiff.el);
        const split = h('div', { style: 'flex:1; min-height:0; display:flex; gap:10px' }, leftPane, rightPane);
        decomposedSplitEl = split;
        contentArea.replaceChildren(split);
        rebuild();
        setTimeout(() => componentDiff.layout(), 50);
    }

    // Raw ⇄ Component toggle in the TOP bar (Swing's NORTH button), only when a
    // decomposed view exists.
    const buttons = [{ label: 'Close', onClick: () => true }];
    let showingRaw = false;
    const rawContainer = h('div', { style: 'flex:1; min-height:0; display:none; flex-direction:column' });
    let toggleBtn = null;
    if (model) {
        contentArea.appendChild(rawContainer);
        toggleBtn = h('button.btn', { onClick: () => toggleRaw() }, 'Show Raw XML');
        topBar.appendChild(toggleBtn);
    }

    function toggleRaw() {
        showingRaw = !showingRaw;
        if (showingRaw) {
            if (!rawDiff) {
                rawDiff = platform.createDiffEditor({ original: oldXml, modified: newXml, language: 'xml' });
                editors.push(rawDiff);
                rawDiff.el.style.cssText = 'flex:1; min-height:0; border:1px solid var(--line); border-radius:6px; overflow:hidden';
                rawContainer.appendChild(paneHeader(h, oldLabel, newLabel));
                rawContainer.appendChild(rawDiff.el);
            }
            if (decomposedSplitEl) decomposedSplitEl.style.display = 'none';
            rawContainer.style.display = 'flex';
            relabelToggle('Show Component View');
            setTimeout(() => rawDiff.layout(), 50);
        } else {
            rawContainer.style.display = 'none';
            if (decomposedSplitEl) decomposedSplitEl.style.display = 'flex';
            relabelToggle('Show Raw XML');
            setTimeout(() => componentDiff.layout(), 50);
        }
    }
    function relabelToggle(text) {
        if (toggleBtn) toggleBtn.textContent = text;
    }

    const handle = modal({
        title, body, size: 'xwide', buttons,
        onClose: () => editors.forEach((e) => e.dispose())
    });
    // Widen well past the default xwide cap — the side-by-side XML panes need the
    // real estate. Near-full-screen, then relayout Monaco to fill the new width.
    handle.el.style.maxWidth = 'none';
    handle.el.style.width = 'min(1900px, calc(100vw - 48px))';
    editors.forEach((e) => setTimeout(() => e.layout(), 60));
    return handle;
}

/* Single-revision viewer (deleted-item "View XML"): decomposed tree navigation
   for channels (raw toggle), or a plain read-only editor for code templates. */
export function openView(platform, { title, xml, decompose }) {
    const { h, modal } = platform.ui;
    const editor = platform.createCodeEditor({ value: xml, language: 'xml', readOnly: true });
    editor.el.style.cssText = 'flex:1; min-height:0; border:1px solid var(--line); border-radius:6px; overflow:hidden';

    const contentArea = h('div', { style: 'flex:1; min-height:0; display:flex; gap:10px' });
    const body = h('div', { style: 'display:flex; flex-direction:column; height:calc(100vh - 220px); min-height:340px; width:100%' }, contentArea);

    let model = null;
    if (decompose) {
        try {
            const dc = decomposeChannel(xml);
            model = dc;
        } catch { model = null; }
    }

    const buttons = [{ label: 'Close', onClick: () => true }];
    if (model) {
        // Tree with everything UNCHANGED (single version); selecting a component
        // shows its content; a raw toggle restores the full XML.
        const changeTypes = new Map([...model.components.keys()].map((k) => [k, CHANGE.UNCHANGED]));
        const { tree } = buildTree(model.components, model.components, model.groupDisplayNames, changeTypes, new Set(), {});
        const rendered = renderTree(h, tree, {
            showLabels: false,
            onSelect: (key) => { const c = model.components.get(key); if (c) editor.setValue(c.content); }
        });
        const leftPane = h('div', { style: 'width:300px; flex:none; display:flex; flex-direction:column; min-height:0; border:1px solid var(--line); border-radius:6px; overflow:hidden' }, rendered.el);
        contentArea.append(leftPane, editor.el);
        let raw = false;
        buttons.unshift({
            label: 'Show Raw XML',
            onClick: () => { raw = !raw; if (raw) editor.setValue(xml); toggleViewLabel(raw ? 'Show Component View' : 'Show Raw XML'); return false; }
        });
    } else {
        contentArea.appendChild(editor.el);
    }

    function toggleViewLabel(text) {
        const btn = handle.el.querySelector('.modal-foot .btn');
        if (btn) btn.textContent = text;
    }

    const handle = modal({ title, body, size: 'xwide', buttons, onClose: () => editor.dispose() });
    handle.el.style.maxWidth = 'none';
    handle.el.style.width = 'min(1900px, calc(100vw - 48px))';
    setTimeout(() => editor.layout && editor.layout(), 60);
    return handle;
}
