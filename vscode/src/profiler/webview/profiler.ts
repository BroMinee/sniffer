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

// Build header
for (const col of COLUMNS) {
    const th = document.createElement('th');
    th.dataset.key = col.key;
    th.textContent = col.label;
    const arrow = document.createElement('span');
    arrow.className = 'arrow';
    th.appendChild(arrow);
    th.addEventListener('click', () => {
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

function renderTable(): void {
    // Update sort arrows
    for (let i = 0; i < columns.children.length; i++) {
        const el = columns.children[i] as HTMLElement;
        const arrow = el.querySelector('.arrow')!;
        if (el.dataset.key === sortKey) {
            arrow.textContent = sortAsc ? '▲' : '▼';
        } else {
            arrow.textContent = '';
        }
    }

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
