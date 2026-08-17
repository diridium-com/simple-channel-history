/*
 * Framework-agnostic core for the Simple Channel History web UI: the REST client
 * bindings, time formatting, and the channel-XML decomposition + change detection
 * ported 1:1 from the Swing client's ChannelXmlDecomposer / ComponentTreePanel.
 *
 * No DOM/React here — just data. The diff/history/deleted-items UI modules build
 * on top of this. The engine plugin serves raw Channel/CodeTemplate XML per
 * revision (diff/decompose is entirely client-side, exactly as in Swing).
 */

const EXT = '/extensions/simple-channel-history';

/* ---- REST client (the 14 servlet endpoints) -------------------------------- */

export function makeApi(api) {
    // Lists come back XStream-wrapped; asList unwraps { list: { <FQCN>: [...] } }.
    const list = (v) => api.asList(v, 'com.diridium.RevisionInfo').length
        ? api.asList(v, 'com.diridium.RevisionInfo') : api.asList(v, 'revisionInfo');
    const dlist = (v) => api.asList(v, 'com.diridium.DeletedItemInfo').length
        ? api.asList(v, 'com.diridium.DeletedItemInfo') : api.asList(v, 'deletedItemInfo');
    return {
        // Channels
        getHistory: async (channelId) => list(await api.get(`${EXT}/history`, { channelId })),
        getContent: (channelId, revision) => api.getXml(`${EXT}/content`, { channelId, revision }),
        revertChannel: (channelId, revision) => api.post(`${EXT}/revertChannel`, null, { params: { channelId, revision } }),
        pruneChannelHistory: (channelId, revision) => api.post(`${EXT}/pruneChannelHistory`, null, { params: { channelId, revision }, raw: true }),
        // Code templates
        getCodeTemplateHistory: async (codeTemplateId) => list(await api.get(`${EXT}/codeTemplateHistory`, { codeTemplateId })),
        getCodeTemplateContent: (codeTemplateId, revision) => api.getXml(`${EXT}/codeTemplateContent`, { codeTemplateId, revision }),
        revertCodeTemplate: (codeTemplateId, revision) => api.post(`${EXT}/revertCodeTemplate`, null, { params: { codeTemplateId, revision } }),
        pruneCodeTemplateHistory: (codeTemplateId, revision) => api.post(`${EXT}/pruneCodeTemplateHistory`, null, { params: { codeTemplateId, revision }, raw: true }),
        // Deleted channels
        getDeletedChannels: async () => dlist(await api.get(`${EXT}/deletedChannels`)),
        getDeletedChannelContent: (id) => api.getXml(`${EXT}/deletedChannelContent`, { id }),
        purgeDeletedChannel: (id) => api.post(`${EXT}/purgeDeletedChannel`, null, { params: { id }, raw: true }),
        // Deleted code templates
        getDeletedCodeTemplates: async () => dlist(await api.get(`${EXT}/deletedCodeTemplates`)),
        getDeletedCodeTemplateContent: (id) => api.getXml(`${EXT}/deletedCodeTemplateContent`, { id }),
        purgeDeletedCodeTemplate: (id) => api.post(`${EXT}/purgeDeletedCodeTemplate`, null, { params: { id }, raw: true })
    };
}

/* ---- time formatting (RevisionInfoTableModel.formatTime, 1:1) -------------- */

function pad(n) { return String(n).padStart(2, '0'); }

export function formatDateTime(ms) {
    const d = new Date(Number(ms) || 0);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
        + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function formatRevisionTime(ms) {
    const t = Number(ms) || 0;
    const elapsed = Date.now() - t;
    if (elapsed < 0) return formatDateTime(t);
    const seconds = Math.floor(elapsed / 1000);
    const minutes = Math.floor(seconds / 60);
    if (minutes >= 60) return formatDateTime(t);
    if (minutes > 0) return `${minutes} minutes ago`;
    return `${seconds} seconds ago`;
}

/* ---- change types + colors (ComponentTreePanel) ---------------------------- */

export const CHANGE = { UNCHANGED: 'UNCHANGED', MODIFIED: 'MODIFIED', LEFT_ONLY: 'LEFT_ONLY', RIGHT_ONLY: 'RIGHT_ONLY' };

// Same RGB the Swing tree uses for the 7px change dots / bold labels.
export const CHANGE_COLOR = {
    MODIFIED: 'rgb(200,130,0)',
    LEFT_ONLY: 'rgb(200,50,50)',
    RIGHT_ONLY: 'rgb(50,140,50)',
    UNCHANGED: 'gray'
};
export const CHANGE_LABEL = { LEFT_ONLY: ' (removed)', RIGHT_ONLY: ' (added)', MODIFIED: ' (changed)', UNCHANGED: '' };

/* ---- channel XML decomposition (ChannelXmlDecomposer, 1:1) ----------------- */

function directChildren(el, name) {
    const out = [];
    for (const n of el.childNodes) if (n.nodeType === 1 && (name == null || n.tagName === name)) out.push(n);
    return out;
}
function directChild(el, name) { return directChildren(el, name)[0] || null; }
function directChildText(el, name) { const c = directChild(el, name); return c ? c.textContent : null; }

function stepTypeName(tag) {
    const i = tag.lastIndexOf('.');
    return (i >= 0 && i < tag.length - 1) ? tag.substring(i + 1) : tag;
}

function escText(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function escAttr(s) { return escText(s).replace(/"/g, '&quot;'); }

// Pretty-print a DOM element with 2-space indentation. Both diff sides go through
// this same serializer, so equal sub-trees produce equal strings (which is all the
// change detection needs); it need not be byte-identical to the engine's output.
function prettyNode(el, indent = '') {
    const attrs = [...el.attributes].map((a) => ` ${a.name}="${escAttr(a.value)}"`).join('');
    const kids = directChildren(el);
    if (kids.length === 0) {
        const text = el.textContent;
        if (text == null || text.trim() === '') return `${indent}<${el.tagName}${attrs}/>`;
        return `${indent}<${el.tagName}${attrs}>${escText(text)}</${el.tagName}>`;
    }
    const inner = kids.map((k) => prettyNode(k, indent + '  ')).join('\n');
    return `${indent}<${el.tagName}${attrs}>\n${inner}\n${indent}</${el.tagName}>`;
}

function put(components, key, displayName, content, category, parentGroup) {
    components.set(key, { key, displayName, content, category, parentGroup });
}

function extractSteps(components, connectorEl, connectorGroup, elementName, displayName, category) {
    const sub = directChild(connectorEl, elementName);
    if (!sub) return;
    const elements = directChild(sub, 'elements');
    if (!elements) return;
    const steps = directChildren(elements);
    if (!steps.length) return;
    const subGroupKey = `${connectorGroup}/${displayName}`;
    steps.forEach((step, i) => {
        const seqText = directChildText(step, 'sequenceNumber');
        const seq = seqText != null ? seqText : String(i);
        let name = directChildText(step, 'name');
        if (!name) name = stepTypeName(step.tagName);
        put(components, `${subGroupKey}/Step ${seq}`, `Step ${seq}: ${name}`, prettyNode(step), category, subGroupKey);
        elements.removeChild(step);
    });
}

function extractConnector(components, connectorEl, group) {
    const props = directChild(connectorEl, 'properties');
    if (props) {
        const script = directChild(props, 'script');
        if (script) { put(components, `${group}/Script`, 'Script', script.textContent, 'CONNECTOR_SCRIPT', group); props.removeChild(script); }
        const pluginProps = directChild(props, 'pluginProperties');
        if (pluginProps) {
            for (const plugin of directChildren(pluginProps)) {
                const pname = stepTypeName(plugin.tagName);
                put(components, `${group}/Plugin: ${pname}`, `Plugin: ${pname}`, prettyNode(plugin), 'CONNECTOR_PLUGIN', group);
            }
            for (const plugin of directChildren(pluginProps)) pluginProps.removeChild(plugin);
        }
    }
    extractSteps(components, connectorEl, group, 'filter', 'Filter', 'FILTER');
    extractSteps(components, connectorEl, group, 'transformer', 'Transformer', 'TRANSFORMER');
    extractSteps(components, connectorEl, group, 'responseTransformer', 'Response Transformer', 'RESPONSE_TRANSFORMER');
}

/* Returns { components: Map<key, comp>, groupDisplayNames: Map<group,name> } or
   throws on unparseable XML (callers fall back to a raw diff, like Swing). */
export function decomposeChannel(channelXml) {
    const doc = new DOMParser().parseFromString(channelXml, 'text/xml');
    if (doc.querySelector('parsererror') || !doc.documentElement) throw new Error('unparseable channel XML');
    const root = doc.documentElement;
    const components = new Map();
    const groupDisplayNames = new Map();

    // Channel-level scripts
    for (const [tag, name] of [['preprocessingScript', 'Preprocessing Script'], ['postprocessingScript', 'Postprocessing Script'],
        ['deployScript', 'Deploy Script'], ['undeployScript', 'Undeploy Script']]) {
        const node = directChild(root, tag);
        if (node) { put(components, `Channel Scripts/${name}`, name, node.textContent, 'CHANNEL_SCRIPT', 'Channel Scripts'); root.removeChild(node); }
    }

    // Source connector
    const source = directChild(root, 'sourceConnector');
    if (source) {
        extractConnector(components, source, 'Source Connector');
        put(components, 'Source Connector/Configuration', 'Configuration', prettyNode(source), 'CONNECTOR_CONFIGURATION', 'Source Connector');
        root.removeChild(source);
    }

    // Destination connectors
    const destWrapper = directChild(root, 'destinationConnectors');
    const destOrder = [];
    if (destWrapper) {
        const connectors = directChildren(destWrapper, 'connector');
        connectors.forEach((connector, i) => {
            const connName = directChildText(connector, 'name');
            const metaDataId = directChildText(connector, 'metaDataId');
            const group = `Destination [${metaDataId}]`;
            groupDisplayNames.set(group, `Destination: ${connName} [${metaDataId}]`);
            destOrder.push(`${i + 1}. ${connName} [${metaDataId}]`);
            extractConnector(components, connector, group);
        });
        for (const connector of connectors) {
            const metaDataId = directChildText(connector, 'metaDataId');
            const group = `Destination [${metaDataId}]`;
            put(components, `${group}/Configuration`, 'Configuration', prettyNode(connector), 'CONNECTOR_CONFIGURATION', group);
        }
        root.removeChild(destWrapper);
    }

    // Channel Properties = the remainder; ordered first, then Destination Order, then the rest.
    const ordered = new Map();
    put(ordered, 'Channel Properties', 'Channel Properties', prettyNode(root), 'CHANNEL_PROPERTIES', 'Channel Properties');
    if (destOrder.length) put(ordered, 'Destination Order', 'Destination Order', destOrder.join('\n'), 'CHANNEL_PROPERTIES', 'Destination Order');
    for (const [k, v] of components) ordered.set(k, v);
    return { components: ordered, groupDisplayNames };
}

/* ---- change detection + tree build (ComponentTreePanel, 1:1) --------------- */

export function computeChangeTypes(left, right) {
    const types = new Map();
    const all = new Set([...left.keys(), ...right.keys()]);
    for (const key of all) {
        const l = left.get(key);
        const r = right.get(key);
        if (!l) types.set(key, CHANGE.RIGHT_ONLY);
        else if (!r) types.set(key, CHANGE.LEFT_ONLY);
        else if (l.content !== r.content) types.set(key, CHANGE.MODIFIED);
        else types.set(key, CHANGE.UNCHANGED);
    }
    return types;
}

const POSITIONAL_RE = /<sequenceNumber>\d+<\/sequenceNumber>|<operator>[^<]*<\/operator>/g;
function stripPositional(content) { return content.replace(POSITIONAL_RE, '').replace(/\s+/g, ' ').trim(); }

export function computeReorderedSubGroups(left, right) {
    const result = new Set();
    const bucket = (map) => {
        const m = new Map();
        for (const comp of map.values()) {
            if (comp.parentGroup.includes('/')) {
                if (!m.has(comp.parentGroup)) m.set(comp.parentGroup, []);
                m.get(comp.parentGroup).push(stripPositional(comp.content));
            }
        }
        return m;
    };
    const leftSub = bucket(left);
    const rightSub = bucket(right);
    for (const [sub, leftContents] of leftSub) {
        const rightContents = rightSub.get(sub);
        if (!rightContents || leftContents.length !== rightContents.length) continue;
        if (JSON.stringify(leftContents) === JSON.stringify(rightContents)) continue;
        const ls = [...leftContents].sort();
        const rs = [...rightContents].sort();
        if (JSON.stringify(ls) === JSON.stringify(rs)) result.add(sub);
    }
    return result;
}

function groupChangeType(changeTypes, keys) {
    let hasLeft = false, hasRight = false, hasMod = false, hasUnch = false;
    for (const key of keys) {
        switch (changeTypes.get(key) || CHANGE.UNCHANGED) {
            case CHANGE.LEFT_ONLY: hasLeft = true; break;
            case CHANGE.RIGHT_ONLY: hasRight = true; break;
            case CHANGE.MODIFIED: hasMod = true; break;
            default: hasUnch = true;
        }
    }
    if (hasLeft && !hasRight && !hasMod && !hasUnch) return CHANGE.LEFT_ONLY;
    if (hasRight && !hasLeft && !hasMod && !hasUnch) return CHANGE.RIGHT_ONLY;
    if (hasMod || hasLeft || hasRight) return CHANGE.MODIFIED;
    return CHANGE.UNCHANGED;
}

/* Builds the render tree (array of group nodes) honoring the Show-Changed-Only
   filter, sub-group nesting, and "(steps reordered)" labelling. Also returns the
   key of the first changed component (for auto-selection). */
export function buildTree(left, right, groupDisplayNames, changeTypes, reordered, { changedOnly = false } = {}) {
    const allKeys = [...new Set([...left.keys(), ...right.keys()])];

    // Group keys by parentGroup, preserving insertion order.
    const groups = new Map();
    for (const key of allKeys) {
        const comp = left.get(key) || right.get(key);
        if (!groups.has(comp.parentGroup)) groups.set(comp.parentGroup, []);
        groups.get(comp.parentGroup).push(key);
    }

    // Top-level vs sub-groups: a group is a sub-group if it starts with another group + "/".
    const groupNames = [...groups.keys()];
    const subGroupSet = new Set();
    const topToSub = new Map();
    for (const g of groupNames) for (const other of groupNames) {
        if (other !== g && g.startsWith(other + '/')) {
            subGroupSet.add(g);
            if (!topToSub.has(other)) topToSub.set(other, []);
            topToSub.get(other).push(g);
        }
    }

    const compNode = (key) => {
        const comp = left.get(key) || right.get(key);
        return { type: 'component', key, label: comp.displayName, changeType: changeTypes.get(key) || CHANGE.UNCHANGED };
    };

    const tree = [];
    let firstChanged = null;
    for (const groupName of groupNames) {
        if (subGroupSet.has(groupName)) continue;
        const subGroups = topToSub.get(groupName) || [];
        const allForGroup = [...(groups.get(groupName) || [])];
        for (const sg of subGroups) allForGroup.push(...(groups.get(sg) || []));
        const gType = groupChangeType(changeTypes, allForGroup);
        if (changedOnly && gType === CHANGE.UNCHANGED) continue;

        const children = [];
        for (const key of (groups.get(groupName) || [])) {
            const ct = changeTypes.get(key) || CHANGE.UNCHANGED;
            if (changedOnly && ct === CHANGE.UNCHANGED) continue;
            const node = compNode(key);
            children.push(node);
            if (!firstChanged && ct !== CHANGE.UNCHANGED) firstChanged = key;
        }
        for (const sg of subGroups) {
            const subKeys = groups.get(sg) || [];
            const sgType = groupChangeType(changeTypes, subKeys);
            if (changedOnly && sgType === CHANGE.UNCHANGED) continue;
            let subLabel = sg.substring(groupName.length + 1);
            if (reordered.has(sg)) subLabel += ' (steps reordered)';
            const subChildren = [];
            for (const key of subKeys) {
                const ct = changeTypes.get(key) || CHANGE.UNCHANGED;
                if (changedOnly && ct === CHANGE.UNCHANGED) continue;
                const node = compNode(key);
                subChildren.push(node);
                if (!firstChanged && ct !== CHANGE.UNCHANGED) firstChanged = key;
            }
            if (subChildren.length) children.push({ type: 'group', label: subLabel, changeType: sgType, children: subChildren });
        }
        if (children.length) tree.push({ type: 'group', label: groupDisplayNames.get(groupName) || groupName, changeType: gType, children });
    }
    return { tree, firstChanged };
}

export function countChanges(changeTypes) {
    let changed = 0;
    for (const t of changeTypes.values()) if (t !== CHANGE.UNCHANGED) changed++;
    return { changed, total: changeTypes.size };
}
