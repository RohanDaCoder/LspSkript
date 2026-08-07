package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the document index: definitions, references, rename,
 * prepare-rename and workspace symbols. No Skript runtime is required.
 */
class DefinitionReferenceProviderTest {

    private val uri = "file:///test.sk"

    private fun provider(vararg docs: Pair<String, String>): DefinitionReferenceProvider {
        val p = DefinitionReferenceProvider()
        for ((u, t) in docs) p.index(u, t)
        return p
    }

    /** Applies edits (assumed non-overlapping) via the service's range math. */
    private fun apply(text: String, edits: List<TextEdit>): String {
        val svc = SkriptTextDocumentService()
        var result = text
        for (e in edits.sortedByDescending { it.range.start.character + it.range.start.line * 1_000_000 }) {
            result = svc.applyRangeEdit(result, e.range, e.newText)
        }
        return result
    }

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    @Test
    fun functionDefinitionResolvesToNameToken() {
        val doc = "function greet(msg: text):\n\tsend msg to player"
        val p = provider(uri to doc)

        val locs = p.definition(uri, doc, Position(0, 11)) // inside "greet"

        assertEquals(1, locs.size)
        assertEquals(0, locs[0].range.start.line)
        assertEquals(9, locs[0].range.start.character)
        assertEquals(14, locs[0].range.end.character)
    }

    @Test
    fun commandDefinitionResolvesByNameWithoutSlash() {
        val doc = "command /hello <player>:"
        val p = provider(uri to doc)

        val locs = p.definition(uri, doc, Position(0, 11)) // inside "hello"

        assertEquals(1, locs.size)
        assertEquals(9, locs[0].range.start.character) // "hello" starts after "command /"
        assertEquals(14, locs[0].range.end.character)
    }

    @Test
    fun unknownWordHasNoDefinition() {
        val doc = "function greet(msg: text):\n\tsend msg to player"
        val p = provider(uri to doc)

        assertTrue(p.definition(uri, doc, Position(1, 3)).isEmpty()) // on "send"
    }

    @Test
    fun variableDefinitionReturnsAllOccurrencesOfBase() {
        val doc = "set {money} to 5\nset {money::bank} to {money}"
        val p = provider(uri to doc)

        val locs = p.definition(uri, doc, Position(0, 6)) // cursor in {money}

        assertEquals(3, locs.size)
    }

    @Test
    fun baseVarNameNormalisesCaseAndListIndices() {
        val p = DefinitionReferenceProvider()
        assertEquals("money", p.baseVarName("Money"))
        assertEquals("player", p.baseVarName("{player::name}"))
        assertEquals("money", p.baseVarName("money::bank::vault"))
    }

    // ------------------------------------------------------------------
    // References
    // ------------------------------------------------------------------

    @Test
    fun functionReferencesExcludeDeclarationWhenRequested() {
        val doc = "function greet(msg: text):\n\tsend msg to player\ngreet(\"hi\")"
        val p = provider(uri to doc)

        val refs = p.references(uri, doc, Position(0, 11), includeDeclaration = false)

        assertEquals(1, refs.size)
        assertEquals(2, refs[0].range.start.line)
    }

    @Test
    fun functionReferencesIncludeDeclarationWhenRequested() {
        val doc = "function greet(msg: text):\n\tsend msg to player\ngreet(\"hi\")"
        val p = provider(uri to doc)

        val refs = p.references(uri, doc, Position(0, 11), includeDeclaration = true)

        assertEquals(2, refs.size)
        assertTrue(refs.any { it.range.start.line == 0 })
        assertTrue(refs.any { it.range.start.line == 2 })
    }

    @Test
    fun wordInsideVariableBracesIsNotAFunctionReference() {
        val doc = "function gold():\n\tsend \"gold\" to player\nset {gold} to 1"
        val p = provider(uri to doc)

        // Cursor on {gold} - must resolve as a variable, not the function.
        val refs = p.references(uri, doc, Position(2, 6), includeDeclaration = true)

        assertEquals(1, refs.size)
        assertEquals(2, refs[0].range.start.line)
    }

    // ------------------------------------------------------------------
    // Rename
    // ------------------------------------------------------------------

    @Test
    fun functionRenameUpdatesDeclarationAndCallSitesOnly() {
        val doc = "function greet(msg: text):\n\tsend msg to player\n\ngreet(\"hi\")"
        val p = provider(uri to doc)

        val edit = p.rename(uri, doc, Position(0, 11), "hello")
        val result = apply(doc, edit.changes[uri]!!)

        assertTrue(result.contains("function hello(msg: text)"))
        assertTrue(result.contains("hello(\"hi\")"))
        assertTrue(result.contains("send msg to player"))
        assertEquals(2, edit.changes[uri]!!.size)
    }

    @Test
    fun commandRenameKeepsLeadingSlash() {
        val doc = "command /hello <player>:\n\tmessage \"hi\""
        val p = provider(uri to doc)

        val edit = p.rename(uri, doc, Position(0, 11), "bye")
        val result = apply(doc, edit.changes[uri]!!)

        assertTrue(result.contains("command /bye <player>:"))
        assertEquals(1, edit.changes[uri]!!.size)
    }

    @Test
    fun variableRenameUpdatesBaseAcrossListIndices() {
        val doc = "set {money} to 5\nset {money::bank} to {money}"
        val p = provider(uri to doc)

        val edit = p.rename(uri, doc, Position(0, 6), "{cash}")
        val result = apply(doc, edit.changes[uri]!!)

        assertTrue(result.contains("set {cash} to 5"))
        assertTrue(result.contains("set {cash::bank} to {cash}"))
        assertEquals(3, edit.changes[uri]!!.size)
    }

    @Test
    fun renameOfUnknownWordReturnsEmptyEdit() {
        val doc = "set {money} to 5"
        val p = provider(uri to doc)

        val edit = p.rename(uri, doc, Position(0, 12), "cash") // on "to", not a variable

        assertTrue(edit.changes.isEmpty())
    }

    // ------------------------------------------------------------------
    // Prepare rename
    // ------------------------------------------------------------------

    @Test
    fun prepareRenameReturnsRangeForFunction() {
        val doc = "function greet(msg: text):"
        val p = provider(uri to doc)

        val result = p.prepareRename(uri, doc, Position(0, 11))

        assertTrue(result != null && result.isFirst)
        val range = result!!.first
        assertEquals(9, range.start.character)
        assertEquals(14, range.end.character)
    }

    @Test
    fun prepareRenameReturnsBraceRangeForVariable() {
        val doc = "set {money} to 5"
        val p = provider(uri to doc)

        val result = p.prepareRename(uri, doc, Position(0, 7)) as Either3<Range, *, *>

        assertTrue(result.isFirst)
        val range = result.first
        assertEquals(4, range.start.character)
        assertEquals(11, range.end.character) // covers "{money}"
    }

    @Test
    fun prepareRenameReturnsNullForUnknownWord() {
        val doc = "set {money} to 5"
        val p = provider(uri to doc)

        assertNull(p.prepareRename(uri, doc, Position(0, 12)))
    }

    // ------------------------------------------------------------------
    // Workspace symbols
    // ------------------------------------------------------------------

    @Test
    fun workspaceSymbolsFiltersByQuery() {
        val p = provider(
            uri to "function greet(msg: text):",
            "file:///other.sk" to "function loot():\n\tgive 1 diamond"
        )

        val symbols = p.workspaceSymbols("gre")

        assertEquals(1, symbols.size)
        assertEquals("greet", symbols[0].name)
    }
}
