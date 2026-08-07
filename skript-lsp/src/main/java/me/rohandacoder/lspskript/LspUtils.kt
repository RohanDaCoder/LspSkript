package me.rohandacoder.lspskript

import org.bukkit.Bukkit
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.util.concurrent.Callable
import java.util.function.Supplier

/**
 * Helpers for LSP position/range math and for running parse work on the
 * server main thread (Skript's parser must run there).
 */
object LspUtils {

    /** A whole-line range on the given 0-based line. */
    fun lineRange(line: Int): Range =
        Range(Position(line, 0), Position(line, Int.MAX_VALUE))

    /** A range covering [startLine, endLine] inclusive, full-width. */
    fun lineRange(startLine: Int, endLine: Int): Range =
        Range(Position(startLine, 0), Position(endLine, Int.MAX_VALUE))

    /**
     * Runs the given supplier on the Bukkit main thread and returns its result.
     * Skript's parser and all syntax `init()` calls must execute there.
     */
    fun <T> onMainThread(supplier: Supplier<T>): T {
        if (Bukkit.isPrimaryThread()) {
            return supplier.get()
        }
        return try {
            val callable = Callable(supplier::get)
            val plugin = requireNotNull(Bukkit.getPluginManager().getPlugin("Skript")) {
                "Skript plugin is not loaded; cannot run parse on main thread"
            }
            Bukkit.getScheduler()
                .callSyncMethod(plugin, callable)
                .get()
        } catch (e: Exception) {
            throw RuntimeException("Failed to run Skript parse on main thread", e)
        }
    }

    fun <T> call(callable: Callable<T>): T {
        return onMainThread(Supplier { callable.call() })
    }
}
