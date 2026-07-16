package me.rohandacoder.lspskript

import ch.njol.skript.config.Config
import ch.njol.skript.config.Node
import ch.njol.skript.config.SectionNode
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Builds the outline (document symbols) by walking Skript's public
 * [Config]/[SectionNode] tree.
 */
class DocumentSymbolProvider {

    fun documentSymbols(uri: String, text: String): List<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> {
        val config = parseConfig(text) ?: return emptyList()
        val symbols: MutableList<DocumentSymbol> = mutableListOf()
        collect(config, symbols, 0)
        val result: MutableList<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> = mutableListOf()
        for (s in symbols) result.add(Either.forRight(s))
        return result
    }

    private fun collect(nodes: Iterable<Node>, out: MutableList<DocumentSymbol>, depth: Int) {
        for (node in nodes) {
            val key = node.key
            if (key == null || key.isEmpty()) continue
            val line = if (node.line > 0) node.line - 1 else 0

            val symbol = DocumentSymbol()
            symbol.name = key
            symbol.kind = kindOf(key)
            val range: Range = LspUtils.lineRange(line)
            symbol.range = range
            symbol.selectionRange = range

            if (node is SectionNode) {
                val children: MutableList<DocumentSymbol> = mutableListOf()
                collect(node, children, depth + 1)
                symbol.children = children
            }
            out.add(symbol)
        }
    }

    private fun kindOf(key: String): SymbolKind {
        val k = key.lowercase(java.util.Locale.ENGLISH)
        if (k.startsWith("on ")) return SymbolKind.Event
        if (k.startsWith("command ")) return SymbolKind.Function
        if (k.startsWith("function ")) return SymbolKind.Function
        if (k == "options" || k == "variables" || k == "aliases" || k.startsWith("using ")) return SymbolKind.Module
        return SymbolKind.Namespace
    }

    companion object {
        @JvmStatic
        fun parseConfig(text: String): Config? {
            return try {
                Config(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)), "lsp.sk", null, true, true, ":")
            } catch (e: Exception) {
                null
            }
        }
    }
}
