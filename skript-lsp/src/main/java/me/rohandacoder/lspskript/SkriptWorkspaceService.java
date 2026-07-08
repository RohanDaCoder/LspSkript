package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.services.WorkspaceService;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Workspace-level LSP service. Provides workspace/symbol search across all
 * loaded scripts and reacts to watched-file changes.
 */
public class SkriptWorkspaceService implements WorkspaceService {

	private final SkriptTextDocumentService textService;
	private org.eclipse.lsp4j.services.LanguageClient client;

	public SkriptWorkspaceService(SkriptTextDocumentService textService) {
		this.textService = textService;
	}

	public void connect(org.eclipse.lsp4j.services.LanguageClient client) {
		this.client = client;
	}

	@JsonRequest("workspace/symbol")
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			return Either.forLeft(textService.workspaceSymbols(params.getQuery()));
		});
	}

	@JsonNotification("workspace/didChangeConfiguration")
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		// Reload settings if needed in the future.
	}

	@JsonNotification("workspace/didChangeWatchedFiles")
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		// Re-validate changed .sk files that we already track.
		textService.onWatchedFilesChanged(params);
	}

	@JsonRequest("workspace/executeCommand")
	public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
		return CompletableFuture.completedFuture(null);
	}

}
