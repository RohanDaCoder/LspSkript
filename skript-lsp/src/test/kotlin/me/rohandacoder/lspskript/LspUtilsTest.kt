package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.Assert.assertEquals
import org.junit.Test

class LspUtilsTest {

    @Test
    fun lineRangeCoversWholeLine() {
        val range = LspUtils.lineRange(3)
        assertEquals(Range(Position(3, 0), Position(3, Int.MAX_VALUE)), range)
    }

    @Test
    fun lineRangeBetweenLinesIsInclusive() {
        val range = LspUtils.lineRange(1, 2)
        assertEquals(Range(Position(1, 0), Position(2, Int.MAX_VALUE)), range)
    }
}
