/**
 * What crosses from the extension to the webview and back.
 *
 * Both sides compile separately, against different libraries, and only these shapes are common to them.
 */

export interface CountReportEntry {
    function: string;
    count: number;
    totalNs: number;
    minNs: number;
    maxNs: number;
}

/** Sent by the panel. */
export type ToWebview =
    | { type: 'countReport'; namespace: string; timers: CountReportEntry[] }
    | { type: 'loading' }
    | { type: 'theme' };

/** Sent by the webview. */
export type FromWebview =
    | { type: 'ready' }
    | { type: 'refresh' };
