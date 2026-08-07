package me.rohandacoder.lspskript

import org.eclipse.lsp4j.services.WorkspaceService
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import java.util.concurrent.CompletableFuture

/**
 * Workspace-level LSP service. Provides workspace/symbol search across all
 * loaded scripts and reacts to watched-file changes.
 */
class SkriptWorkspaceService(private val textService: SkriptTextDocumentService) : WorkspaceService {

    @JsonRequest("workspace/symbol")
    @Suppress("DEPRECATION")
    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val result = textService.workspaceSymbols(params.query)
            Either.forLeft(result)
        }
    }

    @JsonNotification("workspace/didChangeConfiguration")
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        // Reload settings if needed in the future.
    }

    @JsonNotification("workspace/didChangeWatchedFiles")
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        // Re-validate changed .sk files that we already track.
        textService.onWatchedFilesChanged(params)
    }

    @JsonRequest("workspace/executeCommand")
    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return CompletableFuture.completedFuture(null)
    }
}
