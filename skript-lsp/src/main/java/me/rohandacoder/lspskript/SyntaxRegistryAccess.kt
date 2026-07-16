package me.rohandacoder.lspskript

import ch.njol.skript.Skript
import ch.njol.skript.SkriptAddon
import org.skriptlang.skript.registration.SyntaxRegistry

/**
 * Safe access to Skript's live syntax registry. All calls go through public
 * APIs only.
 */
object SyntaxRegistryAccess {

    @Suppress("DEPRECATION")
    fun registry(): SyntaxRegistry {
        val addon: SkriptAddon = Skript.getAddonInstance()
        return addon.syntaxRegistry()
    }
}
