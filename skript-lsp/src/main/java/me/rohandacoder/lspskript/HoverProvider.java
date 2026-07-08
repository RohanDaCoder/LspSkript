package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.util.*;

/**
 * Provides hover documentation by matching the word under the cursor against
 * Skript's registered syntax and rendering Markdown from its metadata.
 */
public class HoverProvider {

	public Hover hover(String uri, String text, Position position) {
		String word = wordAt(text, position);
		if (word.isEmpty())
			return null;

		SyntaxRegistry reg = SyntaxRegistryAccess.registry();
		List<SyntaxInfo<?>> matches = new ArrayList<>();
		collect(reg.syntaxes(SyntaxRegistry.EFFECT), word, matches);
		collect(reg.syntaxes(SyntaxRegistry.CONDITION), word, matches);
		collect(reg.syntaxes(SyntaxRegistry.EXPRESSION), word, matches);
		collect(reg.syntaxes(SyntaxRegistry.STRUCTURE), word, matches);
		collect(reg.syntaxes(SyntaxRegistry.SECTION), word, matches);

		if (matches.isEmpty())
			return null;

		StringBuilder md = new StringBuilder();
		for (SyntaxInfo<?> info : matches) {
			md.append("### ").append(info.origin() == null ? "Skript" : info.origin()).append("\n\n");
			for (String pattern : info.patterns()) {
				md.append("`").append(CompletionProvider.cleanPattern(pattern)).append("`\n\n");
			}
		}
		Hover hover = new Hover();
		hover.setContents(new MarkupContent(MarkupKind.MARKDOWN, md.toString()));
		hover.setRange(CompletionProvider.wordRange(CompletionProvider.lineAt(text, position.getLine()), position.getCharacter()));
		return hover;
	}

	private static void collect(Collection<? extends SyntaxInfo<?>> infos, String word, List<SyntaxInfo<?>> out) {
		String lower = word.toLowerCase(Locale.ENGLISH);
		for (SyntaxInfo<?> info : infos) {
			for (String pattern : info.patterns()) {
				String cleaned = CompletionProvider.cleanPattern(pattern).toLowerCase(Locale.ENGLISH);
				if (cleaned.contains(lower)) {
					out.add(info);
					break;
				}
			}
		}
	}

	private static String wordAt(String text, Position position) {
		String line = CompletionProvider.lineAt(text, position.getLine());
		Range r = CompletionProvider.wordRange(line, position.getCharacter());
		return line.substring(r.getStart().getCharacter(), r.getEnd().getCharacter());
	}
}
