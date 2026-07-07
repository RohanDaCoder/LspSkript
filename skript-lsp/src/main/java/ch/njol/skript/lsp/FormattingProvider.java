package ch.njol.skript.lsp;

import ch.njol.skript.config.Config;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Formats a script by re-serializing Skript's parsed {@link Config} with
 * canonical (tab) indentation. This preserves structure and only normalises
 * whitespace/indentation.
 */
public class FormattingProvider {

	public List<? extends TextEdit> formatting(String uri, String text) {
		return formatAll(text);
	}

	public List<? extends TextEdit> rangeFormatting(String uri, String text, Range range) {
		// For simplicity, format the whole document (range formatting of an
		// indentation-based language is equivalent to full formatting).
		return formatAll(text);
	}

	private List<? extends TextEdit> formatAll(String text) {
		Config config = DocumentSymbolProvider.parseConfig(text);
		if (config == null)
			return Collections.emptyList();

		try {
			File temp = File.createTempFile("skript-format", ".sk");
			config.save(temp);
			String formatted = new String(Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8);
			int lineCount = text.split("\n", -1).length;
			Range full = new Range(new Position(0, 0), new Position(lineCount, 0));
			TextEdit edit = new TextEdit(full, formatted);
			temp.delete();
			return Collections.singletonList(edit);
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}
}
