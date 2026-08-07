package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.skriptlang.skript.registration.SyntaxInfo
import org.skriptlang.skript.registration.SyntaxRegistry
import java.util.*

/**
 * Provides hover documentation by matching the word under the cursor against
 * Skript's registered syntax and rendering Markdown from its metadata.
 */
class HoverProvider {

    fun hover(uri: String, text: String, position: Position): Hover? {
        val word = wordAt(text, position)
        if (word.isEmpty()) return null

        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        val matches: MutableList<SyntaxInfo<*>> = mutableListOf()
        collect(reg.syntaxes(SyntaxRegistry.EFFECT), word, matches)
        collect(reg.syntaxes(SyntaxRegistry.CONDITION), word, matches)
        collect(reg.syntaxes(SyntaxRegistry.EXPRESSION), word, matches)
        collect(reg.syntaxes(SyntaxRegistry.STRUCTURE), word, matches)
        collect(reg.syntaxes(SyntaxRegistry.SECTION), word, matches)

        if (matches.isEmpty()) return null

        val md = buildString {
            for (info in matches) {
                append("### ${info.origin() ?: "Skript"}\n\n")
                for (pattern in info.patterns()) {
                    append("`${CompletionProvider.cleanPattern(pattern)}`\n\n")
                }
            }
        }
        val hover = Hover()
        hover.setContents(MarkupContent(MarkupKind.MARKDOWN, md))
        hover.range = CompletionProvider.wordRange(
            CompletionProvider.lineAt(text, position.line),
            position.character,
            position.line
        )
        return hover
    }

    private fun collect(infos: Collection<SyntaxInfo<*>>, word: String, out: MutableList<SyntaxInfo<*>>) {
        val lower = word.lowercase(Locale.ENGLISH)
        for (info in infos) {
            for (pattern in info.patterns()) {
                val cleaned = CompletionProvider.cleanPattern(pattern).lowercase(Locale.ENGLISH)
                if (cleaned.contains(lower)) {
                    out.add(info)
                    break
                }
            }
        }
    }

    private fun wordAt(text: String, position: Position): String {
        val line = CompletionProvider.lineAt(text, position.line)
        val r: Range = CompletionProvider.wordRange(line, position.character)
        return line.substring(r.start.character, r.end.character)
    }
}
