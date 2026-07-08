package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.HoverOptions;
import org.eclipse.lsp4j.DefinitionOptions;
import org.eclipse.lsp4j.ReferenceOptions;
import org.eclipse.lsp4j.DocumentSymbolOptions;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;

import java.util.concurrent.CompletableFuture;

/**
 * Server-level LSP service: initialize/shutdown/exit and capability advertising.
 */
public class SkriptLanguageServerService implements LanguageServer, LanguageClientAware {

	private final SkriptTextDocumentService textService;
	private final SkriptWorkspaceService workspaceService;
	private LanguageClient client;

	public SkriptLanguageServerService(SkriptTextDocumentService textService, SkriptWorkspaceService workspaceService) {
		this.textService = textService;
		this.workspaceService = workspaceService;
	}

	@Override
	public void connect(LanguageClient client) {
		this.client = client;
		textService.connect(client);
		workspaceService.connect(client);
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return textService;
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return workspaceService;
	}

	@JsonRequest("initialize")
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		ServerCapabilities caps = new ServerCapabilities();
		caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
		caps.setCompletionProvider(new CompletionOptions(true, java.util.List.of("%", "{", "/")));
		caps.setHoverProvider(true);
		caps.setSignatureHelpProvider(new SignatureHelpOptions(java.util.List.of(" ", "%")));
		caps.setDefinitionProvider(true);
		caps.setReferencesProvider(true);
		caps.setDocumentSymbolProvider(true);
		caps.setCodeActionProvider(new CodeActionOptions());
		caps.setRenameProvider(new RenameOptions(false));
		caps.setDocumentFormattingProvider(true);
		caps.setDocumentRangeFormattingProvider(true);
		caps.setWorkspaceSymbolProvider(true);

		InitializeResult result = new InitializeResult(caps);
		return CompletableFuture.completedFuture(result);
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		return CompletableFuture.completedFuture(new Object());
	}

	@Override
	public void exit() {
		// The acceptor thread is owned by SkriptLanguageServer; the plugin
		// lifecycle handles actual shutdown. No-op here.
	}

	@JsonNotification("initialized")
	public void initialized(InitializedParams params) {
		// Nothing required; diagnostics are pushed on document open/change.
	}

	public LanguageClient getClient() {
		return client;
	}

}
