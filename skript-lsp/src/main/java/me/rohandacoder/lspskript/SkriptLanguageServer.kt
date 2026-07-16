package me.rohandacoder.lspskript

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.Channels
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Boots an LSP4J server over a TCP socket on localhost. VS Code (or any LSP
 * client) connects to the configured port.
 */
class SkriptLanguageServer(private val port: Int, private val trace: Boolean) {

    private val running = AtomicBoolean(false)

    private var serverSocket: AsynchronousServerSocketChannel? = null
    private var acceptThread: Thread? = null

    companion object {
        private val LOGGER = Logger.getLogger(SkriptLanguageServer::class.java.name)
        const val DEFAULT_PORT = 30505
    }

    @Throws(Exception::class)
    fun start() {
        if (running.compareAndSet(false, true)) {
            serverSocket = AsynchronousServerSocketChannel.open()
                .bind(InetSocketAddress("localhost", port))
            acceptThread = Thread({ acceptLoop() }, "LspSkript-Acceptor").also {
                it.isDaemon = true
                it.start()
            }
            LOGGER.info("LspSkript server started on localhost:$port")
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket!!.accept().get()
                if (!running.get()) break
                handleClient(socket)
            } catch (e: InterruptedException) {
                if (running.get()) LOGGER.log(Level.SEVERE, "Error accepting LSP connection", e)
                break
            } catch (e: ExecutionException) {
                if (running.get()) LOGGER.log(Level.SEVERE, "Error accepting LSP connection", e)
                break
            } catch (e: Exception) {
                if (running.get()) LOGGER.log(Level.SEVERE, "Unexpected error in LSP accept loop", e)
            }
        }
    }

    private fun handleClient(socket: AsynchronousSocketChannel) {
        try {
            val textService = SkriptTextDocumentService()
            val workspaceService = SkriptWorkspaceService(textService)
            val serverService = SkriptLanguageServerService(textService, workspaceService)

            val launcher: Launcher<LanguageClient> = LSPLauncher.Builder<LanguageClient>()
                .setLocalService(CompositeLanguageServer(textService, workspaceService, serverService))
                .setRemoteInterface(LanguageClient::class.java)
                .setInput(Channels.newInputStream(socket))
                .setOutput(Channels.newOutputStream(socket))
                .create()

            val client = launcher.remoteProxy
            textService.connect(client)
            workspaceService.connect(client)

            launcher.startListening()
        } catch (e: Exception) {
            LOGGER.log(Level.SEVERE, "Error handling LSP client", e)
        }
    }

    @Throws(Exception::class)
    fun stop() {
        if (running.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
            } finally {
                acceptThread?.interrupt()
            }
            LOGGER.info("LspSkript server stopped")
        }
    }
}
