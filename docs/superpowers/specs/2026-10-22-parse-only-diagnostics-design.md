# Parse-only diagnostics & AST-backed features — design

**Date:** 2026-10-22
**Status:** Proposed
**Related:** `docs/skript-api-reference.md` (verified Skript API facts), `docs/superpowers/specs/2026-10-04-ast-driven-features-design.md` (earlier design)

---

## 1. Goal

Replace LspSkript's diagnostic pipeline so that **the language server never loads the user's script into the server**, and rewire the language features to read a single parsed snapshot instead of re-deriving everything from raw text.

Two concrete outcomes:

1. **The user's dev server stops executing their `on load:` block on every keystroke.** Today `ParseBridge` calls `ScriptLoader.loadScripts(...)`, which runs the full load phase; `on load:` is `EvtScript`, whose `postLoad()` fires the trigger (`EvtScript.java:41-45`).
2. **Diagnostics become trustworthy and cheap.** Today we surface `INFO`-level debug chatter, duplicate one-per-line errors that Skript itself collapses, and anchor node-less entries to line 0. The parse-only path gives us Skript's own entries, filtered and deduped the way Skript's own `ParseLogHandler` filters them.

## 2. Scope

Sub-projects 1–3 of the earlier spec. **Out of scope:** the lexer, column reconstruction, and the variable-occurrence index (sub-project 4). References and rename stay text-based in this change.

### 2.1 Open inputs

None. All four research threads have reported and their findings are folded in:

- **The active `ParserInstance`** — `ParserInstance.setActive(Script)` is public and
  advisory-only `@ApiStatus.Internal`; the `ScriptLoadEvent` route is dead for a
  parse-only path. §4.1
- **The command path** — `SubCommandEntryData.getValue(Node)` is usable standalone
  and **no command diagnostics are lost**. §4.4
- **Local function registration** — a synthetic per-document namespace with
  `local = true`; `local = false` permanently breaks the server's real function.
  §4.3
- **The verification approach** — differential comparison on a development
  server: the parse-only path and the legacy load path must produce the same
  diagnostics, and the `on load:` side effect must be gone. §9.1

## 3. Architecture

```
didChange (LSP reader thread)
  ├─ update the in-memory buffer (cheap, incremental)
  └─ schedule a parse for this URI (debounced, server-side)
        └─ SkriptParseEngine.parse(uri, text) ──► DocumentSnapshot
              ├─ diagnostics   List<Diagnostic>
              ├─ lines        TextBuffer
              ├─ sections     List<SectionInfo>
              └─ statements   List<StatementInfo>
        ├─ publish diagnostics (only if this snapshot is still current)
        └─ swap the snapshot for this URI (atomic)
```

**One parse, many consumers.** No feature re-derives positions, splits the document, or re-scans another file.

### 3.1 New components

| File | Responsibility | Imports Skript? |
|---|---|---|
| `TextBuffer.kt` | Line offsets, CRLF-correct splitting, `offsetOf`/`positionOf`, real line ends | no — pure |
| `SkriptParseEngine.kt` | **The only file that imports Skript.** Owns the whole parse | yes |
| `DocumentSnapshot.kt` | The immutable result — data classes only | no — pure |
| `ParseDiagnostics.kt` | `LogEntry` → `Diagnostic` mapping, filtering, dedup | only via the engine's types |
| `SectionSchema.kt` | `entryValidator` → per-structure section key schema, cached | yes, but behind the engine |
| `ScriptParser.kt` | The interface seam so tests can substitute a fake | no |

**`ParseBridge` implements `ScriptParser`.** The engine is the thing that does the
work; `ParseBridge` is the thin adapter that satisfies the interface, and
`SkriptTextDocumentService` depends on the interface rather than on `ParseBridge`.
That keeps the seam one interface wide, not two.

### 3.2 Modified components

| File | Change |
|---|---|
| `ParseBridge.kt` | Becomes a thin wrapper over the engine. Loses the temp file, `loadScripts`, and `unloadScripts` |
| `SkriptTextDocumentService.kt` | Per-URI debounce and snapshot swap, replacing the direct `index()` call |
| `DefinitionReferenceProvider.kt` | Reads the snapshot instead of re-scanning every open document |
| `CompletionProvider.kt` | Reads the snapshot; uses a precomputed pattern table |
| `HoverProvider.kt` | Annotation-driven, not substring matching |
| `DocumentSymbolProvider.kt` | AST sections rather than the `Config` tree alone |
| `LspUtils.kt` | Line ranges from `TextBuffer` — no `Int.MAX_VALUE` |
| `SkriptLanguageServerService.kt` | Capability fixes (see §7) |

### 3.3 Snapshot contents

Minimal for this scope, behind an interface that can grow:

```kotlin
data class DocumentSnapshot(
    val uri: String,
    val sequence: Long,
    val text: String,
    val lines: TextBuffer,
    val diagnostics: List<Diagnostic>,
    val sections: List<SectionInfo>,
    val statements: List<StatementInfo>,
)

data class StatementInfo(
    val line: Int,              // 0-based, as LSP wants
    val syntaxClass: Class<*>,   // the Skript syntax class, for registry lookup
    val pattern: String?,        // the matched pattern, when known
    val kind: StatementKind,     // STRUCTURE | SECTION | STATEMENT
)

data class SectionInfo(
    val line: Int,
    val syntaxClass: Class<*>,
    val children: List<SectionInfo>,
)
```

`syntaxClass` is the key to everything downstream: it is the join between the AST and
the `SyntaxRegistry`, so hover, completion and symbols all resolve through one lookup
rather than by matching text. The richer per-element data — the matched `ParseResult`,
per-pattern-group offsets — is deliberately *not* captured yet; it is what sub-project
4 needs, and adding it later means extending these classes rather than reshaping them.

`DocumentSnapshot` is immutable and swapped atomically per URI. A request that reads
it mid-parse sees the last complete snapshot, never a half-built one.

### 3.4 Why the snapshot holds copied data, not live AST objects

`TriggerItem.getParser()` resolves to a `ThreadLocal` `ParserInstance`, and `setInactive()` clears the data map, caches and hint scopes. Holding AST objects past the parse is use-after-reset. The snapshot is therefore **plain copied data**, which also makes it constructible in a test with no Skript runtime at all.

## 4. The parse engine

### 4.1 Sequence

```kotlin
// 1. Config from text. `file` is only stored, never read (Config.java:58-62).
//    simple MUST be true  — StructOptions/Variables/Aliases call convertToEntries,
//    which throws "config is not simple" otherwise (SectionNode.java:422-423).
//    fileName MUST end in ".sk" and be >= 3 chars — Variable.java:209 does
//    getFileName().substring(0, -3) and would throw StringIndexOutOfBoundsException.
//    fileName is ALSO the function namespace: the call-site lookup uses
//    parser.getCurrentScript().getConfig().getFileName() verbatim
//    (FunctionReferenceParser.java:124), so it must equal the string handed
//    to FunctionParser.parse.
val config = Config(
    ByteArrayInputStream(content.toByteArray(UTF_8)),
    "__lsp__$docId.sk",     // stable per document
    realFileOrNull,         // the real path when it exists, so `auto reload:` does not false-error
    simple = true,
    allowEmptySections = false,
    defaultSeparator = ":",
)

// 2. A fresh Script per parse. OptionsData, DefaultVariables, ScriptAliases,
//    ExperimentSet and suppressedWarnings all live on the Script and would
//    otherwise leak between parses — and suppressedWarnings leaking would
//    corrupt a real script's warning suppression.
val script = Script(config, mutableListOf())

// 3. Activate the parser, on the thread that will parse (ParserInstance is a
//    ThreadLocal — ParserInstance.java:34). setActive is public and marked
//    @ApiStatus.Internal: advisory only, not @Deprecated. It also calls
//    reset(), which clears the per-parse data map (ParserInstance.java:63-64).
val parser = ParserInstance.get()
parser.setActive(script)
try {
    // 4. Parse phase — mirrors ScriptLoader.loadScript (ScriptLoader.java:702-727)
    for (node in config.mainNode) {
        var line = node.key ?: continue
        line = ScriptLoader.replaceOptions(line)      // now actually substitutes {@options}
        if (!SkriptParser.validateLine(line)) continue
        val structure = Structure.parse(line, node, "Can't understand this structure: $line") ?: continue
        parsed += structure
    }

    // 5. Load phase. preLoad() and load() parse the bodies and produce the
    //    diagnostics; postLoad() is NEVER called, so `on load:` never fires
    //    and no Bukkit listener is registered.
    //    Two structures have a load() that writes server state, and one
    //    structure is skipped outright — see the table in §4.5.
} finally {
    // setInactive() resets: currentStructure, currentEventName, currentEvents,
    // currentSections, hasDelayBefore, node, dataMap, expressionParseCache,
    // literalParseCache, and the HintManager (all scopes dropped).
    parser.setInactive()
}
```

### 4.2 What is never called

| Method | Why not |
|---|---|
| `Structure.postLoad()` | `EvtScript.postLoad()` fires `on load:`; `SkriptEvent.postLoad()` registers Bukkit listeners; the 2.17 `StructCommand.postLoad()` processes command registrations; `StructAutoReload.postLoad()` starts a repeating task that reloads the script |
| `StructFunction.preLoad()` and `.load()` | We register the signature ourselves with `local = true` and call `Functions.loadFunction` ourselves, so the body is still parsed but nothing global is touched (§4.3) |
| The 2.17 `StructCommand.load()` | `ScriptCommandRegistrar.register(command)` queues into `PENDING_REGISTRATIONS` with **no clean undo** (`ScriptCommandRegistrar.java:43, 105`) — the leak causes a spurious *"command already defined"* on every later parse. We replicate the two diagnostics instead (§4.4) |
| `StructVariables.load()` | `Task.callSync` + `Variables.setVariable` — creates real global variables. Its `preLoad()` is safe and **is** called |
| `StructAliases` — the whole structure | `init()` calls `Aliases.createScriptAliases(script)` and `scriptAliases.parser.load(node)`, registering aliases into Skript's global alias registry. `Aliases` has no per-script removal API, so the structure is skipped outright (§4.5) |
| `ScriptLoader.loadScripts(...)` | The whole thing we are removing |

### 4.3 Function-call resolution

Skipping `StructFunction.preLoad()` means a call to a function defined in the same
file fails to resolve — `FunctionReferenceParser.doesNotExist(...)` → *"The function
X(...) does not exist."* — **and the whole line then fails to parse**, suppressing
every other diagnostic on it. So we must register the signature ourselves.

**The namespace is the `Config`'s file name, verbatim.** The lookup uses
`parser.getCurrentScript().getConfig().getFileName()`
(`FunctionReferenceParser.java:124`); the registration uses the `script` argument
to `FunctionParser.parse` (`Functions.java:200, 209`). They are the *same string*,
so we use one value for both:

```kotlin
// Stable per document, so repeated parses reuse one namespace.
// Must end in ".sk": Variable.java:209 does getFileName().substring(0, -3),
// which throws for a name that is not at least 3 characters long.
val ns = "__lsp__$docId.sk"

val config = Config(inputStream, ns, /*file*/ null, simple = true, allowEmptySections = false, ":")
// ...setActive(script)...

// For every `function` / `local function` declaration in the buffer:
val sig = FunctionParser.parse(ns, name, args, returns, /*local*/ true) ?: continue
Functions.registerSignature(sig)     // local => touches no global state
registered += sig                     // for cleanup

// Then, in the load phase, parse the function's BODY. Functions.loadFunction is
// public and takes the signature we just registered; because that signature is
// local, the resulting Function lands in OUR namespace and not the global
// bucket (Functions.java:140-144). Skript's own loader does exactly this in
// StructFunction.load() — we just feed it our own signature.
Functions.loadFunction(script, node, sig)
```

**The function's body is still parsed.** `Functions.loadFunction` constructs a
`ScriptFunction`, which is what parses the body — so a syntax error inside a
function body is still reported. Skipping `StructFunction.preLoad()` alone would
have lost it.

**The signature is parsed from the header text**, with the same pattern Skript
uses (`StructFunction.java:84`):

```kotlin
private val FUNCTION_SIGNATURE = Pattern.compile(
    "^(?:local )?function (?<name>${Functions.functionNamePattern})" +
        "\\((?<args>.*?)\\)(?:\\s*(?:->|::| returns )\\s*(?<returns>.+))?$"
)
```

`Functions.functionNamePattern` is public, so the pattern can be rebuilt exactly
rather than copied.

**Always `local = true`.** `local` does not change the namespace *string* — it
selects the registry bucket, and whether `Functions.globalFunctions` is written
(`Functions.java:205-206`).

Registering with `local = false` is **unsafe**: it overwrites the server's
`globalFunctions` entry, and cleanup does a *blind* `globalFunctions.remove(name)`
(`Functions.java:380`) rather than restoring it — so the server's real function
stays broken for `Functions.getGlobalFunction` → `DynamicFunctionReference` →
`the function named "f"` at runtime. The synthetic namespace avoids this entirely.

**A synthetic namespace is strictly safer than the real one.** Using the real
script's namespace would (a) report the user's own `local function` as a duplicate
of itself (`Functions.java:180-197`), and (b) mutate namespace state the server
owns, racing a concurrent `/reload`.

**The one diagnostic we lose** is the genuine *"Function 'f' with the same argument
types already exists in script 'x'."* for a user-written **global** function — the
duplicate check compares locality (`Functions.java:180`), so a local registration
never trips it. We reproduce it ourselves with public API:
`Functions.getGlobalSignature(name)` (`Functions.java:303`) and an exact
argument-type comparison, then emit the same message.

**Cleanup, in a `finally`:**

```kotlin
for (sig in registered) Functions.unregisterFunction(sig)   // Functions.java:372
```

Because the signature is local, this removes it from the `FunctionRegistry` bucket
and from `Functions.namespaces` and **leaves `globalFunctions` untouched**
(`Functions.java:378-384`). Removal is identity-checked (`Namespace.java:158`), so
a pre-existing server signature is never removed.

**Two rules the cleanup depends on:**
- **The namespace is stable per document**, not per parse. `FunctionRegistry.remove`
  empties the inner maps but never removes the `NamespaceIdentifier` entry
  (`FunctionRegistry.java:583-608`) — a per-parse namespace would leak one empty
  `Namespace` per parse.
- **Unregister before re-registering.** `FunctionRegistry.register` throws
  `SkriptAPIException` if the same name and argument types are registered twice in
  the same namespace (`FunctionRegistry.java:112-122, 179-183`) — and
  `FunctionIdentifier.equals` ignores locality (`:726-746`).

**Never call `Functions.validateFunctions()`** — it re-validates references and can
emit *"The function 'x' from the script 'y' no longer exists."*

### 4.4 Command sections

In 2.17.1 the live command structure is `org.skriptlang.skript.bukkit.command.elements.structures.StructCommand`; its `init()` (`:93-97`) only stores the root node; **all** command
diagnostics live in `load()` (`:99-126`), which we skip — because `load()` calls
`ScriptCommandRegistrar.register(command)` (`:122`), which queues into
`PENDING_REGISTRATIONS` with **no clean undo** (`ScriptCommandRegistrar.java:43, 105`).
The leak causes a spurious *"A command with the name /x is already defined"* on every
later parse.

**The route is `SubCommandEntryData.getValue(Node)`.** It is public, carries no
deprecation or `@ApiStatus.Internal` marker, and registers nothing. (The package is
documented as internal API by the replacement `StructCommand`, so it is unsupported
but not blocked — we pin to a Skript version and isolate it in the engine.)

```kotlin
// The exact SectionNode we passed to Structure.parse IS the command's root
// (object identity) — StructCommand.init() stores it as rootNode.
val expr = ScriptLoader.replaceOptions(rootNode.key)   // options must be replaced

parser.setCurrentStructure(structure)   // required if a `cooldown:` entry exists:
                                         //   SubCommandEntryData.java:329-330 asserts non-null
parser.setNode(rootNode)                // so LogEntry carries the right line
val result = SubCommandEntryData("command", false, false)   // == the private ROOT_ENTRY_DATA
    .getValue(rootNode)                 // SubCommandEntryData.java:184
```

**Preconditions** — all satisfied by the engine's setup:

| Requirement | Why |
|---|---|
| Active parser with a current `Script` | `TriggerEntryData.getValue` → `getCurrentScript()` throws otherwise |
| `parser.setCurrentStructure(structure)` | `assert` + NPE in `ErrorSource.fromNodeAndElement` for `cooldown:` |
| `parser.setNode(rootNode)` before the call | `LogEntry` captures the node at construction |
| A log handler around the call | otherwise `Skript.error` reaches `Bukkit.getConsoleSender()` |
| `parser.setActive(script)` **per parse** | see the leak below |

**The `CommandParsingData` leak.** `isRoot` is `parsingData.isEmpty()`, the pushes happen
before the return points, and the pops run **only on the success path**
(`SubCommandEntryData.java:216, 344-347, 468-470`). A *failed* command parse
therefore leaves `argumentsIndices` non-empty, and the next command parsed in the same
`ParserInstance` computes `isRoot == false` and emits bogus *"Only the root of a
command may have aliases."* `setActive()` calls `reset()` (`ParserInstance.java:63-64`),
which clears `dataMap` — so a fresh `setActive` per parse closes it.

**No diagnostics are lost.** The two checks that live only in `StructCommand.load()`
are replicable without registering:

- *"A command must have a name."* — when `result.arguments().size() != 1` or the root
  is not a `LiteralCommandNode`.
- *"A command with the name /x is already defined in the script 'y'."* — via
  `ScriptCommandRegistrar.getCommand(literal)` (public static, reads `PENDING` and
  `REGISTERED`).

**Fallback:** if `SubCommandEntryData` proves unusable in a live test, we document
the gap for command bodies. We do **not** fall back to `loadScripts`, because that
silently reintroduces the `on load:` execution this change exists to remove.

### 4.5 Every structure, and what the engine does with it

Skript 2.17.1 registers ten structures. `Structure.parse` returns one of them, or
null. The engine dispatches on the returned type. This table was read off the
Skript 2.17.1 source, not guessed.

| Structure | `init()` | `preLoad()` | `load()` | `postLoad()` | The engine |
|---|---|---|---|---|---|
| `StructAliases` (prio 200) | **registers aliases globally** | — | `return true` | — | **Skipped** — the node is never parsed |
| `StructAutoReload` (prio 10) | errors unless `ScriptLoader.isAsync()`; errors unless `config.getFile()` exists | — | `return true` | **starts a repeating task that reloads the script** | Parsed; `postLoad()` is never called. The `Config` gets the real file when it exists, so there is no spurious *"auto reload.file not found"* |
| `StructCommand` (2.17, prio 500) | stores the root node | — | parses, validates, **registers the command** | processes registrations | Parsed and validated by hand (§4.4); never registered |
| `StructEvent` (prio 1000) | — | `event.preLoad()` — a no-op | `event.load()` — parses the body and creates the `Trigger` | `SkriptEventHandler.registerBukkitEvents(...)` | `preLoad()` and `load()` are called; `postLoad()` is not |
| `StructExample` (prio 1000) | — | — | `ScriptLoader.loadItems(source)` — parses the body | — | `load()` is called; it registers nothing |
| `StructFunction` (prio 400) | — | parses and registers the signature | `Functions.loadFunction` | `Functions.validateFunctions()` | Handled by hand (§4.3) |
| `StructOptions` (prio 100) | `convertToEntries(-1)`, stores options **on the Script** | — | `return true` | — | Safe — called normally |
| `StructUsing` (prio 15) | `parser.addExperiment(...)` — parser-local | — | `return true` | — | Safe — called normally |
| `StructVariables` (prio 1000) | — | parses the definitions, stores them **on the Script** | **`Variables.setVariable` — creates real global variables** | — | `preLoad()` is called; `load()` is not |
| `ch.njol.skript.structures.StructCommand` | `return false` | — | `return false` | — | **Deprecated and not registered.** The live class is `org.skriptlang.skript.bukkit.command.elements.structures.StructCommand` |

Two `load()` methods are skipped and one structure is skipped outright. Everything
else is called exactly as Skript would call it.

`Structure.parse` calls `init()`, so a structure's `init()` side effect happens
whenever the structure is parsed. That is why `StructAliases` is skipped at the
node level rather than at the `load()` level — by the time we could intervene, the
aliases would already be registered.

### 4.6 Traps, recorded

- `Config` must be `simple = true`; the file name must end in `.sk` and be ≥ 3 chars.
- `ParserInstance` is `ch.njol.skript.lang.parser.ParserInstance`.
- A fresh `Script` per parse.
- `setCurrentEvent` before `loadItems`.
- `Config`'s constructor **closes the InputStream** (`Config.java:60`).
- `RetainingLogHandler.getLog()` returns a **live view** of a mutable deque and has a
  side effect (`RetainingLogHandler.java:188-192`) — copy it.

## 5. Diagnostics

`LogEntry` exposes public fields `level`, `quality`, `message`, `node` — there is no
`getNode()`.

| Rule | Rationale |
|---|---|
| Drop `Level.INFO` and below | `SkriptLogger.DEBUG == Level.INFO` (`SkriptLogger.java:30`); these are debug chatter, not diagnostics |
| Keep one `SEVERE` per line, the one with the highest `ErrorQuality` | Exactly what Skript's own `ParseLogHandler` does (`ParseLogHandler.java:43-46`) |
| `node == null` → a document-scoped diagnostic, not line 0 | `ParserInstance.setNode` nulls parentless nodes (`:469-471`), so a null node means "not attributable to a line" |
| Severity from `level`; `Diagnostic.code` from `ErrorQuality` and the message prefix | Gives clients a stable machine-readable code |
| Range = the line, with the real line end | Not `Int.MAX_VALUE` — the spec disallows special values |
| Prefer a message-embedded span when one exists | `CONSTANT_CONDITION` quotes the condition; `VARIABLE_CONTAINS_COLON` embeds `{name}` |

**Extraction:** the mapping is a **pure function** from a small value type to
`Diagnostic`, so it is unit-testable with no Skript runtime.

## 6. Threading, debounce, supersession

**Threading — the Bukkit main thread, always.** This is forced by the API, not a
preference:

- `Functions.namespaces` (`:37`), `Functions.globalFunctions` (`:52`) and
  `Functions.toValidate` (`:347`) are **plain unsynchronised `HashMap`s**.
- `FunctionRegistry`'s inner `Namespace` maps are plain `HashMap`s too — only
  `register()` synchronises on the `Namespace` (`FunctionRegistry.java:111-117`);
  `getSignatures`/`getSignature`/`remove` iterate them **without** synchronisation.
- `Classes.superClassInfos` (`:56`) is a plain `HashMap` written on nearly every
  parse (`:295`).
- `FunctionRegistry.getRegistry()` has a lazy-init **check-then-act race**
  (`:35-40`) — two threads can each create a different registry.

All of it is process-wide, not per-thread. Only `ParserInstance` and the log-handler
stack are `ThreadLocal`. So a background parse racing a server-side script load is
a genuine data race — and the failure mode is a corrupted `HashMap`, which can
hang the server.

The upside is that the *load phase we are removing* was the expensive part.
Without it, the main-thread cost is a parse rather than a parse plus a full
load/unload cycle with registration churn.

**Debounce — server-side only.** The server must be correct regardless of client. A
VS Code-side debounce is a later network-hygiene win.

**Debounce interval — 200 ms trailing**, configurable as `parse-debounce-ms` in
`config.yml`. 200 ms is below the threshold where a user perceives lag while typing
and above the threshold where a fast typist triggers a parse per keystroke. The
interval is measured from the **last** change, so a burst of keystrokes produces
exactly one parse. Because the parse is on the main thread, the debounce is what
keeps the server from stalling repeatedly during a burst.

**Supersession — sequence number.** Each parse carries a monotonically increasing
sequence per URI. On completion, publish only if it is still the latest. Skript's parser
has no cancellation hook, so we cannot interrupt an in-flight parse; we drop its result
instead.

## 7. Feature rewrites

### 7.1 Hover (first)

`SyntaxInfo.type()` → the implementing class → `@Name` / `@Description` / `@Example` /
`@Since` from `ch.njol.skript.doc`. For expressions, also the return type's `ClassInfo`
documentation. For events, `BukkitSyntaxInfos.Event.description()` / `.examples()` —
event classes carry **no** doc annotations.

`@Description` contains HTML (`<a href="#ExprColored">`, `<b>`, `<code>`); convert to
markdown. jsoup is the only robust option — MIT, 525 KB, zero runtime dependencies.

### 7.2 Completion (second)

- A precomputed table per registry key: `(lowercased cleaned pattern, original label,
  SyntaxInfo)`, built once at snapshot time. Removes the per-pattern `lowercase()` and
  `cleanPattern()` calls and the repeated registry snapshots.
- Include `BukkitSyntaxInfos.Event.KEY` — events are **not** under `STRUCTURE`.
- Cap the result and return `CompletionList(isIncomplete = true)`. Today an empty
  prefix can emit ~4,000 items.
- Section-entry completion from `entryValidator().getEntryData()` — the valid keys under
  `command /x:`.

### 7.3 Document symbols (third)

From the snapshot's sections, which the engine derives from the config tree it
already built.

The ambition of walking the *parsed* structures is **deferred**: Skript's
public API exposes no way to enumerate the structures a parse produced, so
there is nothing to walk. The config tree is the closest faithful
approximation, and it is what the current provider already uses.

### 7.4 Definition / references / rename — unchanged

Enumerating variables *inside* a statement needs a text scan, because Skript's AST cannot
reach expressions. That is sub-project 4. The existing regex index stays, but it must
stop re-scanning every open document on every change: index **per document**, and prune
on `didClose`.

## 8. Correctness and tooling changes

| Change | Detail |
|---|---|
| **Retarget the plugin to Java 21 bytecode** | **This is a shipping blocker, independent of everything else.** The shaded jar is Java 25 bytecode (class-file major 69), but `paper-plugin.yml` declares `api-version: '1.21'`, and Paper's supported Java for the 1.21.x line is **21** (Java 25 is only required for the calendar-versioned 26.x line). A server admin on Paper 1.21.11 with Java 21 — the officially supported combination — gets `UnsupportedClassVersionError` and the plugin silently fails to load. Fix: keep the toolchain at 25, but set `jvmTarget = JVM_21` and `sourceCompatibility`/`targetCompatibility = 21`. Skript 2.17.1 itself is Java 21 bytecode (Skript's own CI matrix has a `java21/paper-1.21.11` environment), so this is the combination Skript itself is tested on |
| **LSP4J 0.23.1 → 1.0.0** | Its own commit, **before** the rewrite. One source change: `Diagnostic.getMessage()` now returns `Either<String, MarkupContent>`. LSP 3.18 support, and the new `CompletableFutures.cancelRequest(future, mayInterruptIfRunning)` |
| **`TextBuffer`** | Splits on `\r\n\|\r\|\n` — `BufferedReader` semantics — not `split('\n')`, which mis-numbers any file containing a lone `\r`. Replaces six duplicated `split('\n')` call sites and the `Int.MAX_VALUE` line ends |
| **Readiness gate** | Serve no requests until `!Skript.isAcceptRegistrations()`. Registration completes one tick after `onEnable` returns, because `ExprArithmetic` registers inside `stopAcceptingRegistrations()` (`Skript.java:1372`) |
| **Async guard** | If `ScriptLoader.isAsync()` is true (`script loader thread size >= 1` in `config.sk`), refuse with an admin-facing message. The current `.join()` on the main thread **deadlocks the server** under that config. Never call `setAsyncLoaderSize(0)` to "fix" it — global state, reverted by `/skript reloadconfig`, and its `size <= 0` branch strands dead threads so a later `set(>=1)` never completes its futures |
| **Capability hygiene** | `resolveProvider: true` with no `completionItem/resolve` handler; bare `textDocumentSync` enum so `didSave` is never delivered |
| **Config flag** | `parse-only: true` in `config.yml`, default on. The fallback is *deliberate and visible* — a user who hits a gap opts into the legacy behaviour knowingly, with the `on load:` side effect documented |
| **Move sources** | `src/main/java/**/*.kt` → `src/main/kotlin/`. Pure `git mv`; several Kotlin tools default to `src/main/kotlin` |
| **detekt + ktlint + JUnit Platform** | Their own commit, before the rewrite. detekt `1.23.8` → `dev.detekt:2.0.0-alpha.6` when we use Kotlin ≥2.1 syntax. ktlint CLI `1.8.0` standalone, **not** `detekt-formatting` (ktlint 0.50 rules). JUnit Platform 6.1.3 with Vintage so the 48 existing tests keep running. `.editorconfig` + `config/detekt/detekt.yml` — the documentation rules are **inactive by default** and must be opted into |
| **Coroutines** | Adopt **with** the rewrite. Real debounce (`delay` + `conflate`), real cancellation, and a single-worker `limitedParallelism(1)` dispatcher. Paper does **not** bundle coroutines — they must be shaded (+1.5 MB). `kotlinx-coroutines-core` only, never `-all` |

## 9. Testing

### 9.1 Verification on a development server

There is no committed test rig. Verification happens against a **development
server** — the same server the maintainer already runs — because that is the
environment the change has to be correct in.

**What must be observed, and how:**

| Check | How |
|---|---|
| Diagnostics still arrive | Open a `.sk` file with a deliberate error in the editor; the squiggle appears. |
| Diagnostics did not regress | Compare the same file's diagnostics before and after the change. |
| `on load:` no longer executes | Put `log "LSP-PROBE" to file "lsp-probe.log"` in an `on load:` block, open the file in the editor, and confirm the file is never created. |
| The server is unaffected | Watch the console while typing: no script-load messages, no command registrations, no listener registrations. |
| The server stays responsive | Type quickly; the server does not stall and logs no warnings. |

**The `on load:` probe must be delivered over LSP**, never placed in
`plugins/Skript/scripts/`. A file in the scripts folder is loaded by the
server at startup, which proves nothing about the editor path.

**The probe is a file write, not a variable.** Skript's `EffLog` supports
`log "<marker>" to file "lsp-probe.log"`, which appends to
`plugins/Skript/logs/` and flushes after every line. A global variable has no
out-of-band observation channel and would pollute the server's persistent
variable store.

**The `parse-only` flag is the escape hatch.** If the parse-only path turns out
to be missing a diagnostic, the maintainer sets `parse-only: false` in
`config.yml` and gets the old behaviour back — knowingly, with the `on load:`
side effect documented in the config file.

### 9.2 Unit tests (no server)

- `TextBuffer` — CRLF, lone `\r`, trailing newlines, offset/position round-trips
- The pure `LogEntry → Diagnostic` mapping — severity, dedup, null-node, code
- The snapshot model — construction, immutability
- `applyRangeEdit` — already covered

### 9.3 The `ScriptParser` seam

`interface ScriptParser { fun parse(uri: String, content: String): List<Diagnostic> }`
with `SkriptScriptParser` in production and a fake in tests. This tests our
orchestration — debounce, supersession, buffer handling — without a server.

**MockK is not adopted.** It cannot reach the thing we want to test: mocking
`loadScripts` means asserting our mock returns our mock, and a mocked
`ParserInstance.get()` returns a stub whose `getCurrentScript()` is null, which
explodes deep inside Skript's parser.

### 9.4 Differential comparison

The parse-only path replaces Skript's own load path, so the only meaningful
check is whether the two produce the same diagnostics for the same input.

Before the change, record the diagnostics for a set of scripts covering the
constructs that matter: sections, conditions, effects, expressions, functions
(local and global), commands, variables, and a deliberate syntax error. After
the change, compare.

The two paths are expected to differ in exactly one way: the parse-only path
must not produce side effects. Everything else should match message for
message.

Keep the comparison scripts in the repository, so the comparison can be
repeated after a Skript upgrade.

## 10. Rollout

| Stage | Content | Gate |
|---|---|---|
| 0 | LSP4J 1.0.0, detekt/ktlint/JUnit Platform, `.editorconfig`, source move | Build green, `check` passes |
| 1 | `TextBuffer` + diagnostics quality + readiness gate + async guard | Diagnostics unchanged on the dev server |
| 2 | The parse engine behind `parse-only: false` | Both paths agree on the same scripts |
| 3 | `parse-only: true` by default | No `on load:` side effect; diagnostics match |
| 4 | Hover, completion, symbols, section entries | Feature by feature, each behind the snapshot |

Stages 2-4 are separate commits. The legacy path stays until stage 3 is verified.

## 11. Risks

| Risk | Mitigation |
|---|---|
| `Classes.superClassInfos` race | Main-thread parsing initially; measure before moving off-thread |
| The `on load:` fix breaks some diagnostic we didn't notice | Differential comparison against the pre-rewrite build; the `parse-only` flag is the escape hatch |
| `SubCommandEntryData` is not usable standalone | Fall back to documenting the gap — **never** to `loadScripts` |
| Local function registration still shadows something | A synthetic per-document namespace, `local = true` only, and `unregisterFunction` in a `finally` — verified against Skript 2.17.1 source (§4.3) |
| detekt's rule set fights the rewrite | `ReturnCount.excludeGuardClauses: true` (collapses 13 findings to ~3); ~20 findings total, all mechanical |

## 12. Out of scope

The lexer, column reconstruction, the variable-occurrence index, references and rename
improvements, semantic tokens, and any upstream PR. The upstream tracker comments are
prepared separately in `docs/drafts/`.
