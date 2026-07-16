import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

plugins {
    kotlin("jvm") version "2.3.0"
    `maven-publish`
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
        expand("version" to project.version)
    }
}

// ---------------------------------------------------------------------------
// release task — a bumpp-style flow for the skript-lsp module.
//
//   ./gradlew :skript-lsp:release                 # patch bump
//   ./gradlew :skript-lsp:release -Ppart=minor
//   ./gradlew :skript-lsp:release -Ppart=major
//   ./gradlew :skript-lsp:release -Pversion=1.2.3 # explicit version
//   ./gradlew :skript-lsp:release -PdryRun        # compute + build, no commit/tag/gh
// ---------------------------------------------------------------------------

interface InjectedExecOps {
    @get:javax.inject.Inject
    val execOps: ExecOperations
}

val execOps = objects.newInstance<InjectedExecOps>().execOps

val versionKey = "skriptLspVersion"
val gradlePropsFile = rootProject.file("gradle.properties")
val currentVersion: String = project.property("skriptLspVersion") as String

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

val nextVersion: String = if (project.hasProperty("releaseVersion")) {
    (project.property("releaseVersion") as String).also { parseVersion(it) }
} else {
    bump(currentVersion, project.findProperty("part") as? String ?: "patch")
}

val dryRun: Boolean = project.hasProperty("dryRun")

tasks.register("printNextVersion") {
    group = "release"
    description = "Print the version a release would produce."
    doLast { println(nextVersion) }
}

tasks.register("release") {
    group = "release"
    description = "Bump version, build the shadowJar, tag, and create a GitHub release via gh."
    dependsOn("shadowJar")

    doLast {
        val tag = "v$nextVersion"
        val jar = tasks.named("shadowJar").get().outputs.files.singleFile
        require(jar.exists()) { "Shadow jar not found at $jar" }

        if (dryRun) {
            println("[dryRun] Would release $tag and publish GitHub release with ${jar.name}.")
            return@doLast
        }

        // 1. Persist the new version into gradle.properties.
        val propsText = gradlePropsFile.readText()
        val updated = Pattern.compile("""(?m)^$versionKey=.*$""")
            .matcher(propsText)
            .replaceFirst("$versionKey=$nextVersion")
        check(updated.contains("$versionKey=$nextVersion")) {
            "Failed to write $versionKey into gradle.properties"
        }
        gradlePropsFile.writeText(updated)

        // 2. Commit + tag.
        execOps.exec { commandLine("git", "add", gradlePropsFile.path) }
        execOps.exec { commandLine("git", "commit", "-m", "chore: release $tag") }
        execOps.exec { commandLine("git", "tag", "-a", tag, "-m", "Release $tag") }

        // 3. Create the GitHub release and upload the jar.
        execOps.exec {
            commandLine(
                "gh", "release", "create", tag,
                "--title", tag,
                "--generate-notes",
                jar.path
            )
        }

        println("Released $tag and published GitHub release with ${jar.name}.")
    }
}
