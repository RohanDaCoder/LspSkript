# Skript API reference & implementation notes

Everything here was verified by reading Skript's source, not by reading its
release notes. Citations are `file:line` against **Skript 2.17.1** (commit
`19172ca`), unless stated otherwise.

This is the working reference for replacing LspSkript's heuristic layer with
Skript's real AST, and for the correctness and performance work that has to
come first.

Related documents:

- `docs/superpowers/specs/2026-10-04-ast-driven-features-design.md` — the design
- `DRAFT-*.md`, `POSTING-PLAN.md` (repo root) — upstream tracker drafts

---

## 1. The three hard constraints

These are properties of Skript's public API, not gaps in our implementation.
Every design decision follows from them.

### 1.1 No character positions

`Node.getLine()` (1-based) is the finest position Skript exposes. There is no
column anywhere — repo-wide grep for `getColumn`, `columnNum`, `int column`
across `src/main/java` returns **0 hits**.

- `ConfigReader` keeps a single line counter: `private int ln = 0;`
  (`ConfigReader.java:22`), incremented in `readLine()` (`:37`). It also strips a
  UTF-8 BOM (`:43-51`) and handles dedent `reset()` (`:54-58`).
- `Node.getLine()` — `Node.java:329-331`, field `protected final int lineNum` at `:24`.
- `MatchResult.exprOffset` **is** the character offset, but it is package-private
  with no getter (`MatchResult.java:19`), and `toParseResult()` copies
  `source`, `regexes`, `mark`, `tags` but **not** `exprOffset` (`:45-52`).
  `SkriptParser.ParseResult` has no offset field at all (`SkriptParser.java:116-141`).
- `LogEntry` carries only a `Node` (`LogEntry.java:20-21`) and formats with
  `node.getLine()` (`:116`, `:149`).

**As of 2.17.1 two more `Node` accessors became public** — `getComment()`
(`Node.java:249-252`) and `getIndentation()` (`:263-265`), both `protected` in
2.16.2. Neither is a column: `getIndentation()` returns the *canonical* indent
(`StringUtils.multiply(config.getIndentation(), getLevel())`, `Node.java:264`),
not the line's actual leading whitespace.

### 1.2 No expression traversal

- No visitor. Repo-wide grep for `SkriptVisitor` / `Visitor` in `src/main/java`:
  **0 hits**.
- `Expression` has no `accept(...)`, no `getChildren()`, no `getParent()`.
- `Effect` and `Condition` keep their parsed `Expression<?>[]` in **private**
  fields with no getter (`Effect.java:21-88`, `Condition.java:23-145`).
  `EffChange.changed` / `changer` likewise (`EffChange.java:89-91`).
- `VariableString.strings` (the parsed `%…%` slots) is `private final` with no
  accessor (`VariableString.java:45`).
- `Variable` has **no** position at all, and its `init()` throws
  `UnsupportedOperationException` (`Variable.java:254-257`) — it is never
  initialised, so it does not even capture a parse node.

**Consequence:** variables inside a statement cannot be enumerated from the AST.
A text-level scan is unavoidable for that.

### 1.3 No source-text retention

`Expression.getSource()` returns an `Expression`, not a `String`
(`Expression.java:287-292`). It is useful as an unwrap chain
(`ConvertedExpression`, `SimplifiedLiteral`) but never gives you the text.

`Node.getKey()` is the *processed* line: comment stripped, `##` collapsed to
`#`, trimmed (`Node.java:73-75`). `Node.save()` is a re-serialisation, not the
original.

**Consequence:** we must keep the editor's buffer ourselves and treat it as the
source of truth for anything positional.

---

## 2. The public API surface, by need

All of these are public and carry no deprecation or `@ApiStatus.Internal`
marker, unless noted.

### 2.1 Diagnostics — one-to-one with a script load

| Need | API |
|---|---|
| Capture the log | `ch.njol.skript.log.RetainingLogHandler` — `start()` `:67` |
| Read it | `getLog()` `:188` (live unmodifiable view — **copy it**), `getErrors()` `:194` (fresh copy), `size()` `:183`, `clear()` `:176` |
| Suppress console output | `ch.njol.skript.log.BlockingLogHandler` — `start()` `:16` |
| Entry contents | `ch.njol.skript.log.LogEntry` — public **fields**: `level` `:15`, `quality` `:16`, `message` `:18`, `node` `:21`. There is **no** `getNode()`; `node` is the field |
| Pick the best error of several | `ch.njol.skript.log.ErrorQuality` — `NONE`=0, `NOT_AN_EXPRESSION`=1, `SEMANTIC_ERROR`=2 (`ordinal()`), read via `ErrorQuality.get(entry.quality)` |
| Locate it | `ch.njol.skript.config.Node` — `getLine()` `:329`, `getKey()` `:73`, `getConfig().getFile()` |

`SkriptLogger.DEBUG == Level.INFO` (`SkriptLogger.java:30` in 2.17.1), so
`INFO`-level entries are debug chatter.

### 2.2 The syntax catalogue — what the server supports

| Need | API |
|---|---|
| The registry | `Skript.instance().syntaxRegistry()` — `Skript.java:176`, `SkriptAddon.syntaxRegistry()` `SkriptAddon.java:84` |
| Enumerate by kind | `SyntaxRegistry.syntaxes(Key)` `:80`; `elements()` `:123` for everything |
| Keys | `STRUCTURE` `:25`, `SECTION` `:30`, `STATEMENT` `:36`, `EFFECT` `:41`, `CONDITION` `:46`, `EXPRESSION` `:51` |
| **Events** | `org.skriptlang.skript.bukkit.registration.BukkitSyntaxInfos.Event.KEY` `:33` — a plain `Key` with **no parent**, so `syntaxes(STRUCTURE)` does **not** contain events |
| The implementing class | `SyntaxInfo.type()` `:79` |
| Which addon | `SyntaxInfo.origin()` `:74` → `Origin.name()` `:54`, `Origin.AddonOrigin.addon()` `:38` |
| Match order | `SyntaxInfo.priority()` `:95`, `patterns()` `:90` |
| Section entry keys | `SyntaxInfo.Structure.entryValidator()` `DefaultSyntaxInfos.java:171` → `EntryValidator.getEntryData()` `:59` → `EntryData.getKey()` `:49`, `isOptional()` `:64`, `supportsMultiple()` `:71`, `getDefaultValue()` `:57`. **May be null** — then the structure handles its own entries |
| Expression return type | `SyntaxInfo.Expression.returnType()` `DefaultSyntaxInfos.java:66` |

`EFFECT` is effects only. Effects + conditions is `STATEMENT`; `EFFECT` and
`CONDITION` are `ChildKey`s of `STATEMENT` (`SyntaxRegistry.java:36-46`,
propagation in `SyntaxRegistryImpl.java:25-30`), and `syntaxes(STATEMENT)` is a
separately sorted union — not `EFFECT ++ CONDITION`.

**Ordering is deterministic and worth preserving.** `SyntaxRegister` is a
`ConcurrentSkipListSet` with `SET_COMPARATOR` (`SyntaxRegister.java:47-69`):
priority → core-before-addon → pattern complexity → `hashCode`. Iterating
`syntaxes(key)` in natural order reproduces Skript's own match order. Note
`SyntaxInfoImpl.hashCode()` is **value-based** (`Objects.hash(type(), patterns(),
priority())`, `SyntaxInfoImpl.java:134-137`).

### 2.3 Documentation — the same prose as docs.skriptlang.org

`ch.njol.skript.doc` holds `@Name`, `@Description`, `@Since`, `@Example`,
`@Examples`, `@Keywords`, `@Events`, `@RequiredPlugins`, `@NoDoc`,
`@RelatedProperty`, `@DocumentationId`. All `public`, all `@Target(TYPE)`,
all `@Retention(RUNTIME)` — readable with plain `Class.getAnnotation()` off
`SyntaxInfo.type()`.

Coverage in Skript core is effectively complete: **870 `@Name` annotations vs 30
`@NoDoc`**. Skript's own `HTMLGenerator` throws rather than degrade when a
syntax lacks docs (`HTMLGenerator.java:67-70`).

**Events are the exception.** `ch/njol/skript/events` contains **zero** doc
annotations. Event docs live on the info instead:
`BukkitSyntaxInfos.Event.description()` `:103`, `examples()` `:109`, `since()`
`:97`, `keywords()` `:115`, `requiredPlugins()` `:121`, `name()` `:80`, `id()` `:85`.

The docs-site anchor is `DocumentationIdProvider.getId(SyntaxInfo)` `:76` — but it
throws `IllegalStateException` for anything outside the five syntax kinds
(`:90`).

### 2.4 Types

| Need | API |
|---|---|
| Expression type | `Expression.getReturnType()` `:178`, `possibleReturnTypes()` `:187`, `canReturn()` `:197` — all cheap field reads, safe to call on any parsed node |
| Skript type name | `ch.njol.skript.registrations.Classes.getSuperClassInfo(Class)` `:284` → `ClassInfo.getCodeName()` `:294` |
| Type docs | `ClassInfo.getDescription()` `:365`, `getUsage()` `:370`, `getExamples()` `:375`, `getSince()` `:380`, `getDocName()` `:384` |
| Type properties | `ClassInfo.getAllProperties()` `:522` → each `Property` has `name()`, `description()`, `since()` |

**`Classes.getSuperClassInfo` is not pure.** It is annotated
`@Contract(pure = true)` (`:283`) but writes to a plain `HashMap`
(`superClassInfos`, `:56`) at `:295`, and it calls `getClassInfos()` →
`checkAllowClassInfoInteraction()` → `Skript.isAcceptRegistrations()`, which
**throws** `IllegalStateException` if Skript was never loaded. It is also called
on virtually every parse, so concurrent parses can corrupt that map.

Safe alternatives when Skript may not be live: `Classes.getClassInfoNoError(String)`
`:258` and `getExactClassInfo(Class)` `:272` — both plain map reads, and their
javadocs explicitly bless early use.

### 2.5 Functions and commands

| Need | API |
|---|---|
| Resolve a call | `SkriptParser.parseFunctionReference()` `:1193` → `org.skriptlang.skript.common.function.FunctionReference` — `name()` `:284`, `namespace()` `:277`, `signature()` `:270`, `function()` `:250` |
| Look up | `ch.njol.skript.lang.function.Functions.getGlobalFunction(String)` `:241`, `getScriptNamespace(String)` `:343` |
| Parse a signature | `org.skriptlang.skript.common.function.FunctionParser.parse(script, name, args, returns, local)` `:37` |
| Commands | `ch.njol.skript.command.Commands.getScriptCommands()` `:76`, `getScriptCommand(String)` `:218` |
| Command detail | `ScriptCommand.getName()` `:495`, `getLabel()` `:503`, `getAliases()` `:593`, `getArguments()` `:386`, `getPattern()` `:390` |

**`Commands.getScriptCommands()` returns the live `HashMap.keySet()`**
(`Commands.java:77`) containing labels **and** aliases — copy it, and dedupe
by identity.

### 2.6 Text primitives already in the API

These are the pieces a `.sk` lexer can be built from, rather than written from
scratch:

| Primitive | Signature | Note |
|---|---|---|
| Comment / quoting split | `Node.splitLine(String)` `Node.java:107`; `splitLine(String, AtomicBoolean)` `:122` | The canonical implementation of Skript's lexical rules. Returns `(value, comment)`. **The 2-arg form is required for whole-file use** — the 1-arg form creates a fresh `AtomicBoolean` and loses `###` block-comment state |
| `###` block comments | same, `AtomicBoolean` toggles | Only when the trimmed line is exactly `###` |
| Brace matching | `VariableString.nextVariableBracket(String, int)` `VariableString.java:283` | Forward only, nesting-aware, **quote-blind**. Returns the index of the matching `}` or -1 |
| Token stepping | `SkriptParser.next(String, int, ParseContext)` `:1450` | Skips strings, variables and brackets. Forward only. `COMMAND`/`PARSE` contexts skip nothing |
| Anchored search | `SkriptParser.nextOccurrence(String, String, int, ParseContext, boolean)` `:1496` | Same skipping |
| Line validation | `SkriptParser.validateLine(String)` `:1640` | Checks quote balance and bracket termination. **Logs on failure** — needs a handler installed |
| Variable name rules | `Variable.isValidVariableName(String, boolean, boolean)` `:107` | **Pass `printErrors = false`** — the `true` path touches `SkriptConfig` and logs, and `SkriptConfig`'s static init throws without a live `Classes` registry |
| Variable constants | `Variable.SEPARATOR` `:50` (`"::"`), `LOCAL_VARIABLE_TOKEN` `:51` (`"_"`), `EPHEMERAL_VARIABLE_TOKEN` `:52` (`"-"`) | public |
| Function name pattern | `Functions.functionNamePattern` `:101` | `[\p{IsAlphabetic}_][\p{IsAlphabetic}\d_]*` |
| Quoting | `VariableString.quote` `:217`, `unquote` `:269`, `isQuotedCorrectly` `:238` | `unquote` has an `assert` — check with `isQuotedCorrectly` first |
| Generic string utils | `ch.njol.util.StringUtils` | 30+ public pure helpers, incl. `indexOfOutsideGroup` `:476` (closest thing to a `%…%` matcher) and `multiply` `:323`/`:338` (indentation) |

### 2.7 Regexes that are **private** and must be copied

| What | Where | Pattern |
|---|---|---|
| Command declaration | `StructCommand.java:71` | `(?i)^command\s+/?(\S+)\s*(\s+(.+))?$` |
| Command argument | `StructCommand.java:72` | `<\s*(?:([^>]+?)\s*:\s*)?(.+?)\s*(?:=\s*([^"]*?(?:"[^"]*?"[^"]*?)*?))?\s*>` |
| Command description | `StructCommand.java:73` | `(?<!\\)%-?(.+?)%` |
| Function signature | `StructFunction.java:83-84` | `^(?:local )?function (?<name>[\p{IsAlphabetic}_][\p{IsAlphabetic}\d_]*)\((?<args>.*?)\)(?:\s*(?:->\|::\| returns )(?<returns>.+))?$` |
| Function call head | `FunctionReferenceParser.java:42-43` | `(?<name>[\p{IsAlphabetic}_][\p{IsAlphabetic}\d_]*)\((?<args>.*)\)` — note `.*` is greedy |
| Function argument | `FunctionArgumentParser.java:18` (class is package-private) | `(?:\s*(?<name>[^:(){}\",]+):)?(?<value>.+)` |
| Function script parameter | `FunctionParser.java:84-85` (private) | `^\s*(?<name>[^:(){}\",]+?)\s*:\s*(?<type>[a-zA-Z ]+?)\s*(?:\s*=\s*(?<def>.+))?\s*$` |
| Command escape / unescape | `Commands.java:116` / `:118` | escape `"[" + Pattern.quote("(|)<>%\\\\") + "]"`; unescape `"\\\\[" + Pattern.quote("(|)<>%\\\\") + "]"` |
| Section-header lookalike | `SectionNode.java:280` | `([^#]\|##)*#-#(\s.*)?` — vestigial, only matches a literal `#-#` |
| Hash re-escaper | `Node.java:279-307` | `escapeUnquotedHashtags` — inserts an extra `#` before every unquoted `#` |
| Variable literal | `SkriptParser.java:441` | `((the )?var(iable)? )?\{.+\}` |

`Documentation.cleanPatterns` (`ch/njol/skript/doc/Documentation.java:171`) is
`protected static` — the pattern cleaner must be reimplemented too.

---

## 3. The parse-only recipe

**This is the core of Phase 0.** It produces Skript's diagnostics **without**
registering, executing or unloading anything — so the editor never causes the
user's `on load:` to run.

### 3.1 Why `postLoad` is the only phase that matters

`Structure` has three phases: `preLoad()` `:137`, `load()` `:146`, `postLoad()`
`:155`. `ScriptLoader` runs them at `:580`, `:609`, `:633`.

Everything dangerous lives in `postLoad`:

| Side effect | Where |
|---|---|
| **`on load:` executes** | `EvtScript.postLoad()` → `runTrigger(trigger, new ScriptEvent())` — `EvtScript.java:40-45` |
| Bukkit listeners registered | `SkriptEvent.postLoad()` → `SkriptEventHandler.registerBukkitEvents(...)` — `SkriptEvent.java:152-155` |
| Command registered | `StructCommand.postLoad()` → `ScriptCommandRegistrar.processRegistrations()` — `StructCommand.java:128-134` |

`on load:` is `EvtScript` — registered at `EvtScript.java:16-18` with patterns
`[:async] [script] (load|init|enable)`; `load = matchedPattern == 0` (`:36`).

**`postLoad` produces no parse diagnostics at all.** Skipping it costs
essentially zero diagnostic fidelity.

`on unload:` also fires — `EvtScript.unload()` `:47-51`, reached via
`ScriptLoader.unloadScripts` `:924-931`. Calling `loadScripts` therefore runs
*both* `on load:` and `on unload:` on every keystroke.

### 3.2 The sequence

```kotlin
val handler = RetainingLogHandler().also { it.start() }   // must be on the parsing thread
try {
    // 1. Config from text, no file. Same parameters Skript uses for .sk files
    //    (ScriptLoader.java:865-872): simple=true, allowEmptySections=false, ":".
    //    `file` may be the real path (never read during parsing) or null.
    val config = Config(
        ByteArrayInputStream(content.toByteArray(UTF_8)),
        fileName, realFile, /*simple=*/true, /*allowEmptySections=*/false, ":"
    )

    // 2. A throw-away Script. The structures list must be mutable.
    val script = Script(config, mutableListOf<Structure>())

    // 3. Activate the parser. setActive is the ONLY public route
    //    (ParserInstance.java:62-63, @ApiStatus.Internal).
    val parser = ParserInstance.get()
    parser.setActive(script)

    // 4. PARSE PHASE — mirrors ScriptLoader.loadScript (ScriptLoader.java:702-727)
    val parsed = mutableListOf<Pair<Structure, Node>>()
    for (node in config.mainNode) {
        if (node !is SimpleNode && node !is SectionNode) {
            Skript.error("could not interpret line as a structure"); continue
        }
        var line = node.key ?: continue
        line = ScriptLoader.replaceOptions(line)
        if (!SkriptParser.validateLine(line)) continue
        val s = Structure.parse(line, node, "Can't understand this structure: $line")
        if (s != null) parsed += s to node
    }

    // 5. PRE-LOAD, in priority order (ScriptLoader.java:567-591).
    //    Needed so in-script function signatures resolve.
    for ((s, _) in parsed.sortedBy { it.first.priority }) {
        parser.setCurrentStructure(s); s.preLoad()
    }

    // 6. LOAD, in priority order (ScriptLoader.java:599-620).
    //    This is where statement-level diagnostics live.
    //    EXCEPT: skip StructVariables.load() and StructCommand.load() — see §4.
    for ((s, _) in parsed.sortedBy { it.first.priority }) {
        parser.setCurrentStructure(s); s.load()
    }

    // 7. ★ NEVER call postLoad() — that is what stops `on load:` running.

    parser.setInactive()
} finally { handler.stop() }
// diagnostics = handler.log.map { it.toDiagnostic() }
```

### 3.3 Conditions for exact parity

1. Same `Config` parameters Skript uses for `.sk` files (`ScriptLoader.java:865-872`).
2. `SkriptParser.validateLine` called ourselves, as `loadScript` does (`:714`).
3. `Structure.parse` per top-level node **in file order**.
4. `preLoad` then `load`, in `Structure.getPriority()` order, as `loadScripts` does.
5. `loadItems` invoked **exactly once per section** — it manages its own nested
   `RetainingLogHandler` and replays the log (`:1083-1157`), so calling it twice
   duplicates errors.
6. `postLoad` never called.

### 3.4 What is lost

Only `"The function 'x' from the script 'y' no longer exists"` — a rare
revalidation warning from `Functions.validateFunctions()` in `postLoad`.

### 3.5 Skript's own precedent

Two structures already do this by hand:

- `StructExample.load()` `:60-69` → `ScriptLoader.loadItems(source)` `:66`.
  Its comment (`:62-64`):
  ```
  // The code is parsed and loaded, but then discarded since it will never be run
  // This allows things like parse problems and errors to be detected.
  ```
- `StructParse.load()` `:89-108` (in the **test runner** package,
  `ch.njol.skript.test.runner`) → `startRetainingLog()` `:90` →
  `Structure.parse(...)` `:93` → `preLoad() && load()` `:96` → `postLoad()` `:97`
  → `handler.getLog()` `:102-105`.

### 3.6 No temp file needed

`Config` accepts an `InputStream` and a name with **no file**
(`Config.java:84-87`); `:62-63` only stores `file` if non-null, and it is never
read during parsing. It is `ScriptLoader.loadScript` that insists on a file
(`:688-689`). So the buffer goes
`String → ByteArrayInputStream → Config → SectionNode tree → diagnostics`, with
no disk I/O at all.

---

## 4. The two structures that need special handling

### 4.1 `StructFunction` — global registry pollution

`StructFunction.preLoad()` `:107-129` ends with
`Functions.registerSignature(signature)` `:128`, which writes into the **global**
`FunctionRegistry` (`Functions.java:205-212`) and overwrites `globalFunctions`.

Consequences:
- If the server already has `function greet(p):` loaded, the LSP's registration
  collides — `Functions.java:195` logs *"Function 'greet' with the same argument
  types already exists in script '…'"*, and `preLoad` returns `false`, so the
  function body is **never parsed** and all diagnostics inside it are lost.
- The real script's function is **permanently shadowed** in `globalFunctions`;
  `Functions.unregisterFunction` `:372-394` only removes our own signature.

**Fix:** don't call `StructFunction.preLoad()`. Instead use the public
`FunctionParser.parse(script, name, args, returns, /*local=*/true)`
(`FunctionParser.java:37`) plus `Functions.registerSignature(signature)`, with
`local = true` and a per-document namespace. A local signature goes to
`FunctionRegistry.register(signature.namespace(), signature)`
(`Functions.java:209`), which `getSignatures(namespace, name)`
(`FunctionRegistry.java:410-431`) still finds. `registerSignature`'s duplicate
check compares locality (`Functions.java:180`), so a real *global* function
triggers no error. Clean up with `Functions.unregisterFunction(signature)`
(`:372`).

### 4.2 `StructCommand` — pending-registration leak

In **2.17.1** the command subsystem was overhauled. `StructCommand` moved to
`org/skriptlang/skript/bukkit/command/elements/structures/StructCommand.java`
and its flat entry model was replaced by `SubCommandEntryData`.

- `StructCommand.load()` `:99-126` calls
  `ScriptCommandRegistrar.register(command)` `:122`, which only **queues** into
  `PENDING_REGISTRATIONS` (`ScriptCommandRegistrar.java:104-106`). The real
  Bukkit registration is in `postLoad` `:128-134`.
- The queue entry **leaks**: there is no public API to remove it
  (`unregister` `:223-225` only touches `REGISTERED_COMMANDS`). So the *next*
  parse of the same command name reports *"A command with the name /x is already
  defined in the script '…'"* (`StructCommand.java:114-116`) — a spurious error
  on every keystroke after the first.

**Fix:** don't call `StructCommand.load()`. Call the public
`SubCommandEntryData.getValue(Node)` on the command's root `SectionNode` — it
performs the full entry validation and parses the `trigger:`/`suggestions:`
sections (`:367`, `:393`) with no registration. Optionally replicate the
duplicate check with `ScriptCommandRegistrar.getCommand(name)` `:272-277`.

Note `StructCommand.init()` `:93-97` in 2.17.1 only stores the root node — **no
entry validation** — so all command diagnostics live in `load()`.

In **2.16.2** `load()` called `Commands.registerCommand` (`:298`), registering
the command with the server outright. Do not call `load()` on a command
structure in either version.

### 4.3 `StructVariables` — skip `load()`

`init()` `:135-229` does all the validation (*"Invalid line in variables
structure"* `:150`, *"Can't understand the type 'x'"* `:183`, *"Can't save '…'"*
`:211`) and stores `DefaultVariables` as `ScriptData` `:227`.

`load()` `:231-251` calls `Task.callSync(...)` `:240` (main-thread only) and
`Variables.setVariable(...)` `:245` — creating **global variables**. Skip it.

---

## 5. The six landmines

| # | Landmine | Detail |
|---|---|---|
| 1 | `Structure.parse` 4-arg overload | Its signature names `StructureInfo`, which is `@Deprecated(forRemoval=true)` since 2.14 (`StructureInfo.java:13`). **Use only the 3-arg overload** (`Structure.java:191`) |
| 2 | `Structure.getEntryContainer()` | `@Deprecated(since="2.10.0", forRemoval=true)` (`:83-88`). Its replacement — *"the Structure should keep a reference to it"* — is impossible externally. **Use `SyntaxInfo.Structure.entryValidator()` instead** |
| 3 | **No public way to activate a `ParserInstance`** | `setActive`/`setInactive` are `@ApiStatus.Internal` (`:62-63`, `:50-51`); `setCurrentScript(Script)` is **private** (`:131`); `setCurrentScript(Config)` is deprecated-for-removal with no replacement (`:804-814`) and needs an already-loaded script. **Clean escape hatch:** register a `ScriptLoader.ScriptLoadEvent` listener — `:1300`, fired at `:654-657` immediately after `setActive` at `:641`. Note `ScriptInitEvent` `:1271` fires *after* `setInactive` `:728`, and `ScriptUnloadEvent` `:1306` *before* `setActive` `:915` — neither gives an active parser |
| 4 | `SyntaxInfo` can *be* a deprecated `SyntaxElementInfo` | `SyntaxElementInfo.java:26` is deprecated-for-removal, and every `SyntaxInfo` method on it is `@ApiStatus.Internal` (`:106,113,121,127,141,149`). **Never call `instance()`** |
| 5 | `AnyNamed.name()` | `AnyNamed.java:18` is deprecated-for-removal since 2.13 with no read-side replacement. `Script`, `Config` and `Node` all implement it. **Use `Script.nameAndPath()` `:156` or `Config.getFileName()` `:167`** |
| 6 | `Classes.getSuperClassInfo` | Annotated `@Contract(pure=true)` but writes to a plain `HashMap` (`:56`, `:295`) and throws `IllegalStateException` if Skript was never loaded (`:211-213`). **Use `getClassInfoNoError` `:258` when Skript may not be live** |

### 5.1 The six overloaded-with-bad-news `Structure` sub-issues

`getNode()` is **not** reliably populated:

| Class | Why |
|---|---|
| `EffectSectionEffect` | `getNode()` is not forwarded to the wrapped `EffectSection`, and the wrapper is never `preInit()`d. Its `effectSection` field is private with no getter (`EffectSectionEffect.java:13`) |
| `EffFunctionCall` | Never `preInit()`d (`EffFunctionCall.java:24`) |
| `SimplifiedCondition` | Built by a private constructor after simplification (`SimplifiedCondition.java:53,65-68`), and `simplifySyntaxesOnParse` defaults to **true** (`SkriptConfig.java:390`) |

**Fix:** don't rely on `getNode()`. `loadItems` appends exactly one `TriggerItem`
per successfully-parsed node, in source order (`ScriptLoader.java:1064`, `:1139`)
— so **zip the i-th item with the i-th candidate node**. Robust against all
three cases, no per-class special-casing.

`Section` *does* capture its own node (`Section.java:48,52`).

### 5.2 Live-view hazards

Returns that look immutable but are not — copy them:

`Script.getStructures()` `:52` (unmodifiable **wrapper over the live
`ArrayList`** `ScriptLoader` mutates at `:571,:577,:600,:624,:631`),
`RetainingLogHandler.getLog()` `:188` (live view **plus a side effect** setting
`printedErrorOrLog`), `Commands.getScriptCommands()` `:77` (live `keySet()`),
`Namespace.getSignatures()`/`getFunctions()` `:165`/`:185` (raw `values()`),
`EntryContainer.getUnhandledNodes()` `:57`, `EntryValidator.getEntryData()` `:59`,
`MatchResult.getExpressions()`/`getTags()`/`getRegexResults()` `:54`/`:66`/`:70`,
`ParserInstance.getCurrentSections()` `:304`, `ClassInfo.before()`/`after()`/
`getUserInputPatterns()` `:452`/`:459`/`:321`,
`ScriptCommand.getArguments()`/`getAliases()`/`getActiveAliases()`
`:386`/`:593`/`:597`.

Also: `SectionNode.fullIterator()` `:157` **rewrites `ParserInstance`'s current
node** as a side effect (`:163`, `:170`).

---

## 6. Threads

`ParserInstance` is a `ThreadLocal` (`ParserInstance.java:34`, `get()` `:40`).
Log handlers are per-`ParserInstance` too (`SkriptLogger.getHandlers()`
`SkriptLogger.java:36-38` → `ParserInstance.handlers` `:452`).

⇒ **The `RetainingLogHandler` must be started on the thread that parses.**
`HandlerList` is an unsynchronised `LinkedList` (`HandlerList.java:13`).

### 6.1 Async loading deadlocks the server

`ScriptLoader.isAsync()` `:279-281` is `asyncLoaderSize > 0`, and
`asyncLoaderSize` comes from `SkriptConfig.scriptLoaderThreadSize`
(`SkriptConfig.java:286-303`).

**Config key:** `script loader thread size` in **`plugins/Skript/config.sk`**
(not `config.yml`), **default `"0"`** → async and parallel both **off**.

The setter is `.optional(true)`, so the key may be absent entirely; the field
default is `0` either way.

If an admin sets it to `1` or more, `makeFuture` `:442-447` hands the parse to
an `AsyncLoaderThread`, which then blocks in `Task.callSync` → `f.get()`
(`Task.java:209-228`) waiting for a tick — while our `.join()` holds the main
thread. **Permanent server deadlock.**

⇒ **Guard on `ScriptLoader.isAsync()` and bound the wait.** Do **not** call
`ScriptLoader.setAsyncLoaderSize(0)` to force sync: it is global state that
`/skript reloadconfig` silently reverts (`SkriptCommand.java:144`, `:167`), it is
unsynchronised, and its `size <= 0` branch `:320-324` never clears
`loaderThreads` — so a later `set(>=1)` can leave dead threads in the list with
`isAsync()` true and nothing polling `loadQueue`: futures that never complete.

### 6.2 Off the main thread

The pure parse machinery is thread-agnostic. The hazards are the shared
registries:

- `Classes.getSuperClassInfo` — plain `HashMap` write (`:56`, `:295`)
- `Functions.namespaces` `:37`, `globalFunctions` `:52` — plain `HashMap`
- `FunctionRegistry.Namespace` inner maps `:656-666` — plain `HashMap`

**Recommendation:** parse on a **single dedicated worker thread**, serialised,
never concurrent with itself. Or parse on the main thread with the load phase
skipped, and measure before moving it.

---

## 7. Bugs found in LspSkript

| Where | Bug | Status |
|---|---|---|
| `ParseBridge.kt:88-92` | `INFO` entries become `Information` diagnostics — and `DEBUG == INFO` | open |
| `ParseBridge.kt:86-105` | no dedupe; Skript keeps only the best `SEVERE` per line by `ErrorQuality` | open |
| `ParseBridge.kt:94-97` | node-less entries anchored to line 0 | open |
| `LspUtils.kt:16-17` | `Position(line, Int.MAX_VALUE)` serialised to the client — the spec disallows special values | open |
| `ParseBridge.kt:58` | `.join()` on the main thread — deadlock risk under async loading | open |
| `ParseBridge.kt:52-84` | temp file written and script loaded/unloaded per keystroke | open |
| `ParseBridge.kt:107-121` | temp file written outside `scripts/` — prevents auto-load, **not** `on load` execution | open |
| `SkriptTextDocumentService.kt:99` | `split('\n')` vs `BufferedReader.readLine()` semantics — a lone `\r` desyncs every line number | open |
| `CompletionProvider.kt:167` | `prefix.lowercase()` recomputed per pattern | open |
| `CompletionProvider.kt:171-173` | dedupe rescans the whole accumulated list, per call | open |
| `CompletionProvider.kt:105-106` | events missing from completions | **fixed** `d0eac08` |
| `CompletionProvider.kt:180` | `origin()` null check + `toString()` → `OriginImpl$UnknownOrigin@hash` | **fixed** `d0eac08` |
| `SkriptTextDocumentService.kt:99-107` | `applyRangeEdit` threw on newline-terminated files → permanent buffer desync | **fixed** `a58fa83` |
| `DefinitionReferenceProvider.kt:69-102` | `rebuild()` re-indexes **all** open documents on every change; `documents` never pruned on `didClose` | open |
| `HoverProvider.kt:25-29`, `:55` | five registry snapshots + `cleanPattern`+`lowercase` per pattern, per hover | open |
| `SignatureHelpProvider.kt:64-85` | tail recomputed per pattern; triggers on every space | open |
| `SkriptLanguageServerService.kt:44` | `resolveProvider: true` with no `completionItem/resolve` handler | open |
| `SkriptLanguageServerService.kt:43` | bare `textDocumentSync` enum → `didSave` never delivered, so the duplicate-parse path is dormant | open |

### 7.1 Per-keystroke cost, as measured

For one keystroke in a 400-line document, with 5 tracked documents:

- ≥6 whole-document traversals, 2 whole-document allocations
- 1 file write, 1 file read, 1 **full Skript parse**
- ~6,000 `Matcher` allocations from the O(N) re-index
- **all of it proportional to the number of open documents**, not the one edited

The dominant term is the Skript parse (main thread). Second is the O(N) re-index,
which is entirely redundant for N−1 of them.

**Therefore: index per document, using the `contentChanges[i].range` we already
receive and currently discard** (`SkriptTextDocumentService.kt:54-60`). That
keeps the AST work O(1) in open files instead of doubling an O(N) term.

---

## 8. Version history and deprecation policy

### 8.1 2.16.2 → 2.17.1

**316 files changed** (+14,726 / −6,147) in one minor release. 25 files deleted,
26 renamed.

| Change | Effect on us |
|---|---|
| `registration/**` — **byte-identical** (all 9 blobs) | our syntax-catalogue surface is untouched |
| `ch/njol/skript/doc/**` — **byte-identical** (all 18 files) | our documentation surface is untouched |
| `ParserInstance.java` — byte-identical | — |
| `Structure.java` — byte-identical | — |
| `PatternCompiler`/`SkriptPattern`/`MatchResult` — byte-identical | — |
| `Node`/`Config`/`SectionNode`, `Variable`/`VariableString`, `LogEntry`/`RetainingLogHandler`, `EntryValidator`/`EntryData` — unchanged | — |
| `Node.getComment()`, `Node.getIndentation()` — `protected` → **`public`** | new, useful |
| `StructCommand` — gutted (343 → 43 lines), replaced by `org.skriptlang.skript.bukkit.command.elements.structures.StructCommand` with `SubCommandEntryData` | see §4.2 |
| `ScriptLoader.getScriptFromName(...)` ×2 — newly deprecated-for-removal | not used by us |
| `Skript.experiments()`, `Skript.dispatchCommand(...)`, `SkriptParser.parseArguments(...)` — newly deprecated-for-removal | not used by us |
| `ch.njol.skript.command.*` (`Argument`, `ScriptCommand`, `ScriptCommandEvent`, `CommandUsage`) — newly deprecated-for-removal | `ScriptCommand.getArguments()` is used by nothing of ours yet |
| `ch.njol.skript.entity.*` → `org.skriptlang.skript.bukkit.entity.data.*` | 26 renames |
| `ch.njol.skript.SkriptCommand`, `SkriptCommandTabCompleter` — **deleted** | not used by us |
| `Commands.m_too_many_arguments`, `m_internal_error`, `m_correct_usage` — **deleted** | not used by us |
| `ScriptCommand.m_executable_by_players`, `m_executable_by_console` — **deleted** | not used by us |
| `ch.njol.skript.entity.EntityData` — now abstract, different abstract-method set | not used by us |
| `SyntaxInfo`/`SyntaxRegistry` — unchanged | — |

**Every 2.14-deprecated class survived.** `SyntaxElementInfo`, `StructureInfo`,
`ExpressionInfo`, `SkriptEventInfo`, `ExpressionType`, `FunctionReference`,
`Parameter`, `AnyNamed`, `NonNullPair`, `Converters`, `JavaFunction`,
`SimpleJavaFunction` — all still present.

### 8.2 Timeline of the registration API

- **2.10.0** — `SyntaxRegistry`/`SyntaxInfo` first shipped, as
  `@ApiStatus.Experimental` (commit `a0f3a5b586`, *"New Registration API (#6246)"*,
  2024-12-29)
- **2.14.0** — stabilised; the `@ApiStatus.Experimental` marker removed
  (PR **#8316** *"Stabilize Registration API"*, merged 2025-12-22). The old API
  was deprecated in the same release (PR #8392).
  PR #8316's own words: *"The new Registration API was introduced as a preview
  feature in 2.10."*

### 8.3 Policy

`code-conventions.md`:

> Deprecated elements may be removed three feature releases after the version
> they were deprecated in.

At ~4 feature releases/year that is **~9 months minimum notice**. But Skript
**did not remove** the 2.14 cohort in 2.17 — it deferred. And it freely removes
**never-deprecated** internal classes (`SkriptCommand`, `EntityData` impls).

⇒ Treat deprecated-and-Internal APIs as unsafe to depend on, and isolate
contact behind one adapter file. Build it with
`-Xlint:deprecation -Xlint:removal -Werror` so upgrades fail at compile time
rather than at runtime.

### 8.4 Release cadence

Feature releases on the **15th** of Jan/Apr/Jul/Oct; pre-releases on the 1st.
~16 releases/year. Next feature release **2.18.0, ~15 Jan 2027**; content must be
in a pre-release first (~1 Jan). PRs need two core approvals. API additions are
feature-release material, not patch material.

### 8.5 Open work in flight upstream

**PR #8253 "SkriptParser refactor, add ParsingConstraints"** (open since
2025-10-23, by `sovdeeth`) — a from-scratch parser rewrite in the `skriptlang`
package, with `ParsingConstraints` replacing the bitmap flags. The old
`SkriptParser` is retained for back-compatibility with its methods redirecting, so
our calls keep working, but **parse internals and possibly error text will
shift**. Its "Testing Completed" section is empty.

---

## 9. The ecosystem

| Project | Language | Licence | Scale | Positions? | Notes |
|---|---|---|---|---|---|
| `skript-studio/skript-core` | Rust | MIT (workspace) | 0★ | **yes** — `Span` on every node, `node_at_offset()` | Complete lexer + AST + scope tree + `find_references()`. **Ours.** Best port source for a lexer |
| `skript-studio/skript-lsp` | Rust | MIT | 0★ | yes | LSP 3.17 over WebSocket; refs/rename **not** wired up |
| `JohnHeikens/IntelliSkript` | TypeScript | GPL-3.0 | 11★, **32k VS Code installs** | char offsets | Full LSP, but its engine reimplements Skript so it cannot be right on addon servers. `referencesProvider`, `renameProvider` and `documentHighlightProvider` are all **commented out** |
| `SkEditorTeam/SkEditor` | C# / Avalonia | MIT | 91★ | **no** | Polished editor, installers, Winget, Discord — but line-based splitting only, no intelligence |
| `paw-jakub/tree-sitter-skript` | tree-sitter | GPL-3.0 | 0★, 3 commits | yes (row/col) | Lexical only; its own header says Skript's grammar is context-sensitive and free-text, so it "deliberately stays lexical" |
| `Mwexim/skript-parser` | Java | MIT | 50★ | **line only** | Reimplements Skript's *pattern* semantics faithfully. `FileParser` carries only a line number. Not usable for positions |
| `SkriptInsight/SkriptInsightKt` | Kotlin | LGPL-3.0 | archived 2021 | yes | Closest in language to us. `Matcher.getGroupRange()` is the column-from-regex trick. Its tokeniser is a **stub** |
| `SkriptLang/skript-grammar` | CSON / TextMate | MIT | — | (a TextMate engine would) | Our submodule. Highlighting only; its own README says "in development and not in a usable state" |

**Nobody implements rename or references for Skript variables.** IntelliSkript has
both commented out; `skript-core` has the algorithm but does not expose it over
LSP; `skript-studio`'s README lists cross-file variable tracking as not
included. That is the gap.

**The `skript-parser` project is the cautionary tale**: `bluelhf` (SkriptLang
contributor), on the closed LSP issue #6447:

> projects for parsing independent of a server like skript-parser are a
> monumental task to make, suffer from feature parity issues, and generally
> don't seem to receive much interest from developers.

---

## 10. Open items

Not yet determined, and worth resolving before implementation:

- Whether the parse runs on a dedicated worker thread or stays on the main
  thread (§6.2). The `Classes.superClassInfos` write is the deciding risk.
- Whether the lexer is ported from `skript-core` (MIT, ~300 lines + ~40 tests)
  or written fresh on Skript's public text primitives (§2.6). Porting is less
  work and inherits a test corpus.
- How to get a *live active* `ParserInstance` without `@ApiStatus.Internal`:
  the `ScriptLoadEvent` route (§5.3 landmine 3) needs verification.
- Exact column-reconstruction algorithm. `MatchResult.getRegexResults()` gives
  exact offsets for `<regex>` elements only; `%…%` slots need either the
  matcher re-walk or a text scan.
- Whether `StructExample`/`StructParse` are the right models to follow, given
  both are `@NoDoc` and one is test-only.

---

## 11. Verification method

Two corrections to how research was done, worth remembering:

- The local Skript clone is **depth-1 shallow**, so `git log --diff-filter=A`
  returns HEAD for every file and is useless for "when did this first appear".
  Version archaeology has to go through the GitHub API.
- Several of our own claims were only found wrong by checking them against
  source: the "byte-for-byte identical" README claim, the "only public way to
  get parse errors" claim, `exprOffset` being "private" rather than
  package-private, the registry shipping in 2.10 rather than 2.14, and
  `EFFECT` being effects-only. **Check every citation.**
