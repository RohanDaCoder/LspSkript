package me.rohandacoder.lspskript

import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

/**
 * The LspSkript plugin. Boots an LSP4J language server that reuses Skript's
 * real parser (running in the same server JVM) to provide language features
 * for `.sk` files.
 */
class LspSkript : JavaPlugin() {

    companion object {
        const val DEFAULT_PORT = 30505
    }

    private var server: SkriptLanguageServer? = null

    override fun onEnable() {
        saveDefaultConfig()
        val port = config.getInt("port", DEFAULT_PORT)
        val trace = config.getBoolean("trace", false)

        server = SkriptLanguageServer(port, trace)
        try {
            server?.start()
            logger.info("LspSkript listening on port $port")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to start LspSkript", e)
        }
    }

    override fun onDisable() {
        server?.let { s ->
            try {
                s.stop()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Error stopping LspSkript", e)
            }
            server = null
        }
    }
}
