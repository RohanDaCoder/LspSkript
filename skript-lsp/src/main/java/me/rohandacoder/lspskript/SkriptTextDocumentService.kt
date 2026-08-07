package me.rohandacoder.lspskript

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-document-level LSP service. Owns the in-memory document store, drives
 * diagnostics via [ParseBridge], and delegates feature requests to the
 * individual providers.
 */
class SkriptTextDocumentService : TextDocumentService {

    private val documents: MutableMap<String, String> = ConcurrentHashMap()
    private var client: LanguageClient? = null

    // Feature providers (lazy-initialised against the live Skript runtime).
    private val parseBridge: ParseBridge by lazy { ParseBridge() }
    private val completionProvider: CompletionProvider by lazy { CompletionProvider() }
    private val hoverProvider: HoverProvider by lazy { HoverProvider() }
    private val signatureHelpProvider: SignatureHelpProvider by lazy { SignatureHelpProvider() }
    private val documentSymbolProvider: DocumentSymbolProvider by lazy { DocumentSymbolProvider() }
    private val definitionReferenceProvider: DefinitionReferenceProvider by lazy { DefinitionReferenceProvider() }
    private val formattingProvider: FormattingProvider by lazy { FormattingProvider() }
    private val codeActionProvider: CodeActionProvider by lazy { CodeActionProvider() }

    fun connect(client: LanguageClient) {
        this.client = client
    }

    // ---------------------------------------------------------------------
    // Document lifecycle
    // ---------------------------------------------------------------------

    @JsonNotification("textDocument/didOpen")
    override fun didOpen(params: DidOpenTextDocumentParams) {
        val item: TextDocumentItem = params.textDocument
        val uri = item.uri
        documents[uri] = item.text
        republishDiagnostics(uri, item.text)
    }

    @JsonNotification("textDocument/didChange")
    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        var text = documents[uri] ?: return
        for (change in params.contentChanges) {
            text = if (change.range != null) {
                applyRangeEdit(text, change.range, change.text)
            } else {
                change.text
            }
        }
        documents[uri] = text
        republishDiagnostics(uri, text)
    }

    @JsonNotification("textDocument/didSave")
    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = documents[uri]
        if (text != null) republishDiagnostics(uri, text)
    }

    @JsonNotification("textDocument/didClose")
    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        documents.remove(uri)
        // Clear diagnostics for the closed file.
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    fun onWatchedFilesChanged(params: DidChangeWatchedFilesParams) {
        if (params.changes == null) return
        for (event in params.changes) {
            val uri = event.uri
            if (documents.containsKey(uri)) {
                // Content changed externally; we can't read it here, but clear cache.
                parseBridge.invalidate(uri)
            }
        }
    }

    private fun republishDiagnostics(uri: String, text: String) {
        val client = client ?: return
        val diagnostics: List<Diagnostic> = parseBridge.parse(uri, text)
        client.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
        definitionReferenceProvider.index(uri, text)
    }

    internal fun applyRangeEdit(text: String, range: Range, replacement: String): String {
        val lines = text.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val startLine = range.start.line
        val startChar = range.start.character
        val endLine = range.end.line
        val endChar = range.end.character

        val sb = buildString {
            for (i in 0 until startLine) append(lines[i]).append('\n')
            val startLineText = lines[startLine]
            append(startLineText, 0, Math.min(startChar, startLineText.length))
            append(replacement)
            if (endLine < lines.size) {
                val endLineText = lines[endLine]
                append(endLineText, Math.min(endChar, endLineText.length), endLineText.length)
                for (i in endLine + 1 until lines.size) append('\n').append(lines[i])
            }
        }
        return sb
    }

    // ---------------------------------------------------------------------
    // Feature requests
    // ---------------------------------------------------------------------

    @JsonRequest("textDocument/completion")
    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync Either.forLeft(emptyList<CompletionItem>())
            val items = completionProvider.complete(
                uri, text, params.position,
                definitionReferenceProvider.variableNames(),
                definitionReferenceProvider.functionNames(),
                definitionReferenceProvider.commandNames(),
            )
            Either.forLeft(items)
        }
    }

    @JsonRequest("textDocument/hover")
    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync null
            hoverProvider.hover(uri, text, params.position)
        }
    }

    @JsonRequest("textDocument/signatureHelp")
    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync null
            signatureHelpProvider.signatureHelp(uri, text, params.position)
        }
    }

    @JsonRequest("textDocument/definition")
    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync Either.forLeft(emptyList<Location>())
            val locations: List<Location> = definitionReferenceProvider.definition(uri, text, params.position)
            Either.forLeft(locations)
        }
    }

    @JsonRequest("textDocument/references")
    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<Location>()
            definitionReferenceProvider.references(uri, text, params.position, params.context.isIncludeDeclaration)
        }
    }

    @JsonRequest("textDocument/documentSymbol")
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<Either<SymbolInformation, DocumentSymbol>>()
            documentSymbolProvider.documentSymbols(uri, text)
        }
    }

    @JsonRequest("textDocument/formatting")
    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<TextEdit>()
            formattingProvider.formatting(uri, text)
        }
    }

    @JsonRequest("textDocument/rangeFormatting")
    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<TextEdit>()
            formattingProvider.rangeFormatting(uri, text, params.range)
        }
    }

    @JsonRequest("textDocument/rename")
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync WorkspaceEdit()
            definitionReferenceProvider.rename(uri, text, params.position, params.newName)
        }
    }

    @JsonRequest("textDocument/prepareRename")
    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri] ?: return@computeAsync null
            definitionReferenceProvider.prepareRename(uri, text, params.position)
        }
    }

    @JsonRequest("textDocument/codeAction")
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<Either<Command, CodeAction>>()
            codeActionProvider.codeAction(uri, text, params)
        }
    }

    fun workspaceSymbols(query: String?): List<SymbolInformation> {
        return definitionReferenceProvider.workspaceSymbols(query)
    }
}
