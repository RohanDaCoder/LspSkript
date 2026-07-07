package ch.njol.skript.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channels;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Boots an LSP4J server over a TCP socket on localhost. VS Code (or any LSP
 * client) connects to the configured port.
 */
public class SkriptLanguageServer {

	private static final Logger LOGGER = Logger.getLogger(SkriptLanguageServer.class.getName());

	private final int port;
	private final boolean trace;
	private final AtomicBoolean running = new AtomicBoolean(false);

	private AsynchronousServerSocketChannel serverSocket;
	private Thread acceptThread;

	public SkriptLanguageServer(int port, boolean trace) {
		this.port = port;
		this.trace = trace;
	}

	public void start() throws Exception {
		if (running.compareAndSet(false, true)) {
			serverSocket = AsynchronousServerSocketChannel.open()
				.bind(new InetSocketAddress("localhost", port));
			acceptThread = new Thread(this::acceptLoop, "SkriptLSP-Acceptor");
			acceptThread.setDaemon(true);
			acceptThread.start();
			LOGGER.info("SkriptLSP server started on localhost:" + port);
		}
	}

	private void acceptLoop() {
		while (running.get()) {
			try {
				AsynchronousSocketChannel socket = serverSocket.accept().get();
				if (!running.get())
					break;
				handleClient(socket);
			} catch (InterruptedException | ExecutionException e) {
				if (running.get())
					LOGGER.log(Level.SEVERE, "Error accepting LSP connection", e);
				break;
			} catch (Exception e) {
				if (running.get())
					LOGGER.log(Level.SEVERE, "Unexpected error in LSP accept loop", e);
			}
		}
	}

	private void handleClient(AsynchronousSocketChannel socket) {
		try {
			SkriptTextDocumentService textService = new SkriptTextDocumentService();
			SkriptWorkspaceService workspaceService = new SkriptWorkspaceService(textService);
			SkriptLanguageServerService serverService = new SkriptLanguageServerService(textService, workspaceService);

			Launcher<LanguageClient> launcher = new LSPLauncher.Builder<LanguageClient>()
				.setLocalService(new CompositeLanguageServer(textService, workspaceService, serverService))
				.setRemoteInterface(LanguageClient.class)
				.setInput(Channels.newInputStream(socket))
				.setOutput(Channels.newOutputStream(socket))
				.create();

			LanguageClient client = launcher.getRemoteProxy();
			textService.connect(client);
			workspaceService.connect(client);

			launcher.startListening();
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Error handling LSP client", e);
		}
	}

	public void stop() throws Exception {
		if (running.compareAndSet(true, false)) {
			try {
				if (serverSocket != null)
					serverSocket.close();
			} finally {
				if (acceptThread != null)
					acceptThread.interrupt();
			}
			LOGGER.info("SkriptLSP server stopped");
		}
	}

}
