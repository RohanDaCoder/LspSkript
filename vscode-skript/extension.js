const net = require('net');
const { workspace, ExtensionContext } = require('vscode');
const { LanguageClient, LanguageClientOptions } = require('vscode-languageclient/node');

let client;

function activate(context) {
	const config = workspace.getConfiguration('skriptLsp');
	const port = config.get('port', 30505);
	const trace = config.get('trace', 'off');

	// The LSP server runs inside the Minecraft/Bukkit server as the SkriptLSP
	// plugin and listens on a TCP port. VS Code connects to it as a client.
	const serverOptions = () => {
		return new Promise((resolve, reject) => {
			const socket = net.connect(port, 'localhost', () => {
				const streamInfo = {
					reader: socket,
					writer: socket
				};
				resolve(streamInfo);
			});
			socket.on('error', (err) => reject(err));
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

	client.start();
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
