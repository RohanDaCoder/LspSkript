package me.rohandacoder.lspskript

import ch.njol.skript.ScriptLoader
import ch.njol.skript.config.Config
import ch.njol.skript.log.LogEntry
import ch.njol.skript.log.RetainingLogHandler
import ch.njol.skript.log.SkriptLogger
import ch.njol.util.OpenCloseable
import org.skriptlang.skript.lang.script.Script
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.*
import java.util.function.Supplier
import java.util.logging.Level

/**
 * Parses `.sk` content through Skript's real loader to produce
 * diagnostics that are identical to what Skript prints in-game.
 *
 * Strategy: the editor content is written to a temporary `.sk` file
 * inside the server's scripts directory, then [ScriptLoader.loadScripts]
 * is invoked on the main thread with a [RetainingLogHandler] capturing
 * all errors. The script is then unloaded so it does not linger in Skript's
 * loaded-set.
 */
class ParseBridge {

    private val tempFiles: MutableMap<String, File> = WeakHashMap()
    private val lspDir: File by lazy {
        try {
            Files.createTempDirectory("lspskript").toFile()
        } catch (e: IOException) {
            val fallback = File(System.getProperty("java.io.tmpdir"), "lspskript")
            fallback.mkdirs()
            fallback
        }
    }

    /** Parse the given content and return diagnostics mapped from Skript's log. */
    fun parse(uri: String, content: String): List<Diagnostic> {
        val tempFile = prepareTempFile(uri, content) ?: return emptyList()
        return LspUtils.onMainThread(Supplier<List<Diagnostic>> { doParse(tempFile) })
    }

    private fun doParse(tempFile: File): List<Diagnostic> {
        val handler = RetainingLogHandler()
        handler.start()
        val before: Set<Script> = ScriptLoader.getLoadedScripts().toSet()
        try {
            val files: Set<File> = Collections.singleton(tempFile)
            ScriptLoader.loadScripts(files, OpenCloseable.EMPTY).join()
        } catch (e: Exception) {
            // loadScripts may throw for fatal structural issues; the handler
            // still captured what it could.
            SkriptLogger.LOGGER.log(Level.FINE, "LspSkript parse exception", e)
        } finally {
            handler.stop()
        }

        val diagnostics: MutableList<Diagnostic> = mutableListOf()
        for (entry in handler.log) {
            val d = toDiagnostic(entry)
            if (d != null) diagnostics.add(d)
        }

        // Unload the script(s) we just loaded so they do not linger in Skript's
        // live set (registered triggers / commands / functions). Note: a temp
        // snippet's `on load` effects may still execute during loading; the
        // unload only prevents the script from staying registered.
        val loaded: MutableSet<Script> = ScriptLoader.getLoadedScripts().toMutableSet()
        loaded.removeAll(before)
        if (loaded.isNotEmpty()) {
            ScriptLoader.unloadScripts(loaded)
        }

        return diagnostics
    }

    private fun toDiagnostic(entry: LogEntry): Diagnostic? {
        val level = entry.level ?: return null
        val severity: DiagnosticSeverity = when {
            level.intValue() >= Level.SEVERE.intValue() -> DiagnosticSeverity.Error
            level.intValue() >= Level.WARNING.intValue() -> DiagnosticSeverity.Warning
            else -> DiagnosticSeverity.Information
        }

        val node = entry.node
        val line = if (node != null && node.line > 0) node.line - 1 else 0

        val range: Range = LspUtils.lineRange(line)
        val diagnostic = Diagnostic(range, entry.message ?: "")
        diagnostic.severity = severity
        diagnostic.source = "Skript"
        if (entry.quality >= 0) {
            diagnostic.setCode("q" + entry.quality)
        }
        return diagnostic
    }

    private fun prepareTempFile(uri: String, content: String): File? {
        return try {
            // IMPORTANT: write outside Skript's scripts folder so the server
            // never auto-loads these temp files on (re)start.
            val dir = lspDir
            val name = sanitize(uri) + ".sk"
            val tempFile = File(dir, name)
            Files.write(tempFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
            tempFiles[uri] = tempFile
            tempFile
        } catch (e: IOException) {
            SkriptLogger.LOGGER.log(Level.WARNING, "LspSkript failed to prepare temp script", e)
            null
        }
    }

    /** Remove the cached temp file for a URI (e.g. on external change). */
    fun invalidate(uri: String) {
        val f = tempFiles.remove(uri)
        if (f != null && f.exists()) {
            f.delete()
        }
    }

    private fun sanitize(uri: String): String {
        var s = uri
        val slash = s.lastIndexOf('/')
        if (slash >= 0) s = s.substring(slash + 1)
        val colon = s.lastIndexOf(':')
        if (colon >= 0) s = s.substring(colon + 1)
        s = s.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return if (s.isEmpty()) "script" else s
    }
}
