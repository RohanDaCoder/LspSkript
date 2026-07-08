package me.rohandacoder.lspskript;

import ch.njol.skript.config.Config;
import ch.njol.skript.config.Node;
import ch.njol.skript.config.SectionNode;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Builds the outline (document symbols) by walking Skript's public
 * {@link Config}/{@link SectionNode} tree.
 */
public class DocumentSymbolProvider {

	public List<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> documentSymbols(String uri, String text) {
		Config config = parseConfig(text);
		if (config == null)
			return Collections.emptyList();

		List<DocumentSymbol> symbols = new ArrayList<>();
		collect(config, symbols, 0);
		List<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
		for (DocumentSymbol s : symbols)
			result.add(Either.forRight(s));
		return result;
	}

	private void collect(Iterable<Node> nodes, List<DocumentSymbol> out, int depth) {
		for (Node node : nodes) {
			String key = node.getKey();
			if (key == null || key.isEmpty())
				continue;
			int line = node.getLine() > 0 ? node.getLine() - 1 : 0;

			DocumentSymbol symbol = new DocumentSymbol();
			symbol.setName(key);
			symbol.setKind(kindOf(key));
			Range range = LspUtils.lineRange(line);
			symbol.setRange(range);
			symbol.setSelectionRange(range);

			if (node instanceof SectionNode) {
				List<DocumentSymbol> children = new ArrayList<>();
				collect((SectionNode) node, children, depth + 1);
				symbol.setChildren(children);
			}
			out.add(symbol);
		}
	}

	private SymbolKind kindOf(String key) {
		String k = key.toLowerCase(Locale.ENGLISH);
		if (k.startsWith("on "))
			return SymbolKind.Event;
		if (k.startsWith("command "))
			return SymbolKind.Function;
		if (k.startsWith("function "))
			return SymbolKind.Function;
		if (k.equals("options") || k.equals("variables") || k.equals("aliases") || k.startsWith("using "))
			return SymbolKind.Module;
		return SymbolKind.Namespace;
	}

	static Config parseConfig(String text) {
		try {
			return new Config(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
				"lsp.sk", null, true, true, ":");
		} catch (Exception e) {
			return null;
		}
	}
}
