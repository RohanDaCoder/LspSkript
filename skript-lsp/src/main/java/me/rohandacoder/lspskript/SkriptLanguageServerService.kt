package me.rohandacoder.lspskript

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.HoverOptions
import org.eclipse.lsp4j.DefinitionOptions
import org.eclipse.lsp4j.ReferenceOptions
import org.eclipse.lsp4j.DocumentSymbolOptions
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.RenameOptions
import java.util.concurrent.CompletableFuture

/**
 * Server-level LSP service: initialize/shutdown/exit and capability advertising.
 */
class SkriptLanguageServerService(
    private val textService: SkriptTextDocumentService,
    private val workspaceService: SkriptWorkspaceService
) : LanguageServer, LanguageClientAware {

    private var client: LanguageClient? = null

    override fun connect(client: LanguageClient) {
        this.client = client
        textService.connect(client)
        workspaceService.connect(client)
    }

    override fun getTextDocumentService(): TextDocumentService = textService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    @JsonRequest("initialize")
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val caps = ServerCapabilities()
        caps.setTextDocumentSync(TextDocumentSyncKind.Incremental)
        caps.setCompletionProvider(CompletionOptions(true, listOf("%", "{", "/")))
        caps.setHoverProvider(true)
        caps.setSignatureHelpProvider(SignatureHelpOptions(listOf(" ", "%")))
        caps.setDefinitionProvider(true)
        caps.setReferencesProvider(true)
        caps.setDocumentSymbolProvider(true)
        caps.setCodeActionProvider(CodeActionOptions())
        caps.setRenameProvider(RenameOptions(false))
        caps.setDocumentFormattingProvider(true)
        caps.setDocumentRangeFormattingProvider(true)
        caps.setWorkspaceSymbolProvider(true)

        return CompletableFuture.completedFuture(InitializeResult(caps))
    }

    override fun shutdown(): CompletableFuture<Any> {
        return CompletableFuture.completedFuture(Any())
    }

    override fun exit() {
        // The acceptor thread is owned by SkriptLanguageServer; the plugin
        // lifecycle handles actual shutdown. No-op here.
    }

    @JsonNotification("initialized")
    override fun initialized(params: InitializedParams) {
        // Nothing required; diagnostics are pushed on document open/change.
    }

    fun getClient(): LanguageClient? = client
}
