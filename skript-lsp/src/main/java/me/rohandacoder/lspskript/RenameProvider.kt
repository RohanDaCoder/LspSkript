package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Rename support, delegating to [DefinitionReferenceProvider] which holds
 * the cross-document symbol index.
 */
class RenameProvider {

    fun rename(uri: String, text: String, position: Position, newName: String): WorkspaceEdit {
        // The DefinitionReferenceProvider is owned by the text service; this
        // provider exists for clarity/separation and reuses the same logic.
        return DefinitionReferenceProvider().rename(uri, text, position, newName)
    }
}
