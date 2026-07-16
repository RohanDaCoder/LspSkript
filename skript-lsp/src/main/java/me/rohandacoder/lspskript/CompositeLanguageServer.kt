package me.rohandacoder.lspskript

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

/**
 * Aggregates the text-document, workspace and server services into a single
 * object exposed to the LSP client.
 */
class CompositeLanguageServer(
    private val textDocumentService: TextDocumentService,
    private val workspaceService: WorkspaceService,
    private val serverService: LanguageServer
) : LanguageServer, LanguageClientAware {

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        return serverService.initialize(params)
    }

    override fun shutdown(): CompletableFuture<Any> {
        return serverService.shutdown()
    }

    override fun exit() {
        serverService.exit()
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        if (serverService is LanguageClientAware) {
            (serverService as LanguageClientAware).connect(client)
        }
    }
}
