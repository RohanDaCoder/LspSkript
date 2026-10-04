# AST-Driven Language Features — Design

**Date:** 2026-10-04
**Status:** Proposed — awaiting sign-off on phase ordering (Phase 0 before Phase 2)

## Goal

Replace LspSkript's heuristic feature layer with Skript's real AST, while
preserving the project's core guarantee: diagnostics stay byte-identical to a
real `/skript reload`.

## What the research changed

Twelve research passes over Skript 2.16.2 source established that the loaded AST
is a **semantic** AST, not a **positional** one.

Confirmed and usable:

| Capability | API |
| --- | --- |
| Top-level structures | `Script.getStructures()` |
| Statement identity per line | `ScriptLoader.loadItems(SectionNode)` + order-zip against `Node.getLine()` |
| Section entry schemas | `SyntaxInfo.Structure.entryValidator().getEntryData()` |
| Type information + docs | `Expression.getReturnType()`, `ClassInfo` doc accessors |
| Function resolution | `SkriptParser.parseFunctionReference()` |
| Syntax prose docs | `@Name`/`@Description`/`@Example`/`@Since` on `SyntaxInfo.type()` |
| Exact 1-based line index | `Config`/`SectionNode` node tree |

Hard limits — not effort, missing API:

- **No expression traversal.** No visitor, no `getChildren()`. `Effect`/`Condition`
  keep their `Expression[]` private, so variables inside a statement cannot be
  enumerated.
- **No source text retention.** `getSource()` returns an `Expression`, not a `String`.
- **No positions below the line.** `Node.getLine()` is the floor; no column exists
  anywhere in the public API.
- **No public definition positions** for functions, commands, or variables.

Consequence: **"replace every heuristic feature with the AST" is not achievable
as stated.** Roughly half the feature surface can become genuinely AST-driven;
the rest needs a positional layer we own, regardless of how much Skript is reused.

## Architecture

```
didChange ──► ParseBridge.parse()            [existing, mostly unchanged]
                ├─ write temp .sk
                ├─ ScriptLoader.loadScripts() ──► diagnostics (LogEntry)
                └─ NEW: AstIndexer.index()
                     ├─ Config/SectionNode tree  ──► line → (raw text, kind, depth)
                     ├─ loadItems() order-zip    ──► line → syntax class
                     └─ snapshot to plain Kotlin data classes   ◄── before unload
                └─ ScriptLoader.unloadScripts() [existing]

  Features read the snapshot. No live Skript objects escape the indexer.
```

The snapshot must be taken **before** unload: `Script.invalidate()` cascades to
`config.invalidate()` and the docs warn data "could have cleared during unloading".

Two complementary sources, deliberately asymmetric:

- **`loadItems` — authoritative identity.** One extra parse per content hash,
  cached. Skript's own parser decides what each line is.
- **Pattern index in `SyntaxRegister` order — documentation only.** Near-free,
  because `SkriptPattern.countTypes() == 0` means literal-only patterns cost no
  expression parsing, and `match()` has a keyword/length pre-filter.

Hand-rolling a matcher over `SyntaxInfo.patterns()` is **not** a valid shortcut:
`TypePatternElement.match` runs a full expression parse per candidate offset, so
it costs the same as a real parse while being less accurate (it skips event
restrictions, experimental gating, and section-claim conflicts).

## Column strategy — three tiers, degrading

| Tier | Provides | Cost |
| --- | --- | --- |
| 1 | Statement span via `rawLine.indexOf(node.getKey())` | trivial, always works |
| 2 | Exact `<regex>` slots (`getRegexResults()`) and `UnparsedLiteral` slots | cheap, most real code |
| 3 | Exact `%…%` slots for arbitrary expressions | re-implements the matcher walk |

Tier 1 is the default. Tiers 2–3 are opportunistic. Any failure falls back to
Tier 1, never to a wrong position. Offsets map back with
`rawIndex = leadWhitespace + offsetInCode`, valid unless a `##` collapse or
`{@option}` expansion occurs earlier on the line.

## Reuse vs. build

Reuse stock Skript wherever it answers. Verified public and unit-testable without
a server: `Node.splitLine`, `VariableString.nextVariableBracket`,
`VariableString.quote/isQuotedCorrectly/unquote`, `SkriptParser.next`,
`SkriptParser.nextOccurrence`, `Variable.isValidVariableName(…, printErrors=false)`,
`Functions.functionNamePattern`, `StringUtils.*`, `PatternCompiler.compile`,
`SkriptPattern.match`, `Config` + node tree.

Must be written here (regexes captured verbatim during research):
`StructCommand.COMMAND_PATTERN`, `StructFunction.SIGNATURE_PATTERN`,
`FunctionReferenceParser.FUNCTION_CALL_PATTERN`, `FunctionParser.SCRIPT_PARAMETER_PATTERN`,
command argument/description/escape patterns, indentation detection, `%…%` pairing,
and pattern cleaning (`Documentation.cleanPatterns` is `protected static`).

Never use `Structure.getEntryContainer()` — deprecated for removal since 2.10
with a replacement that is impossible from outside. Use
`SyntaxInfo.Structure.entryValidator()`.

## Adapter boundary

All Skript API contact lives in one file. Tier budget: ~155 clean, 11 experimental,
20 internal, 20 deprecated-for-removal. Build with `-Xlint:deprecation
-Xlint:removal -Werror` scoped to it.

Six landmines to design around:

1. `Structure.parse` 4-arg overload forces a deprecated type into source — use 3-arg only.
2. `getEntryContainer()` — avoid entirely.
3. **No public way to activate a `ParserInstance`.** `setActive`/`setInactive` are
   `@ApiStatus.Internal`; `setCurrentScript(Script)` is private. Clean escape hatch:
   register a `ScriptLoader.ScriptLoadEvent` listener — fired at `ScriptLoader.java:644-647`,
   immediately after `setActive` at `:641`. `ScriptInitEvent` and `ScriptUnloadEvent`
   both fire with the parser inactive.
4. `SyntaxInfo` can be a deprecated `SyntaxElementInfo` — never call `instance()`.
5. `AnyNamed.name()` is deprecated with no read-side replacement — use `nameAndPath()`.
6. `Classes.getSuperClassInfo` is annotated `@Contract(pure = true)` but writes and
   throws if Skript isn't live — use `getClassInfoNoError`.

Removal window is ~9 months minimum (`code-conventions.md:344`), so upgrades are
scheduled maintenance, not emergencies.

~21 accessors return live mutable views despite being `unmodifiable` — including
`Script.getStructures()`, `RetainingLogHandler.getLog()`, and
`EntryValidator.getEntryData()`. Copy defensively.

## Phases

**Phase 0 — correctness (done, shipped in `a58fa83`, `d0eac08`).** Fixed a crash in
`applyRangeEdit` that desynced the buffer on any newline-terminated file; fixed
`origin()` rendering `OriginImpl$UnknownOrigin@hash` in completion details; added
the missing `BukkitSyntaxInfos.Event.KEY` so `on <event>:` completions are non-empty.
Still outstanding, all cheap:

- Readiness gate on `!Skript.isAcceptRegistrations()`. Load order in
  `paper-plugin.yml` is already correct (`load: BEFORE` on Skript means Skript
  loads first) but registration completes one tick after `onEnable` returns,
  because `ExprArithmetic` registers inside `stopAcceptingRegistrations()`.
- Guard `ScriptLoader.isAsync()` — at `script loader thread size >= 1` in
  `config.sk`, `.join()` deadlocks the server outright.
- Bound the `.join()` with a timeout so a wedged parse degrades instead of freezing.
- Drop `INFO` from diagnostics (`SkriptLogger.DEBUG == Level.INFO`).
- Dedupe diagnostics by `ErrorQuality`; treat `node == null` as document-scoped.
- Prune `DefinitionReferenceProvider.documents` on `didClose`.

**Phase 1 — per-keystroke cost.** `rebuild()` re-indexes every tracked document on
every keystroke and is O(N) in open files; `contentChanges[i].range` is already in
hand and discarded. `didChange` also blocks the LSP reader thread on a main-thread
parse with no coalescing. Thread `contentChanges[i].range` through, index only the
changed document, and coalesce. A line-offset table removes 4 whole-document splits
per completion, 3 per definition, 2 per hover. Precompute the registry pattern
table — completion currently emits up to ~4,000 items when the prefix is empty.

**Phase 2 — AST index + feature rewrites.** Build `AstIndexer` per Phase 1.
Then rewrite hover (off `SyntaxInfo.type()` + annotations — effectively the docs
site inline), document outline, definition/references/rename (keeping the text
index for positions), and completion context. Each lands behind a checkpoint.

**Phase 3 — optional.** Tier 2/3 columns, type-aware expression hover,
`parseFunctionReference` resolution.

## Testing

A JVM harness cannot exercise real AST parsing: `Skript.instance()` is an
un-settable private static and `BukkitClasses`' static initializer NPEs without a
Server. Skript's maintainers disabled their own `test` task and run everything in
a downloaded Paper server.

Testable in plain JUnit: the `Config`/`SectionNode` line index, `PatternCompiler`,
the lexer built on Skript's public text primitives, column alignment, and range
math. Plus **golden snapshots** — the best defence against ~16 releases/year of API drift,
because a diff names the construct that changed.

Server-backed integration tests only for what genuinely needs the parser.

## Known blind spots

- `SecConditional`'s multiline `if` bodies collapse to one `conditional`, so those
  condition lines get no syntax class.
- Nested `TriggerItem`s are unreachable (`TriggerSection.first` is `protected`).
- `EffectSectionEffect`, `EffFunctionCall` and `SimplifiedCondition` return null
  from `getNode()` — hence the order-zip rather than per-item lookup.
- 15 `Equippable*` experimental syntaxes register unconditionally on Paper 1.21.2+,
  so completions must be annotated or filtered per-script rather than blindly offered.
- Entry value types are not exposed (no `getType()` anywhere).
- `event-conditions` does not exist in 2.16.2.