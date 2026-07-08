package me.rohandacoder.lspskript;

import org.bukkit.Bukkit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Helpers for LSP position/range math and for running parse work on the
 * server main thread (Skript's parser must run there).
 */
public final class LspUtils {

	private LspUtils() {
	}

	/** Convert a 0-based line/character LSP position to a 0-based line index. */
	public static int lineOf(Position position) {
		return position.getLine();
	}

	/** A whole-line range on the given 0-based line. */
	public static Range lineRange(int line) {
		return new Range(new Position(line, 0), new Position(line, Integer.MAX_VALUE));
	}

	/** A range covering [startLine, endLine] inclusive, full-width. */
	public static Range lineRange(int startLine, int endLine) {
		return new Range(new Position(startLine, 0), new Position(endLine, Integer.MAX_VALUE));
	}

	/**
	 * Runs the given supplier on the Bukkit main thread and returns its result.
	 * Skript's parser and all syntax {@code init()} calls must execute there.
	 */
	public static <T> T onMainThread(Supplier<T> supplier) {
		if (Bukkit.isPrimaryThread()) {
			return supplier.get();
		}
		try {
			Callable<T> callable = supplier::get;
			return Bukkit.getScheduler()
				.callSyncMethod(Bukkit.getPluginManager().getPlugin("Skript"), callable)
				.get();
		} catch (Exception e) {
			throw new RuntimeException("Failed to run Skript parse on main thread", e);
		}
	}

	/**
	 * Variant for void callables.
	 */
	public static void onMainThread(Runnable runnable) {
		onMainThread((Supplier<Void>) () -> {
			runnable.run();
			return null;
		});
	}

	public static <T> T call(Callable<T> callable) {
		return onMainThread(() -> {
			try {
				return callable.call();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}
}
