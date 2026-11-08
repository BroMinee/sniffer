import * as vscode from 'vscode';
import { configureLaunch, offerSetup } from './launch/configureLaunch';
import { ProfilerPanel } from './profiler/profilerPanel';
import { ProfilerTreeProvider } from './profiler/profilerTree';
import { TracePanel } from './trace/tracePanel';

/**
 * What both entry points do, the desktop one adding the WebSocket adapter a browser cannot run.
 * Shared rather than written twice, since anything registered in one and forgotten in the other
 * is a feature missing from the web build with nothing to say so.
 */
const emptyTree: vscode.TreeDataProvider<never> = {
    getTreeItem: () => { throw new Error('no items'); },
    getChildren: () => [],
};

export function activateCommon(context: vscode.ExtensionContext): void {
    const profilerTree = new ProfilerTreeProvider();

    context.subscriptions.push(
        vscode.commands.registerCommand('sniffer.configureLaunch', () => configureLaunch()),
        vscode.commands.registerCommand('sniffer.profilerReport', () => ProfilerPanel.requestReport(context.extensionUri)),
        vscode.commands.registerCommand('sniffer.profilerReportNamespace', (ns: string) => ProfilerPanel.requestReportForNamespace(ns)),
        vscode.commands.registerCommand('sniffer.connect', () => connect()),
    );
    vscode.window.registerTreeDataProvider('sniffer.debug', emptyTree);
    vscode.window.registerTreeDataProvider('sniffer.profiler', profilerTree);
    ProfilerPanel.setTimerListListener(namespaces => profilerTree.update(['*', ...namespaces]));
    TracePanel.register(context);
    ProfilerPanel.register(context);
    void offerSetup(context);
}

async function connect(): Promise<void> {
    const configs = vscode.workspace.getConfiguration('launch').get<any[]>('configurations') ?? [];
    const sniffer = configs.find(c => c.type === 'sniffer');
    if (sniffer) {
        await vscode.debug.startDebugging(undefined, sniffer);
    } else {
        await vscode.debug.startDebugging(undefined, {
            type: 'sniffer',
            request: 'attach',
            name: 'Connect to Minecraft',
            address: 'ws://localhost:25599/dap',
        });
    }
}
