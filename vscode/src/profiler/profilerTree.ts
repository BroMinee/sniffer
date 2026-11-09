import * as vscode from 'vscode';

export class ProfilerTreeProvider implements vscode.TreeDataProvider<string> {

    private namespaces: string[] = [];
    private readonly _onDidChangeTreeData = new vscode.EventEmitter<void>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    update(namespaces: string[]): void {
        this.namespaces = namespaces;
        this._onDidChangeTreeData.fire();
    }

    getTreeItem(ns: string): vscode.TreeItem {
        const item = new vscode.TreeItem(ns, vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('package');
        item.command = {
            command: 'sniffer.profilerReportNamespace',
            title: 'Generate Report',
            arguments: [ns],
        };
        item.tooltip = `Generate profiler report for ${ns}`;
        return item;
    }

    getChildren(): string[] {
        return this.namespaces;
    }
}
