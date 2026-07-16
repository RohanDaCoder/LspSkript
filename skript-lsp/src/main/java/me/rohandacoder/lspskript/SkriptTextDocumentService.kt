package me.rohandacoder.lspskript

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.net.URI
import java.util.*
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
    @Volatile
    private var parseBridge: ParseBridge? = null
    @Volatile
    private var completionProvider: CompletionProvider? = null
    @Volatile
    private var hoverProvider: HoverProvider? = null
    @Volatile
    private var signatureHelpProvider: SignatureHelpProvider? = null
    @Volatile
    private var documentSymbolProvider: DocumentSymbolProvider? = null
    @Volatile
    private var definitionReferenceProvider: DefinitionReferenceProvider? = null
    @Volatile
    private var formattingProvider: FormattingProvider? = null
    @Volatile
    private var renameProvider: RenameProvider? = null
    @Volatile
    private var codeActionProvider: CodeActionProvider? = null

    fun connect(client: LanguageClient) {
        this.client = client
    }

    private fun parseBridge(): ParseBridge {
        if (parseBridge == null) {
            synchronized(this) {
                if (parseBridge == null) parseBridge = ParseBridge()
            }
        }
        return parseBridge!!
    }

    private fun completionProvider(): CompletionProvider {
        if (completionProvider == null) {
            synchronized(this) {
                if (completionProvider == null) completionProvider = CompletionProvider()
            }
        }
        return completionProvider!!
    }

    private fun hoverProvider(): HoverProvider {
        if (hoverProvider == null) {
            synchronized(this) {
                if (hoverProvider == null) hoverProvider = HoverProvider()
            }
        }
        return hoverProvider!!
    }

    private fun signatureHelpProvider(): SignatureHelpProvider {
        if (signatureHelpProvider == null) {
            synchronized(this) {
                if (signatureHelpProvider == null) signatureHelpProvider = SignatureHelpProvider()
            }
        }
        return signatureHelpProvider!!
    }

    private fun documentSymbolProvider(): DocumentSymbolProvider {
        if (documentSymbolProvider == null) {
            synchronized(this) {
                if (documentSymbolProvider == null) documentSymbolProvider = DocumentSymbolProvider()
            }
        }
        return documentSymbolProvider!!
    }

    private fun definitionReferenceProvider(): DefinitionReferenceProvider {
        if (definitionReferenceProvider == null) {
            synchronized(this) {
                if (definitionReferenceProvider == null) definitionReferenceProvider = DefinitionReferenceProvider()
            }
        }
        return definitionReferenceProvider!!
    }

    private fun formattingProvider(): FormattingProvider {
        if (formattingProvider == null) {
            synchronized(this) {
                if (formattingProvider == null) formattingProvider = FormattingProvider()
            }
        }
        return formattingProvider!!
    }

    private fun renameProvider(): RenameProvider {
        if (renameProvider == null) {
            synchronized(this) {
                if (renameProvider == null) renameProvider = RenameProvider()
            }
        }
        return renameProvider!!
    }

    private fun codeActionProvider(): CodeActionProvider {
        if (codeActionProvider == null) {
            synchronized(this) {
                if (codeActionProvider == null) codeActionProvider = CodeActionProvider()
            }
        }
        return codeActionProvider!!
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
        var text = documents[uri]
        for (change in params.contentChanges) {
            text = if (change.range != null) {
                applyRangeEdit(text!!, change.range, change.text)
            } else {
                change.text
            }
        }
        documents[uri] = text!!
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
        if (client != null) {
            client!!.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
        }
    }

    fun onWatchedFilesChanged(params: DidChangeWatchedFilesParams) {
        if (params.changes == null) return
        for (event in params.changes) {
            val uri = event.uri
            if (documents.containsKey(uri)) {
                // Content changed externally; we can't read it here, but clear cache.
                parseBridge().invalidate(uri)
            }
        }
    }

    private fun republishDiagnostics(uri: String, text: String) {
        if (client == null) return
        val diagnostics: List<Diagnostic> = parseBridge().parse(uri, text)
        client!!.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
        definitionReferenceProvider().index(uri, text)
    }

    private fun applyRangeEdit(text: String, range: Range, replacement: String): String {
        val lines = text.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val startLine = range.start.line
        val startChar = range.start.character
        val endLine = range.end.line
        val endChar = range.end.character

        val sb = StringBuilder()
        for (i in 0 until startLine) sb.append(lines[i]).append('\n')
        val startLineText = lines[startLine]
        sb.append(startLineText, 0, Math.min(startChar, startLineText.length))
        sb.append(replacement)
        if (endLine < lines.size) {
            val endLineText = lines[endLine]
            sb.append(endLineText, Math.min(endChar, endLineText.length), endLineText.length)
            for (i in endLine + 1 until lines.size) sb.append('\n').append(lines[i])
        }
        return sb.toString()
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
            val items = completionProvider().complete(uri, text, params.position)
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
            hoverProvider().hover(uri, text, params.position)
        }
    }

    @JsonRequest("textDocument/signatureHelp")
    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync null
            signatureHelpProvider().signatureHelp(uri, text, params.position)
        }
    }

    @JsonRequest("textDocument/definition")
    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<out Location>, List<out LocationLink>>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync Either.forLeft(emptyList<Location>())
            val locations: List<out Location> = definitionReferenceProvider().definition(uri, text, params.position)
            Either.forLeft(locations)
        }
    }

    @JsonRequest("textDocument/references")
    override fun references(params: ReferenceParams): CompletableFuture<List<out Location>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<Location>()
            definitionReferenceProvider().references(uri, text, params.position, params.context.isIncludeDeclaration)
        }
    }

    @JsonRequest("textDocument/documentSymbol")
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<Either<SymbolInformation, DocumentSymbol>>()
            documentSymbolProvider().documentSymbols(uri, text)
        }
    }

    @JsonRequest("textDocument/formatting")
    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<out TextEdit>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<TextEdit>()
            formattingProvider().formatting(uri, text)
        }
    }

    @JsonRequest("textDocument/rangeFormatting")
    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<out TextEdit>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<TextEdit>()
            formattingProvider().rangeFormatting(uri, text, params.range)
        }
    }

    @JsonRequest("textDocument/rename")
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync WorkspaceEdit()
            renameProvider().rename(uri, text, params.position, params.newName)
        }
    }

    @JsonRequest("textDocument/codeAction")
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return CompletableFutures.computeAsync { cancelToken ->
            cancelToken.checkCanceled()
            val uri = params.textDocument.uri
            val text = documents[uri]
            if (text == null) return@computeAsync emptyList<Either<Command, CodeAction>>()
            codeActionProvider().codeAction(uri, text, params)
        }
    }

    fun workspaceSymbols(query: String?): List<SymbolInformation> {
        return definitionReferenceProvider().workspaceSymbols(query)
    }

    fun getDocument(uri: String): String? = documents[uri]
}
