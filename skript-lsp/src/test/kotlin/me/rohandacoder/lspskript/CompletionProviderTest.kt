package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for completion pattern helpers and context detection (all pure string
 * logic; no live SyntaxRegistry needed).
 */
class CompletionProviderTest {

    @Test
    fun cleanPatternStripsParseTags() {
        assertEquals("[foo] bar", CompletionProvider.cleanPattern("[a:foo] bar"))
        assertEquals("[give %item%]", CompletionProvider.cleanPattern("[a:give %item%]"))
    }

    @Test
    fun toSnippetBuildsTabstops() {
        assertEquals(
            "set \${1:expr} to \${2:objects}",
            CompletionProvider.toSnippet("set %expr% to %objects%")
        )
    }

    @Test
    fun toSnippetKeepsPlainTextIntact() {
        assertEquals("give player a diamond", CompletionProvider.toSnippet("give player a diamond"))
    }

    @Test
    fun detectContextTopLevelAtIndentZero() {
        val ctx = CompletionProvider().detectContext("on load:\n", Position(0, 2))
        assertEquals(CompletionProvider.Context.TOP_LEVEL, ctx)
    }

    @Test
    fun detectContextInsideEventSection() {
        val ctx = CompletionProvider().detectContext("on load:\n\t", Position(1, 1))
        assertEquals(CompletionProvider.Context.IN_SECTION, ctx)
    }

    @Test
    fun detectContextInsideNestedConditional() {
        val text = "on load:\n\tif player is op:\n\t\t"
        val ctx = CompletionProvider().detectContext(text, Position(2, 2))
        assertEquals(CompletionProvider.Context.IN_SECTION, ctx)
    }

    @Test
    fun detectContextInsideExpressionSlot() {
        val ctx = CompletionProvider().detectContext("set %player% to %", Position(0, 19))
        assertEquals(CompletionProvider.Context.IN_EXPRESSION, ctx)
    }

    @Test
    fun detectContextAfterOpenBraceIsExpression() {
        val ctx = CompletionProvider().detectContext("set {mo", Position(0, 7))
        assertEquals(CompletionProvider.Context.IN_EXPRESSION, ctx)
    }

    @Test
    fun wordRangeUsesProvidedLineIndex() {
        val r = CompletionProvider.wordRange("send msg", 3, 2)
        assertEquals(2, r.start.line)
        assertEquals(0, r.start.character)
        assertEquals(2, r.end.line)
        assertEquals(4, r.end.character)
    }

    @Test
    fun indentOfCountsLeadingWhitespace() {
        assertEquals(0, CompletionProvider.indentOf("send msg"))
        assertEquals(2, CompletionProvider.indentOf("  send msg"))
        assertEquals(1, CompletionProvider.indentOf("\tmsg"))
    }

    @Test
    fun currentWordExtractsTrailingIdentifier() {
        assertEquals("msg", CompletionProvider.currentWord("send msg", 8))
        assertEquals("", CompletionProvider.currentWord("send ", 5))
    }
}
