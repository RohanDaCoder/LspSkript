package me.rohandacoder.lspskript

import ch.njol.skript.Skript
import org.skriptlang.skript.registration.SyntaxRegistry

/**
 * Safe access to Skript's live syntax registry. All calls go through public
 * APIs only.
 */
object SyntaxRegistryAccess {

    fun registry(): SyntaxRegistry {
        return Skript.instance().syntaxRegistry()
    }
}
