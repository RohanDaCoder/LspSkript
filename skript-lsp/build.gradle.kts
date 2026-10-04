import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.regex.Pattern

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.5"
    id("io.github.rohandacoder.gradle-release") version "0.1.0"
}

group = "me.rohandacoder"
version = project.property("skriptLspVersion") as String
description = "Language Server Protocol backend for the Skript language, reusing the real Skript parser."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    // Skript releases
    maven { url = uri("https://repo.skriptlang.org/releases") }
    // Paper / Spigot APIs (needed to compile against Bukkit types)
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}

dependencies {
    // The real Skript runtime (prebuilt). Provides all public parser/metadata APIs.
    // compileOnly because Skript is present at runtime in the server's plugins/ folder.
    compileOnly("com.github.SkriptLang:Skript:2.16.0")
    // Bukkit/Spigot/Paper API for type references (also provided at runtime).
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // LSP4J: the Language Server Protocol implementation.
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

// Produce a runnable plugin jar (LspSkript.jar) with LSP4J and the Kotlin
// stdlib shaded in, so the plugin is self-contained at runtime.
// Skript and the Paper API are compileOnly and are provided by the server.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("LspSkript")
    archiveVersion.set(providers.gradleProperty("skriptLspVersion"))
    archiveClassifier.set("")
    manifest {
        attributes(
            "Name" to "me/rohandacoder/lspskript",
            "Automatic-Module-Name" to "me.rohandacoder.lspskript"
        )
    }
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// `build` should produce the shaded plugin jar.
tasks.named("build") { dependsOn("shadowJar") }
tasks.named<org.gradle.jvm.tasks.Jar>("jar") { enabled = false }

tasks.named<ProcessResources>("processResources") {
    filesMatching("paper-plugin.yml") {
        expand("version" to providers.gradleProperty("skriptLspVersion").get())
    }
}

// ---------------------------------------------------------------------------
// Release configuration — uses the io.github.rohandacoder.gradle-release plugin.
//
//   ./gradlew :skript-lsp:release                 # patch bump
//   ./gradlew :skript-lsp:release -Ppart=minor
//   ./gradlew :skript-lsp:release -Ppart=major
//   ./gradlew :skript-lsp:release "-PreleaseVersion=1.2.3"   # explicit version
//   ./gradlew :skript-lsp:release -PdryRun       # compute + build, no commit/tag/gh
//   ./gradlew :skript-lsp:printNextVersion -Ppart=minor
//   ./gradlew :skript-lsp:printReleaseNotes "-Ptag=v0.1.5" ["-PprevTag=v0.1.4"]
//
// NOTE: quote -P arguments in PowerShell so `=` is passed through verbatim.
//
// `buildTask` is auto-detected as `shadowJar` because this module applies the
// Shadow plugin; `repoUrl` is read from the `origin` git remote.
// ---------------------------------------------------------------------------

/** `Skript:<version>` from the compileOnly dependency below. */
val skriptDependencyVersion: Pattern = Pattern.compile("""SkriptLang:Skript:([\d.]+)""")

/** `api-version:` from paper-plugin.yml, i.e. the MC version the plugin targets. */
val paperApiVersion: Pattern = Pattern.compile("""api-version:\s*['"]?([\d.]+)['"]?""")

fun patternGroup(rx: Pattern, text: String): String =
    rx.matcher(text).let { if (it.find()) it.group(1) else "unknown" }

release {
    versionKey.set("skriptLspVersion")
    projectName.set("LspSkript")
    jarBaseName.set("LspSkript")
    // shadowJar sets archiveClassifier to "" so the released artifact is
    // LspSkript-<version>.jar, with no `-all` suffix.
    jarClassifier.set("")

    notes.set { ctx ->
        val repoUrl = repoUrl.get()
        val skriptVer = patternGroup(skriptDependencyVersion, file("build.gradle.kts").readText())
        val mcVer = patternGroup(paperApiVersion, file("src/main/resources/paper-plugin.yml").readText())
        val name = projectName.get()
        val pluginVer = ctx.tag.removePrefix("v")
        buildString {
            appendLine("## $name $pluginVer")
            appendLine()
            appendLine("**Plugin:** $name $pluginVer")
            appendLine("**Paper API:** $mcVer")
            appendLine("**Skript:** $skriptVer")
            appendLine()
            appendLine("### Changes since ${ctx.prevTag ?: "the beginning"}")
            appendLine()
            if (ctx.commits.isEmpty()) {
                appendLine("_No commits since the last release._")
            } else {
                for (subject in ctx.commits) {
                    appendLine("- $subject")
                }
            }
            if (ctx.prevTag != null && repoUrl.isNotBlank()) {
                appendLine()
                appendLine("[Changes since ${ctx.prevTag}]($repoUrl/compare/${ctx.prevTag}...${ctx.tag})")
            }
        }.trimEnd()
    }
}
