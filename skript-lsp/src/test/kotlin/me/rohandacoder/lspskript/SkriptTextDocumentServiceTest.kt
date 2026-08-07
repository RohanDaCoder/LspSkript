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
}
