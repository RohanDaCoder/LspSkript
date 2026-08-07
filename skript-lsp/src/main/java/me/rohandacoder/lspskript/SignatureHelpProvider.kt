package me.rohandacoder.lspskript

import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.skriptlang.skript.registration.SyntaxInfo
import org.skriptlang.skript.registration.SyntaxRegistry
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Provides signature help by showing the pattern of the syntax surrounding the
 * cursor, with the active `%type%` parameter highlighted.
 */
class SignatureHelpProvider {

    private val typePattern: Pattern = Pattern.compile("%([^%]+)%")
    private val typeStripPattern: Regex = Regex("[-@0-9]")
    private val whitespacePattern: Regex = Regex("\\s+")

    fun signatureHelp(uri: String, text: String, position: Position): SignatureHelp? {
        val line = CompletionProvider.lineAt(text, position.line)
        val before = line.substring(0, Math.min(position.character, line.length))

        // Find the matching syntax by seeing if any registered pattern's prefix
        // appears in the text before the cursor.
        val reg: SyntaxRegistry = SyntaxRegistryAccess.registry()
        val candidates: MutableList<SyntaxInfo<*>> = mutableListOf()
        addAll(reg.syntaxes(SyntaxRegistry.EFFECT), before, candidates)
        addAll(reg.syntaxes(SyntaxRegistry.CONDITION), before, candidates)
        addAll(reg.syntaxes(SyntaxRegistry.EXPRESSION), before, candidates)

        if (candidates.isEmpty()) return null
        val first = candidates.first()
        val firstPattern = first.patterns().firstOrNull() ?: return null

        val info = SignatureInformation()
        // Use the first candidate's first pattern.
        val pattern = CompletionProvider.cleanPattern(firstPattern)
        info.setLabel(pattern)

        val params: MutableList<ParameterInformation> = mutableListOf()
        val m: Matcher = typePattern.matcher(pattern)
        var cursorTypesSeen = 0
        while (m.find()) {
            val type = m.group(1).replace(typeStripPattern, "").trim()
            val pi = ParameterInformation()
            pi.setLabel(type)
            params.add(pi)
            // crude active-parameter detection: count %...% openings before cursor
            if (m.start() <= before.length) cursorTypesSeen++
        }
        info.parameters = params

        val help = SignatureHelp()
        help.signatures = listOf(info)
        help.activeSignature = 0
        help.activeParameter = Math.max(0, cursorTypesSeen - 1)
        return help
    }

    private fun addAll(infos: Collection<SyntaxInfo<*>>, before: String, out: MutableList<SyntaxInfo<*>>) {
        val lowerBefore = before.lowercase(Locale.ENGLISH)
        for (info in infos) {
            for (pattern in info.patterns()) {
                val cleaned = CompletionProvider.cleanPattern(pattern).lowercase(Locale.ENGLISH)
                // The text before the cursor should overlap with the literal parts.
                if (cleaned.isNotEmpty() && overlaps(lowerBefore, cleaned)) {
                    out.add(info)
                    break
                }
            }
        }
    }

    private fun overlaps(before: String, pattern: String): Boolean {
        // Check that the last few words of `before` appear within the pattern.
        val words = before.trim().split(whitespacePattern)
        if (words.isEmpty()) return false
        val take = Math.min(3, words.size)
        val tail = words.takeLast(take).joinToString(" ")
        return pattern.contains(tail.trim())
    }
}
