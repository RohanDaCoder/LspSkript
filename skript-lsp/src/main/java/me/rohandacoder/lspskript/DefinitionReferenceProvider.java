package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks definitions (functions, commands) and variables across all open
 * documents to support go-to-definition, find-references, workspace symbols,
 * and rename.
 */
public class DefinitionReferenceProvider {

	private final Map<String, String> documents = new HashMap<>();

	// function/command name (lowercase) -> location
	private final Map<String, Location> definitions = new HashMap<>();
	// variable base name -> locations of declarations/usages
	private final Map<String, List<Location>> variables = new HashMap<>();

	private static final Pattern FUNCTION_DEF = Pattern.compile("^\\s*function\\s+([a-zA-Z0-9_]+)\\s*\\(");
	private static final Pattern COMMAND_DEF = Pattern.compile("^\\s*command\\s+/(.+?)\\s*:");
	private static final Pattern VAR_USAGE = Pattern.compile("\\{([^{}]+)\\}");

	public void index(String uri, String text) {
		documents.put(uri, text);
		rebuild();
	}

	private void rebuild() {
		definitions.clear();
		variables.clear();
		for (Map.Entry<String, String> entry : documents.entrySet()) {
			String uri = entry.getKey();
			String[] lines = entry.getValue().split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				Matcher fm = FUNCTION_DEF.matcher(line);
				if (fm.find()) {
					definitions.put(fm.group(1).toLowerCase(Locale.ENGLISH),
						new Location(uri, LspUtils.lineRange(i)));
					continue;
				}
				Matcher cm = COMMAND_DEF.matcher(line);
				if (cm.find()) {
					definitions.put(cm.group(1).toLowerCase(Locale.ENGLISH),
						new Location(uri, LspUtils.lineRange(i)));
				}
				Matcher vm = VAR_USAGE.matcher(line);
				while (vm.find()) {
					String base = baseVarName(vm.group(1));
					variables.computeIfAbsent(base, k -> new ArrayList<>())
						.add(new Location(uri, LspUtils.lineRange(i)));
				}
			}
		}
	}

	public List<? extends Location> definition(String uri, String text, Position position) {
		String word = wordAt(text, position);
		if (word.isEmpty())
			return Collections.emptyList();
		Location loc = definitions.get(word.toLowerCase(Locale.ENGLISH));
		if (loc != null)
			return Collections.singletonList(loc);
		// variable?
		String varBase = variableAtCursor(text, position);
		if (varBase != null) {
			List<Location> locs = variables.get(varBase);
			return locs == null ? Collections.emptyList() : locs;
		}
		return Collections.emptyList();
	}

	public List<? extends Location> references(String uri, String text, Position position, boolean includeDeclaration) {
		String varBase = variableAtCursor(text, position);
		if (varBase != null) {
			List<Location> locs = variables.get(varBase);
			return locs == null ? Collections.emptyList() : locs;
		}
		String word = wordAt(text, position);
		Location def = definitions.get(word.toLowerCase(Locale.ENGLISH));
		if (def != null) {
			List<Location> result = new ArrayList<>();
			if (includeDeclaration)
				result.add(def);
			// Also include any usage lines referencing the name as a word.
			for (Map.Entry<String, String> e : documents.entrySet()) {
				String[] lines = e.getValue().split("\n", -1);
				for (int i = 0; i < lines.length; i++) {
					if (lines[i].contains(word) && !(e.getKey().equals(def.getUri()) && i == def.getRange().getStart().getLine()))
						result.add(new Location(e.getKey(), LspUtils.lineRange(i)));
				}
			}
			return result;
		}
		return Collections.emptyList();
	}

	public List<? extends SymbolInformation> workspaceSymbols(String query) {
		List<SymbolInformation> result = new ArrayList<>();
		String q = query == null ? "" : query.toLowerCase(Locale.ENGLISH);
		for (Map.Entry<String, Location> e : definitions.entrySet()) {
			if (e.getKey().contains(q))
				result.add(new SymbolInformation(e.getKey(), SymbolKind.Function, e.getValue()));
		}
		return result;
	}

	public WorkspaceEdit rename(String uri, String text, Position position, String newName) {
		List<? extends Location> refs = references(uri, text, position, true);
		if (refs.isEmpty())
			return new WorkspaceEdit();

		Map<String, List<TextEdit>> changes = new HashMap<>();
		for (Location loc : refs) {
			Range r = loc.getRange();
			TextEdit edit = new TextEdit(r, newName);
			changes.computeIfAbsent(loc.getUri(), k -> new ArrayList<>()).add(edit);
		}
		WorkspaceEdit edit = new WorkspaceEdit();
		edit.setChanges(changes);
		return edit;
	}

	// ------------------------------------------------------------------

	private static String wordAt(String text, Position position) {
		String line = CompletionProvider.lineAt(text, position.getLine());
		Range r = CompletionProvider.wordRange(line, position.getCharacter());
		return line.substring(r.getStart().getCharacter(), r.getEnd().getCharacter());
	}

	private static String variableAtCursor(String text, Position position) {
		String line = CompletionProvider.lineAt(text, position.getLine());
		Matcher m = VAR_USAGE.matcher(line);
		while (m.find()) {
			int start = m.start();
			int end = m.end();
			if (position.getCharacter() >= start && position.getCharacter() <= end)
				return baseVarName(m.group(1));
		}
		return null;
	}

	static String baseVarName(String var) {
		// normalise: strip list indices and lowercase; keep :: structure base.
		String s = var.trim().toLowerCase(Locale.ENGLISH);
		s = s.replaceAll("\\{", "").replaceAll("\\}", "");
		// base is everything up to first :: or first index segment
		int dbl = s.indexOf("::");
		if (dbl >= 0)
			s = s.substring(0, dbl);
		return s;
	}
}
