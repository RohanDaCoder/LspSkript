package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the hand-rolled incremental-sync range edits and position math.
 */
class SkriptTextDocumentServiceTest {

    private val svc = SkriptTextDocumentService()

    private fun edit(startLine: Int, startChar: Int, endLine: Int, endChar: Int, replacement: String) =
        svc.applyRangeEdit(
            "a\nb\nc",
            Range(Position(startLine, startChar), Position(endLine, endChar)),
            replacement
        )

    @Test
    fun replacesWholeMiddleLine() {
        assertEquals("a\nXc", edit(1, 0, 2, 0, "X"))
    }

    @Test
    fun replacesSpanAcrossLines() {
        assertEquals("xW\nVz", svc.applyRangeEdit(
            "x\ny\nz",
            Range(Position(0, 1), Position(2, 0)),
            "W\nV"
        ))
    }

    @Test
    fun insertsAtEndOfLine() {
        assertEquals("ab", svc.applyRangeEdit("ab", Range(Position(0, 2), Position(0, 2)), ""))
        assertEquals("abc", svc.applyRangeEdit("ab", Range(Position(0, 2), Position(0, 2)), "c"))
    }

    @Test
    fun deletesRangeWithinLine() {
        assertEquals("aef", svc.applyRangeEdit("abcdef", Range(Position(0, 1), Position(0, 4)), ""))
    }

    @Test
    fun replacesWithinLine() {
        assertEquals("hello there", svc.applyRangeEdit("hello world", Range(Position(0, 6), Position(0, 11)), "there"))
    }

    @Test
    fun clampsCharactersBeyondLineLength() {
        // Out-of-range positions clamp to the line end -> insertion at end.
        assertEquals("hello world!", svc.applyRangeEdit("hello world", Range(Position(0, 99), Position(0, 99)), "!"))
    }

    // --- Trailing newlines -----------------------------------------------------
    //
    // A buffer ending in "\n" has a final, empty line. Editors report typing
    // there as line N (0-based), so the edit must address it — but `split('\n')`
    // yields a trailing "" that `dropLastWhile` used to discard, making the
    // final line unreachable and throwing IndexOutOfBoundsException out of
    // didChange. That exception escapes before `documents[uri]` is written, so
    // the server's buffer permanently desyncs from the client and diagnostics
    // for the file silently stop updating.

    @Test
    fun editsTheEmptyLineAfterATrailingNewline() {
        assertEquals(
            "a\nb\nc\nx",
            svc.applyRangeEdit("a\nb\nc\n", Range(Position(3, 0), Position(3, 0)), "x")
        )
    }

    @Test
    fun editsWithinAFileEndingInMultipleNewlines() {
        // "a\n\n" is line0="a", line1="", line2="" (the cursor sits on line2).
        assertEquals(
            "a\n\ny",
            svc.applyRangeEdit("a\n\n", Range(Position(2, 0), Position(2, 0)), "y")
        )
    }

    @Test
    fun appendsToTheLastContentLineOfATrailingNewlineFile() {
        assertEquals(
            "a\nbc\n",
            svc.applyRangeEdit("a\nb\n", Range(Position(1, 1), Position(1, 1)), "c")
        )
    }

    @Test
    fun deletingTheFinalNewlineRemovesTheEmptyLine() {
        // Range covering the newline itself: from end of "a" to start of line 1.
        assertEquals(
            "a",
            svc.applyRangeEdit("a\n", Range(Position(0, 1), Position(1, 0)), "")
        )
    }

    @Test
    fun emptyDocumentAcceptsAnEditOnLineZero() {
        assertEquals("hi", svc.applyRangeEdit("", Range(Position(0, 0), Position(0, 0)), "hi"))
    }
}
