# Idiomatic Kotlin Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the 17 Kotlin files in `me.rohandacoder.lspskript` idiomatic Kotlin (remove Java-isms and dead code) **without changing runtime behavior**, keeping the Gradle `:skript-lsp:build` green at every step.

**Architecture:** Pure refactor of an already-compiling LSP backend. Changes are localized per file: `by lazy` replaces the double-checked-locking provider singletons, shared range sentinels move to `LspUtils`, dead code/empty loops are deleted, and Java collection/string builders are swapped for stdlib equivalents. No public APIs or `paper-plugin.yml` change.

**Tech Stack:** Kotlin 2.3.0 on JVM toolchain 25, Gradle 9.4.0, Eclipse LSP4J 0.23.1, Spigot/Bukkit API, Skript/SkriptLang API. Build verified via `.\gradlew.bat :skript-lsp:build --console=plain --no-daemon`.

## Global Constraints

- **Behavior must not change.** This is a refactor; every task ends with a green `:skript-lsp:build`. Do not alter logic, return values, or diagnostics output.
- **Keep `object LspUtils` / `companion object` structures intact.** Converting to top-level functions would require renaming 26 cross-class call sites — out of scope. Only add constants to `LspUtils`; do not remove its `object`.
- **Keep `@Suppress` annotations** (`UNCHECKED_CAST` in `LspUtils`, `DEPRECATION` in `SyntaxRegistryAccess`) — they are legitimate Java-interop/deprecation suppressions.
- **Keep `ConcurrentHashMap` and `WeakHashMap`** (`SkriptTextDocumentService.documents`, `ParseBridge.tempFiles`) — no stdlib equivalent.
- **`var` → `val` only where never reassigned;** `by lazy` only for the provider singletons (thread-safe, equivalent to current double-checked locking).
- Kotlin stdlib naming: `ArrayList()` → `mutableListOf()`, `HashMap()` → `mutableMapOf()`, `HashSet(x)` → `x.toSet()`/`x.toMutableSet()`.
- Commit after **every** task with a message like `refactor(lspskript): <short summary>`.
- Path root for all files: `skript-lsp/src/main/java/me/rohandacoder/lspskript/`

---

### Task 1: Add shared range constants to `LspUtils`

**Files:**
- Modify: `LspUtils.kt`

**Interfaces:**
- Produces: `LspUtils.FULL_RANGE: Range` and `LspUtils.END_OF_DOC: Position`, consumed by Task 2 (FormattingProvider) and elsewhere via `LspUtils.lineRange`.

- [ ] **Step 1: Add the two constants inside the `object LspUtils` body** (after line 24, before `onMainThread`)

```kotlin
    /** Zero-width range at document origin; also used as a "full document" sentinel where the caller replaces the end. */
    val FULL_RANGE: Range = Range(Position(0, 0), Position(0, 0))

    /** Position representing the very end of the document. */
    val END_OF_DOC: Position = Position(Int.MAX_VALUE, Int.MAX_VALUE)
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`, no new warnings.

- [ ] **Step 3: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/LspUtils.kt
git commit -m "refactor(lspskript): add FULL_RANGE/END_OF_DOC constants to LspUtils"
```

---

### Task 2: Replace provider double-checked-locking with `by lazy`

**Files:**
- Modify: `SkriptTextDocumentService.kt`

**Interfaces:**
- Consumes: provider classes (`ParseBridge`, `CompletionProvider`, …) constructors (unchanged).
- Produces: provider fields become `val by lazy` properties; all `x()` accessor calls become `x` property reads. This changes 9 field declarations, deletes 9 accessor methods, and rewrites 14 call sites within the same file.

- [ ] **Step 1: Replace the 9 `@Volatile var ...? = null` fields (lines 26–43) with `by lazy` vals**

```kotlin
    // Feature providers (lazy-initialised against the live Skript runtime).
    private val parseBridge: ParseBridge by lazy { ParseBridge() }
    private val completionProvider: CompletionProvider by lazy { CompletionProvider() }
    private val hoverProvider: HoverProvider by lazy { HoverProvider() }
    private val signatureHelpProvider: SignatureHelpProvider by lazy { SignatureHelpProvider() }
    private val documentSymbolProvider: DocumentSymbolProvider by lazy { DocumentSymbolProvider() }
    private val definitionReferenceProvider: DefinitionReferenceProvider by lazy { DefinitionReferenceProvider() }
    private val formattingProvider: FormattingProvider by lazy { FormattingProvider() }
    private val renameProvider: RenameProvider by lazy { RenameProvider() }
    private val codeActionProvider: CodeActionProvider by lazy { CodeActionProvider() }
```

- [ ] **Step 2: Delete the 9 accessor methods (lines 49–128, the entire block from `private fun parseBridge()` through `private fun codeActionProvider()`)**

- [ ] **Step 3: Rewrite the 14 call sites from `x()` to `x`**

In `SkriptTextDocumentService.kt`, change each:
- `parseBridge().invalidate(uri)` → `parseBridge.invalidate(uri)`
- `parseBridge().parse(...)` → `parseBridge.parse(...)`
- `definitionReferenceProvider().index(...)` → `definitionReferenceProvider.index(...)`
- `completionProvider().complete(...)` → `completionProvider.complete(...)`
- `hoverProvider().hover(...)` → `hoverProvider.hover(...)`
- `signatureHelpProvider().signatureHelp(...)` → `signatureHelpProvider.signatureHelp(...)`
- `definitionReferenceProvider().definition(...)` → `definitionReferenceProvider.definition(...)`
- `definitionReferenceProvider().references(...)` → `definitionReferenceProvider.references(...)`
- `documentSymbolProvider().documentSymbols(...)` → `documentSymbolProvider.documentSymbols(...)`
- `formattingProvider().formatting(...)` → `formattingProvider.formatting(...)`
- `formattingProvider().rangeFormatting(...)` → `formattingProvider.rangeFormatting(...)`
- `renameProvider().rename(...)` → `renameProvider.rename(...)`
- `codeActionProvider().codeAction(...)` → `codeActionProvider.codeAction(...)`
- `definitionReferenceProvider().workspaceSymbols(...)` → `definitionReferenceProvider.workspaceSymbols(...)`

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`. (If any `x()` call site was missed, the compiler reports "unresolved reference" — fix and recompile.)

- [ ] **Step 5: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/SkriptTextDocumentService.kt
git commit -m "refactor(lspskript): replace provider double-checked-locking with by lazy"
```

---

### Task 3: Clean null-handling and dead `var`s in `SkriptTextDocumentService`

**Files:**
- Modify: `SkriptTextDocumentService.kt`

**Interfaces:** (internal only; no signature changes)

- [ ] **Step 1: Guard missing document in `didChange` (around lines 144–154)** — replace the `var text = documents[uri]` + `text!!` pattern with an early `?: return`

Current shape:
```kotlin
        var text = documents[uri]
        for (change in params.contentChanges) {
            text = if (change.range != null) {
                applyRangeEdit(text!!, change.range, change.text)
            } else {
                change.text
            }
        }
        documents[uri] = text!!
```
Replace with:
```kotlin
        var text = documents[uri] ?: return
        for (change in params.contentChanges) {
            text = if (change.range != null) {
                applyRangeEdit(text, change.range, change.text)
            } else {
                change.text
            }
        }
        documents[uri] = text
```

- [ ] **Step 2: Simplify `didClose` (lines 169–171)** — replace `if (client != null) { client!!.publishDiagnostics(...) }` with

```kotlin
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
```

- [ ] **Step 3: Simplify `republishDiagnostics` (lines 185–188)** — replace `if (client == null) return` + `client!!.publishDiagnostics(...)` with

```kotlin
        val client = client ?: return
        ...
        client.publishDiagnostics(...)
```

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`, no `!!` warnings in this file.

- [ ] **Step 5: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/SkriptTextDocumentService.kt
git commit -m "refactor(lspskript): simplify null-handling in SkriptTextDocumentService"
```

---

### Task 4: Delete dead loop and unused vars in `CodeActionProvider`

**Files:**
- Modify: `CodeActionProvider.kt`

**Interfaces:** (internal only)

- [ ] **Step 1: Delete the dead loop and `insertPos` (lines ~65–68)** — remove:
```kotlin
        var insertPos = 0
        for (i in text.indices) {
            // approximate: not perfectly accurate, but adequate for a quick fix
        }
```
(Confirm `insertPos` is not referenced anywhere else in the file before deleting — grep `insertPos`.)

- [ ] **Step 2: Replace `ArrayList()` with `mutableListOf()` (line 18) and `HashMap()` (line 51)**

```kotlin
    val actions: MutableList<Either<Command, CodeAction>> = mutableListOf()
```
```kotlin
    val changes: MutableMap<String, List<TextEdit>> = mutableMapOf()
```

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/CodeActionProvider.kt
git commit -m "refactor(lspskript): remove dead loop and use stdlib collections in CodeActionProvider"
```

---

### Task 5: Remove redundant `out` projections in provider return types

**Files:**
- Modify: `DefinitionReferenceProvider.kt`, `FormattingProvider.kt`, `SkriptWorkspaceService.kt`, `SkriptTextDocumentService.kt`

**Interfaces:** (return-type only; must stay compatible with LSP4J override signatures, which are invariant `List<X>`)

- [ ] **Step 1: `DefinitionReferenceProvider.kt`** — change `List<out Location>` → `List<Location>` at the `definition` (line 65) and `references` (line 79) function signatures.

- [ ] **Step 2: `FormattingProvider.kt`** — change `List<out TextEdit>` → `List<TextEdit>` at `formatting` (line 19), `rangeFormatting` (line 21), and `formatAll` (line 27) signatures.

- [ ] **Step 3: `SkriptWorkspaceService.kt`** — change `Either<List<out SymbolInformation>, List<out WorkspaceSymbol>>` → `Either<List<SymbolInformation>, List<WorkspaceSymbol>>` (line 29).

- [ ] **Step 4: `SkriptTextDocumentService.kt`** — change override return types:
  - line 251: `Either<List<out Location>, List<out LocationLink>>` → `Either<List<Location>, List<LocationLink>>`
  - line 257: `val locations: List<out Location>` → `val locations: List<Location>`
  - line 263: `CompletableFuture<List<out Location>>` → `CompletableFuture<List<Location>>`
  - line 285: `CompletableFuture<List<out TextEdit>>` → `CompletableFuture<List<TextEdit>>`
  - line 296: `CompletableFuture<List<out TextEdit>>` → `CompletableFuture<List<TextEdit>>`

- [ ] **Step 5: Verify it compiles (warnings about "Projection is redundant" should be gone)**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`, no "Projection is redundant" warnings for these files.

- [ ] **Step 6: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/DefinitionReferenceProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/FormattingProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/SkriptWorkspaceService.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/SkriptTextDocumentService.kt
git commit -m "refactor(lspskript): drop redundant out projections on provider return types"
```

---

### Task 6: Replace Java collections with stdlib builders (bulk)

**Files:**
- Modify: `CompletionProvider.kt`, `DefinitionReferenceProvider.kt`, `DocumentSymbolProvider.kt`, `HoverProvider.kt`, `SignatureHelpProvider.kt`, `ParseBridge.kt`

**Interfaces:** (internal only)

- [ ] **Step 1: `CompletionProvider.kt`**
  - line 28: `ArrayList()` → `mutableListOf()`
  - line 116: `HashSet()` → `mutableSetOf()`

- [ ] **Step 2: `DefinitionReferenceProvider.kt`**
  - line 59: `variables.computeIfAbsent(base) { ArrayList() }` → `{ mutableListOf() }`
  - line 88: `ArrayList()` → `mutableListOf()`
  - line 105: `ArrayList()` → `mutableListOf()`
  - line 121: `changes.computeIfAbsent(loc.uri) { ArrayList() }` → `{ mutableListOf() }`
  - lines 24, 27, 29: `HashMap()` → `mutableMapOf()`
  - line 117: `HashMap()` → `mutableMapOf()`

- [ ] **Step 3: `DocumentSymbolProvider.kt`**
  - lines 22, 24, 43: `ArrayList()` → `mutableListOf()`

- [ ] **Step 4: `HoverProvider.kt`**
  - line 24: `ArrayList()` → `mutableListOf()`

- [ ] **Step 5: `SignatureHelpProvider.kt`**
  - lines 28, 40: `ArrayList()` → `mutableListOf()`

- [ ] **Step 6: `ParseBridge.kt`** (keep `WeakHashMap` on line 34)
  - line 46: `HashSet(ScriptLoader.getLoadedScripts())` → `ScriptLoader.getLoadedScripts().toSet()`
  - line 58: `ArrayList()` → `mutableListOf()`
  - line 68: `HashSet(ScriptLoader.getLoadedScripts())` → `ScriptLoader.getLoadedScripts().toMutableSet()`

- [ ] **Step 7: Clean now-unused `import java.util.*` where safe** — in each of the above files, if `ArrayList`/`HashMap`/`HashSet` were the only `java.util.*` types used, replace `import java.util.*` with the specific imports still needed (e.g. `Locale`, `Collections`). If `Locale` or `Collections` is still used, keep `import java.util.*`. Do not delete imports that are still required (compile will fail).

- [ ] **Step 8: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/CompletionProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/DefinitionReferenceProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/DocumentSymbolProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/HoverProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/SignatureHelpProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/ParseBridge.kt
git commit -m "refactor(lspskript): use Kotlin stdlib collections instead of Java builders"
```

---

### Task 7: Use `by lazy` for `ParseBridge.lspDir` and clean `ParseBridge` nulls

**Files:**
- Modify: `ParseBridge.kt`

**Interfaces:** (internal only)

- [ ] **Step 1: Replace `private var lspDir: File? = null` (line 35) + `lspDir()` method (lines 101–112) with a `by lazy` val**

Delete lines 35 and 101–112. Add inside the class:
```kotlin
    private val lspDir: File by lazy {
        try {
            Files.createTempDirectory("lspskript").toFile()
        } catch (e: IOException) {
            val fallback = File(System.getProperty("java.io.tmpdir"), "lspskript")
            fallback.mkdirs()
            fallback
        }
    }
```

- [ ] **Step 2: Update the call site** — `prepareTempFile` line 118 `val dir = lspDir()` → `val dir = lspDir`

- [ ] **Step 3: Simplify `toDiagnostic` line var (lines 85–89)** — replace:
```kotlin
        var line = 0
        val node = entry.node
        if (node != null && node.line > 0) {
            line = node.line - 1
        }
```
with:
```kotlin
        val node = entry.node
        val line = if (node != null && node.line > 0) node.line - 1 else 0
```

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/ParseBridge.kt
git commit -m "refactor(lspskript): use by lazy for ParseBridge.lspDir and simplify toDiagnostic"
```

---

### Task 8: Remove dead vars in `SignatureHelpProvider`

**Files:**
- Modify: `SignatureHelpProvider.kt`

**Interfaces:** (internal only)

- [ ] **Step 1: Delete unused `var active = 0` (line 42) and `var idx = 0` (line 43) plus its increment** — grep to confirm `active` and `idx` are never read. Remove the declarations and any `idx++` statement.

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/SignatureHelpProvider.kt
git commit -m "refactor(lspskript): remove dead active/idx vars in SignatureHelpProvider"
```

---

### Task 9: Replace `StringBuilder` concatenation with `buildString` / `joinToString`

**Files:**
- Modify: `HoverProvider.kt`, `SignatureHelpProvider.kt`

**Interfaces:** (internal only)

- [ ] **Step 1: `HoverProvider.kt` (lines 33–39)** — replace the `StringBuilder` loop with `buildString`:

Current:
```kotlin
        val md = StringBuilder()
        for (info in matches) {
            md.append("### ").append(if (info.origin() == null) "Skript" else info.origin()).append("\n\n")
            for (pattern in info.patterns()) {
                md.append("`").append(CompletionProvider.cleanPattern(pattern)).append("`\n\n")
            }
        }
```
Replace with:
```kotlin
        val md = buildString {
            for (info in matches) {
                appendLine("### ${info.origin() ?: "Skript"}")
                appendLine()
                for (pattern in info.patterns()) {
                    appendLine("`${CompletionProvider.cleanPattern(pattern)}`")
                    appendLine()
                }
            }
        }
```

- [ ] **Step 2: `SignatureHelpProvider.kt` (lines 82–86)** — replace the tail-building loop with `joinToString`:

Current:
```kotlin
        val tail = StringBuilder()
        for (i in words.size - take until words.size) {
            if (i > words.size - take) tail.append(' ')
            tail.append(words[i])
        }
```
Replace with:
```kotlin
        val tail = words.takeLast(take).joinToString(" ")
```

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/HoverProvider.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/SignatureHelpProvider.kt
git commit -m "refactor(lspskript): use buildString/joinToString instead of StringBuilder"
```

---

### Task 10: Clean `!!` in `LspSkript` and `SkriptLanguageServer`

**Files:**
- Modify: `LspSkript.kt`, `SkriptLanguageServer.kt`

**Interfaces:** (internal only)

- [ ] **Step 1: `LspSkript.kt` line 26** — `server!!.start()` → `server?.start()`

- [ ] **Step 2: `LspSkript.kt` lines 34–36** — replace `if (server != null) { try { server!!.stop() ... } }` with:
```kotlin
        server?.let { s ->
            try {
                s.stop()
```
(keep the surrounding try/catch/finally body intact, closing `}` for the `let` instead of the `if`.)

- [ ] **Step 3: `SkriptLanguageServer.kt` line 47** — `val socket = serverSocket!!.accept().get()` → `val socket = checkNotNull(serverSocket).accept().get()`

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add skript-lsp/src/main/java/me/rohandacoder/lspskrit/LspSkript.kt skript-lsp/src/main/java/me/rohandacoder/lspskrit/SkriptLanguageServer.kt
git commit -m "refactor(lspskript): replace unsafe !! with safe calls/checkNotNull"
```

---

### Task 11: Final full build and warning sweep

**Files:**
- None (verification only)

**Interfaces:** (none)

- [ ] **Step 1: Run the full module build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL` with `compileKotlin` producing **no** warnings (no "Projection is redundant", no "This cast can never succeed", no "Condition is always 'false'", no "deprecated" except the two intentional `@Suppress` sites).

- [ ] **Step 2: If any warnings remain that this plan addressed, fix them in the relevant file and re-run Step 1, then commit**

```bash
git add -A
git commit -m "refactor(lspskript): final cleanup pass, zero Kotlin warnings"
```

- [ ] **Step 3: (Optional) `git log --oneline -12` and confirm 11 refactor commits are present**

---

## Self-Review Notes

- **Spec coverage:** The user's scope was "Idiomatic Kotlin cleanup" (behavior-preserving). All 12 catalog categories are covered except #11 (`object`→top-level, intentionally skipped to avoid 26 call-site renames) and the `@Suppress` removal in #12 (kept as legitimate). Both skips are documented in Global Constraints.
- **No placeholders:** Every step has concrete code or an exact command with expected output.
- **Type consistency:** `by lazy` provider types match constructor names; `List<Location>`/`List<TextEdit>` changes are consistent between `DefinitionReferenceProvider`/`FormattingProvider` (producers) and `SkriptTextDocumentService`/`SkriptWorkspaceService` (overriders). `FULL_RANGE`/`END_OF_DOC` added in Task 1, referenced only from `LspUtils.lineRange` callers (no new cross-file rename required).
- **Risk:** Each task is independently compile-verifiable; the `by lazy` change (Task 2) is the largest but confined to one file with 14 mechanical call-site rewrites.
