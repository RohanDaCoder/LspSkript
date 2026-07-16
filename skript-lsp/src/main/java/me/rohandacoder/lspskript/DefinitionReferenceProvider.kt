package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import java.net.URI
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Tracks definitions (functions, commands) and variables across all open
 * documents to support go-to-definition, find-references, workspace symbols,
 * and rename.
 */
class DefinitionReferenceProvider {

    private val documents: MutableMap<String, String> = HashMap()

    // function/command name (lowercase) -> location
    private val definitions: MutableMap<String, Location> = HashMap()
    // variable base name -> locations of declarations/usages
    private val variables: MutableMap<String, MutableList<Location>> = HashMap()

    private val functionDef: Pattern = Pattern.compile("^\\s*function\\s+([a-zA-Z0-9_]+)\\s*\\(")
    private val commandDef: Pattern = Pattern.compile("^\\s*command\\s+/(.+?)\\s*:")
    private val varUsage: Pattern = Pattern.compile("\\{([^{}]+)\\}")

    fun index(uri: String, text: String) {
        documents[uri] = text
        rebuild()
    }

    private fun rebuild() {
        definitions.clear()
        variables.clear()
        for ((uri, text) in documents) {
            val lines = text.split("\n".toRegex()).toTypedArray()
            for (i in lines.indices) {
                val line = lines[i]
                val fm: Matcher = functionDef.matcher(line)
                if (fm.find()) {
                    definitions[fm.group(1).lowercase(Locale.ENGLISH)] = Location(uri, LspUtils.lineRange(i))
                    continue
                }
                val cm: Matcher = commandDef.matcher(line)
                if (cm.find()) {
                    definitions[cm.group(1).lowercase(Locale.ENGLISH)] = Location(uri, LspUtils.lineRange(i))
                }
                val vm: Matcher = varUsage.matcher(line)
                while (vm.find()) {
                    val base = baseVarName(vm.group(1))
                    variables.computeIfAbsent(base) { ArrayList() }.add(Location(uri, LspUtils.lineRange(i)))
                }
            }
        }
    }

    fun definition(uri: String, text: String, position: Position): List<out Location> {
        val word = wordAt(text, position)
        if (word.isEmpty()) return emptyList()
        val loc = definitions[word.lowercase(Locale.ENGLISH)]
        if (loc != null) return listOf(loc)
        // variable?
        val varBase = variableAtCursor(text, position)
        if (varBase != null) {
            val locs = variables[varBase]
            return locs ?: emptyList()
        }
        return emptyList()
    }

    fun references(uri: String, text: String, position: Position, includeDeclaration: Boolean): List<out Location> {
        val varBase = variableAtCursor(text, position)
        if (varBase != null) {
            val locs = variables[varBase]
            return locs ?: emptyList()
        }
        val word = wordAt(text, position)
        val def = definitions[word.lowercase(Locale.ENGLISH)]
        if (def != null) {
            val result: MutableList<Location> = ArrayList()
            if (includeDeclaration) result.add(def)
            // Also include any usage lines referencing the name as a word.
            for ((u, t) in documents) {
                val lines = t.split("\n".toRegex()).toTypedArray()
                for (i in lines.indices) {
                    if (lines[i].contains(word) && !(u == def.uri && i == def.range.start.line)) {
                        result.add(Location(u, LspUtils.lineRange(i)))
                    }
                }
            }
            return result
        }
        return emptyList()
    }

    fun workspaceSymbols(query: String?): List<SymbolInformation> {
        val result: MutableList<SymbolInformation> = ArrayList()
        val q = query?.lowercase(Locale.ENGLISH) ?: ""
        for ((key, value) in definitions) {
            if (key.contains(q)) result.add(SymbolInformation(key, SymbolKind.Function, value))
        }
        return result
    }

    fun rename(uri: String, text: String, position: Position, newName: String): WorkspaceEdit {
        val refs = references(uri, text, position, true)
        if (refs.isEmpty()) return WorkspaceEdit()

        val changes: MutableMap<String, MutableList<TextEdit>> = HashMap()
        for (loc in refs) {
            val r: Range = loc.range
            val edit = TextEdit(r, newName)
            changes.computeIfAbsent(loc.uri) { ArrayList() }.add(edit)
        }
        val edit = WorkspaceEdit()
        edit.changes = changes
        return edit
    }

    // ------------------------------------------------------------------

    private fun wordAt(text: String, position: Position): String {
        val line = CompletionProvider.lineAt(text, position.line)
        val r: Range = CompletionProvider.wordRange(line, position.character)
        return line.substring(r.start.character, r.end.character)
    }

    private fun variableAtCursor(text: String, position: Position): String? {
        val line = CompletionProvider.lineAt(text, position.line)
        val m: Matcher = varUsage.matcher(line)
        while (m.find()) {
            val start = m.start()
            val end = m.end()
            if (position.character >= start && position.character <= end) return baseVarName(m.group(1))
        }
        return null
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
}
