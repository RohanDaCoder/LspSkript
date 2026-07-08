package me.rohandacoder.lspskript;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * The LspSkript plugin. Boots an LSP4J language server that reuses Skript's
 * real parser (running in the same server JVM) to provide language features
 * for {@code .sk} files.
 */
public class LspSkript extends JavaPlugin {

	public static final int DEFAULT_PORT = 30505;

	private SkriptLanguageServer server;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		int port = getConfig().getInt("port", DEFAULT_PORT);
		boolean trace = getConfig().getBoolean("trace", false);

		server = new SkriptLanguageServer(port, trace);
		try {
			server.start();
			getLogger().info("LspSkript listening on port " + port);
		} catch (Exception e) {
			getLogger().log(Level.SEVERE, "Failed to start LspSkript", e);
		}
	}

	@Override
	public void onDisable() {
		if (server != null) {
			try {
				server.stop();
			} catch (Exception e) {
				getLogger().log(Level.SEVERE, "Error stopping LspSkript", e);
			}
			server = null;
		}
	}

}
