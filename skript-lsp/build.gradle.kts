import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.5"
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
// release task — a bumpp-style flow for the skript-lsp module.
//
//   ./gradlew :skript-lsp:release                 # patch bump
//   ./gradlew :skript-lsp:release -Ppart=minor
//   ./gradlew :skript-lsp:release -Ppart=major
//   ./gradlew :skript-lsp:release -PreleaseVersion=1.2.3  # explicit version
//   ./gradlew :skript-lsp:release -PdryRun        # compute + build, no commit/tag/gh
// ---------------------------------------------------------------------------

interface InjectedExecOps {
    @get:javax.inject.Inject
    val execOps: ExecOperations
}

val execOps = objects.newInstance<InjectedExecOps>().execOps

val versionKey = "skriptLspVersion"
val gradlePropsFile = rootProject.file("gradle.properties")

fun readVersion(): String {
    val m = Pattern.compile("""(?m)^$versionKey=(.+)""").matcher(gradlePropsFile.readText())
    require(m.find()) { "$versionKey not found in gradle.properties" }
    return m.group(1).trim()
}

fun parseVersion(v: String): Triple<Int, Int, Int> {
    val m = Pattern.compile("""(\d+)\.(\d+)\.(\d+)""").matcher(v)
    require(m.matches()) { "Version '$v' is not in semver (X.Y.Z) form." }
    return Triple(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt())
}

fun bump(v: String, part: String): String {
    val (maj, min, pat) = parseVersion(v)
    return when (part.lowercase()) {
        "major" -> "${maj + 1}.0.0"
        "minor" -> "$maj.${min + 1}.0"
        "patch" -> "$maj.$min.${pat + 1}"
        else -> error("Unknown bump part '$part' (use major|minor|patch)")
    }
}

fun nextVersionFromProps(): String = if (project.hasProperty("releaseVersion")) {
    (project.property("releaseVersion") as String).also { parseVersion(it) }
} else {
    bump(readVersion(), project.findProperty("part") as? String ?: "patch")
}

val dryRun: Boolean = project.hasProperty("dryRun")

tasks.register("printNextVersion") {
    group = "release"
    description = "Print the version a release would produce."
    doLast { println(nextVersionFromProps()) }
}

tasks.register("bumpVersion") {
    group = "release"
    description = "Write the next version into gradle.properties (no build/tag/gh)."
    doLast {
        val next = nextVersionFromProps()
        writeVersion(next)
        println("Version set to $next")
    }
}

fun writeVersion(next: String) {
    val propsText = gradlePropsFile.readText()
    val updated = Pattern.compile("""(?m)^$versionKey=.*$""")
        .matcher(propsText)
        .replaceFirst("$versionKey=$next")
    check(updated.contains("$versionKey=$next")) {
        "Failed to write $versionKey into gradle.properties"
    }
    gradlePropsFile.writeText(updated)
}

// --- Release notes helpers -------------------------------------------------

val skriptVersion: String =
    run {
        val m = Pattern.compile("""Skript:([\d.]+)""").matcher(file("build.gradle.kts").readText())
        if (m.find()) m.group(1) else "unknown"
    }

val minecraftVersion: String =
    run {
        val yml = file("src/main/resources/paper-plugin.yml").readText()
        val m = Pattern.compile("""api-version:\s*['"]?([\d.]+)['"]?""").matcher(yml)
        if (m.find()) m.group(1) else "unknown"
    }

val pluginName: String =
    run {
        val yml = file("src/main/resources/paper-plugin.yml").readText()
        val m = Pattern.compile("""(?m)^name:\s*(.+)$""").matcher(yml)
        if (m.find()) m.group(1).trim() else "unknown"
    }

val pluginMainClass: String =
    run {
        val yml = file("src/main/resources/paper-plugin.yml").readText()
        val m = Pattern.compile("""(?m)^main:\s*(.+)$""").matcher(yml)
        if (m.find()) m.group(1).trim() else "unknown"
    }

fun lastReleaseTag(): String? {
    val out = ByteArrayOutputStream()
    execOps.exec {
        commandLine("git", "tag", "--list", "v*.*.*", "--sort=-v:refname")
        standardOutput = out
    }
    return out.toString().trim().lineSequence().firstOrNull()
}

fun commitsSince(tag: String?): List<String> {
    val range = if (tag != null) "$tag..HEAD" else "HEAD"
    val out = ByteArrayOutputStream()
    execOps.exec {
        commandLine("git", "log", "--pretty=format:%s", range)
        standardOutput = out
    }
    return out.toString().trim().lineSequence()
        .filter { it.isNotBlank() }
        .toList()
}

fun buildReleaseNotes(tag: String): String {
    val prev = lastReleaseTag()?.takeIf { it != tag }
    val commits = commitsSince(prev)
    val repoUrl = "https://github.com/RohanDaCoder/LspSkript"
    val sb = StringBuilder()
    sb.appendLine("## LspSkript $tag")
    sb.appendLine()
    sb.appendLine("**Plugin:** $pluginName ${tag.removePrefix("v")}")
    sb.appendLine("**Main class:** $pluginMainClass")
    sb.appendLine("**Paper API:** $minecraftVersion")
    sb.appendLine("**Skript:** $skriptVersion (required, loaded before the plugin)")
    sb.appendLine()
    sb.appendLine("### Changes since ${prev ?: "the beginning"}")
    sb.appendLine()
    if (commits.isEmpty()) {
        sb.appendLine("_No commits since the last release._")
    } else {
        for (subject in commits) {
            sb.appendLine("- $subject")
        }
    }
    if (prev != null) {
        sb.appendLine()
        sb.appendLine("[Compare $prev...$tag]($repoUrl/compare/$prev...$tag)")
    }
    return sb.toString().trimEnd()
}

tasks.register("release") {
    group = "release"
    description = "Bump version, build the shadowJar, tag, and create a GitHub release via gh."

    doFirst {
        // Bump the version first so the subsequently-built jar carries it.
        if (!dryRun) {
            writeVersion(nextVersionFromProps())
        }
    }

    doLast {
        // Dry-run preview targets the *next* version so the notes show exactly
        // what a real release would contain (commits since the last tag).
        val tag = if (dryRun) "v${nextVersionFromProps()}" else "v${readVersion()}"

        if (dryRun) {
            println("[dryRun] Would release $tag and build LspSkript-$tag.jar, then tag + gh release.")
            println("---- release notes preview ----")
            println(buildReleaseNotes(tag))
            println("-------------------------------")
            return@doLast
        }

        // Build the jar AFTER the version bump (nested invocation so the
        // version written above is picked up by shadowJar's archive version).
        val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        execOps.exec {
            commandLine(
                if (isWindows) rootProject.projectDir.resolve("gradlew.bat").path else "./gradlew",
                ":skript-lsp:shadowJar",
                "--no-daemon"
            )
        }

        val jar = layout.buildDirectory.file("libs/LspSkript-${readVersion()}.jar").get().asFile
        require(jar.exists()) { "Shadow jar not found at $jar" }

        // Commit + tag, then push so the tag is available for the GitHub release.
        execOps.exec { commandLine("git", "add", gradlePropsFile.path) }
        execOps.exec { commandLine("git", "commit", "-m", "chore: release $tag") }
        execOps.exec { commandLine("git", "tag", "-a", tag, "-m", "Release $tag") }
        execOps.exec { commandLine("git", "push", "origin", "HEAD", "--tags") }

        // Create the GitHub release and upload the jar.
        val notes = buildReleaseNotes(tag)
        execOps.exec {
            commandLine(
                "gh", "release", "create", tag,
                "--title", tag,
                "--notes", notes,
                jar.path
            )
        }

        println("Released $tag and published GitHub release with ${jar.name}.")
    }
}
