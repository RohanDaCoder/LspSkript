package me.rohandacoder.lspskript

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.skriptlang.skript.registration.SyntaxInfo
import org.skriptlang.skript.registration.SyntaxRegistry
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Produces context-aware completions by combining Skript's registered syntax
 * patterns (via the public [SyntaxRegistry]) with the line/column context
 * of the cursor.
 */
class CompletionProvider {

    fun complete(uri: String, text: String, position: Position): List<CompletionItem> {
        val line = lineAt(text, position.line)
        val prefix = currentWord(line, position.character)

        val context = detectContext(text, position)

        val items: MutableList<CompletionItem> = mutableListOf()
        when (context) {
            Context.TOP_LEVEL -> addStructures(items, prefix)
            Context.IN_SECTION -> {
                addEffectsConditions(items, prefix)
                addStructures(items, prefix) // allow nested e.g. command/function
            }
            Context.IN_EXPRESSION -> {
                addExpressions(items, prefix)
                addVariables(items, prefix)
            }
        }
        return items
    }

    private enum class Context { TOP_LEVEL, IN_SECTION, IN_EXPRESSION }

    private fun detectContext(text: String, position: Position): Context {
        // Inspect indentation/structure of preceding lines.
        val lineIdx = position.line
        val lines = text.split("\n".toRegex()).toTypedArray()
        val indent = indentOf(lineAt(text, lineIdx))

        // If the current line is inside a %...% expression, offer expressions.
        val currentLine = lineAt(text, lineIdx)
        val cursor = position.character
        val before = currentLine.substring(0, Math.min(cursor, currentLine.length))
        if (before.contains("%") && before.indexOf('%', before.indexOf('%') + 1) == -1) {
            // unbalanced '%' -> inside an expression slot
            return Context.IN_EXPRESSION
        }

        var insideSection = false
        var i = lineIdx - 1
        while (i >= 0) {
            val l = lines[i]
            if (l.trim().isEmpty() || l.trim().startsWith("#")) {
                i--
                continue
            }
            val ind = indentOf(l)
            if (ind < indent) {
                insideSection = !isTopLevelStructure(l.trim())
                break
            }
            i--
        }
        return if (insideSection) Context.IN_SECTION else Context.TOP_LEVEL
    }

    private fun isTopLevelStructure(trimmed: String): Boolean {
        // Heuristics: events, commands, functions, options, variables, using, aliases.
        return trimmed.startsWith("on ")
            || trimmed.startsWith("command ")
            || trimmed.startsWith("function ")
            || trimmed.startsWith("options")
            || trimmed.startsWith("variables")
            || trimmed.startsWith("using ")
            || trimmed.startsWith("aliases")
    }

    private fun addStructures(items: MutableList<CompletionItem>, prefix: String) {
        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        addFrom(reg.syntaxes(SyntaxRegistry.STRUCTURE), items, prefix, CompletionItemKind.Class)
        addFrom(reg.syntaxes(SyntaxRegistry.SECTION), items, prefix, CompletionItemKind.Class)
    }

    private fun addEffectsConditions(items: MutableList<CompletionItem>, prefix: String) {
        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        addFrom(reg.syntaxes(SyntaxRegistry.EFFECT), items, prefix, CompletionItemKind.Function)
        addFrom(reg.syntaxes(SyntaxRegistry.CONDITION), items, prefix, CompletionItemKind.Event)
    }

    private fun addExpressions(items: MutableList<CompletionItem>, prefix: String) {
        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        addFrom(reg.syntaxes(SyntaxRegistry.EXPRESSION), items, prefix, CompletionItemKind.Field)
    }

    private fun addFrom(infos: Collection<out SyntaxInfo<*>>, items: MutableList<CompletionItem>, prefix: String, kind: CompletionItemKind) {
        for (info in infos) {
            for (pattern in info.patterns()) {
                val cleaned = cleanPattern(pattern)
                if (cleaned.isEmpty()) continue
                if (prefix.isNotEmpty() && !cleaned.lowercase(Locale.ENGLISH).startsWith(prefix.lowercase(Locale.ENGLISH))) continue
                items.add(buildItem(info, cleaned, kind))
            }
        }
        // De-duplicate by label.
        val seen: MutableSet<String> = mutableSetOf()
        items.removeIf { !seen.add(it.label) }
    }

    private fun buildItem(info: SyntaxInfo<*>, pattern: String, kind: CompletionItemKind): CompletionItem {
        val item = CompletionItem()
        item.label = pattern
        item.kind = kind
        item.detail = if (info.origin() == null) "Skript" else info.origin().toString()

        // Build a snippet: replace %type% with ${n:type} tabstops.
        val snippet = toSnippet(pattern)
        if (snippet.contains("\${")) {
            item.insertText = snippet
            item.insertTextFormat = InsertTextFormat.Snippet
        } else {
            item.insertText = pattern
        }
        item.setDocumentation(pattern)
        return item
    }

    companion object {
        val trailingWord: Pattern = Pattern.compile("([A-Za-z][A-Za-z0-9 _%-]*)\\z")

        @JvmStatic
        fun cleanPattern(pattern: String): String {
        // Remove parse tags like [a:], markers, and %types% are kept for display.
        val s = pattern.replace("\\[[a-zA-Z]:".toRegex(), "[")
        return s.trim()
    }

    @JvmStatic
    fun toSnippet(pattern: String): String {
        val m: Matcher = Pattern.compile("%([^%]+)%").matcher(pattern)
        val sb = StringBuffer()
        var i = 1
        while (m.find()) {
            val type = m.group(1).replace("[-@0-9]".toRegex(), "").trim()
            m.appendReplacement(sb, "\${" + (i++) + ":" + Matcher.quoteReplacement(type) + "}")
        }
        m.appendTail(sb)
        return sb.toString()
    }

    private fun addVariables(items: MutableList<CompletionItem>, prefix: String) {
        if (prefix.startsWith("{")) {
            val item = CompletionItem()
            item.label = "{variable}"
            item.kind = CompletionItemKind.Variable
            item.insertText = "{variable}"
            item.setDocumentation("A Skript variable. Use {name::%expr%} for lists.")
            items.add(item)
        }
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    @JvmStatic
    fun lineAt(text: String, line: Int): String {
        val lines = text.split("\n".toRegex()).toTypedArray()
        return if (line >= 0 && line < lines.size) lines[line] else ""
    }

    @JvmStatic
    fun indentOf(line: String): Int {
        var i = 0
        while (i < line.length && (line[i] == '\t' || line[i] == ' ')) i++
        return i
    }

    @JvmStatic
    fun currentWord(line: String, character: Int): String {
        val before = line.substring(0, Math.min(character, line.length))
        val m: Matcher = trailingWord.matcher(before)
        return if (m.find()) m.group(1).trim() else ""
    }

    @JvmStatic
    fun wordRange(line: String, character: Int): Range {
        var start = character
        while (start > 0 && Character.isLetterOrDigit(line[start - 1])) start--
        var end = character
        while (end < line.length && Character.isLetterOrDigit(line[end])) end++
        return Range(Position(0, start), Position(0, end))
    }
    }
}
