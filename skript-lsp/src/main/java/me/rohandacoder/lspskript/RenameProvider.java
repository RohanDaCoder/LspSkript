package me.rohandacoder.lspskript;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.WorkspaceEdit;

/**
 * Rename support, delegating to {@link DefinitionReferenceProvider} which holds
 * the cross-document symbol index.
 */
public class RenameProvider {

	public WorkspaceEdit rename(String uri, String text, Position position, String newName) {
		// The DefinitionReferenceProvider is owned by the text service; this
		// provider exists for clarity/separation and reuses the same logic.
		return new DefinitionReferenceProvider().rename(uri, text, position, newName);
	}
}
