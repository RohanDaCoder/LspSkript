package ch.njol.skript.lsp;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * The SkriptLSP plugin. Boots an LSP4J language server that reuses Skript's
 * real parser (running in the same server JVM) to provide language features
 * for {@code .sk} files.
 */
public class SkriptLSP extends JavaPlugin {

	public static final int DEFAULT_PORT = 30505;

	private SkriptLanguageServer server;

	@Override
	public void onEnable() {
		int port = getConfig().getInt("port", DEFAULT_PORT);
		boolean trace = getConfig().getBoolean("trace", false);

		saveDefaultConfig();

		server = new SkriptLanguageServer(port, trace);
		try {
			server.start();
			getLogger().info("SkriptLSP listening on port " + port);
		} catch (Exception e) {
			getLogger().log(Level.SEVERE, "Failed to start SkriptLSP", e);
		}
	}

	@Override
	public void onDisable() {
		if (server != null) {
			try {
				server.stop();
			} catch (Exception e) {
				getLogger().log(Level.SEVERE, "Error stopping SkriptLSP", e);
			}
			server = null;
		}
	}

}
