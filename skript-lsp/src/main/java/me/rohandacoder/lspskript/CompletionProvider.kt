package me.rohandacoder.lspskript

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
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

    fun complete(
        uri: String,
        text: String,
        position: Position,
        knownVariables: Collection<String> = emptyList(),
        functionNames: Collection<String> = emptyList(),
        commandNames: Collection<String> = emptyList(),
    ): List<CompletionItem> {
        val line = lineAt(text, position.line)
        val prefix = currentWord(line, position.character)
        // Exact span the client should replace on accept. `prefix` is the
        // trailing chunk before the cursor, so its start is simply here.
        val editRange = Range(
            Position(position.line, position.character - prefix.length),
            Position(position.line, position.character)
        )

        val context = detectContext(text, position)

        val items: MutableList<CompletionItem> = mutableListOf()
        when (context) {
            Context.TOP_LEVEL -> addStructures(items, prefix, editRange)
            Context.IN_SECTION -> {
                addEffectsConditions(items, prefix, editRange)
                addStructures(items, prefix, editRange) // allow nested e.g. command/function
                addUserFunctions(items, prefix, editRange, functionNames)
                addCommands(items, prefix, editRange, commandNames)
            }
            Context.IN_EXPRESSION -> {
                addExpressions(items, prefix, editRange)
                if (hasBraceBefore(line, position.character)) {
                    addVariables(items, prefix, editRange, knownVariables)
                }
            }
        }
        return items
    }

    internal enum class Context { TOP_LEVEL, IN_SECTION, IN_EXPRESSION }

    internal fun detectContext(text: String, position: Position): Context {
        // Inspect indentation/structure of preceding lines.
        val lineIdx = position.line
        val lines = text.split('\n')
        val indent = indentOf(lineAt(text, lineIdx))

        // If the current line is inside a %...% expression, offer expressions.
        val currentLine = lineAt(text, lineIdx)
        val cursor = position.character
        val before = currentLine.substring(0, Math.min(cursor, currentLine.length))
        if (before.count { it == '%' } % 2 == 1) {
            // odd number of '%' on the line before the cursor -> inside a
            // %...% expression slot
            return Context.IN_EXPRESSION
        }
        if (before.contains("{")) {
            // typing inside (or after) a `{...}` variable brace
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
                // Found the enclosing block header: the cursor is inside a
                // section (event, command, function, if, loop, ...).
                insideSection = true
                break
            }
            i--
        }
        return if (insideSection) Context.IN_SECTION else Context.TOP_LEVEL
    }

    private fun addStructures(items: MutableList<CompletionItem>, prefix: String, range: Range) {
        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        addFrom(reg.syntaxes(SyntaxRegistry.STRUCTURE), items, prefix, range, CompletionItemKind.Class)
        addFrom(reg.syntaxes(SyntaxRegistry.SECTION), items, prefix, range, CompletionItemKind.Class)
    }

    private fun addEffectsConditions(items: MutableList<CompletionItem>, prefix: String, range: Range) {
        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        addFrom(reg.syntaxes(SyntaxRegistry.EFFECT), items, prefix, range, CompletionItemKind.Function)
        addFrom(reg.syntaxes(SyntaxRegistry.CONDITION), items, prefix, range, CompletionItemKind.Event)
    }

    private fun addExpressions(items: MutableList<CompletionItem>, prefix: String, range: Range) {
        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        addFrom(reg.syntaxes(SyntaxRegistry.EXPRESSION), items, prefix, range, CompletionItemKind.Field)
    }

    private fun addUserFunctions(
        items: MutableList<CompletionItem>,
        prefix: String,
        range: Range,
        functionNames: Collection<String>,
    ) {
        // Only while actually typing the name; a bare insert after `(` would
        // duplicate the already-typed call.
        if (prefix.isEmpty()) return
        for (name in functionNames) {
            if (!name.startsWith(prefix.lowercase(Locale.ENGLISH))) continue
            val item = CompletionItem()
            item.label = "$name()"
            item.kind = CompletionItemKind.Function
            item.detail = "user function"
            val snippet = "$name(\${1})"
            item.insertText = snippet
            item.insertTextFormat = InsertTextFormat.Snippet
            item.textEdit = Either.forLeft(TextEdit(range, snippet))
            items.add(item)
        }
    }

    private fun addCommands(
        items: MutableList<CompletionItem>,
        prefix: String,
        range: Range,
        commandNames: Collection<String>,
    ) {
        if (prefix.isEmpty()) return
        for (name in commandNames) {
            if (!name.startsWith(prefix.lowercase(Locale.ENGLISH))) continue
            val item = CompletionItem()
            item.label = "/$name"
            item.kind = CompletionItemKind.Function
            item.detail = "user command"
            item.insertText = "/$name"
            item.textEdit = Either.forLeft(TextEdit(range, "/$name"))
            items.add(item)
        }
    }

    private fun addFrom(infos: Collection<SyntaxInfo<*>>, items: MutableList<CompletionItem>, prefix: String, range: Range, kind: CompletionItemKind) {
        for (info in infos) {
            for (pattern in info.patterns()) {
                val cleaned = cleanPattern(pattern)
                if (cleaned.isEmpty()) continue
                if (prefix.isNotEmpty() && !cleaned.lowercase(Locale.ENGLISH).startsWith(prefix.lowercase(Locale.ENGLISH))) continue
                items.add(buildItem(info, cleaned, kind, range))
            }
        }
        // De-duplicate by label.
        val seen: MutableSet<String> = mutableSetOf()
        items.removeIf { !seen.add(it.label) }
    }

    private fun buildItem(info: SyntaxInfo<*>, pattern: String, kind: CompletionItemKind, range: Range): CompletionItem {
        val item = CompletionItem()
        item.label = pattern
        item.kind = kind
        item.detail = if (info.origin() == null) "Skript" else info.origin().toString()

        // Build a snippet: replace %type% with ${n:type} tabstops.
        val snippet = toSnippet(pattern)
        if (snippet.contains("\${")) {
            item.insertText = snippet
            item.insertTextFormat = InsertTextFormat.Snippet
            item.textEdit = Either.forLeft(TextEdit(range, snippet))
        } else {
            item.insertText = pattern
            item.textEdit = Either.forLeft(TextEdit(range, pattern))
        }
        item.setDocumentation(pattern)
        return item
    }

    companion object {
        // The trailing identifier-like chunk before the cursor: letters, digits,
        // `_`, `{`, `}`, `%`, `.` and `-`. Deliberately excludes whitespace so
        // completing after a space yields the word being typed, not the phrase.
        val trailingWord: Pattern = Pattern.compile("([A-Za-z0-9_{}%.-]*)\\z")

        /** Parse-tag marker like `[a:` (the `[` is kept for display). */
        private val tagPattern: Regex = Regex("\\[[a-zA-Z]:")

        /** `%type%` slot markers, for building snippets and signatures. */
        private val typeSlotPattern: Pattern = Pattern.compile("%([^%]+)%")

        /** Type-shaping characters stripped when naming snippet tabstops. */
        private val typeStripPattern: Regex = Regex("[-@0-9]")

        @JvmStatic
        fun cleanPattern(pattern: String): String {
            // Remove parse tags like [a:], markers, and %types% are kept for display.
            return pattern.replace(tagPattern, "[").trim()
        }

        @JvmStatic
        fun toSnippet(pattern: String): String {
            val m: Matcher = typeSlotPattern.matcher(pattern)
            val sb = StringBuilder()
            var i = 1
            var last = 0
            while (m.find()) {
                val type = m.group(1).replace(typeStripPattern, "").trim()
                sb.append(pattern, last, m.start())
                sb.append("\${").append(i++).append(':').append(type).append('}')
                last = m.end()
            }
            sb.append(pattern, last, pattern.length)
            return sb.toString()
        }

    private fun addVariables(items: MutableList<CompletionItem>, prefix: String, range: Range, knownVariables: Collection<String>) {
        val filter = prefix.removePrefix("{").lowercase(Locale.ENGLISH)
        for (name in knownVariables) {
            if (name.lowercase(Locale.ENGLISH).startsWith(filter)) {
                val item = CompletionItem()
                item.label = "{$name}"
                item.kind = CompletionItemKind.Variable
                item.insertText = "{$name}"
                // Replaces the `{..` typed so far, including the brace.
                item.textEdit = Either.forLeft(TextEdit(range, "{$name}"))
                items.add(item)
            }
        }
        if (knownVariables.none { it.lowercase(Locale.ENGLISH).startsWith(filter) }) {
            val item = CompletionItem()
            item.label = "{variable}"
            item.kind = CompletionItemKind.Variable
            item.insertText = "{variable}"
            item.textEdit = Either.forLeft(TextEdit(range, "{variable}"))
            item.setDocumentation("A Skript variable. Use {name::%expr%} for lists.")
            items.add(item)
        }
    }

    private fun hasBraceBefore(line: String, character: Int): Boolean =
        line.substring(0, Math.min(character, line.length)).contains("{")

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    @JvmStatic
    fun lineAt(text: String, line: Int): String {
        val lines = text.split('\n')
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
    fun wordRange(line: String, character: Int, lineIndex: Int = 0): Range {
        var start = character
        while (start > 0 && Character.isLetterOrDigit(line[start - 1])) start--
        var end = character
        while (end < line.length && Character.isLetterOrDigit(line[end])) end++
        return Range(Position(lineIndex, start), Position(lineIndex, end))
    }
    }
}
