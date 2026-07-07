package ch.njol.skript.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.*;

/**
 * Lightweight code actions. Surfaces quick-fixes derived from diagnostics:
 *  - "Add missing 'and'/'or'" for {@code MISSING_CONJUNCTION} style warnings.
 *  - Suppress a deprecated-syntax warning by inserting the relevant comment.
 *
 * <p>More sophisticated fixes can be added over time; the structure mirrors
 * Skript's {@code ScriptWarning} categories.</p>
 */
public class CodeActionProvider {

	public List<Either<Command, CodeAction>> codeAction(String uri, String text, CodeActionParams params) {
		List<Either<Command, CodeAction>> actions = new ArrayList<>();

		for (Diagnostic diagnostic : params.getContext().getDiagnostics()) {
			String message = diagnostic.getMessage().toLowerCase(Locale.ENGLISH);

			if (message.contains("conjunction") || message.contains("missing and") || message.contains("missing or")) {
				actions.add(Either.forRight(quickFix(
					"Insert 'and'", uri, diagnostic,
					insertAtLineStart(text, diagnostic.getRange().getStart().getLine(), "and "))));
			}
			if (message.contains("deprecated")) {
				actions.add(Either.forRight(quickFix(
					"Suppress deprecated syntax warning", uri, diagnostic,
					insertAtLineStart(text, diagnostic.getRange().getStart().getLine(), "#! deprecation\n"))));
			}
		}
		return actions;
	}

	private CodeAction quickFix(String title, String uri, Diagnostic diagnostic, TextEdit edit) {
		CodeAction action = new CodeAction(title);
		action.setKind(CodeActionKind.QuickFix);
		action.setDiagnostics(Collections.singletonList(diagnostic));
		Map<String, List<TextEdit>> changes = new HashMap<>();
		changes.put(uri, Collections.singletonList(edit));
		WorkspaceEdit we = new WorkspaceEdit();
		we.setChanges(changes);
		action.setEdit(we);
		return action;
	}

	private static TextEdit insertAtLineStart(String text, int line, String insertion) {
		String[] lines = text.split("\n", -1);
		String existing = line >= 0 && line < lines.length ? lines[line] : "";
		// Preserve indentation.
		int indent = CompletionProvider.indentOf(existing);
		String indentStr = existing.substring(0, indent);
		int insertPos = 0;
		for (int i = 0; i < text.length(); i++) {
			// approximate: not perfectly accurate, but adequate for a quick fix
		}
		Range range = LspUtils.lineRange(line);
		TextEdit edit = new TextEdit(range, indentStr + insertion + existing.substring(indent) + "\n");
		return edit;
	}
}
