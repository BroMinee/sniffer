import type { CountReportEntry, FromWebview, ToWebview } from '../profilerWire';

declare function acquireVsCodeApi(): { postMessage(message: FromWebview): void };

const vscode = acquireVsCodeApi();

const title = document.getElementById('title')!;
const lastRefresh = document.getElementById('last-refresh')!;
const refreshBtn = document.getElementById('refresh')!;
const spinner = document.getElementById('spinner')!;
const empty = document.getElementById('empty')!;
const content = document.getElementById('content')!;
const columns = document.getElementById('columns')!;
const rows = document.getElementById('rows')!;
const modeFlatBtn = document.getElementById('mode-flat')!;
const modeTreeBtn = document.getElementById('mode-tree')!;

type Column = {
    key: string;
    label: string;
    value: (e: CountReportEntry, total: number) => number | string;
    numeric: boolean;
};

const COLUMNS: Column[] = [
    { key: 'function', label: 'Function', value: e => e.function, numeric: false },
    { key: 'count', label: 'Count', value: e => e.count, numeric: true },
    { key: 'total', label: 'Total', value: e => e.totalNs, numeric: true },
    { key: 'avg', label: 'Avg', value: e => e.count > 0 ? e.totalNs / e.count : 0, numeric: true },
    { key: 'min', label: 'Min', value: e => e.minNs, numeric: true },
    { key: 'max', label: 'Max', value: e => e.maxNs, numeric: true },
    { key: 'weight', label: 'Weight %', value: (e, total) => total > 0 ? (e.totalNs / total) * 100 : 0, numeric: true },
];

let currentTimers: CountReportEntry[] = [];
let grandTotal = 0;
let maxTotal = 0;
let sortKey = 'total';
let sortAsc = false;
let viewMode: 'flat' | 'tree' = 'flat';

// --- Tree data structure ---

interface TreeNode {
    segment: string;
    fullPath: string;
    entry?: CountReportEntry;    // present if this node is a leaf (actual timer)
    children: Map<string, TreeNode>;
    // aggregated stats
    totalNs: number;
    count: number;
    minNs: number;
    maxNs: number;
}

function buildTree(timers: CountReportEntry[]): TreeNode {
    const root: TreeNode = {
        segment: '', fullPath: '', children: new Map(),
        totalNs: 0, count: 0, minNs: Infinity, maxNs: 0,
    };

    for (const timer of timers) {
        // Split on ":" first, then "/" for the rest
        // e.g. "computer:alu/float/add" → ["computer", "alu", "float", "add"]
        const colonIdx = timer.function.indexOf(':');
        let segments: string[];
        if (colonIdx >= 0) {
            const ns = timer.function.substring(0, colonIdx + 1); // include the colon in the display
            const rest = timer.function.substring(colonIdx + 1);
            segments = [ns, ...rest.split('/')];
        } else {
            segments = timer.function.split('/');
        }

        let node = root;
        let path = '';
        for (let i = 0; i < segments.length; i++) {
            const seg = segments[i];
            path = i === 0 ? seg : path + '/' + seg;
            let child = node.children.get(seg);
            if (!child) {
                child = {
                    segment: seg, fullPath: path, children: new Map(),
                    totalNs: 0, count: 0, minNs: Infinity, maxNs: 0,
                };
                node.children.set(seg, child);
            }
            node = child;
        }
        node.entry = timer;
    }

    // Aggregate stats bottom-up
    aggregateNode(root);
    return root;
}

function aggregateNode(node: TreeNode): void {
    if (node.entry && node.children.size === 0) {
        // Leaf with actual data
        node.totalNs = node.entry.totalNs;
        node.count = node.entry.count;
        node.minNs = node.entry.minNs;
        node.maxNs = node.entry.maxNs;
        return;
    }

    for (const child of node.children.values()) {
        aggregateNode(child);
    }

    // If this node has its own entry, start with that; otherwise aggregate children
    if (node.entry) {
        node.totalNs = node.entry.totalNs;
        node.count = node.entry.count;
        node.minNs = node.entry.minNs;
        node.maxNs = node.entry.maxNs;
    } else {
        node.totalNs = 0;
        node.count = 0;
        node.minNs = Infinity;
        node.maxNs = 0;
        for (const child of node.children.values()) {
            node.totalNs += child.totalNs;
            node.count += child.count;
            node.minNs = Math.min(node.minNs, child.minNs);
            node.maxNs = Math.max(node.maxNs, child.maxNs);
        }
    }
}

// Collapsed state: track by fullPath
const collapsed = new Set<string>();

interface FlatRow {
    entry: CountReportEntry;
    depth: number;
    segment: string;
    hasChildren: boolean;
    isCollapsed: boolean;
    fullPath: string;
    isGroup: boolean; // true if this is a synthetic group node (no own entry)
}

function flattenTree(node: TreeNode, depth: number): FlatRow[] {
    const result: FlatRow[] = [];
    // Sort children by totalNs descending (consistent with default sort)
    const children = [...node.children.values()].sort((a, b) => b.totalNs - a.totalNs);

    for (const child of children) {
        const hasChildren = child.children.size > 0;
        const isCollapsed = collapsed.has(child.fullPath);
        const isGroup = !child.entry;

        // Create a synthetic entry for group nodes
        const entry: CountReportEntry = child.entry ?? {
            function: child.fullPath,
            count: child.count,
            totalNs: child.totalNs,
            minNs: child.minNs,
            maxNs: child.maxNs,
        };

        result.push({
            entry,
            depth,
            segment: child.segment,
            hasChildren,
            isCollapsed,
            fullPath: child.fullPath,
            isGroup,
        });

        if (hasChildren && !isCollapsed) {
            result.push(...flattenTree(child, depth + 1));
        }
    }
    return result;
}

// --- Build header ---

for (const col of COLUMNS) {
    const th = document.createElement('th');
    th.dataset.key = col.key;
    th.textContent = col.label;
    const arrow = document.createElement('span');
    arrow.className = 'arrow';
    th.appendChild(arrow);
    th.addEventListener('click', () => {
        if (viewMode === 'tree') return;
        if (sortKey === col.key) {
            sortAsc = !sortAsc;
        } else {
            sortKey = col.key;
            sortAsc = col.key === 'function';
        }
        renderTable();
    });
    columns.appendChild(th);
}

// --- Mode toggle ---

modeFlatBtn.addEventListener('click', () => {
    if (viewMode === 'flat') return;
    viewMode = 'flat';
    modeFlatBtn.classList.add('active');
    modeTreeBtn.classList.remove('active');
    renderTable();
});

modeTreeBtn.addEventListener('click', () => {
    if (viewMode === 'tree') return;
    viewMode = 'tree';
    modeTreeBtn.classList.add('active');
    modeFlatBtn.classList.remove('active');
    renderTable();
});

// --- Messages ---

window.addEventListener('message', event => {
    const message = event.data as ToWebview;
    if (message.type === 'countReport') {
        spinner.style.display = 'none';
        refreshBtn.style.display = '';
        currentTimers = message.timers;
        grandTotal = currentTimers.reduce((sum, t) => sum + t.totalNs, 0);
        maxTotal = currentTimers.reduce((max, t) => Math.max(max, t.totalNs), 0);
        title.textContent = `Profiler: ${message.namespace}, ${currentTimers.length} timer${currentTimers.length === 1 ? '' : 's'}`;
        lastRefresh.textContent = new Date().toLocaleTimeString();
        empty.style.display = 'none';
        content.style.display = '';
        renderTable();
    } else if (message.type === 'loading') {
        spinner.style.display = '';
        refreshBtn.style.display = 'none';
    }
});

refreshBtn.addEventListener('click', () => {
    vscode.postMessage({ type: 'refresh' });
});

vscode.postMessage({ type: 'ready' });

// --- Render ---

function renderTable(): void {
    // Update sort arrows (hidden in tree mode)
    for (let i = 0; i < columns.children.length; i++) {
        const el = columns.children[i] as HTMLElement;
        const arrow = el.querySelector('.arrow')!;
        el.style.cursor = viewMode === 'tree' ? 'default' : 'pointer';
        if (viewMode === 'tree') {
            arrow.textContent = '';
        } else if (el.dataset.key === sortKey) {
            arrow.textContent = sortAsc ? '▲' : '▼';
        } else {
            arrow.textContent = '';
        }
    }

    if (viewMode === 'flat') {
        renderFlat();
    } else {
        renderTreeView();
    }
}

function renderFlat(): void {
    const col = COLUMNS.find(c => c.key === sortKey)!;
    const sorted = [...currentTimers].sort((a, b) => {
        const va = col.value(a, grandTotal);
        const vb = col.value(b, grandTotal);
        if (typeof va === 'string' && typeof vb === 'string') {
            return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
        }
        const na = va as number;
        const nb = vb as number;
        return sortAsc ? na - nb : nb - na;
    });

    rows.innerHTML = '';
    for (const timer of sorted) {
        const tr = document.createElement('tr');
        for (const c of COLUMNS) {
            const td = document.createElement('td');
            const raw = c.value(timer, grandTotal);
            if (c.key === 'function') {
                td.className = 'fn';
                td.textContent = raw as string;
            } else if (c.key === 'weight') {
                td.className = 'num bar-cell';
                const pct = raw as number;
                renderBar(td, pct, pct.toFixed(1) + '%');
            } else if (c.key === 'total') {
                td.className = 'num bar-cell';
                const pct = maxTotal > 0 ? (timer.totalNs / maxTotal) * 100 : 0;
                renderBar(td, pct, formatNs(raw as number));
            } else if (c.key === 'count') {
                td.className = 'num';
                td.textContent = (raw as number).toLocaleString();
            } else {
                td.className = 'num';
                td.textContent = formatNs(raw as number);
            }
            tr.appendChild(td);
        }
        rows.appendChild(tr);
    }
}

function renderTreeView(): void {
    const tree = buildTree(currentTimers);
    const flatRows = flattenTree(tree, 0);

    // Compute maxTotal for tree view (use the max among all visible rows)
    const treeMaxTotal = flatRows.reduce((max, r) => Math.max(max, r.entry.totalNs), 0);

    rows.innerHTML = '';
    for (const row of flatRows) {
        const tr = document.createElement('tr');
        if (row.isGroup) {
            tr.classList.add('tree-group');
        }

        for (const c of COLUMNS) {
            const td = document.createElement('td');
            const raw = c.value(row.entry, grandTotal);

            if (c.key === 'function') {
                td.className = 'fn';
                // Indentation
                const indent = row.depth * 20;
                td.style.paddingLeft = `${10 + indent}px`;

                // Collapse toggle
                const toggle = document.createElement('span');
                toggle.className = 'tree-toggle' + (row.hasChildren ? '' : ' leaf');
                if (row.hasChildren) {
                    toggle.textContent = row.isCollapsed ? '▶' : '▼';
                    toggle.addEventListener('click', (e) => {
                        e.stopPropagation();
                        if (collapsed.has(row.fullPath)) {
                            collapsed.delete(row.fullPath);
                        } else {
                            collapsed.add(row.fullPath);
                        }
                        renderTable();
                    });
                }
                td.appendChild(toggle);

                const text = document.createTextNode(' ' + row.segment);
                td.appendChild(text);
            } else if (c.key === 'weight') {
                td.className = 'num bar-cell';
                const pct = raw as number;
                renderBar(td, pct, pct.toFixed(1) + '%');
            } else if (c.key === 'total') {
                td.className = 'num bar-cell';
                const pct = treeMaxTotal > 0 ? (row.entry.totalNs / treeMaxTotal) * 100 : 0;
                renderBar(td, pct, formatNs(raw as number));
            } else if (c.key === 'count') {
                td.className = 'num';
                td.textContent = (raw as number).toLocaleString();
            } else if ((c.key === 'avg' || c.key === 'min' || c.key === 'max') && row.isGroup) {
                td.className = 'num';
                td.textContent = '—';
            } else {
                td.className = 'num';
                td.textContent = formatNs(raw as number);
            }
            tr.appendChild(td);
        }

        // Make the whole row clickable to toggle
        if (row.hasChildren) {
            tr.style.cursor = 'pointer';
            tr.addEventListener('click', () => {
                if (collapsed.has(row.fullPath)) {
                    collapsed.delete(row.fullPath);
                } else {
                    collapsed.add(row.fullPath);
                }
                renderTable();
            });
        }

        rows.appendChild(tr);
    }
}

function renderBar(td: HTMLElement, pct: number, text: string): void {
    const bg = document.createElement('div');
    bg.className = 'bar-bg';
    td.appendChild(bg);
    const bar = document.createElement('div');
    bar.className = 'bar';
    bar.style.width = `${pct}%`;
    td.appendChild(bar);
    const span = document.createElement('span');
    span.style.position = 'relative';
    span.textContent = text;
    td.appendChild(span);
}

function formatNs(ns: number): string {
    if (ns >= 1_000_000_000) {
        return (ns / 1_000_000_000).toFixed(2) + ' s';
    }
    if (ns >= 1_000_000) {
        return (ns / 1_000_000).toFixed(2) + ' ms';
    }
    if (ns >= 1_000) {
        return (ns / 1_000).toFixed(1) + ' us';
    }
    return ns + ' ns';
}
