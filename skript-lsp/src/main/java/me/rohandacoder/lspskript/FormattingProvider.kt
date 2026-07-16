package me.rohandacoder.lspskript

import ch.njol.skript.config.Config
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Formats a script by re-serializing Skript's parsed [Config] with
 * canonical (tab) indentation. This preserves structure and only normalises
 * whitespace/indentation.
 */
class FormattingProvider {

    fun formatting(uri: String, text: String): List<TextEdit> = formatAll(text)

    fun rangeFormatting(uri: String, text: String, range: Range): List<TextEdit> {
        // For simplicity, format the whole document (range formatting of an
        // indentation-based language is equivalent to full formatting).
        return formatAll(text)
    }

    private fun formatAll(text: String): List<TextEdit> {
        val config = DocumentSymbolProvider.parseConfig(text) ?: return emptyList()
        return try {
            val temp = File.createTempFile("skript-format", ".sk")
            config.save(temp)
            val formatted = String(Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8)
            val lineCount = text.split("\n".toRegex()).toTypedArray().size
            val full = Range(Position(0, 0), Position(lineCount, 0))
            val edit = TextEdit(full, formatted)
            temp.delete()
            listOf(edit)
        } catch (e: IOException) {
            emptyList()
        }
    }
}
