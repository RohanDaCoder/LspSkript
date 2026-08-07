const net = require('net');
const { workspace } = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');

let client;

function activate(context) {
	const config = workspace.getConfiguration('skriptLsp');
	const host = config.get('host', 'localhost');
	const port = config.get('port', 30505);
	const trace = config.get('trace', 'off');

	// The LSP server runs inside the Minecraft/Bukkit server as the LspSkript
	// plugin and listens on a TCP port. VS Code connects to it as a client.
	const serverOptions = () => {
		return new Promise((resolve, reject) => {
			const socket = net.connect(port, host);
			socket.once('connect', () => {
				resolve({ reader: socket, writer: socket });
			});
			socket.once('error', (err) => reject(err));
		});
	};

	const clientOptions = {
		documentSelector: [{ scheme: 'file', language: 'skript' }],
		traceServer: trace
	};

	client = new LanguageClient(
		'skriptLsp',
		'Skript LSP',
		serverOptions,
		clientOptions
	);

	// The plugin may not be up yet (server still booting, plugin reloaded).
	// Retry with backoff instead of giving up on the first failed connect.
	let retryDelayMs = 2000;
	async function startWithRetry() {
		try {
			await client.start();
			retryDelayMs = 2000;
		} catch (err) {
			console.warn(`skriptLsp: connection to ${host}:${port} failed (${err.message}); retrying in ${retryDelayMs}ms`);
			setTimeout(startWithRetry, retryDelayMs);
			retryDelayMs = Math.min(retryDelayMs * 2, 30000);
		}
	}
	startWithRetry();

	context.subscriptions.push({
		dispose: () => client?.stop()
	});
}

function deactivate() {
	return client?.stop();
}

module.exports = {
	activate,
	deactivate
};
