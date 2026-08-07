package me.rohandacoder.lspskript

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.PrintWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.Channels
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Boots an LSP4J server over a TCP socket on localhost. VS Code (or any LSP
 * client) connects to the configured port.
 *
 * Each client connection is served on its own daemon thread, so multiple
 * editors can be attached at the same time; [stop] closes every open client
 * socket.
 */
class SkriptLanguageServer(private val port: Int, private val trace: Boolean) {

    private val running = AtomicBoolean(false)
    private val clients: MutableSet<AsynchronousSocketChannel> = ConcurrentHashMap.newKeySet()

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
                val socket = checkNotNull(serverSocket).accept().get()
                if (!running.get()) {
                    socket.close()
                    break
                }
                clients.add(socket)
                Thread({ handleClient(socket) }, "LspSkript-Client").also {
                    it.isDaemon = true
                    it.start()
                }
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
            val serverService = SkriptLanguageServerService(textService)

            val launcher: Launcher<LanguageClient> = LSPLauncher.Builder<LanguageClient>()
                .setLocalService(CompositeLanguageServer(textService, workspaceService, serverService))
                .setRemoteInterface(LanguageClient::class.java)
                .setInput(Channels.newInputStream(socket))
                .setOutput(Channels.newOutputStream(socket))
                .traceMessages(traceWriter())
                .create()

            val client = launcher.remoteProxy
            textService.connect(client)

            launcher.startListening()
        } catch (e: Exception) {
            LOGGER.log(Level.SEVERE, "Error handling LSP client", e)
        } finally {
            clients.remove(socket)
            try {
                socket.close()
            } catch (ignored: Exception) {
                // socket already closed
            }
        }
    }

    /**
     * LSP message tracing, routed into the plugin logger. Returns null when
     * tracing is disabled (the LSP4J builder treats null as "no tracer").
     */
    private fun traceWriter(): PrintWriter? {
        if (!trace) return null
        return PrintWriter(object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                LOGGER.log(Level.FINE, String(cbuf, off, len))
            }

            override fun flush() {}

            override fun close() {}
        }, true)
    }

    @Throws(Exception::class)
    fun stop() {
        if (running.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
                for (client in clients) {
                    try {
                        client.close()
                    } catch (ignored: Exception) {
                        // client already gone
                    }
                }
            } finally {
                acceptThread?.interrupt()
            }
            LOGGER.info("LspSkript server stopped")
        }
    }
}
