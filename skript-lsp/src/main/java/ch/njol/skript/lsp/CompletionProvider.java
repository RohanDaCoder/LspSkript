package ch.njol.skript.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces context-aware completions by combining Skript's registered syntax
 * patterns (via the public {@link SyntaxRegistry}) with the line/column context
 * of the cursor.
 */
public class CompletionProvider {

	private static final Pattern TRAILING_WORD = Pattern.compile("([A-Za-z][A-Za-z0-9 _%-]*)\\z");

	public List<CompletionItem> complete(String uri, String text, Position position) {
		String line = lineAt(text, position.getLine());
		String prefix = currentWord(line, position.getCharacter());

		Context context = detectContext(text, position);

		List<CompletionItem> items = new ArrayList<>();
		switch (context) {
			case TOP_LEVEL:
				addStructures(items, prefix);
				break;
			case IN_SECTION:
				addEffectsConditions(items, prefix);
				addStructures(items, prefix); // allow nested e.g. command/function
				break;
			case IN_EXPRESSION:
				addExpressions(items, prefix);
				addVariables(items, prefix);
				break;
		}
		return items;
	}

	private enum Context { TOP_LEVEL, IN_SECTION, IN_EXPRESSION }

	private Context detectContext(String text, Position position) {
		// Inspect indentation/structure of preceding lines.
		int lineIdx = position.getLine();
		String[] lines = text.split("\n", -1);
		int indent = indentOf(lineAt(text, lineIdx));

		// If the current line is inside a %...% expression, offer expressions.
		String currentLine = lineAt(text, lineIdx);
		int cursor = position.getCharacter();
		String before = currentLine.substring(0, Math.min(cursor, currentLine.length()));
		if (before.contains("%") && before.indexOf('%', before.indexOf('%') + 1) == -1) {
			// unbalanced '%' -> inside an expression slot
			return Context.IN_EXPRESSION;
		}

		boolean insideSection = false;
		for (int i = lineIdx - 1; i >= 0; i--) {
			String l = lines[i];
			if (l.trim().isEmpty() || l.trim().startsWith("#"))
				continue;
			int ind = indentOf(l);
			if (ind < indent) {
				insideSection = !isTopLevelStructure(l.trim());
				break;
			}
		}
		return insideSection ? Context.IN_SECTION : Context.TOP_LEVEL;
	}

	private static boolean isTopLevelStructure(String trimmed) {
		// Heuristics: events, commands, functions, options, variables, using, aliases.
		return trimmed.startsWith("on ")
			|| trimmed.startsWith("command ")
			|| trimmed.startsWith("function ")
			|| trimmed.startsWith("options")
			|| trimmed.startsWith("variables")
			|| trimmed.startsWith("using ")
			|| trimmed.startsWith("aliases");
	}

	private void addStructures(List<CompletionItem> items, String prefix) {
		SyntaxRegistry reg = SyntaxRegistryAccess.registry();
		addFrom(reg.syntaxes(SyntaxRegistry.STRUCTURE), items, prefix, CompletionItemKind.Class);
		addFrom(reg.syntaxes(SyntaxRegistry.SECTION), items, prefix, CompletionItemKind.Class);
	}

	private void addEffectsConditions(List<CompletionItem> items, String prefix) {
		SyntaxRegistry reg = SyntaxRegistryAccess.registry();
		addFrom(reg.syntaxes(SyntaxRegistry.EFFECT), items, prefix, CompletionItemKind.Function);
		addFrom(reg.syntaxes(SyntaxRegistry.CONDITION), items, prefix, CompletionItemKind.Event);
	}

	private void addExpressions(List<CompletionItem> items, String prefix) {
		SyntaxRegistry reg = SyntaxRegistryAccess.registry();
		addFrom(reg.syntaxes(SyntaxRegistry.EXPRESSION), items, prefix, CompletionItemKind.Field);
	}

	private static void addFrom(Collection<? extends SyntaxInfo<?>> infos, List<CompletionItem> items, String prefix, CompletionItemKind kind) {
		for (SyntaxInfo<?> info : infos) {
			for (String pattern : info.patterns()) {
				String cleaned = cleanPattern(pattern);
				if (cleaned.isEmpty())
					continue;
				if (!prefix.isEmpty() && !cleaned.toLowerCase(Locale.ENGLISH).startsWith(prefix.toLowerCase(Locale.ENGLISH)))
					continue;
				items.add(buildItem(info, cleaned, kind));
			}
		}
		// De-duplicate by label.
		Set<String> seen = new HashSet<>();
		items.removeIf(it -> !seen.add(it.getLabel()));
	}

	private static CompletionItem buildItem(SyntaxInfo<?> info, String pattern, CompletionItemKind kind) {
		CompletionItem item = new CompletionItem();
		item.setLabel(pattern);
		item.setKind(kind);
		item.setDetail(info.origin() == null ? "Skript" : info.origin().toString());

		// Build a snippet: replace %type% with ${n:type} tabstops.
		String snippet = toSnippet(pattern);
		if (snippet.contains("${")) {
			item.setInsertText(snippet);
			item.setInsertTextFormat(InsertTextFormat.Snippet);
		} else {
			item.setInsertText(pattern);
		}
		item.setDocumentation(pattern);
		return item;
	}

	static String cleanPattern(String pattern) {
		// Remove parse tags like [a:], markers, and %types% are kept for display.
		String s = pattern.replaceAll("\\[[a-zA-Z]:", "[");
		return s.trim();
	}

	static String toSnippet(String pattern) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([^%]+)%").matcher(pattern);
		StringBuffer sb = new StringBuffer();
		int i = 1;
		while (m.find()) {
			String type = m.group(1).replaceAll("[-@0-9]", "").trim();
			m.appendReplacement(sb, "${" + (i++) + ":" + Matcher.quoteReplacement(type) + "}");
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private void addVariables(List<CompletionItem> items, String prefix) {
		if (prefix.startsWith("{")) {
			CompletionItem item = new CompletionItem();
			item.setLabel("{variable}");
			item.setKind(CompletionItemKind.Variable);
			item.setInsertText("{variable}");
			item.setDocumentation("A Skript variable. Use {name::%expr%} for lists.");
			items.add(item);
		}
	}

	// ------------------------------------------------------------------
	// Text helpers
	// ------------------------------------------------------------------

	static String lineAt(String text, int line) {
		String[] lines = text.split("\n", -1);
		return line >= 0 && line < lines.length ? lines[line] : "";
	}

	static int indentOf(String line) {
		int i = 0;
		while (i < line.length() && (line.charAt(i) == '\t' || line.charAt(i) == ' '))
			i++;
		return i;
	}

	static String currentWord(String line, int character) {
		String before = line.substring(0, Math.min(character, line.length()));
		Matcher m = TRAILING_WORD.matcher(before);
		return m.find() ? m.group(1).trim() : "";
	}

	static Range wordRange(String line, int character) {
		int start = character;
		while (start > 0 && Character.isLetterOrDigit(line.charAt(start - 1)))
			start--;
		int end = character;
		while (end < line.length() && Character.isLetterOrDigit(line.charAt(end)))
			end++;
		return new Range(new Position(0, start), new Position(0, end));
	}
}
