# Parse-only diagnostics — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop LspSkript loading the user's script into the server to produce diagnostics, and rewire the language features to read a single parsed snapshot.

**Architecture:** One parse per document produces an immutable `DocumentSnapshot` (diagnostics, a line index, sections, statements). Every feature reads the snapshot. A single `SkriptParseEngine` is the only file permitted to import Skript.

**Tech stack:** Kotlin 2.3.0, JVM toolchain 25 (bytecode target 21), Gradle 9.4.0, LSP4J 1.0.0, Skript 2.17.1 (compileOnly), Paper API 1.21.11, JUnit 6 with the Vintage engine, detekt + ktlint.

**Design spec:** `docs/superpowers/specs/2026-10-22-parse-only-diagnostics-design.md` — read it before starting; it carries the reasoning and the citations.
**Skript API facts:** `docs/skript-api-reference.md` — every `file:line` citation is verified against Skript 2.17.1.

## Global Constraints

- The plugin's declared Skript dependency is `com.github.SkriptLang:Skript:2.17.1`.
- `SkriptParseEngine.kt` is the **only** file permitted to import `ch.njol.skript.*` or `org.skriptlang.*`. Every other file is Skript-free. A reviewer should be able to grep for those imports and find exactly one file.
- The plugin's bytecode target is **Java 21** (`jvmTarget = JVM_21`, `sourceCompatibility`/`targetCompatibility = 21`), matching Paper 1.21.x's supported Java. The toolchain stays at 25.
- `Config` is built with `simple = true`, `allowEmptySections = false`, separator `":"`.
- The `Config`'s file name is `__lsp__<docId>.sk` — stable per document, ending in `.sk` and at least 3 characters. It is used as the function namespace, so it must equal the string passed to `FunctionParser.parse`.
- A **fresh** `Script` per parse. `parser.setInactive()` is called in a `finally`.
- `postLoad()` is **never** called on a parsed structure.
- `StructCommand.load()`, `StructVariables.load()` and `StructFunction.preLoad()` are **never** called.
- Function signatures are registered with `local = true` only, and unregistered in a `finally` via `Functions.unregisterFunction(sig)`.
- The `on load:` trigger must not execute. `EvtScript.postLoad()` is the only thing that fires it.
- The parse runs on the **Bukkit main thread**. `Functions.namespaces`, `Functions.globalFunctions`, `Functions.toValidate`, `FunctionRegistry`'s inner maps and `Classes.superClassInfos` are all plain unsynchronised `HashMap`s.
- `parse-only` config key defaults to `true`. The debounce is 200 ms, configurable as `parse-debounce-ms`.
- Diagnostics: drop `Level.INFO` and below; keep one `SEVERE` per line, the highest `ErrorQuality`; `node == null` becomes a document-scoped diagnostic, never line 0.
- Line ranges use the real line end. `Position(line, Int.MAX_VALUE)` must not appear in any serialised range.
- The document is split on `\r\n`, `\r` or `\n` — `BufferedReader` semantics — never on `\n` alone.
- Kotlin conventions: no `!!` without a documented justification, no `GlobalScope`, sealed classes for state modelling, KDoc on public declarations.
- Every task ends with `:skript-lsp:build` green and `:skript-lsp:check` green.
- `kotlinx-coroutines-core` **is** adopted, in Task 9. Paper does not bundle it, so it must be shaded. `kotlinx-coroutines-core` only, never `-all`.
- The JUnit Platform migration is **deferred**. The tests stay on JUnit 4 (`junit:junit:4.13.2`); nothing in this plan needs the Platform, and Task 9's `runTest` works as a plain JUnit 4 test method.

---

### Task 1: Move sources to `src/main/kotlin` and add static analysis

**Files:**
- Move: `skript-lsp/src/main/java/me/rohandacoder/lspskript/*.kt` → `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/`
- Create: `.editorconfig`
- Create: `config/detekt/detekt.yml`
- Modify: `skript-lsp/build.gradle.kts`

**Interfaces:**
- Produces: a `check` task running detekt, ktlint and the tests together; a repo-wide 120-column limit shared by both linters; sources in the conventional directory.

- [ ] **Step 1: Move the sources**

```bash
mkdir -p skript-lsp/src/main/kotlin/me/rohandacoder/lspskript
git mv skript-lsp/src/main/java/me/rohandacoder/lspskript/*.kt skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/
rmdir skript-lsp/src/main/java/me/rohandacoder/lspskript skript-lsp/src/main/java
```

- [ ] **Step 2: Verify the build still passes**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 48 tests passing.

- [ ] **Step 3: Create `.editorconfig`**

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
indent_style = space
indent_size = 4
insert_final_newline = true
trim_trailing_whitespace = true
max_line_length = 120

[*.{kt,kts}]
ktlint_code_style = intellij_idea
max_line_length = 120
ij_kotlin_packages_to_use_import_on_demand = java.util.*,kotlinx.android.synthetic.**
```

- [ ] **Step 4: Create `config/detekt/detekt.yml`**

```yaml
build:
  maxIssues: 0

complexity:
  LongMethod:
    active: true
    threshold: 60
  TooManyFunctions:
    active: true
    thresholdInFiles: 11
    thresholdInClasses: 11
    thresholdInObjects: 11

style:
  MaxLineLength:
    active: true
    maxLineLength: 120
  ReturnCount:
    active: true
    max: 2
    excludeGuardClauses: true
  MagicNumber:
    active: true
    ignoreNumbers: ['-1', '0', '1', '2']
  WildcardImport:
    active: true
    excludeImports: ['java.util.*']

exceptions:
  TooGenericExceptionCaught:
    active: true
  SwallowedException:
    active: true

comments:
  UndocumentedPublicClass:
    active: true
  UndocumentedPublicFunction:
    active: true
  UndocumentedPublicProperty:
    active: true
```

- [ ] **Step 5: Wire the linters into `skript-lsp/build.gradle.kts`**

**ktlint runs as the standalone CLI, not as a Gradle plugin.** The ktlint
Gradle plugin has not kept pace with Gradle 9, and the standalone CLI is what
the design spec calls for. Resolve the CLI as a normal configuration and run
it with `JavaExec`:

```kotlin
val ktlint by configurations.creating

dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:1.8.0")
}

val ktlintCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs ktlint over the module's Kotlin sources."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.cli.internal.KtlintCommandLine")
    args("check", "src/main/kotlin", "src/test/kotlin")
}

tasks.named("check") { dependsOn(ktlintCheck) }
```

If the CLI's entry point differs in 1.8.0, find it with `jar tf` on the
resolved jar, or run the CLI with `--help`, and correct `mainClass`.

**detekt: start with 1.23.8, and fall back to 2.0.0-alpha.6 if it does not
work.** detekt 1.23.8 embeds a Kotlin 1.9 compiler, so it may not parse
Kotlin 2.3 sources, and its Gradle plugin predates Gradle 9. The design spec
anticipates this: *"detekt 1.23.8 → dev.detekt:2.0.0-alpha.6 when we use
Kotlin ≥2.1 syntax."* This project uses Kotlin 2.3.0, so expect the fallback.

Add to the `plugins` block:

```kotlin
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
```

Add at the top level of the file:

```kotlin
detekt {
    toolVersion = "1.23.8"
    buildUponDefaultConfig = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
```

**If detekt 1.23.8 fails** — either the Gradle plugin is incompatible with
Gradle 9.4, or it cannot parse the Kotlin 2.3 sources — switch to:

```kotlin
    id("io.gitlab.arturbosch.detekt") version "2.0.0-alpha.6"
```

```kotlin
detekt {
    toolVersion = "2.0.0-alpha.6"
    buildUponDefaultConfig = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
```

detekt 2.0's configuration format differs from 1.x: rules are grouped
differently, and some 1.x rule names no longer exist. Regenerate a config
for 2.0 with the task detekt provides, then re-apply the rule set from Step 4
— the *intent* (a 120-column limit, no wildcard imports, documented public
API, at most two return points) is what matters, not the exact YAML shape.
**Say in the report which version you settled on and why.**

- [ ] **Step 6: Run the linters and record the findings**

Run: `.\gradlew.bat :skript-lsp:check --console=plain`
Expected: FAIL, because the existing code has not been cleaned up. Record each rule name and file:line.

- [ ] **Step 7: Fix the findings**

Work through the recorded list. For `ReturnCount`, the config already sets `excludeGuardClauses: true`, which removes the guard-clause cases. For `WildcardImport`, replace `import org.eclipse.lsp4j.*` with explicit imports. For `MaxLineLength`, wrap the offending lines. For `TooManyFunctions`, the three large classes (`CompletionProvider`, `DefinitionReferenceProvider`, `SkriptTextDocumentService`) are **expected** to exceed the threshold — these are the classes the rewrite will split, so raise `thresholdInFiles`/`thresholdInClasses`/`thresholdInObjects` to 25 in the config and note it.

- [ ] **Step 8: Re-run until green**

Run: `.\gradlew.bat :skript-lsp:check --console=plain`
Expected: BUILD SUCCESSFUL, 48 tests passing, no lint findings.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "build: add detekt and ktlint; move sources to src/main/kotlin"
```

---

### Task 2: Retarget the plugin to Java 21 bytecode

**Files:**
- Modify: `skript-lsp/build.gradle.kts`

**Interfaces:**
- Produces: a shaded jar whose class files are Java 21 (major 65), loadable on Paper 1.21.x running Java 21.

- [ ] **Step 1: Add the target configuration**

In `skript-lsp/build.gradle.kts`, inside the existing `java { }` block add:
```kotlin
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
```

And inside the existing `kotlin { }` block add:
```kotlin
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
```

- [ ] **Step 2: Build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify the bytecode version**

```bash
javap -verbose -cp skript-lsp/build/classes/kotlin/main me.rohandacoder.lspskript.LspSkript | grep major
```
Expected: `major version: 65` (Java 21). It was 69 (Java 25) before this change.

- [ ] **Step 4: Commit**

```bash
git add skript-lsp/build.gradle.kts
git commit -m "build: target Java 21 bytecode so the plugin loads on Paper 1.21.x"
```

---

### Task 3: Upgrade LSP4J to 1.0.0

**Files:**
- Modify: `skript-lsp/build.gradle.kts`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/CodeActionProvider.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Diagnostic.getMessage()` returns `Either<String, MarkupContent>` instead of `String`.

- [ ] **Step 1: Bump the dependency**

In `skript-lsp/build.gradle.kts`:
```kotlin
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0")
```

- [ ] **Step 2: Build and read the errors**

Run: `.\gradlew.bat :skript-lsp:compileKotlin --console=plain`
Expected: one error in `CodeActionProvider.kt` at the `diagnostic.message.lowercase(...)` call.

- [ ] **Step 3: Fix the call site**

The line reads `val message = diagnostic.message.lowercase(Locale.ENGLISH)`. Change it to take the left side of the `Either`:

```kotlin
        val message = diagnostic.message.left.lowercase(Locale.ENGLISH)
```

If the compiler rejects `.left`, use `diagnostic.message.getLeft()`.

**If the accessor did not change at all** — LSP4J 1.0.0 may still return a plain
`String` — then this step is a no-op: leave the call site as it was and say so
in the report. Do not contort the code to match a change that is not there.

- [ ] **Step 4: Build and test**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 48 tests passing.

- [ ] **Step 5: Commit**

```bash
git add skript-lsp/build.gradle.kts skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/CodeActionProvider.kt
git commit -m "build: upgrade LSP4J to 1.0.0 for LSP 3.18 support"
```

---

### Task 4: `TextBuffer` — a correct line index

**Files:**
- Create: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/TextBuffer.kt`
- Create: `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/TextBufferTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class TextBuffer private constructor(
      val text: String,
      private val lineStarts: IntArray,
  ) {
      companion object {
          fun of(text: String): TextBuffer
      }
      val lineCount: Int
      fun lineStart(line: Int): Int
      fun lineEnd(line: Int): Int          // exclusive, before the line terminator
      fun lineText(line: Int): String
      fun offsetOf(line: Int, character: Int): Int
      fun positionOf(offset: Int): Pair<Int, Int>   // (line, character)
      fun lineOf(offset: Int): Int
  }
  ```
  `line` and `character` are **0-based**. `offsetOf` clamps `character` to the line's length, per the LSP specification's rule that a character beyond the line length is clamped to the line length. `positionOf` clamps `offset` into the document.

- [ ] **Step 1: Write the failing tests**

Create `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/TextBufferTest.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBufferTest {

    @Test
    fun splitsOnUnixNewlines() {
        val buf = TextBuffer.of("a\nb\nc")
        assertEquals(3, buf.lineCount)
        assertEquals("a", buf.lineText(0))
        assertEquals("c", buf.lineText(2))
    }

    @Test
    fun splitsOnWindowsNewlines() {
        val buf = TextBuffer.of("a\r\nb\r\nc")
        assertEquals(3, buf.lineCount)
        assertEquals("a", buf.lineText(0))
        assertEquals("c", buf.lineText(2))
    }

    @Test
    fun splitsOnLoneCarriageReturn() {
        // BufferedReader.readLine() treats a lone \r as a terminator. text.split('\n')
        // does not, which is how line numbers drift.
        val buf = TextBuffer.of("a\rb\rc")
        assertEquals(3, buf.lineCount)
        assertEquals("b", buf.lineText(1))
    }

    @Test
    fun keepsTheTrailingEmptyLineOfATrailingNewline() {
        val buf = TextBuffer.of("a\nb\n")
        assertEquals(3, buf.lineCount)
        assertEquals("", buf.lineText(2))
    }

    @Test
    fun treatsAnEmptyDocumentAsOneLine() {
        val buf = TextBuffer.of("")
        assertEquals(1, buf.lineCount)
        assertEquals("", buf.lineText(0))
    }

    @Test
    fun lineEndExcludesTheTerminator() {
        val buf = TextBuffer.of("abc\r\ndef")
        assertEquals(3, buf.lineEnd(0))
        assertEquals("abc", buf.lineText(0))
    }

    @Test
    fun offsetOfClampsTheCharacterToTheLineLength() {
        val buf = TextBuffer.of("abc\ndef")
        // The spec says a character beyond the line length is clamped to it.
        assertEquals(3, buf.offsetOf(0, 99))
    }

    @Test
    fun positionOfRoundTrips() {
        val buf = TextBuffer.of("abc\r\ndef")
        assertEquals(0 to 0, buf.positionOf(0))
        assertEquals(0 to 2, buf.positionOf(2))
        assertEquals(1 to 0, buf.positionOf(5))
    }

    @Test
    fun positionOfClampsAnOffsetPastTheEnd() {
        val buf = TextBuffer.of("abc")
        assertEquals(0 to 3, buf.positionOf(999))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :skript-lsp:test --tests '*TextBufferTest*' --console=plain`
Expected: FAIL — `TextBuffer` does not exist.

- [ ] **Step 3: Implement `TextBuffer`**

Create `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/TextBuffer.kt`:

```kotlin
package me.rohandacoder.lspskript

/**
 * An immutable index over a document's text.
 *
 * Lines are split the way [java.io.BufferedReader.readLine] splits them: on
 * `\r\n`, a lone `\r`, or a lone `\n`. Splitting on `\n` alone — which is
 * what `String.split('\n')` does — treats a lone `\r` as ordinary text and
 * makes every subsequent line number disagree with the editor's.
 *
 * All line and character indices are 0-based, matching the LSP.
 */
class TextBuffer private constructor(
    /** The full document text. */
    val text: String,
    /** The offset at which each line starts. */
    private val lineStarts: IntArray,
) {

    /** The number of lines. A document always has at least one. */
    val lineCount: Int get() = lineStarts.size

    /** The offset at which [line] starts. */
    fun lineStart(line: Int): Int = lineStarts[line.coerceIn(0, lineCount - 1)]

    /** The offset just past the last character of [line], before its terminator. */
    fun lineEnd(line: Int): Int {
        val index = line.coerceIn(0, lineCount - 1)
        val next = lineStarts.getOrNull(index + 1)
        if (next == null) return text.length
        // Strip the terminator: \r\n, \n or \r.
        var end = next
        if (end > 0 && text[end - 1] == '\n') end--
        if (end > 0 && text[end - 1] == '\r') end--
        return end
    }

    /** The text of [line], without its terminator. */
    fun lineText(line: Int): String {
        val index = line.coerceIn(0, lineCount - 1)
        return text.substring(lineStart(index), lineEnd(index))
    }

    /**
     * The document offset of [character] on [line].
     *
     * A character beyond the end of the line is clamped to the line's length,
     * as the LSP specification requires.
     */
    fun offsetOf(line: Int, character: Int): Int {
        val index = line.coerceIn(0, lineCount - 1)
        val start = lineStart(index)
        val length = lineEnd(index) - start
        return start + character.coerceIn(0, length)
    }

    /** The line containing [offset]. */
    fun lineOf(offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        // Binary search for the last line start at or before the offset.
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lineStarts[mid] <= clamped) low = mid else high = mid - 1
        }
        return low
    }

    /** The (line, character) position of [offset]. */
    fun positionOf(offset: Int): Pair<Int, Int> {
        val line = lineOf(offset)
        return line to (offset.coerceIn(0, text.length) - lineStart(line))
    }

    companion object {
        /**
         * Index [text], splitting lines the way [java.io.BufferedReader] does.
         */
        fun of(text: String): TextBuffer {
            val starts = ArrayList<Int>()
            starts.add(0)
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\n' -> {
                        starts.add(i + 1)
                        i++
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') {
                            starts.add(i + 2)
                            i += 2
                        } else {
                            starts.add(i + 1)
                            i++
                        }
                    }
                    else -> i++
                }
            }
            return TextBuffer(text, starts.toIntArray())
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :skript-lsp:test --tests '*TextBufferTest*' --console=plain`
Expected: PASS, 9 tests.

- [ ] **Step 5: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 57 tests passing.

- [ ] **Step 6: Commit**

```bash
git add skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/TextBuffer.kt skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/TextBufferTest.kt
git commit -m "feat(lspskript): add TextBuffer, a correct line index over document text"
```

---

### Task 5: The pure `LogEntry` to `Diagnostic` mapping

**Files:**
- Create: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/ParseDiagnostics.kt`
- Create: `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/ParseDiagnosticsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** A log entry reduced to the facts a diagnostic needs. */
  data class DiagnosticInput(
      val severity: DiagnosticSeverity,
      val message: String,
      /** The 0-based line the error is on, or null when it has no line. */
      val line: Int?,
      val quality: Int,
  )

  object ParseDiagnostics {
      fun fromEntries(entries: List<DiagnosticInput>): List<Diagnostic>
  }
  ```
  `fromEntries` filters, dedupes and converts. It is pure — no Skript types, no I/O.

- [ ] **Step 1: Write the failing tests**

Create `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/ParseDiagnosticsTest.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseDiagnosticsTest {

    private fun error(line: Int?, message: String, quality: Int = 2) =
        DiagnosticInput(DiagnosticSeverity.Error, message, line, quality)

    @Test
    fun dropsInfoEntries() {
        val out = ParseDiagnostics.fromEntries(
            listOf(
                DiagnosticInput(DiagnosticSeverity.Information, "loading trigger 'on load:'", 0, 0),
                error(3, "Can't understand this condition/effect: foo"),
            )
        )
        assertEquals(1, out.size)
        assertEquals("Can't understand this condition/effect: foo", out[0].message)
    }

    @Test
    fun keepsOnlyTheHighestQualityErrorPerLine() {
        val out = ParseDiagnostics.fromEntries(
            listOf(
                error(3, "first", quality = 0),
                error(3, "second", quality = 2),
                error(3, "third", quality = 1),
            )
        )
        assertEquals(1, out.size)
        assertEquals("second", out[0].message)
    }

    @Test
    fun keepsErrorsOnDifferentLines() {
        val out = ParseDiagnostics.fromEntries(
            listOf(error(1, "a"), error(2, "b"))
        )
        assertEquals(2, out.size)
    }

    @Test
    fun aLineLessDiagnosticIsNotAnchoredToLineZero() {
        val out = ParseDiagnostics.fromEntries(listOf(error(null, "no line")))
        assertEquals(1, out.size)
        assertEquals(0, out[0].range.start.line)
        assertEquals(0, out[0].range.start.character)
    }

    @Test
    fun theCodeCarriesTheQuality() {
        val out = ParseDiagnostics.fromEntries(listOf(error(3, "boom", quality = 2)))
        assertEquals("q2", out[0].code)
    }

    @Test
    fun theSourceIsSkript() {
        val out = ParseDiagnostics.fromEntries(listOf(error(3, "boom")))
        assertEquals("Skript", out[0].source)
    }

    @Test
    fun theRangeCoversTheLine() {
        val out = ParseDiagnostics.fromEntries(listOf(error(3, "boom")))
        assertEquals(3, out[0].range.start.line)
        assertEquals(3, out[0].range.end.line)
        assertTrue(out[0].range.end.character < Int.MAX_VALUE)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :skript-lsp:test --tests '*ParseDiagnosticsTest*' --console=plain`
Expected: FAIL — `ParseDiagnostics` does not exist.

- [ ] **Step 3: Implement `ParseDiagnostics`**

Create `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/ParseDiagnostics.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * A log entry reduced to the facts a diagnostic needs.
 *
 * This exists so the conversion can be tested without a Skript runtime.
 */
data class DiagnosticInput(
    val severity: DiagnosticSeverity,
    val message: String,
    /** The 0-based line the entry is on, or null when it has no line. */
    val line: Int?,
    val quality: Int,
)

/**
 * Converts Skript's log entries into LSP diagnostics.
 *
 * Two behaviours are copied from Skript's own `ParseLogHandler`: entries below
 * `WARNING` are dropped, and only the single best `SEVERE` per line is kept,
 * ranked by `ErrorQuality`. Skript does this so that a line with several
 * candidate patterns reports one error rather than several.
 */
object ParseDiagnostics {

    /** The source reported on every diagnostic. */
    const val SOURCE: String = "Skript"

    /**
     * Filter, dedupe and convert [entries].
     *
     * An entry with no line is reported at the start of the document rather
     * than being attributed to line 0, which would point at the first line of
     * the file.
     */
    fun fromEntries(entries: List<DiagnosticInput>): List<Diagnostic> {
        val bestPerLine = LinkedHashMap<Int, DiagnosticInput>()
        val withoutLine = mutableListOf<DiagnosticInput>()

        for (entry in entries) {
            if (entry.severity == DiagnosticSeverity.Information) continue
            if (entry.severity == DiagnosticSeverity.Hint) continue
            val line = entry.line
            if (line == null) {
                withoutLine += entry
                continue
            }
            val existing = bestPerLine[line]
            if (existing == null || entry.quality > existing.quality) {
                bestPerLine[line] = entry
            }
        }

        val out = ArrayList<Diagnostic>(bestPerLine.size + withoutLine.size)
        for (entry in withoutLine) out += toDiagnostic(entry, 0)
        for ((line, entry) in bestPerLine) out += toDiagnostic(entry, line)
        return out
    }

    private fun toDiagnostic(entry: DiagnosticInput, line: Int): Diagnostic {
        val diagnostic = Diagnostic()
        diagnostic.severity = entry.severity
        diagnostic.message = entry.message
        diagnostic.source = SOURCE
        diagnostic.code = "q${entry.quality}"
        diagnostic.range = Range(Position(line, 0), Position(line, 0))
        return diagnostic
    }
}
```

**Note:** the range's end is set to the line start. The engine fills in the real end offset from the `TextBuffer` once it has one — a diagnostic's range must not be `Int.MAX_VALUE`, and the mapping step is the engine's job, not this object's.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :skript-lsp:test --tests '*ParseDiagnosticsTest*' --console=plain`
Expected: PASS, 7 tests.

- [ ] **Step 5: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 64 tests passing.

- [ ] **Step 6: Commit**

```bash
git add skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/ParseDiagnostics.kt skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/ParseDiagnosticsTest.kt
git commit -m "feat(lspskript): add the pure log-entry to diagnostic mapping"
```

---

### Task 6: `DocumentSnapshot` — the immutable model

**Files:**
- Create: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/DocumentSnapshot.kt`
- Create: `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/DocumentSnapshotTest.kt`

**Interfaces:**
- Consumes: `TextBuffer` (Task 4).
- Produces:
  ```kotlin
  /**
   * A section of a Skript document: a header line plus the lines beneath it.
   *
   * @param header the section's header text, e.g. "on join:"
   * @param headerLine the 0-based line the header is on
   * @param endLine the 0-based line of the last line belonging to the section
   */
  data class SectionInfo(
      val header: String,
      val headerLine: Int,
      val endLine: Int,
      val children: List<SectionInfo> = emptyList(),
  )

  /**
   * A single statement line inside a section.
   */
  data class StatementInfo(
      val text: String,
      val line: Int,
      val indent: Int,
  )

  /**
   * An immutable view of one parsed document.
   *
   * Every feature reads this rather than re-parsing. It is plain copied data:
   * no live AST objects, because `setInactive()` clears the parser's caches
   * and hint scopes, so holding them would be use-after-reset.
   */
  data class DocumentSnapshot(
      val uri: String,
      val version: Int,
      val buffer: TextBuffer,
      val diagnostics: List<Diagnostic>,
      val sections: List<SectionInfo>,
      val statements: List<StatementInfo>,
  ) {
      /** The innermost section containing [line], or null. */
      fun sectionAt(line: Int): SectionInfo?

      /** The statement on [line], or null. */
      fun statementAt(line: Int): StatementInfo?
  }
  ```

- [ ] **Step 1: Write the failing tests**

Create `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/DocumentSnapshotTest.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentSnapshotTest {

    private fun snapshot(
        sections: List<SectionInfo> = emptyList(),
        statements: List<StatementInfo> = emptyList(),
    ) = DocumentSnapshot(
        uri = "file:///a.sk",
        version = 1,
        buffer = TextBuffer.of(""),
        diagnostics = emptyList(),
        sections = sections,
        statements = statements,
    )

    @Test
    fun sectionAtReturnsNullWhenThereAreNoSections() {
        assertNull(snapshot().sectionAt(3))
    }

    @Test
    fun sectionAtReturnsNullForALineOutsideEverySection() {
        val s = snapshot(listOf(SectionInfo("on join:", 2, 5)))
        assertNull(s.sectionAt(0))
        assertNull(s.sectionAt(6))
    }

    @Test
    fun sectionAtReturnsTheSectionContainingTheLine() {
        val s = snapshot(listOf(SectionInfo("on join:", 2, 5)))
        assertEquals("on join:", s.sectionAt(4)?.header)
    }

    @Test
    fun sectionAtReturnsTheInnermostSection() {
        val inner = SectionInfo("if x:", 4, 5)
        val outer = SectionInfo("on join:", 2, 5, listOf(inner))
        val s = snapshot(listOf(outer))
        assertEquals("if x:", s.sectionAt(4)?.header)
        assertEquals("on join:", s.sectionAt(3)?.header)
    }

    @Test
    fun statementAtReturnsTheStatementOnThatLine() {
        val s = snapshot(statements = listOf(StatementInfo("set {x} to 1", 3, 4)))
        assertEquals("set {x} to 1", s.statementAt(3)?.text)
        assertNull(s.statementAt(4))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :skript-lsp:test --tests '*DocumentSnapshotTest*' --console=plain`
Expected: FAIL — `DocumentSnapshot` does not exist.

- [ ] **Step 3: Implement the model**

Create `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/DocumentSnapshot.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.eclipse.lsp4j.Diagnostic

/**
 * A section of a Skript document: a header line plus the lines beneath it.
 *
 * @param header the section's header text, e.g. "on join:"
 * @param headerLine the 0-based line the header is on
 * @param endLine the 0-based line of the last line belonging to the section
 */
data class SectionInfo(
    val header: String,
    val headerLine: Int,
    val endLine: Int,
    val children: List<SectionInfo> = emptyList(),
)

/**
 * A single statement line inside a section.
 *
 * @param text the statement's text
 * @param line the 0-based line the statement is on
 * @param indent the statement's indentation, in characters
 */
data class StatementInfo(
    val text: String,
    val line: Int,
    val indent: Int,
)

/**
 * An immutable view of one parsed document.
 *
 * Every feature reads this rather than re-parsing. It is plain copied data:
 * no live AST objects, because `setInactive()` clears the parser's caches and
 * hint scopes, so holding them would be use-after-reset.
 */
data class DocumentSnapshot(
    val uri: String,
    val version: Int,
    val buffer: TextBuffer,
    val diagnostics: List<Diagnostic>,
    val sections: List<SectionInfo>,
    val statements: List<StatementInfo>,
) {

    /** The innermost section containing [line], or null. */
    fun sectionAt(line: Int): SectionInfo? {
        fun search(candidates: List<SectionInfo>): SectionInfo? {
            for (section in candidates) {
                if (line < section.headerLine || line > section.endLine) continue
                return search(section.children) ?: section
            }
            return null
        }
        return search(sections)
    }

    /** The statement on [line], or null. */
    fun statementAt(line: Int): StatementInfo? = statements.firstOrNull { it.line == line }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :skript-lsp:test --tests '*DocumentSnapshotTest*' --console=plain`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 69 tests passing.

- [ ] **Step 6: Commit**

```bash
git add skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/DocumentSnapshot.kt skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/DocumentSnapshotTest.kt
git commit -m "feat(lspskript): add the immutable DocumentSnapshot model"
```

---

### Task 7: `SkriptParseEngine` — the parse-only path

**Files:**
- Create: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptParseEngine.kt`

**Interfaces:**
- Consumes: `TextBuffer` (Task 4), `ParseDiagnostics` (Task 5), `DocumentSnapshot` (Task 6).
- Produces:
  ```kotlin
  class SkriptParseEngine {
      /**
       * Parse [content] as a Skript document, without loading it.
       *
       * MUST be called on the Bukkit main thread: `Functions.namespaces`,
       * `Functions.globalFunctions`, `FunctionRegistry`'s inner maps and
       * `Classes.superClassInfos` are all plain unsynchronised `HashMap`s.
       */
      fun parse(
          docId: String,
          uri: String,
          version: Int,
          content: String,
          realFile: File?,
      ): DocumentSnapshot
  }
  ```
- This is the **only** file in the plugin permitted to import `ch.njol.skript.*`
  or `org.skriptlang.skript.*`. Every other file is Skript-free.

**There is no unit test for this task.** The engine needs a live Skript
registry, so it is verified by the differential comparison on the development
server, not by a JUnit test. Do not add a test that mocks Skript — a mocked
`ParserInstance.get()` returns a stub whose `getCurrentScript()` is null, which
explodes deep inside Skript's parser.

- [ ] **Step 1: Write the engine**

Create `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptParseEngine.kt`:

```kotlin
package me.rohandacoder.lspskript

import ch.njol.skript.ScriptLoader
import ch.njol.skript.Skript
import ch.njol.skript.config.Config
import ch.njol.skript.config.Node
import ch.njol.skript.config.SectionNode
import ch.njol.skript.lang.SkriptParser
import ch.njol.skript.lang.function.FunctionEvent
import ch.njol.skript.lang.function.Functions
import ch.njol.skript.lang.function.Signature
import ch.njol.skript.lang.parser.ParserInstance
import ch.njol.skript.log.LogEntry
import ch.njol.skript.log.RetainingLogHandler
import ch.njol.skript.structures.StructFunction
import ch.njol.skript.structures.StructVariables
import com.mojang.brigadier.tree.LiteralCommandNode
import org.skriptlang.skript.bukkit.command.custom.ScriptCommandRegistrar
import org.skriptlang.skript.bukkit.command.elements.structures.StructCommand
import org.skriptlang.skript.bukkit.command.elements.structures.util.SubCommandEntryData
import org.skriptlang.skript.common.function.FunctionParser
import org.skriptlang.skript.lang.script.Script
import org.skriptlang.skript.lang.structure.Structure
import java.io.ByteArrayInputStream
import java.io.File
import java.util.logging.Level
import java.util.regex.Pattern

/**
 * Parses Skript source **without loading it**.
 *
 * This is the only file in the plugin that imports Skript. Everything else
 * works with [DocumentSnapshot].
 *
 * The whole design rests on one omission: `Structure.postLoad()` is never
 * called. `on load:` is an ordinary event ([ch.njol.skript.events.EvtScript])
 * whose [ch.njol.skript.events.EvtScript.postLoad] fires the trigger — so
 * omitting the post-load phase omits the side effect. The same omission stops
 * Bukkit listeners from being registered and commands from being claimed.
 *
 * The algorithm mirrors [ScriptLoader.loadStructure] and the load phases in
 * `ScriptLoader.loadScripts`: parse every top-level node into a `Structure`,
 * then run `preLoad()` over them in priority order, then `load()` over them in
 * priority order. `postLoad()` is the phase we skip.
 *
 * Two structures are handled differently, and one is skipped outright — see
 * the design spec, section 4.5, for why.
 */
class SkriptParseEngine {

    /**
     * A structure together with the config node it came from.
     *
     * The node is needed for two things: `parser.setNode(node)` so log entries
     * carry the right line, and the function and command paths, which need the
     * node's key and body.
     */
    private class ParsedStructure(
        val structure: Structure,
        val node: Node,
        val line: String,
    )

    /**
     * The function signature pattern, rebuilt from Skript's own.
     *
     * `StructFunction.SIGNATURE_PATTERN` is private, but the one part of it
     * that is not a literal — the function name pattern — is public.
     */
    private val functionSignature = Pattern.compile(
        "^(?:local )?function (?<name>${Functions.functionNamePattern})" +
            "\\((?<args>.*?)\\)(?:\\s*(?:->|::| returns )\\s*(?<returns>.+))?$",
    )

    /**
     * Parse [content] as a Skript document, without loading it.
     *
     * MUST be called on the Bukkit main thread: `Functions.namespaces`,
     * `Functions.globalFunctions`, `Functions.toValidate`,
     * `FunctionRegistry`'s inner maps and `Classes.superClassInfos` are all
     * plain unsynchronised `HashMap`s, and a background parse racing a
     * server-side script load can corrupt them.
     */
    fun parse(
        docId: String,
        uri: String,
        version: Int,
        content: String,
        realFile: File?,
    ): DocumentSnapshot {
        val namespace = namespaceFor(docId)
        val buffer = TextBuffer.of(content)
        val handler = RetainingLogHandler()
        handler.start()
        val parser = ParserInstance.get()
        val registered = mutableListOf<Signature<*>>()
        try {
            val config = Config(
                ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
                namespace,
                realFile,
                true,
                false,
                ":",
            )
            val script = Script(config, mutableListOf())
            parser.setActive(script)

            val parsed = parseStructures(config, parser)
            val signatures = registerFunctions(parsed, namespace, parser, registered)
            loadStructures(parsed, signatures, script, parser)

            return DocumentSnapshot(
                uri = uri,
                version = version,
                buffer = buffer,
                diagnostics = diagnostics(handler, buffer),
                sections = sections(config),
                statements = statements(config),
            )
        } finally {
            // Local signatures only: this removes them from the FunctionRegistry
            // bucket and from `Functions.namespaces`, and leaves
            // `Functions.globalFunctions` untouched. Removal is identity-checked,
            // so a pre-existing server signature is never removed.
            for (signature in registered) {
                Functions.unregisterFunction(signature)
            }
            parser.setInactive()
            handler.stop()
        }
    }

    /**
     * The function namespace for a document.
     *
     * Stable per document, so repeated parses reuse one namespace — the
     * registry keeps an empty `Namespace` object per identifier and never
     * removes it.
     *
     * It must end in `.sk` and be at least three characters:
     * `Variable.java:209` does `getFileName().substring(0, -3)`, which throws
     * for a shorter name.
     */
    private fun namespaceFor(docId: String): String = "__lsp__$docId.sk"

    // ------------------------------------------------------------------
    // Parse phase
    // ------------------------------------------------------------------

    private fun parseStructures(config: Config, parser: ParserInstance): List<ParsedStructure> {
        val parsed = mutableListOf<ParsedStructure>()
        for (node in config.mainNode) {
            var line = node.key ?: continue
            line = ScriptLoader.replaceOptions(line)
            if (line.isBlank()) continue
            if (isAliasesStructure(line)) continue
            if (!SkriptParser.validateLine(line)) continue
            val structure = Structure.parse(line, node, "Can't understand this structure: $line") ?: continue
            parsed += ParsedStructure(structure, node, line)
        }
        return parsed
    }

    /**
     * `StructAliases.init()` registers aliases into Skript's global alias
     * registry, and `Aliases` has no per-script removal API — so the
     * structure is never parsed at all. The cost is that alias definitions
     * are not validated in the editor.
     */
    private fun isAliasesStructure(line: String): Boolean = line.trim().equals("aliases", ignoreCase = true)

    // ------------------------------------------------------------------
    // Functions
    // ------------------------------------------------------------------

    /**
     * Register every function declared in the buffer, as a **local** function
     * in our own namespace.
     *
     * `local = true` matters: it selects the registry bucket and decides
     * whether `Functions.globalFunctions` is written. With `local = false` the
     * server's own global function would be overwritten, and the cleanup would
     * *remove* the mapping rather than restore it — permanently breaking
     * `the function named "f"` at runtime.
     *
     * The namespace must be byte-for-byte the `Config`'s file name, because
     * that is what the call site looks up
     * (`FunctionReferenceParser.java:124`).
     */
    private fun registerFunctions(
        parsed: List<ParsedStructure>,
        namespace: String,
        parser: ParserInstance,
        registered: MutableList<Signature<*>>,
    ): Map<ParsedStructure, Signature<*>> {
        val signatures = mutableMapOf<ParsedStructure, Signature<*>>()
        for (entry in parsed) {
            if (entry.structure !is StructFunction) continue
            val matcher = functionSignature.matcher(entry.node.key ?: continue)
            if (!matcher.matches()) {
                Skript.error("Invalid function signature: " + entry.node.key)
                continue
            }
            parser.setCurrentStructure(entry.structure)
            parser.setNode(entry.node)
            val signature = FunctionParser.parse(
                namespace,
                matcher.group("name"),
                matcher.group("args"),
                matcher.group("returns"),
                true,
            ) ?: continue
            if (Functions.registerSignature(signature) == null) continue
            registered += signature
            signatures[entry] = signature
        }
        return signatures
    }

    // ------------------------------------------------------------------
    // Load phase
    // ------------------------------------------------------------------

    /**
     * Run `preLoad()` and then `load()` over the structures, in priority
     * order — exactly as `ScriptLoader.loadScripts` does.
     *
     * `postLoad()` is never called. That single omission is what stops
     * `on load:` from firing, stops Bukkit listeners from being registered,
     * stops commands from being claimed, and stops `auto reload` from
     * starting its reload task.
     */
    private fun loadStructures(
        parsed: List<ParsedStructure>,
        signatures: Map<ParsedStructure, Signature<*>>,
        script: Script,
        parser: ParserInstance,
    ) {
        val ordered = parsed.sortedBy { it.structure.priority }

        // pre-loading
        val survivors = ordered.filter { entry ->
            if (entry.structure is StructFunction) return@filter true // handled below
            parser.setActive(script)
            parser.setCurrentStructure(entry.structure)
            parser.setNode(entry.node)
            try {
                entry.structure.preLoad()
            } catch (e: Exception) {
                Skript.exception(e, "An error occurred while trying to preLoad a Structure.")
                false
            }
        }

        // loading
        for (entry in survivors) {
            parser.setActive(script)
            parser.setCurrentStructure(entry.structure)
            parser.setNode(entry.node)
            try {
                when {
                    // The function's body is parsed by Functions.loadFunction,
                    // using the signature we registered ourselves.
                    entry.structure is StructFunction -> {
                        val signature = signatures[entry] ?: continue
                        parser.setCurrentEvent("local function", FunctionEvent::class.java)
                        try {
                            Functions.loadFunction(script, entry.node as SectionNode, signature)
                        } finally {
                            parser.deleteCurrentEvent()
                        }
                    }
                    // load() creates real global variables.
                    entry.structure is StructVariables -> Unit
                    // load() registers the command; we do the diagnostics by hand.
                    entry.structure is StructCommand -> parseCommand(entry, parser)
                    else -> entry.structure.load()
                }
            } catch (e: Exception) {
                Skript.exception(e, "An error occurred while trying to load a Structure.")
            }
        }
        parser.setInactive()
    }
}
```

**The pieces this sketch leaves open — here is what each one is, verified
against Skript 2.17.1:**

#### `FunctionParserBridge.parse`

There is no such bridge. Call
`org.skriptlang.skript.common.function.FunctionParser.parse(script, name, args, returns, local)`
directly — it is public static and returns a `Signature<?>` or null.

#### `isCommandStructure`

The live class is
`org.skriptlang.skript.bukkit.command.elements.structures.StructCommand`.
The deprecated `ch.njol.skript.structures.StructCommand` is **not registered**
and its `init()` returns false, so it never appears in a parse result.

#### `parseCommand`

`StructCommand.load()` does three things: it parses the command through
`SubCommandEntryData`, it validates the result, and it registers the command
with `ScriptCommandRegistrar`. The first two produce the diagnostics; the
third has no clean undo, so it is skipped.

```kotlin
    /**
     * Reproduce the 2.17 `StructCommand.load()` diagnostics without
     * registering the command.
     */
    private fun parseCommand(entry: ParsedStructure, parser: ParserInstance) {
        val rootNode = entry.node as? SectionNode ?: return
        parser.setCurrentStructure(entry.structure)
        parser.setNode(rootNode)
        val result = SubCommandEntryData("command", false, false).getValue(rootNode) ?: return
        if (result.arguments().size != 1) {
            Skript.error("A command must have a name.")
            return
        }
        val built = result.arguments().first().builder().build()
        val node = built as? LiteralCommandNode<*> ?: run {
            Skript.error("A command must have a name.")
            return
        }
        val existing = ScriptCommandRegistrar.getCommand(node.literal)
        if (existing != null) {
            Skript.error(
                "A command with the name /" + node.literal +
                    " is already defined in the script '" + existing.script().nameAndPath() + ".sk'",
            )
        }
    }
```

The types: `SubCommandEntryData.Result` is a **record** with
`arguments(): List<ScriptArgumentBuilder>`; `ScriptArgumentBuilder` is a record
with `builder(): ArgumentBuilder<CommandSourceStack, ?>`, whose `build()` gives
an `ArgumentNode`. `ScriptBrigadierCommand` is a record with
`script(): Script` and `node(): LiteralCommandNode<CommandSourceStack>`.
`LiteralCommandNode.getLiteral()` is a brigadier getter, so in Kotlin it is the
property `node.literal`.

**Java record components are methods, not properties, in Kotlin.** Call
`result.arguments()` and `existing.script()` — do not write
`result.arguments` or `existing.script`.

`io.papermc.paper.command.brigadier.CommandSourceStack` appears only in
generics, so it never has to be named in our source. Skript 2.17.1's command
support is Paper-only anyway, and so is this plugin.

#### The function body

```kotlin
    // in the load phase, for a StructFunction:
    val signature = signatures[entry] ?: continue
    parser.setCurrentEvent("local function", FunctionEvent::class.java)
    try {
        Functions.loadFunction(script, entry.node as SectionNode, signature)
    } finally {
        parser.deleteCurrentEvent()
    }
```

`Functions.loadFunction(script, node, signature)` is public static. Because the
signature is local, the resulting `Function` lands in our namespace and not in
the global bucket (`Functions.java:140-144`). `FunctionEvent` is
`ch.njol.skript.lang.function.FunctionEvent`.

#### `diagnostics(handler, buffer)`

Map the handler's log entries through `ParseDiagnostics` (Task 5), then widen
each range to the real end of its line using the `TextBuffer`:
`ParseDiagnostics` sets the range to the line start, so replace the end with
`Position(line, buffer.lineText(line).length)`.

**The log handler must be started before the `Config` is constructed.** The
config parser logs its own errors — *"indentation error: expected 1 tab, but
found 2 spaces"* — and those are diagnostics we want.

`RetainingLogHandler.getLog()` returns a **live view** of a mutable deque and
has a side effect. Copy it: `handler.log.toList()`.

#### `sections(config)` and `statements(config)`

Walk the config tree. The facts:

| What | How |
|---|---|
| The root | `config.mainNode` — a `SectionNode` |
| A section's header | `node.key` — **without** the trailing colon, e.g. `"on join"` |
| A node's line | `node.line` — **1-based**; subtract one for LSP |
| A node's indentation | `node.indentation` — the indentation string |
| A nested section | the child is a `SectionNode` |
| A statement | the child is any other `Node` — a `SimpleNode`, an `InvalidNode` (bad indentation) or a `VoidNode` (a comment or blank line) |

`InvalidNode` and `VoidNode` are **not** statements. An `InvalidNode` already
produced an *"indentation error"* diagnostic, and a `VoidNode` is a comment.
Only report a child as a statement when it is neither.

A section's `endLine` is the largest line among **all** of its descendants —
its statements *and* its nested sections' end lines — or its own header line
when it has no descendants.

**Empty sections are kept.** `allowEmptySections = false` does not drop them;
it makes the config parser emit Skript's *"empty section"* warning, which
matches what Skript's own loader does.

#### The `Config` file name and namespace

`Config`'s file name is also the function namespace, and the call site looks
it up verbatim (`FunctionReferenceParser.java:124`). Use **one** value for
both: `namespaceFor(docId)`.

**Two rules the cleanup depends on:**

- **The namespace is stable per document**, not per parse.
  `FunctionRegistry.remove` empties the inner maps but never removes the
  `NamespaceIdentifier` entry, so a per-parse namespace would leak one empty
  `Namespace` per parse.
- **Unregister before re-registering.** `FunctionRegistry.register` throws
  `SkriptAPIException` if the same name and argument types are registered twice
  in the same namespace, and `FunctionIdentifier.equals` ignores locality.

- [ ] **Step 2: Verify the import rule**

Run:
```bash
grep -rlE "^import (ch\.njol\.skript|org\.skriptlang\.skript)\." skript-lsp/src/main/kotlin/ | sort
```
Expected: exactly one path — `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptParseEngine.kt`.

If more than one file matches, move those imports out. `SyntaxRegistryAccess.kt`
and the providers currently import `org.skriptlang.skript.registration.*` —
that package is a **separate** concern from the parse engine, so if the rule
proves too strict to hold, say so in the report rather than silently widening
it. The rule's purpose is that the *parse* path is isolated, not that the
plugin may never mention Skript.

- [ ] **Step 3: Build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 69 tests passing.

- [ ] **Step 4: Check the engine never calls `postLoad`**

Run:
```bash
grep -n "postLoad" skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptParseEngine.kt
```
Expected: no output. A `postLoad` call in this file is a critical bug — it is
what fires `on load:`.

- [ ] **Step 5: Commit**

```bash
git add skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptParseEngine.kt
git commit -m "feat(lspskript): add the parse-only Skript engine"
```

---

### Task 8: The `ScriptParser` seam, and `ParseBridge` rewritten

**Files:**
- Create: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/ScriptParser.kt`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/ParseBridge.kt`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/LspSkript.kt`
- Create: `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/ParseBridgeTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /**
   * The seam between the language server's orchestration and Skript's parser.
   *
   * Production uses [SkriptScriptParser]; tests use a fake. This is what lets
   * the debounce and supersession logic be tested without a server.
   */
  interface ScriptParser {
      fun parse(docId: String, uri: String, content: String, realFile: File?): DocumentSnapshot
  }
  ```
- `ParseBridge` becomes a thin holder for the `ScriptParser` and the
  `parse-only` flag. It no longer writes temp files and no longer calls
  `ScriptLoader.loadScripts`.

- [ ] **Step 1: Write the failing test**

Create `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/ParseBridgeTest.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParseBridgeTest {

    private class FakeParser(private val snapshot: DocumentSnapshot) : ScriptParser {
        var calls = 0
        override fun parse(docId: String, uri: String, content: String, realFile: File?): DocumentSnapshot {
            calls++
            return snapshot
        }
    }

    private fun snapshot(uri: String) = DocumentSnapshot(
        uri = uri,
        version = 1,
        buffer = TextBuffer.of("on join:\n\tbroadcast \"hi\"\n"),
        diagnostics = emptyList(),
        sections = listOf(SectionInfo("on join:", 0, 1)),
        statements = listOf(StatementInfo("broadcast \"hi\"", 1, 1)),
    )

    @Test
    fun theParseOnlyPathReturnsTheParserSnapshot() {
        val parser = FakeParser(snapshot("file:///a.sk"))
        val bridge = ParseBridge(parser, parseOnly = true)
        val result = bridge.parse("doc1", "file:///a.sk", "on join:\n\tbroadcast \"hi\"\n", null)
        assertEquals(1, parser.calls)
        assertEquals("on join:", result.sectionAt(0)?.header)
    }

    @Test
    fun theLegacyPathIsUsedWhenParseOnlyIsOff() {
        val parser = FakeParser(snapshot("file:///a.sk"))
        val bridge = ParseBridge(parser, parseOnly = false)
        val result = bridge.parse("doc1", "file:///a.sk", "", null)
        assertEquals(0, parser.calls)
        assertTrue(result.diagnostics.isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :skript-lsp:test --tests '*ParseBridgeTest*' --console=plain`
Expected: FAIL — `ScriptParser` and the new `ParseBridge` do not exist.

- [ ] **Step 3: Create the seam**

Create `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/ScriptParser.kt`:

```kotlin
package me.rohandacoder.lspskript

import java.io.File

/**
 * The seam between the language server's orchestration and Skript's parser.
 *
 * Production uses the parse-only engine; tests use a fake. This is what lets
 * the debounce and supersession logic be tested without a server.
 */
interface ScriptParser {

    /**
     * Parse [content] and return a snapshot of the result.
     *
     * @param docId a stable identifier for the document, used as the function
     *   namespace. It must be the same for every parse of the same document.
     * @param realFile the document's file on disk, when it exists
     */
    fun parse(docId: String, uri: String, content: String, realFile: File?): DocumentSnapshot
}
```

- [ ] **Step 4: Rewrite `ParseBridge`**

Replace the whole of `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/ParseBridge.kt`:

```kotlin
package me.rohandacoder.lspskript

import ch.njol.skript.ScriptLoader
import ch.njol.skript.config.Config
import ch.njol.skript.log.LogEntry
import ch.njol.skript.log.RetainingLogHandler
import ch.njol.skript.log.SkriptLogger
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.logging.Level

/**
 * Produces diagnostics for a document.
 *
 * The default path is parse-only: the document is parsed and never loaded, so
 * the user's `on load:` block does not run and the server's state is not
 * touched. The legacy path — which loads the script for real — is kept behind
 * the `parse-only` config flag, for the case where the parse-only path turns
 * out to be missing a diagnostic.
 */
class ParseBridge(
    private val parser: ScriptParser,
    private val parseOnly: Boolean,
) {

    /**
     * Parse [content] and return the resulting snapshot.
     *
     * [docId] must be stable per document: it is the function namespace, and a
     * namespace that changes between parses leaks a registry entry per parse.
     */
    fun parse(docId: String, uri: String, content: String, realFile: File?): DocumentSnapshot {
        return if (parseOnly) {
            // The parse MUST run on the Bukkit main thread. `Functions.namespaces`,
            // `Functions.globalFunctions`, `FunctionRegistry`'s inner maps and
            // `Classes.superClassInfos` are all plain unsynchronised `HashMap`s.
            LspUtils.onMainThread { parser.parse(docId, uri, content, realFile) }
        } else {
            DocumentSnapshot(
                uri = uri,
                version = 0,
                buffer = TextBuffer.of(content),
                diagnostics = legacyParse(uri, content),
                sections = emptyList(),
                statements = emptyList(),
            )
        }
    }

    /**
     * The legacy path: write the content to a temporary file and let Skript
     * load it for real.
     *
     * This executes the user's `on load:` block and registers the script's
     * commands, listeners and functions on the server. It exists only as an
     * escape hatch, and it is off by default.
     */
    private fun legacyParse(uri: String, content: String): List<Diagnostic> {
        val tempFile = prepareTempFile(uri, content) ?: return emptyList()
        val handler = RetainingLogHandler()
        handler.start()
        try {
            ScriptLoader.loadScripts(setOf(tempFile), ch.njol.util.OpenCloseable.EMPTY).join()
        } catch (e: Exception) {
            SkriptLogger.LOGGER.log(Level.FINE, "LspSkript parse exception", e)
        } finally {
            handler.stop()
        }
        val diagnostics = mutableListOf<Diagnostic>()
        for (entry in handler.log) {
            toDiagnostic(entry)?.let { diagnostics += it }
        }
        return diagnostics
    }

    private fun toDiagnostic(entry: LogEntry): Diagnostic? {
        val level = entry.level ?: return null
        val severity: DiagnosticSeverity = when {
            level.intValue() >= Level.SEVERE.intValue() -> DiagnosticSeverity.Error
            level.intValue() >= Level.WARNING.intValue() -> DiagnosticSeverity.Warning
            else -> DiagnosticSeverity.Information
        }
        val node = entry.node
        val line = if (node != null && node.line > 0) node.line - 1 else 0
        val diagnostic = Diagnostic(Range(Position(line, 0), Position(line, Int.MAX_VALUE)), entry.message ?: "")
        diagnostic.severity = severity
        diagnostic.source = "Skript"
        if (entry.quality >= 0) {
            diagnostic.setCode("q" + entry.quality)
        }
        return diagnostic
    }

    private val tempDir: File by lazy {
        try {
            Files.createTempDirectory("lspskript").toFile()
        } catch (e: IOException) {
            val fallback = File(System.getProperty("java.io.tmpdir"), "lspskript")
            fallback.mkdirs()
            fallback
        }
    }

    /**
     * Write the content outside Skript's scripts folder, so the server never
     * auto-loads it on (re)start.
     */
    private fun prepareTempFile(uri: String, content: String): File? {
        return try {
            val file = File(tempDir, sanitize(uri) + ".sk")
            Files.write(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
            file
        } catch (e: IOException) {
            SkriptLogger.LOGGER.log(Level.WARNING, "LspSkript failed to prepare temp script", e)
            null
        }
    }

    private fun sanitize(uri: String): String {
        var s = uri
        val slash = s.lastIndexOf('/')
        if (slash >= 0) s = s.substring(slash + 1)
        val colon = s.lastIndexOf(':')
        if (colon >= 0) s = s.substring(colon + 1)
        s = s.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (s.isEmpty()) "script" else s
    }
}
```

- [ ] **Step 5: Wire the flag in `LspSkript`**

In `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/LspSkript.kt`, replace the
`server = SkriptLanguageServer(port, trace)` construction so the `ParseBridge`
is built with the flag and the engine:

```kotlin
        val parseOnly = config.getBoolean("parse-only", true)
        server = SkriptLanguageServer(port, trace, parseOnly)
```

and add to `skript-lsp/src/main/resources/config.yml`:

```yaml
# Parse the document for diagnostics without loading it into the server.
#
# When true (the default), the language server parses your script and reads
# the result, but never loads it: your `on load:` block does not run, and no
# commands, listeners or functions are registered on the server.
#
# Set it to false to fall back to the old behaviour, where the script is
# really loaded to produce diagnostics. Be aware that this runs your
# `on load:` block every time the file changes.
parse-only: true
```

Thread the `parseOnly` flag through `SkriptLanguageServer` to
`SkriptTextDocumentService`, and build the bridge there:

```kotlin
    private val parseBridge: ParseBridge by lazy {
        ParseBridge(SkriptParseEngine(), parseOnly)
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat :skript-lsp:test --tests '*ParseBridgeTest*' --console=plain`
Expected: PASS, 2 tests.

- [ ] **Step 7: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 71 tests passing.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(lspskript): parse documents without loading them"
```

---

### Task 9: Per-URI debounce and supersession

**Files:**
- Modify: `skript-lsp/build.gradle.kts`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptTextDocumentService.kt`
- Create: `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/DebouncerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /**
   * Coalesces a burst of changes into one call.
   *
   * Each URI has its own trailing timer. A call that is superseded before it
   * runs is dropped.
   */
  class Debouncer(
      private val delayMs: Long,
      private val scope: CoroutineScope,
      private val onFire: (String) -> Unit,
  ) {
      fun schedule(uri: String)
      fun cancel(uri: String)
      fun cancelAll()
  }
  ```

- [ ] **Step 1: Add the coroutines dependency**

In `skript-lsp/build.gradle.kts`, inside `dependencies`:

```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
```

And inside the `tasks.named<...ShadowJar>` block, so it is shaded into the
plugin jar — Paper does not bundle it:

```kotlin
    // Paper does not provide coroutines at runtime, so they must be shaded in.
```

(If the shadow plugin does not pick the dependency up automatically, add
`implementation(...)` to the `shadowJar` configuration; verify by checking
that `kotlinx/coroutines/` appears in the shaded jar.)

- [ ] **Step 2: Write the failing test**

Create `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/DebouncerTest.kt`:

```kotlin
package me.rohandacoder.lspskript

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test
    fun aBurstOfChangesProducesOneCall() = runTest {
        val fired = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val debouncer = Debouncer(200, TestScope(dispatcher)) { fired += it }

        debouncer.schedule("a")
        debouncer.schedule("a")
        debouncer.schedule("a")
        advanceTimeBy(199)
        assertEquals(0, fired.size)
        advanceUntilIdle()
        assertEquals(listOf("a"), fired)
    }

    @Test
    fun differentUrisAreIndependent() = runTest {
        val fired = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val debouncer = Debouncer(200, TestScope(dispatcher)) { fired += it }

        debouncer.schedule("a")
        debouncer.schedule("b")
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), fired.sorted())
    }

    @Test
    fun cancellingStopsTheCall() = runTest {
        val fired = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val debouncer = Debouncer(200, TestScope(dispatcher)) { fired += it }

        debouncer.schedule("a")
        debouncer.cancel("a")
        advanceUntilIdle()
        assertEquals(0, fired.size)
    }
}
```

Add the test dependency in `skript-lsp/build.gradle.kts`:

```kotlin
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `.\gradlew.bat :skript-lsp:test --tests '*DebouncerTest*' --console=plain`
Expected: FAIL — `Debouncer` does not exist.

- [ ] **Step 4: Implement `Debouncer`**

Create `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/Debouncer.kt`:

```kotlin
package me.rohandacoder.lspskript

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces a burst of changes into one call.
 *
 * Each URI has its own trailing timer, so a burst of keystrokes produces
 * exactly one call, measured from the **last** change. Scheduling a URI again
 * cancels the pending call for that URI and starts a new one.
 */
class Debouncer(
    private val delayMs: Long,
    private val scope: CoroutineScope,
    private val onFire: (String) -> Unit,
) {

    private val pending = mutableMapOf<String, Job>()

    /** Schedule [uri] to fire in [delayMs] milliseconds. */
    fun schedule(uri: String) {
        pending.remove(uri)?.cancel()
        pending[uri] = scope.launch {
            delay(delayMs)
            pending.remove(uri)
            onFire(uri)
        }
    }

    /** Cancel any pending call for [uri]. */
    fun cancel(uri: String) {
        pending.remove(uri)?.cancel()
    }

    /** Cancel every pending call. */
    fun cancelAll() {
        for (job in pending.values) job.cancel()
        pending.clear()
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `.\gradlew.bat :skript-lsp:test --tests '*DebouncerTest*' --console=plain`
Expected: PASS, 3 tests.

- [ ] **Step 6: Use it in `SkriptTextDocumentService`**

In `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptTextDocumentService.kt`:

- Add a field:

  ```kotlin
      private val debounceMs = config.getLong("parse-debounce-ms", 200L)
  ```

  (`SkriptTextDocumentService` currently has no config; pass the value in from
  `LspSkript` instead, or give the service a `parseDebounceMs` constructor
  parameter. Prefer the constructor parameter — the service should not reach
  for the plugin's config itself.)

- Replace `republishDiagnostics(uri, text)` with a debounced call:

  ```kotlin
      private val debouncer = Debouncer(debounceMs, scope) { uri -> publishDiagnostics(uri) }
  ```

  where `scope` is a `CoroutineScope` owned by the service and cancelled in
  `close()`.

- The publish step must be **supersession-safe**: keep a per-URI sequence
  number, and publish only if the sequence is still the latest.

  ```kotlin
      private val sequences = java.util.concurrent.ConcurrentHashMap<String, Long>()

      private fun publishDiagnostics(uri: String) {
          val text = documents[uri] ?: return
          val sequence = sequences.merge(uri, 1L, Long::plus) ?: 1L
          val snapshot = parseBridge.parse(docId(uri), uri, text, realFile(uri))
          if (sequences[uri] != sequence) return   // superseded while parsing
          client?.publishDiagnostics(PublishDiagnosticsParams(uri, snapshot.diagnostics))
      }
  ```

  The parse itself runs on the Bukkit main thread (see `LspUtils.onMainThread`),
  so the sequence check is what prevents a slow parse from publishing stale
  diagnostics over fresh ones.

- `didClose` must cancel the pending debounce and drop the sequence:

  ```kotlin
      debouncer.cancel(uri)
      sequences.remove(uri)
  ```

- `docId(uri)` must be **stable per URI** — the function namespace depends on
  it. A hash of the URI works; it must not change between parses.

- [ ] **Step 7: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 74 tests passing.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(lspskript): debounce diagnostics per URI and drop stale results"
```

---

### Task 10: Readiness gate and async-loader guard

**Files:**
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/LspSkript.kt`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptLanguageServerService.kt`

**Interfaces:**
- The server must not answer requests before Skript's syntax registry is
  populated, and must refuse to run at all when Skript's script loader is
  asynchronous.

- [ ] **Step 1: Add the readiness gate**

Skript finishes registering its syntaxes one tick after `onEnable` returns,
because `ExprArithmetic` registers inside `stopAcceptingRegistrations()`
(`Skript.java:1372`). Until then `Classes.getClassInfoFromUserInput` throws
`IllegalStateException("Cannot use classinfos until registration is over")`.

In `LspSkript.onEnable`, before starting the server, schedule a check on the
next tick:

```kotlin
        server = Bukkit.getScheduler().runTaskLater(
            this,
            Runnable {
                if (Skript.isAcceptRegistrations()) {
                    logger.warning("Skript is still accepting registrations; deferring the language server.")
                    // Re-check on the next tick until it is done.
                } else {
                    startServer(port, trace, parseOnly)
                }
            },
            1L,
        ).let { }
```

Re-check on a timer until `!Skript.isAcceptRegistrations()`, and only then
bind the LSP socket. The plugin's own readiness line — *"LspSkript listening on
port N"* — must only be logged once the socket is actually bound.

- [ ] **Step 2: Add the async-loader guard**

`ScriptLoader.isAsync()` is true when Skript's `script loader thread size` is
at least 1. The current code calls `ScriptLoader.loadScripts(...).join()` on
the main thread, which **deadlocks the server** under that configuration.

In `LspSkript.onEnable`:

```kotlin
        if (ch.njol.skript.ScriptLoader.isAsync()) {
            logger.severe(
                "Skript's script loader is running asynchronously (script loader thread size >= 1). " +
                    "LspSkript cannot run in this configuration, because the main thread would wait on a " +
                    "loader that cannot proceed. Set 'script loader thread size' to 0 in Skript's config.sk."
            )
            server = null
            return
        }
```

**Never** call `ScriptLoader.setAsyncLoaderSize(0)` to "fix" it. It is global
state, it is reverted by `/skript reloadconfig`, and its `size <= 0` branch
strands dead threads so a later `set(>=1)` never completes its futures.

- [ ] **Step 3: Fix the advertised capabilities**

Two of the advertised capabilities are lies, and a client will hit them.

**`completionProvider.resolveProvider` is `true`, but there is no
`completionItem/resolve` handler.** A client that believes the server can
resolve a completion item will call it and get a *method not found* error.
Either implement the handler or stop advertising it. There is nothing to
resolve today — the items are built complete — so stop advertising it:

```kotlin
        caps.setCompletionProvider(CompletionOptions(false, listOf("%", "{", "/")))
```

**`textDocumentSync` is a bare `TextDocumentSyncKind.Incremental`.** That
means only `didOpen`, `didChange` and `didClose` are delivered — `didSave` is
never called, so `SkriptTextDocumentService.didSave` is dead code. Either
advertise the save options and implement it, or delete the dead handler.
The parse-only path republishes diagnostics on every change, so `didSave` adds
nothing: **delete the dead handler** and leave the sync kind as it is.

`renameProvider.prepareProvider` is `true` and there *is* a `prepareRename`
handler, so that one is accurate — leave it.

- [ ] **Step 4: Build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 74 tests passing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(lspskript): wait for Skript's registry and refuse an async script loader"
```

---

### Task 11: Hover from the syntax's own documentation

**Files:**
- Modify: `skript-lsp/build.gradle.kts`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/HoverProvider.kt`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptTextDocumentService.kt`

**Interfaces:**
- Consumes: `DocumentSnapshot` (Task 6).
- Produces: hover content built from Skript's own documentation annotations.

**The content, in order:**

1. `@Name` — the syntax's display name.
2. `@Description` — the description, converted from HTML to Markdown.
3. The matched pattern, in a code span.
4. `@Example` — the examples.
5. For an **expression**: the return type's `ClassInfo` documentation.
6. For an **event**: `BukkitSyntaxInfos.Event.description()` and `.examples()`.
   Event classes carry **no** doc annotations — the documentation lives on the
   `SyntaxInfo`, not on the class.

**Where each piece comes from:**

| Piece | Source |
|---|---|
| The implementing class | `SyntaxInfo.type()` — public, returns `Class<E>` |
| `@Name` | `ch.njol.skript.doc.Name` — `value()` is the display name |
| `@Description` | `ch.njol.skript.doc.Description` — `value()` is `String[]` |
| `@Example` | `ch.njol.skript.doc.Example` — `value()` is `String[]` |
| `@Since` | `ch.njol.skript.doc.Since` — `value()` is `String[]` |
| Event documentation | `org.skriptlang.skript.bukkit.registration.BukkitSyntaxInfos.Event` — `description()` returns `SequencedCollection<String>`, `examples()` returns `Collection<String>` |
| The return type's documentation | `ch.njol.skript.registrations.Classes.getClassInfoFromUserInput(name)` returns a `ClassInfo`, whose `getDescription()` and `getCodedName()` are the documentation |

- [ ] **Step 1: Add the HTML-to-Markdown converter**

`@Description` contains HTML (`<a href="#ExprColored">`, `<b>`, `<code>`). jsoup
is the only robust option — MIT, 525 KB, zero runtime dependencies.

In `skript-lsp/build.gradle.kts`, inside `dependencies`:

```kotlin
    implementation("org.jsoup:jsoup:1.21.1")
```

Shade it, like the other runtime dependencies.

- [ ] **Step 2: Write the HTML conversion as a pure function**

Create `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/HtmlToMarkdown.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts the HTML in Skript's `@Description` annotations to Markdown.
 *
 * Skript's descriptions are HTML fragments: `<a href="#ExprColored">`, `<b>`,
 * `<code>`. A Markdown client renders them as literal tags unless they are
 * converted.
 */
object HtmlToMarkdown {

    /** Convert an HTML fragment to Markdown. */
    fun toMarkdown(html: String): String {
        val body = Jsoup.parseBodyFragment(html)
        val out = StringBuilder()
        for (child in body.body().childNodes()) {
            append(child, out)
        }
        return out.toString().trim()
    }

    private fun append(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> out.append(node.text())
            is Element -> {
                when (node.tagName().lowercase()) {
                    "b", "strong" -> out.append("**").append(text(node)).append("**")
                    "i", "em" -> out.append("_").append(text(node)).append("_")
                    "code" -> out.append("`").append(text(node)).append("`")
                    "a" -> {
                        val label = text(node)
                        val href = node.attr("href")
                        // Skript links to other syntaxes with a "#Syntax" fragment.
                        if (href.startsWith("#")) out.append("**").append(label).append("**")
                        else out.append("[").append(label).append("](").append(href).append(")")
                    }
                    "br" -> out.append('\n')
                    else -> {
                        for (child in node.childNodes()) append(child, out)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun text(element: Element): String {
        val out = StringBuilder()
        for (child in element.childNodes()) append(child, out)
        return out.toString()
    }
}
```

- [ ] **Step 3: Write the tests**

Create `skript-lsp/src/test/kotlin/me/rohandacoder/lspskript/HtmlToMarkdownTest.kt`:

```kotlin
package me.rohandacoder.lspskript

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlToMarkdownTest {

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("hello", HtmlToMarkdown.toMarkdown("hello"))
    }

    @Test
    fun boldBecomesMarkdown() {
        assertEquals("**bold**", HtmlToMarkdown.toMarkdown("<b>bold</b>"))
    }

    @Test
    fun italicBecomesMarkdown() {
        assertEquals("_italic_", HtmlToMarkdown.toMarkdown("<i>italic</i>"))
    }

    @Test
    fun codeBecomesACodeSpan() {
        assertEquals("`code`", HtmlToMarkdown.toMarkdown("<code>code</code>"))
    }

    @Test
    fun aSyntaxLinkBecomesBold() {
        assertEquals("**the name**", HtmlToMarkdown.toMarkdown("<a href=\"#ExprName\">the name</a>"))
    }

    @Test
    fun anExternalLinkKeepsItsHref() {
        assertEquals("[text](https://example.com)", HtmlToMarkdown.toMarkdown("<a href=\"https://example.com\">text</a>"))
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail, then pass**

Run: `.\gradlew.bat :skript-lsp:test --tests '*HtmlToMarkdownTest*' --console=plain`
Expected: FAIL, then PASS, 6 tests.

- [ ] **Step 5: Rewrite `HoverProvider`**

Replace `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/HoverProvider.kt` so that:

- The signature becomes `fun hover(snapshot: DocumentSnapshot, position: Position): Hover?`.
- The word under the cursor is read from `snapshot.buffer`, not from a raw
  string — `TextBuffer.lineText(line)` and a word range.
- The matching uses `SyntaxInfo.type()` to find the documentation, and the
  matched pattern is shown in a code span.
- Events are matched through `BukkitSyntaxInfos.Event.KEY`, not through
  `SyntaxRegistry.STRUCTURE` — events are not structures.

The lookup of the syntax registry stays where it is
(`SyntaxRegistryAccess.registry()`), but the *documentation* comes from the
syntax info, not from a text match against the pattern.

- [ ] **Step 6: Wire it to the snapshot**

In `SkriptTextDocumentService.hover`, use the current snapshot rather than the
raw text. The service must keep the latest snapshot per URI — add a
`ConcurrentHashMap<String, DocumentSnapshot>` populated by the publish step in
Task 9, and read it in every feature request.

- [ ] **Step 7: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 80 tests passing.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(lspskript): render hover documentation from Skript's own annotations"
```

---

### Task 12: Completion from a precomputed table

**Files:**
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/CompletionProvider.kt`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptTextDocumentService.kt`

**Interfaces:**
- Consumes: `DocumentSnapshot` (Task 6).

**Four changes, in order of value:**

1. **A precomputed table per registry key.** Today every completion request
   re-walks the registry, calls `cleanPattern()` and `lowercase()` on every
   pattern, and takes a fresh registry snapshot. Build the table once — at
   snapshot time — as `(lowercased cleaned pattern, original label, SyntaxInfo)`
   per registry key, and match against it.

2. **Include `BukkitSyntaxInfos.Event.KEY`.** Events are **not** under
   `SyntaxRegistry.STRUCTURE`; they live under their own key. Without this,
   `on <tab>` offers no events.

3. **Cap the result and return `CompletionList(isIncomplete = true)`.** Today an
   empty prefix can emit roughly four thousand items, which stalls the client.
   Cap at a few hundred and mark the list incomplete so the client asks again
   as the user types.

4. **Section-entry completion.** When the cursor is inside a section that has
   an entry validator — `command /x:` — offer the valid keys, from
   `entryValidator().getEntryData()`.

- [ ] **Step 1: Change the signature**

`CompletionProvider.complete(...)` becomes:

```kotlin
    fun complete(
        snapshot: DocumentSnapshot,
        position: Position,
        variableNames: Collection<String> = emptyList(),
        functionNames: Collection<String> = emptyList(),
        commandNames: Collection<String> = emptyList(),
    ): CompletionList
```

It returns a `CompletionList`, not a `List<CompletionItem>`, so it can carry
`isIncomplete`.

- [ ] **Step 2: Build the pattern table**

A private data class and a per-key cache:

```kotlin
    /**
     * One completion candidate: the pattern as it will be shown, and the
     * lowercased form used for matching.
     */
    private data class Candidate(
        val label: String,
        val matchText: String,
        val info: SyntaxInfo<*>,
    )
```

Build it once per registry key, lazily, and cache it. The registry does not
change while the server is running, so the cache never needs invalidating —
unless Skript is reloaded, in which case the plugin reloads with it.

- [ ] **Step 3: Add the event key**

Include `BukkitSyntaxInfos.Event.KEY` in the set of registry keys the table is
built from, and label those items `CompletionItemKind.Event`.

- [ ] **Step 4: Cap the result**

```kotlin
    /** The most items to return in one completion response. */
    private const val MAX_ITEMS = 300
```

When the result is truncated, return `CompletionList(items, true)`.

- [ ] **Step 5: Add section-entry completion**

When the cursor is inside a section whose header starts with `command`, offer
the entry keys. The live command structure is
`org.skriptlang.skript.bukkit.command.elements.structures.StructCommand`, and
`SubCommandEntryData.SUB_COMMAND_VALIDATOR` is a public `EntryValidator` whose
`getEntryData()` yields the valid keys.

- [ ] **Step 6: Wire it to the snapshot**

In `SkriptTextDocumentService.completion`, read the current snapshot and pass
it in. Return the `CompletionList` directly:

```kotlin
            val items = completionProvider.complete(snapshot, params.position, ...)
            Either.forRight(items)
```

- [ ] **Step 7: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 80 tests passing.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "perf(lspskript): complete from a precomputed table and cap the result"
```

---

### Task 13: Document symbols from the snapshot

**Files:**
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/DocumentSymbolProvider.kt`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptTextDocumentService.kt`

**Interfaces:**
- Consumes: `DocumentSnapshot` (Task 6).

**The scope, stated plainly:** the symbols come from the snapshot's
`sections`, which the engine derives from the config tree it already built.
The design spec's ambition — symbols from the AST, so the outline matches
exactly what Skript parsed — is **deferred**: Skript's public API exposes no
way to walk the parsed structures, so there is nothing to walk. The config
tree is the closest faithful approximation, and it is what the current
provider already uses.

- [ ] **Step 1: Change the signature**

```kotlin
    fun documentSymbols(snapshot: DocumentSnapshot): List<Either<SymbolInformation, DocumentSymbol>>
```

- [ ] **Step 2: Build the tree**

Walk `snapshot.sections` and turn each `SectionInfo` into a `DocumentSymbol`:

- `name` — the section header, with the trailing `:` removed.
- `kind` — the existing `kindOf(key)` mapping, which is already text-based.
- `range` — the header line, full width.
- `selectionRange` — the header line, full width.
- `children` — the section's child sections.

Every range's end must be the **real** end of the line, taken from
`snapshot.buffer.lineEnd(line)` — not `Int.MAX_VALUE`.

- [ ] **Step 3: Wire it to the snapshot**

In `SkriptTextDocumentService.documentSymbol`, read the current snapshot.

- [ ] **Step 4: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 80 tests passing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(lspskript): build document symbols from the snapshot"
```

---

### Task 14: Index per document, and prune on close

**Files:**
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/DefinitionReferenceProvider.kt`
- Modify: `skript-lsp/src/main/kotlin/me/rohandacoder/lspskript/SkriptTextDocumentService.kt`

**Interfaces:**
- Consumes: `DocumentSnapshot` (Task 6).

**The problem:** `DefinitionReferenceProvider.index(uri, text)` re-scans
**every open document** on every change, so the cost grows with the number of
open files. Closing a file does not remove its entries.

- [ ] **Step 1: Index per document**

Replace the single snapshot with a map:

```kotlin
    private val byUri = ConcurrentHashMap<String, DocumentIndex>()

    /** Index [snapshot]'s document, replacing any previous index for its URI. */
    fun index(snapshot: DocumentSnapshot) {
        byUri[snapshot.uri] = DocumentIndex(snapshot)
    }

    /** Drop the index for [uri]. */
    fun remove(uri: String) {
        byUri.remove(uri)
    }
```

`DocumentIndex` holds that document's variables, functions and commands. The
queries — `variableNames()`, `functionNames()`, `commandNames()` — become
lookups across the map's values rather than over one merged snapshot.

- [ ] **Step 2: Index from the snapshot, not from a text scan**

The engine already walked the config tree, so the variables, functions and
commands are in `snapshot.sections` and `snapshot.statements`. Use them.

If a construct cannot be recovered from the snapshot, keep the existing regex
scan **for that one document** — but it must run over that document only, not
over every open document.

- [ ] **Step 3: Prune on close**

In `SkriptTextDocumentService.didClose`:

```kotlin
        definitionReferenceProvider.remove(uri)
```

- [ ] **Step 4: Run the whole build**

Run: `.\gradlew.bat :skript-lsp:build --console=plain`
Expected: BUILD SUCCESSFUL, 80 tests passing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "perf(lspskript): index definitions per document and prune on close"
```

---

## Done

All fourteen tasks are complete when:

- `.\gradlew.bat :skript-lsp:build --console=plain` is green
- `.\gradlew.bat :skript-lsp:check --console=plain` is green
- `grep -rlE "^import (ch\.njol\.skript|org\.skriptlang\.skript)\." skript-lsp/src/main/kotlin/`
  lists exactly one file, the parse engine
- `grep -rn "postLoad" skript-lsp/src/main/kotlin/` finds nothing
- The differential comparison on the development server shows the same
  diagnostics as the pre-rewrite build, and the `on load:` probe file is not
  created



---
