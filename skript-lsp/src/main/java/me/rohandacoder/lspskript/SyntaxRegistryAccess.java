package me.rohandacoder.lspskript;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptAddon;
import org.skriptlang.skript.registration.SyntaxRegistry;

/**
 * Safe access to Skript's live syntax registry. All calls go through public
 * APIs only.
 */
public final class SyntaxRegistryAccess {

	private SyntaxRegistryAccess() {
	}

	@SuppressWarnings("deprecation")
	public static SyntaxRegistry registry() {
		SkriptAddon addon = Skript.getAddonInstance();
		return addon.syntaxRegistry();
	}
}
