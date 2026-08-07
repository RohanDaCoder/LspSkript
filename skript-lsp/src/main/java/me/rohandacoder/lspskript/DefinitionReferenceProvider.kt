package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Tracks definitions (functions, commands) and variables across all open
 * documents to support go-to-definition, find-references, workspace symbols,
 * rename and prepare-rename.
 *
 * The index is rebuilt on every document change and published as an immutable
 * snapshot, so readers on other LSP worker threads never observe a partially
 * updated map. Document texts live in a [ConcurrentHashMap]; all lookups read
 * the current [snapshot].
 *
 * Locations carry precise token ranges (not whole lines), which keeps
 * find-references accurate and lets rename replace just the identifier.
 */
class DefinitionReferenceProvider {

    private val documents: MutableMap<String, String> = ConcurrentHashMap()

    /** Immutable index snapshot; swapped atomically by [index]. */
    @Volatile
    private var snapshot: Index = Index(emptyMap(), emptyMap(), emptySet(), emptySet())

    private val functionDef: Pattern = Pattern.compile("^\\s*function\\s+([a-zA-Z0-9_]+)\\s*\\(")
    private val commandDef: Pattern = Pattern.compile("^\\s*command\\s+/([^\\s<]+)")
    private val varUsage: Pattern = Pattern.compile("\\{([^{}]+)\\}")
    private val wordPattern: Pattern = Pattern.compile("[a-zA-Z0-9_]+")

    private class Index(
        /** function/command bare name (lowercase) -> definition location. */
        val definitions: Map<String, Location>,
        /** variable base name -> locations of every `{...}` occurrence. */
        val variables: Map<String, List<Location>>,
        /** lowercase bare names of user-defined functions. */
        val functions: Set<String>,
        /** lowercase bare names of user-defined commands (no leading `/`). */
        val commands: Set<String>,
    )

    fun index(uri: String, text: String) {
        documents[uri] = text
        rebuild()
    }

    /** Names of all variables seen across open documents (for completion). */
    fun variableNames(): Set<String> = snapshot.variables.keys

    /** Bare names of user-defined functions across open documents (for completion). */
    fun functionNames(): Set<String> = snapshot.functions

    /** Bare names of user-defined commands, without the leading `/` (for completion). */
    fun commandNames(): Set<String> = snapshot.commands

    private fun rebuild() {
        val definitions: MutableMap<String, Location> = mutableMapOf()
        val variables: MutableMap<String, MutableList<Location>> = mutableMapOf()
        val functions: MutableSet<String> = mutableSetOf()
        val commands: MutableSet<String> = mutableSetOf()
        for ((uri, text) in documents) {
            val lines = text.split('\n')
            for (i in lines.indices) {
                val line = lines[i]
                val fm: Matcher = functionDef.matcher(line)
                if (fm.find()) {
                    val name = fm.group(1).lowercase(Locale.ENGLISH)
                    definitions.putIfAbsent(name, Location(uri, matchRange(i, fm, 1)))
                    functions.add(name)
                    continue
                }
                val cm: Matcher = commandDef.matcher(line)
                if (cm.find()) {
                    val name = cm.group(1).lowercase(Locale.ENGLISH)
                    definitions.putIfAbsent(name, Location(uri, commandNameRange(i, cm)))
                    commands.add(name)
                }
                val vm: Matcher = varUsage.matcher(line)
                while (vm.find()) {
                    val base = baseVarName(vm.group(1))
                    // group 0 = the full `{...}` span; rename needs the braces
                    // to locate the base segment inside the match.
                    variables.computeIfAbsent(base) { mutableListOf() }
                        .add(Location(uri, matchRange(i, vm, 0)))
                }
            }
        }
        snapshot = Index(definitions, variables, functions, commands)
    }

    fun definition(uri: String, text: String, position: Position): List<Location> {
        val index = snapshot
        val varAt = variableMatchAtCursor(text, position)
        if (varAt != null) {
            return index.variables[varAt.first] ?: emptyList()
        }
        val word = wordAt(text, position)
        if (word.isEmpty()) return emptyList()
        val loc = index.definitions[word.lowercase(Locale.ENGLISH)]
        return if (loc != null) listOf(loc) else emptyList()
    }

    fun references(uri: String, text: String, position: Position, includeDeclaration: Boolean): List<Location> {
        val index = snapshot
        val varAt = variableMatchAtCursor(text, position)
        if (varAt != null) {
            return index.variables[varAt.first] ?: emptyList()
        }
        val word = wordAt(text, position)
        if (word.isEmpty()) return emptyList()
        val def = index.definitions[word.lowercase(Locale.ENGLISH)] ?: return emptyList()
        return findWordUsages(word, def, includeDeclaration)
    }

    @Suppress("DEPRECATION")
    fun workspaceSymbols(query: String?): List<SymbolInformation> {
        val result: MutableList<SymbolInformation> = mutableListOf()
        val q = query?.lowercase(Locale.ENGLISH) ?: ""
        for ((key, value) in snapshot.definitions) {
            if (key.contains(q)) result.add(SymbolInformation(key, SymbolKind.Function, value))
        }
        return result
    }

    /**
     * Returns the range the client should pre-select for rename, or null when
     * the symbol at [position] cannot be renamed.
     */
    fun prepareRename(uri: String, text: String, position: Position): Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>? {
        val index = snapshot
        val varAt = variableMatchAtCursor(text, position)
        if (varAt != null) {
            return Either3.forFirst(varAt.second)
        }
        val word = wordAt(text, position)
        if (word.isEmpty()) return null
        val def = index.definitions[word.lowercase(Locale.ENGLISH)] ?: return null
        return Either3.forFirst(wordRangeAt(text, position))
    }

    fun rename(uri: String, text: String, position: Position, newName: String): WorkspaceEdit {
        val index = snapshot
        val varAt = variableMatchAtCursor(text, position)
        if (varAt != null) {
            return renameVariables(varAt.first, newName)
        }
        val word = wordAt(text, position)
        if (word.isEmpty()) return WorkspaceEdit()
        val def = index.definitions[word.lowercase(Locale.ENGLISH)] ?: return WorkspaceEdit()
        val refs = findWordUsages(word, def, includeDeclaration = true)
        if (refs.isEmpty()) return WorkspaceEdit()

        val changes: MutableMap<String, MutableList<TextEdit>> = mutableMapOf()
        for (loc in refs) {
            changes.computeIfAbsent(loc.uri) { mutableListOf() }.add(TextEdit(loc.range, newName))
        }
        return workspaceEdit(changes)
    }

    /**
     * Renames the *base* of a variable (everything up to the first `::`), so
     * `{foo}`, `{foo::a}` and `{foo::b}` all follow the rename while list
     * indices are preserved: `{foo::a}` -> `{bar::a}`.
     */
    private fun renameVariables(base: String, newName: String): WorkspaceEdit {
        val newBase = variableNewName(newName)
        if (newBase.isEmpty()) return WorkspaceEdit()
        val locs = snapshot.variables[base] ?: return WorkspaceEdit()

        val changes: MutableMap<String, MutableList<TextEdit>> = mutableMapOf()
        for (loc in locs) {
            val doc = documents[loc.uri] ?: continue
            val matchText = textAt(doc, loc.range) ?: continue
            if (matchText.length < 2 || !matchText.startsWith("{")) continue
            val inner = matchText.substring(1, matchText.length - 1)
            val dbl = inner.indexOf("::")
            val baseLen = if (dbl >= 0) dbl else inner.length
            val start = loc.range.start.character + 1
            val editRange = Range(
                Position(loc.range.start.line, start),
                Position(loc.range.start.line, start + baseLen)
            )
            changes.computeIfAbsent(loc.uri) { mutableListOf() }.add(TextEdit(editRange, newBase))
        }
        return workspaceEdit(changes)
    }

    // ------------------------------------------------------------------

    /** Whole-word, case-insensitive usages of [word], skipping variable braces. */
    private fun findWordUsages(word: String, definition: Location, includeDeclaration: Boolean): List<Location> {
        val lower = word.lowercase(Locale.ENGLISH)
        val result: MutableList<Location> = mutableListOf()
        for ((uri, text) in documents) {
            val lines = text.split('\n')
            for (i in lines.indices) {
                val m: Matcher = wordPattern.matcher(lines[i])
                while (m.find()) {
                    if (!m.group().lowercase(Locale.ENGLISH).equals(lower)) continue
                    if (insideBraces(lines[i], m.start())) continue
                    val isDefLine = uri == definition.uri && i == definition.range.start.line
                    if (isDefLine && !includeDeclaration) continue
                    result.add(Location(uri, Range(Position(i, m.start()), Position(i, m.end()))))
                }
            }
        }
        return result
    }

    /** True when [charOffset] falls inside a `{...}` span on [line]. */
    private fun insideBraces(line: String, charOffset: Int): Boolean {
        val m: Matcher = varUsage.matcher(line)
        while (m.find()) {
            if (charOffset >= m.start() && charOffset < m.end()) return true
        }
        return false
    }

    private fun wordAt(text: String, position: Position): String {
        val line = CompletionProvider.lineAt(text, position.line)
        val r = wordRangeAt(text, position)
        return line.substring(r.start.character, r.end.character)
    }

    private fun wordRangeAt(text: String, position: Position): Range {
        val line = CompletionProvider.lineAt(text, position.line)
        return CompletionProvider.wordRange(line, position.character, position.line)
    }

    /** The `{...}` occurrence under the cursor, with its precise range. */
    private fun variableMatchAtCursor(text: String, position: Position): Pair<String, Range>? {
        val line = CompletionProvider.lineAt(text, position.line)
        val m: Matcher = varUsage.matcher(line)
        while (m.find()) {
            if (position.character >= m.start() && position.character <= m.end()) {
                val range = Range(Position(position.line, m.start()), Position(position.line, m.end()))
                return baseVarName(m.group(1)) to range
            }
        }
        return null
    }

    /** Range of regex [group] within [lineIndex] (matcher was built from that line). */
    private fun matchRange(lineIndex: Int, m: Matcher, group: Int): Range =
        Range(Position(lineIndex, m.start(group)), Position(lineIndex, m.end(group)))

    /** Range of the bare command name; the leading `/` is outside the group. */
    private fun commandNameRange(lineIndex: Int, m: Matcher): Range =
        Range(Position(lineIndex, m.start(1)), Position(lineIndex, m.end(1)))

    private fun textAt(text: String, range: Range): String? {
        if (range.start.line != range.end.line) return null
        val line = text.split('\n').getOrNull(range.start.line) ?: return null
        val start = range.start.character.coerceIn(0, line.length)
        val end = range.end.character.coerceIn(start, line.length)
        return line.substring(start, end)
    }

    private fun workspaceEdit(changes: Map<String, MutableList<TextEdit>>): WorkspaceEdit {
        val edit = WorkspaceEdit()
        edit.changes = changes
        return edit
    }

    internal fun baseVarName(variable: String): String {
        // normalise: strip list indices and lowercase; keep :: structure base.
        var s = variable.trim().lowercase(Locale.ENGLISH)
        s = s.replace("{", "").replace("}", "")
        // base is everything up to first :: or first index segment
        val dbl = s.indexOf("::")
        if (dbl >= 0) s = s.substring(0, dbl)
        return s
    }

    /** Normalises a user-supplied variable rename to a bare base name. */
    private fun variableNewName(newName: String): String {
        var s = newName.trim()
        if (s.startsWith("{")) s = s.removePrefix("{")
        if (s.endsWith("}")) s = s.removeSuffix("}")
        val dbl = s.indexOf("::")
        if (dbl >= 0) s = s.substring(0, dbl)
        return s
    }
}
