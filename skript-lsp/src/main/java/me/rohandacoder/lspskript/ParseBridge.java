package me.rohandacoder.lspskript;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.config.Config;
import ch.njol.skript.log.LogEntry;
import ch.njol.skript.log.RetainingLogHandler;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.util.OpenCloseable;
import org.skriptlang.skript.lang.script.Script;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

/**
 * Parses {@code .sk} content through Skript's real loader to produce
 * diagnostics that are identical to what Skript prints in-game.
 *
 * <p>Strategy: the editor content is written to a temporary {@code .sk} file
 * inside the server's scripts directory, then {@link ScriptLoader#loadScripts}
 * is invoked on the main thread with a {@link RetainingLogHandler} capturing
 * all errors. The script is then unloaded so it does not linger in Skript's
 * loaded-set.</p>
 */
public class ParseBridge {

	private final Map<String, File> tempFiles = new WeakHashMap<>();
	private File lspDir;

	/** Parse the given content and return diagnostics mapped from Skript's log. */
	public List<Diagnostic> parse(String uri, String content) {
		File tempFile = prepareTempFile(uri, content);
		if (tempFile == null)
			return Collections.emptyList();

		return LspUtils.onMainThread(() -> doParse(tempFile));
	}

	private List<Diagnostic> doParse(File tempFile) {
		RetainingLogHandler handler = new RetainingLogHandler();
		handler.start();
		Set<Script> before = new HashSet<>(ScriptLoader.getLoadedScripts());
		try {
			Set<File> files = Collections.singleton(tempFile);
			ScriptLoader.loadScripts(files, OpenCloseable.EMPTY).join();
		} catch (Exception e) {
			// loadScripts may throw for fatal structural issues; the handler
			// still captured what it could.
			SkriptLogger.LOGGER.log(Level.FINE, "LspSkript parse exception", e);
		} finally {
			handler.stop();
		}

		List<Diagnostic> diagnostics = new ArrayList<>();
		for (LogEntry entry : handler.getLog()) {
			Diagnostic d = toDiagnostic(entry);
			if (d != null)
				diagnostics.add(d);
		}

		// Unload the script(s) we just loaded so they do not linger in Skript's
		// live set (registered triggers / commands / functions). Note: a temp
		// snippet's `on load` effects may still execute during loading; the
		// unload only prevents the script from staying registered.
		Set<Script> loaded = new HashSet<>(ScriptLoader.getLoadedScripts());
		loaded.removeAll(before);
		if (!loaded.isEmpty()) {
			ScriptLoader.unloadScripts(loaded);
		}

		return diagnostics;
	}

	private Diagnostic toDiagnostic(LogEntry entry) {
		Level level = entry.getLevel();
		DiagnosticSeverity severity;
		if (level.intValue() >= Level.SEVERE.intValue()) {
			severity = DiagnosticSeverity.Error;
		} else if (level.intValue() >= Level.WARNING.intValue()) {
			severity = DiagnosticSeverity.Warning;
		} else {
			severity = DiagnosticSeverity.Information;
		}

		int line = 0;
		if (entry.node != null && entry.node.getLine() > 0) {
			line = entry.node.getLine() - 1;
		}

		Range range = LspUtils.lineRange(line);
		Diagnostic diagnostic = new Diagnostic(range, entry.message);
		diagnostic.setSeverity(severity);
		diagnostic.setSource("Skript");
		if (entry.quality >= 0)
			diagnostic.setCode("q" + entry.quality);
		return diagnostic;
	}

	private File lspDir() {
		if (lspDir == null) {
			try {
				lspDir = Files.createTempDirectory("lspskript").toFile();
			} catch (IOException e) {
				lspDir = new File(System.getProperty("java.io.tmpdir"), "lspskript");
				lspDir.mkdirs();
			}
		}
		return lspDir;
	}

	private File prepareTempFile(String uri, String content) {
		try {
			// IMPORTANT: write outside Skript's scripts folder so the server
			// never auto-loads these temp files on (re)start.
			File dir = lspDir();
			String name = sanitize(uri) + ".sk";
			File tempFile = new File(dir, name);
			Files.write(tempFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
			tempFiles.put(uri, tempFile);
			return tempFile;
		} catch (IOException e) {
			SkriptLogger.LOGGER.log(Level.WARNING, "LspSkript failed to prepare temp script", e);
			return null;
		}
	}

	/** Remove the cached temp file for a URI (e.g. on external change). */
	public void invalidate(String uri) {
		File f = tempFiles.remove(uri);
		if (f != null && f.exists())
			f.delete();
	}

	private static String sanitize(String uri) {
		String s = uri;
		int slash = s.lastIndexOf('/');
		if (slash >= 0)
			s = s.substring(slash + 1);
		int colon = s.lastIndexOf(':');
		if (colon >= 0)
			s = s.substring(colon + 1);
		s = s.replaceAll("[^a-zA-Z0-9._-]", "_");
		return s.isEmpty() ? "script" : s;
	}
}
