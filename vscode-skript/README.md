# LspSkript

Full language support for [Skript](https://github.com/SkriptLang/Skript), powered by
Skript's **real parser** running inside your Minecraft server.

This extension is the VS Code client. The actual language server lives in the
**LspSkript** Paper plugin (`me.rohandacoder.lspskript`), which boots an LSP4J
server over a TCP socket and reuses Skript's own loader to produce diagnostics
that are identical to what Skript prints in-game.

## Features

- Diagnostics (errors/warnings) identical to a real `/skript reload`
- Completion (effects, conditions, expressions, structures, variables)
- Hover documentation
- Signature help
- Go to definition / find references (functions, commands, variables)
- Document outline (sections, commands, functions, events)
- Formatting (re-serializes Skript's parsed config)
- Rename & quick-fix code actions

## Requirements

- A PaperMC server (1.21.x) with:
  - [Skript](https://github.com/SkriptLang/Skript) `2.16.0` (hard dependency)
  - The **LspSkript** plugin jar installed in `plugins/`
- The LspSkript plugin listens on a TCP port (default `30505`).

## Setup

1. Drop `LspSkript.jar` into your server's `plugins/` folder and start the server.
2. In VS Code, install this extension.
3. Open a `.sk` file. The extension connects to `localhost:30505` automatically.
   If your server runs elsewhere or uses a different port, set `skriptLsp.host`
   and `skriptLsp.port` in your VS Code settings. The extension keeps retrying
   the connection with backoff, so opening VS Code before the server is up
   (or reloading the plugin) reconnects automatically.

## Settings

| Setting | Default | Description |
| ------- | ------- | ----------- |
| `skriptLsp.host` | `localhost` | Host the LspSkript plugin listens on. |
| `skriptLsp.port` | `30505` | TCP port the LspSkript plugin listens on. |
| `skriptLsp.trace` | `off` | LSP message tracing: `off` \| `messages` \| `verbose`. |

## How it works

The plugin writes the editor buffer to a temporary `.sk` file (outside Skript's
`scripts/` folder), runs Skript's real `ScriptLoader` on the server main thread
with a `RetainingLogHandler`, then unmaps the resulting `LogEntry`s into LSP
diagnostics and unloads the temporary script so it never lingers.

## License

MIT
