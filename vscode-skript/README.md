# LspSkript

Language support for [Skript](https://github.com/SkriptLang/Skript), powered by
Skript's **real parser** running inside your Minecraft server.

This extension is the VS Code client. The actual language server lives in the
**LspSkript** Paper plugin (`me.rohandacoder.lspskript`), which boots an LSP4J
server over a TCP socket and reuses Skript's own loader, so the diagnostics come
from Skript's own parser.

## Features

- Diagnostics from Skript's own parser (the messages `/skript reload` prints)
- Completion (effects, conditions, expressions, structures, variables)
- Hover documentation
- Signature help
- Go to definition / find references (functions, commands, variables)
- Document outline (sections, commands, functions, events)
- Formatting (re-serializes Skript's parsed config)
- Rename & quick-fix code actions

## Requirements

- A PaperMC server (1.21.x) with:
    - [Skript](https://github.com/SkriptLang/Skript) `2.17.1` (hard dependency)
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

| Setting           | Default     | Description                                            |
| ----------------- | ----------- | ------------------------------------------------------ |
| `skriptLsp.host`  | `localhost` | Host the LspSkript plugin listens on.                  |
| `skriptLsp.port`  | `30505`     | TCP port the LspSkript plugin listens on.              |
| `skriptLsp.trace` | `off`       | LSP message tracing: `off` \| `messages` \| `verbose`. |

## How it works

The plugin writes the editor buffer to a temporary `.sk` file (outside Skript's
`scripts/` folder), runs Skript's real `ScriptLoader` on the server main thread
with a `RetainingLogHandler`, then unmaps the resulting `LogEntry`s into LSP
diagnostics and unloads the temporary script so it never lingers.

## Development

The extension is TypeScript. It is built, type checked, linted and formatted
with [Bun](https://bun.sh) — no `typescript` dependency and no Prettier install.

| Command             | What it does                                                                   |
| ------------------- | ------------------------------------------------------------------------------ |
| `bun run typecheck` | Type checks the project with Bun's type checker                                |
| `bun run lint`      | Lints the sources with Bun's built-in linter                                   |
| `bun run format`    | Formats the sources with Bun's built-in formatter                              |
| `bun run verify`    | Type checks, lints, then checks that everything is formatted                   |
| `bun run grammar`   | Rebuilds `syntaxes/skript.tmLanguage.json` from the `skript-grammar` submodule |
| `bun run build`     | Verifies, rebuilds the grammar, then bundles `dist/extension.js`               |

`bun fmt` is Prettier-compatible and reads `bunfig.toml`; `bun check` reads
`tsconfig.json`. Both are also available directly as `bun fmt` and `bun check`.

`bun lint` ships 1,000+ rules from ESLint, typescript-eslint, React, import,
unicorn and more, and reads an existing ESLint or oxlint config if you have
one. It is **canary-only** for now, so it needs `bun upgrade --canary`.

**Bun 1.4.4 or newer is required.** `bun check` and `bun fmt` were added in
1.4.4. `bun` is deliberately _not_ a devDependency: package scripts find
`node_modules/.bin` before the `bun` on your `PATH`, so a pinned copy would
shadow the one you installed.

## License

MIT
