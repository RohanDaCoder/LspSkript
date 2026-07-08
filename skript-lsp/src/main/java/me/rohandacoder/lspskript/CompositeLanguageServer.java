package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.concurrent.CompletableFuture;

/**
 * Aggregates the text-document, workspace and server services into a single
 * object exposed to the LSP client.
 */
public class CompositeLanguageServer implements LanguageServer, LanguageClientAware {

	private final TextDocumentService textDocumentService;
	private final WorkspaceService workspaceService;
	private final LanguageServer serverService;

	public CompositeLanguageServer(TextDocumentService textDocumentService,
	                               WorkspaceService workspaceService,
	                               LanguageServer serverService) {
		this.textDocumentService = textDocumentService;
		this.workspaceService = workspaceService;
		this.serverService = serverService;
	}

	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		return serverService.initialize(params);
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		return serverService.shutdown();
	}

	@Override
	public void exit() {
		serverService.exit();
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return textDocumentService;
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return workspaceService;
	}

	@Override
	public void connect(LanguageClient client) {
		if (serverService instanceof LanguageClientAware)
			((LanguageClientAware) serverService).connect(client);
	}

}
