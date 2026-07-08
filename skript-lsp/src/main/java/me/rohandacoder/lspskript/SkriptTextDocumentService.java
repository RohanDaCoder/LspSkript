package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text-document-level LSP service. Owns the in-memory document store, drives
 * diagnostics via {@link ParseBridge}, and delegates feature requests to the
 * individual providers.
 */
public class SkriptTextDocumentService implements TextDocumentService {

	private final Map<String, String> documents = new ConcurrentHashMap<>();
	private LanguageClient client;

	// Feature providers (lazy-initialised against the live Skript runtime).
	private volatile ParseBridge parseBridge;
	private volatile CompletionProvider completionProvider;
	private volatile HoverProvider hoverProvider;
	private volatile SignatureHelpProvider signatureHelpProvider;
	private volatile DocumentSymbolProvider documentSymbolProvider;
	private volatile DefinitionReferenceProvider definitionReferenceProvider;
	private volatile FormattingProvider formattingProvider;
	private volatile RenameProvider renameProvider;
	private volatile CodeActionProvider codeActionProvider;

	public void connect(LanguageClient client) {
		this.client = client;
	}

	private ParseBridge parseBridge() {
		if (parseBridge == null) {
			synchronized (this) {
				if (parseBridge == null)
					parseBridge = new ParseBridge();
			}
		}
		return parseBridge;
	}

	private CompletionProvider completionProvider() {
		if (completionProvider == null) {
			synchronized (this) {
				if (completionProvider == null)
					completionProvider = new CompletionProvider();
			}
		}
		return completionProvider;
	}

	private HoverProvider hoverProvider() {
		if (hoverProvider == null) {
			synchronized (this) {
				if (hoverProvider == null)
					hoverProvider = new HoverProvider();
			}
		}
		return hoverProvider;
	}

	private SignatureHelpProvider signatureHelpProvider() {
		if (signatureHelpProvider == null) {
			synchronized (this) {
				if (signatureHelpProvider == null)
					signatureHelpProvider = new SignatureHelpProvider();
			}
		}
		return signatureHelpProvider;
	}

	private DocumentSymbolProvider documentSymbolProvider() {
		if (documentSymbolProvider == null) {
			synchronized (this) {
				if (documentSymbolProvider == null)
					documentSymbolProvider = new DocumentSymbolProvider();
			}
		}
		return documentSymbolProvider;
	}

	private DefinitionReferenceProvider definitionReferenceProvider() {
		if (definitionReferenceProvider == null) {
			synchronized (this) {
				if (definitionReferenceProvider == null)
					definitionReferenceProvider = new DefinitionReferenceProvider();
			}
		}
		return definitionReferenceProvider;
	}

	private FormattingProvider formattingProvider() {
		if (formattingProvider == null) {
			synchronized (this) {
				if (formattingProvider == null)
					formattingProvider = new FormattingProvider();
			}
		}
		return formattingProvider;
	}

	private RenameProvider renameProvider() {
		if (renameProvider == null) {
			synchronized (this) {
				if (renameProvider == null)
					renameProvider = new RenameProvider();
			}
		}
		return renameProvider;
	}

	private CodeActionProvider codeActionProvider() {
		if (codeActionProvider == null) {
			synchronized (this) {
				if (codeActionProvider == null)
					codeActionProvider = new CodeActionProvider();
			}
		}
		return codeActionProvider;
	}

	// ---------------------------------------------------------------------
	// Document lifecycle
	// ---------------------------------------------------------------------

	@JsonNotification("textDocument/didOpen")
	public void didOpen(DidOpenTextDocumentParams params) {
		TextDocumentItem item = params.getTextDocument();
		String uri = item.getUri();
		documents.put(uri, item.getText());
		republishDiagnostics(uri, item.getText());
	}

	@JsonNotification("textDocument/didChange")
	public void didChange(DidChangeTextDocumentParams params) {
		String uri = params.getTextDocument().getUri();
		String text = documents.get(uri);
		for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
			if (change.getRange() != null) {
				text = applyRangeEdit(text, change.getRange(), change.getText());
			} else {
				text = change.getText();
			}
		}
		documents.put(uri, text);
		republishDiagnostics(uri, text);
	}

	@JsonNotification("textDocument/didSave")
	public void didSave(DidSaveTextDocumentParams params) {
		String uri = params.getTextDocument().getUri();
		String text = documents.get(uri);
		if (text != null)
			republishDiagnostics(uri, text);
	}

	@JsonNotification("textDocument/didClose")
	public void didClose(DidCloseTextDocumentParams params) {
		String uri = params.getTextDocument().getUri();
		documents.remove(uri);
		// Clear diagnostics for the closed file.
		if (client != null) {
			client.publishDiagnostics(new PublishDiagnosticsParams(uri, Collections.emptyList()));
		}
	}

	void onWatchedFilesChanged(DidChangeWatchedFilesParams params) {
		if (params == null || params.getChanges() == null)
			return;
		for (FileEvent event : params.getChanges()) {
			String uri = event.getUri();
			if (documents.containsKey(uri)) {
				// Content changed externally; we can't read it here, but clear cache.
				parseBridge().invalidate(uri);
			}
		}
	}

	private void republishDiagnostics(String uri, String text) {
		if (client == null)
			return;
		List<Diagnostic> diagnostics = parseBridge().parse(uri, text);
		client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
		definitionReferenceProvider().index(uri, text);
	}

	private static String applyRangeEdit(String text, Range range, String replacement) {
		String[] lines = text.split("\n", -1);
		int startLine = range.getStart().getLine();
		int startChar = range.getStart().getCharacter();
		int endLine = range.getEnd().getLine();
		int endChar = range.getEnd().getCharacter();

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < startLine; i++)
			sb.append(lines[i]).append('\n');
		String startLineText = lines[startLine];
		sb.append(startLineText, 0, Math.min(startChar, startLineText.length()));
		sb.append(replacement);
		if (endLine < lines.length) {
			String endLineText = lines[endLine];
			sb.append(endLineText, Math.min(endChar, endLineText.length()), endLineText.length());
			for (int i = endLine + 1; i < lines.length; i++)
				sb.append('\n').append(lines[i]);
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------------
	// Feature requests
	// ---------------------------------------------------------------------

	@JsonRequest("textDocument/completion")
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return Either.forLeft(Collections.emptyList());
			List<CompletionItem> items = completionProvider().complete(uri, text, params.getPosition());
			return Either.forLeft(items);
		});
	}

	@JsonRequest("textDocument/hover")
	public CompletableFuture<Hover> hover(HoverParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return null;
			return hoverProvider().hover(uri, text, params.getPosition());
		});
	}

	@JsonRequest("textDocument/signatureHelp")
	public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return null;
			return signatureHelpProvider().signatureHelp(uri, text, params.getPosition());
		});
	}

	@JsonRequest("textDocument/definition")
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return Either.forLeft(Collections.emptyList());
			List<? extends Location> locations = definitionReferenceProvider().definition(uri, text, params.getPosition());
			return Either.forLeft(locations);
		});
	}

	@JsonRequest("textDocument/references")
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return Collections.emptyList();
			return definitionReferenceProvider().references(uri, text, params.getPosition(), params.getContext().isIncludeDeclaration());
		});
	}

	@JsonRequest("textDocument/documentSymbol")
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return Collections.emptyList();
			return documentSymbolProvider().documentSymbols(uri, text);
		});
	}

	@JsonRequest("textDocument/formatting")
	public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return Collections.emptyList();
			return formattingProvider().formatting(uri, text);
		});
	}

	@JsonRequest("textDocument/rangeFormatting")
	public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return Collections.emptyList();
			return formattingProvider().rangeFormatting(uri, text, params.getRange());
		});
	}

	@JsonRequest("textDocument/rename")
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return new WorkspaceEdit();
			return renameProvider().rename(uri, text, params.getPosition(), params.getNewName());
		});
	}

	@JsonRequest("textDocument/codeAction")
	public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
		return CompletableFutures.computeAsync(cancelToken -> {
			cancelToken.checkCanceled();
			String uri = params.getTextDocument().getUri();
			String text = documents.get(uri);
			if (text == null)
				return Collections.emptyList();
			return codeActionProvider().codeAction(uri, text, params);
		});
	}

	List<? extends SymbolInformation> workspaceSymbols(String query) {
		return definitionReferenceProvider().workspaceSymbols(query);
	}

	String getDocument(String uri) {
		return documents.get(uri);
	}
}
