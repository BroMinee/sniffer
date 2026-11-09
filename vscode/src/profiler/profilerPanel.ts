import * as vscode from 'vscode';
import type { CountReportEntry, FromWebview, ToWebview } from './profilerWire';

/**
 * A sortable table of timer statistics, shown in an editor tab.
 *
 * A single panel is reused for every report: the table replaces the previous one.
 */
export class ProfilerPanel {

    private static instance?: ProfilerPanel;
    private static reportTimeout?: NodeJS.Timeout;

    private ready = false;
    private pending?: ToWebview;
    private lastNamespace = '*';

    private constructor(
        private readonly panel: vscode.WebviewPanel,
        private readonly extensionUri: vscode.Uri,
    ) {
        panel.webview.html = this.html();
        panel.webview.onDidReceiveMessage(message => this.onMessage(message));
        panel.onDidDispose(() => {
            ProfilerPanel.instance = undefined;
        });
    }

    /** Listens for profiler report events for as long as the extension lives. */
    static register(context: vscode.ExtensionContext): void {
        context.subscriptions.push(
            vscode.debug.onDidReceiveDebugSessionCustomEvent(
                event => ProfilerPanel.handleEvent(event, context.extensionUri),
            ),
        );
    }

    /** Opens the panel, or brings back the one already open. */
    static reveal(extensionUri: vscode.Uri): ProfilerPanel {
        if (ProfilerPanel.instance) {
            ProfilerPanel.instance.panel.reveal(undefined, true);
            return ProfilerPanel.instance;
        }
        const panel = vscode.window.createWebviewPanel(
            'sniffer.profiler',
            'Profiler',
            { viewColumn: vscode.ViewColumn.Active, preserveFocus: false },
            { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [extensionUri] },
        );
        ProfilerPanel.instance = new ProfilerPanel(panel, extensionUri);
        return ProfilerPanel.instance;
    }

    private static onTimerList?: (namespaces: string[]) => void;

    static setTimerListListener(listener: (namespaces: string[]) => void): void {
        ProfilerPanel.onTimerList = listener;
    }

    static handleEvent(event: vscode.DebugSessionCustomEvent, extensionUri: vscode.Uri): void {
        if (event.event === 'snifferProfilerCountReport') {
            if (ProfilerPanel.reportTimeout) {
                clearTimeout(ProfilerPanel.reportTimeout);
                ProfilerPanel.reportTimeout = undefined;
            }
            const body = event.body as { namespace: string; timers: CountReportEntry[] };
            const instance = ProfilerPanel.reveal(extensionUri);
            instance.lastNamespace = body.namespace;
            instance.panel.title = `Profiler: ${body.namespace}`;
            instance.send({ type: 'countReport', namespace: body.namespace, timers: body.timers });
        } else if (event.event === 'snifferProfilerTimerList') {
            const body = event.body as { namespaces: string[] };
            ProfilerPanel.onTimerList?.(body.namespaces);
        }
    }

    /** Sends the report command for a given namespace without prompting. */
    static async requestReportForNamespace(namespace: string): Promise<void> {
        await ProfilerPanel.sendReportCommand(namespace);
    }

    /** Asks the user for a namespace and sends the report command via the debug console. */
    static async requestReport(extensionUri: vscode.Uri): Promise<void> {
        const session = vscode.debug.activeDebugSession;
        if (!session) {
            void vscode.window.showWarningMessage('No active debug session. Connect to Minecraft first.');
            return;
        }
        const namespace = await vscode.window.showInputBox({
            prompt: 'Timer namespace to report',
            value: ProfilerPanel.instance?.lastNamespace ?? '*',
            placeHolder: '*',
        });
        if (namespace === undefined) {
            return;
        }
        await ProfilerPanel.sendReportCommand(namespace);
    }

    private static async sendReportCommand(namespace: string): Promise<void> {
        const session = vscode.debug.activeDebugSession;
        if (!session) {
            void vscode.window.showWarningMessage('No active debug session. Connect to Minecraft first.');
            return;
        }
        if (ProfilerPanel.reportTimeout) {
            clearTimeout(ProfilerPanel.reportTimeout);
        }
        ProfilerPanel.instance?.send({ type: 'loading' });
        try {
            const response = await session.customRequest('evaluate', {
                expression: `/jvmtimer profile report ${namespace}`,
                context: 'repl',
            });
            if (response?.result === '0') {
                void vscode.window.showWarningMessage(`Profiler report failed for "${namespace}". Check the debug console for details.`);
                return;
            }
        } catch (err: any) {
            void vscode.window.showErrorMessage(err?.message ?? 'Failed to send report command.');
            return;
        }
    }

    private send(message: ToWebview): void {
        if (this.ready) {
            void this.panel.webview.postMessage(message);
        } else {
            this.pending = message;
        }
    }

    private onMessage(message: FromWebview): void {
        if (message.type === 'ready') {
            this.ready = true;
            if (this.pending) {
                void this.panel.webview.postMessage(this.pending);
                this.pending = undefined;
            }
        } else if (message.type === 'refresh') {
            void ProfilerPanel.sendReportCommand(this.lastNamespace);
        }
    }

    private html(): string {
        const script = this.panel.webview.asWebviewUri(
            vscode.Uri.joinPath(this.extensionUri, 'dist', 'profiler.js'),
        );
        const nonce = Array.from({ length: 32 }, () => Math.floor(Math.random() * 36).toString(36)).join('');

        return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy"
      content="default-src 'none'; style-src ${this.panel.webview.cspSource} 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>
  html, body {
    height: 100%; margin: 0;
    font-family: var(--vscode-font-family); color: var(--vscode-foreground);
    background: var(--vscode-editor-background);
  }
  header {
    display: flex; align-items: center; gap: 10px;
    padding: 8px 14px; font-size: 13px;
    border-bottom: 1px solid var(--vscode-panel-border);
  }
  #title { flex: 1; }
  #last-refresh { opacity: 0.6; font-size: 11px; }
  button {
    background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground);
    border: none; padding: 3px 10px; border-radius: 2px; cursor: pointer; font-size: 12px;
  }
  button:hover { background: var(--vscode-button-secondaryHoverBackground); }
  @keyframes spin { to { transform: rotate(360deg); } }
  .spinner {
    display: none; width: 14px; height: 14px;
    border: 2px solid var(--vscode-foreground); border-top-color: transparent;
    border-radius: 50%; animation: spin 0.8s linear infinite;
  }
  #empty {
    padding: 40px; text-align: center; opacity: 0.7; font-size: 13px;
  }
  table {
    width: 100%; border-collapse: collapse; font-size: 12px;
    font-family: var(--vscode-editor-font-family, monospace);
  }
  thead { position: sticky; top: 0; z-index: 1; }
  th {
    text-align: left; padding: 6px 10px; cursor: pointer; user-select: none;
    background: var(--vscode-editorGroupHeader-tabsBackground);
    border-bottom: 2px solid var(--vscode-panel-border);
    border-right: 1px solid var(--vscode-panel-border);
    white-space: nowrap;
  }
  th:last-child { border-right: none; }
  th:hover { color: var(--vscode-textLink-foreground); }
  th .arrow { font-size: 10px; margin-left: 4px; opacity: 0.6; }
  td {
    padding: 4px 10px;
    border-bottom: 1px solid var(--vscode-panel-border);
    border-right: 1px solid var(--vscode-panel-border);
  }
  td:last-child { border-right: none; }
  td.num { text-align: right; font-variant-numeric: tabular-nums; }
  td.fn { word-break: break-all; }
  tr:hover td { background: var(--vscode-list-hoverBackground); }
  .bar-cell { position: relative; }
  .bar-bg {
    position: absolute; top: 2px; bottom: 2px; left: 0; right: 0;
    background: var(--vscode-foreground); opacity: 0.06; border-radius: 2px;
  }
  .bar {
    position: absolute; top: 2px; bottom: 2px; left: 0;
    background: var(--vscode-charts-blue); opacity: 0.15; border-radius: 2px;
  }
</style>
</head>
<body>
<header>
  <span id="title">Profiler</span>
  <span id="last-refresh"></span>
  <div id="spinner" class="spinner"></div>
  <button id="refresh">Refresh</button>
</header>
<div id="empty">No profiler report yet. Run <code>/jvmtimer profile report</code> in count mode.</div>
<div id="content" style="display:none; overflow:auto; height:calc(100% - 40px);">
  <table>
    <thead><tr id="columns"></tr></thead>
    <tbody id="rows"></tbody>
  </table>
</div>
<script nonce="${nonce}" src="${script}"></script>
</body>
</html>`;
    }
}
