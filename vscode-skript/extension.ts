import * as net from 'node:net';

import { type ExtensionContext, workspace } from 'vscode';
import { LanguageClient, type StreamInfo, Trace } from 'vscode-languageclient/node';

/**
 * The language client. Undefined until the connection is established.
 */
let client: LanguageClient | undefined;

/**
 * Maps the `skriptLsp.trace` setting onto the protocol's trace levels.
 *
 * The setting is a string because that is what a VS Code configuration value
 * is, but the client wants a `Trace` enum.
 */
const TRACE_BY_NAME: Record<string, Trace> = {
    off: Trace.Off,
    messages: Trace.Messages,
    verbose: Trace.Verbose,
};

/**
 * Connect to the LspSkript plugin and start the language client.
 *
 * The plugin may not be up yet - the server is still booting, or the plugin
 * was reloaded - so the connection is retried with backoff rather than
 * abandoned on the first failure.
 */
export async function activate(context: ExtensionContext): Promise<void> {
    const config = workspace.getConfiguration('skriptLsp');
    const host = config.get<string>('host', 'localhost');
    const port = config.get<number>('port', 30505);
    const trace = TRACE_BY_NAME[config.get<string>('trace', 'off')] ?? Trace.Off;

    // The LSP server runs inside the Minecraft/Bukkit server as the LspSkript
    // plugin and listens on a TCP port. VS Code connects to it as a client.
    const serverOptions = () =>
        new Promise<StreamInfo>((resolve, reject) => {
            const socket = net.connect(port, host);
            socket.once('connect', () => {
                resolve({ reader: socket, writer: socket });
            });
            socket.once('error', (err) => reject(err));
        });

    const clientOptions = {
        documentSelector: [{ scheme: 'file', language: 'skript' }],
        traceServer: trace,
    };

    client = new LanguageClient('skriptLsp', 'Skript LSP', serverOptions, clientOptions);

    // The plugin may not be up yet (server still booting, plugin reloaded).
    // Retry with backoff instead of giving up on the first failed connect.
    let retryDelayMs = 2000;
    async function startWithRetry(): Promise<void> {
        try {
            await client?.start();
            retryDelayMs = 2000;
        } catch (err) {
            const message = err instanceof Error ? err.message : String(err);
            console.warn(`skriptLsp: connection to ${host}:${port} failed (${message}); retrying in ${retryDelayMs}ms`);
            setTimeout(startWithRetry, retryDelayMs);
            retryDelayMs = Math.min(retryDelayMs * 2, 30000);
        }
    }
    startWithRetry();

    context.subscriptions.push({
        dispose: () => client?.stop(),
    });
}

/**
 * Stop the language client when the extension is deactivated.
 */
export async function deactivate(): Promise<void> {
    await client?.stop();
}
