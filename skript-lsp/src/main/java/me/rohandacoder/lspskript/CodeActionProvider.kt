package me.rohandacoder.lspskript

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.*

/**
 * Lightweight code actions. Surfaces quick-fixes derived from diagnostics:
 *  - "Add missing 'and'/'or'" for `MISSING_CONJUNCTION` style warnings.
 *  - Suppress a deprecated-syntax warning by inserting the relevant comment.
 *
 * More sophisticated fixes can be added over time; the structure mirrors
 * Skript's `ScriptWarning` categories.
 */
class CodeActionProvider {

    fun codeAction(uri: String, text: String, params: CodeActionParams): List<Either<Command, CodeAction>> {
        val actions: MutableList<Either<Command, CodeAction>> = mutableListOf()

        for (diagnostic in params.context.diagnostics) {
            val message = diagnostic.message.lowercase(Locale.ENGLISH)

            if (message.contains("conjunction") || message.contains("missing and") || message.contains("missing or")) {
                actions.add(
                    Either.forRight(
                        quickFix(
                            "Insert 'and'", uri, diagnostic,
                            insertAtLineStart(text, diagnostic.range.start.line, "and ")
                        )
                    )
                )
            }
            if (message.contains("deprecated")) {
                actions.add(
                    Either.forRight(
                        quickFix(
                            "Suppress deprecated syntax warning", uri, diagnostic,
                            insertAtLineStart(text, diagnostic.range.start.line, "#! deprecation\n")
                        )
                    )
                )
            }
        }
        return actions
    }

    private fun quickFix(title: String, uri: String, diagnostic: Diagnostic, edit: TextEdit): CodeAction {
        val action = CodeAction(title)
        action.kind = CodeActionKind.QuickFix
        action.diagnostics = listOf(diagnostic)
        val changes: MutableMap<String, List<TextEdit>> = mutableMapOf()
        changes[uri] = listOf(edit)
        val we = WorkspaceEdit()
        we.changes = changes
        action.edit = we
        return action
    }

    private fun insertAtLineStart(text: String, line: Int, insertion: String): TextEdit {
        val lines = text.split("\n".toRegex()).toTypedArray()
        val existing = if (line >= 0 && line < lines.size) lines[line] else ""
        // Preserve indentation.
        val indent = CompletionProvider.indentOf(existing)
        val indentStr = existing.substring(0, indent)
        val range: Range = LspUtils.lineRange(line)
        val edit = TextEdit(range, indentStr + insertion + existing.substring(indent) + "\n")
        return edit
    }
}
