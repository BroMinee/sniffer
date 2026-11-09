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
export function activateCommon(context: vscode.ExtensionContext): void {
    const profilerTree = new ProfilerTreeProvider();

    context.subscriptions.push(
        vscode.commands.registerCommand('sniffer.configureLaunch', () => configureLaunch()),
        vscode.commands.registerCommand('sniffer.profilerReport', () => ProfilerPanel.requestReport(context.extensionUri)),
        vscode.commands.registerCommand('sniffer.profilerReportNamespace', (ns: string) => ProfilerPanel.requestReportForNamespace(ns)),
        vscode.debug.onDidStartDebugSession(s => {
            if (s.type === 'sniffer') vscode.commands.executeCommand('setContext', 'sniffer.connected', true);
        }),
        vscode.debug.onDidTerminateDebugSession(s => {
            if (s.type === 'sniffer') vscode.commands.executeCommand('setContext', 'sniffer.connected', false);
        }),
    );
    vscode.window.registerTreeDataProvider('sniffer.profiler', profilerTree);
    ProfilerPanel.setTimerListListener(namespaces => profilerTree.update(['*', ...namespaces]));
    TracePanel.register(context);
    ProfilerPanel.register(context);
    void offerSetup(context);
}
