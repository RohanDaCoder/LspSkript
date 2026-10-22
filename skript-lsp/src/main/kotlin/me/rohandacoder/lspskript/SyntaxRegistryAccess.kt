package me.rohandacoder.lspskript

import ch.njol.skript.Skript
import org.skriptlang.skript.registration.SyntaxRegistry

/**
 * Safe access to Skript's live syntax registry. All calls go through public
 * APIs only.
 */
object SyntaxRegistryAccess {

    /** Skript's live syntax registry (all feature providers read from this). */
    fun registry(): SyntaxRegistry = Skript.instance().syntaxRegistry()
}
