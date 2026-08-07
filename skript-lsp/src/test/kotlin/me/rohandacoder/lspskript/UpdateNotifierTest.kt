package me.rohandacoder.lspskript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.logging.Logger

class UpdateNotifierTest {

    private val notifier = UpdateNotifier(Logger.getLogger("test"), "0.1.2")

    @Test
    fun parseTagNameExtractsTagFromApiBody() {
        val body = """{"url":"https://api.github.com/repos/RohanDaCoder/LspSkript/releases/3","tag_name":"v0.1.2","name":"v0.1.2","draft":false}"""
        assertEquals("v0.1.2", notifier.parseTagName(body))
    }

    @Test
    fun parseTagNameReturnsNullWithoutMatch() {
        assertNull(notifier.parseTagName("""{"message":"Not Found"}"""))
    }

    @Test
    fun isNewerTrueWhenLatestTripleIsHigher() {
        assertTrue(notifier.isNewer("v0.1.3", "0.1.2"))
        assertTrue(notifier.isNewer("v0.2.0", "0.1.9"))
        assertTrue(notifier.isNewer("v1.0.0", "0.9.9"))
    }

    @Test
    fun isNewerFalseWhenNotNewer() {
        assertFalse(notifier.isNewer("v0.1.2", "0.1.2"))
        assertFalse(notifier.isNewer("v0.1.1", "0.1.2"))
        assertFalse(notifier.isNewer("v0.1.9", "0.2.0"))
    }

    @Test
    fun isNewerFalseWhenEitherVersionIsGarbage() {
        assertFalse(notifier.isNewer("not-a-version", "0.1.2"))
        assertFalse(notifier.isNewer("v0.1.3", "not-a-version"))
    }
}
