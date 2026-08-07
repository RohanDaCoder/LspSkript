package me.rohandacoder.lspskript

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger
import java.util.regex.Pattern

/**
 * Checks the latest published GitHub release on plugin load and logs a console
 * notice when a newer version exists.
 *
 * The check runs asynchronously over [java.net.http.HttpClient], so plugin
 * enable is never blocked. Any failure (offline server, rate limit, parse
 * trouble) is silent: a server without network simply gets no notification.
 */
class UpdateNotifier(
    private val logger: Logger,
    private val currentVersion: String,
    private val repo: String = "RohanDaCoder/LspSkript",
) {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** Fires the version check and returns immediately. */
    fun checkAsync() {
        val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$repo/releases/latest"))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "LspSkript/$currentVersion")
            .GET()
            .build()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, error ->
                val latest = if (error == null && response.statusCode() == 200) {
                    parseTagName(response.body())
                } else {
                    null
                }
                if (latest != null && isNewer(latest, currentVersion)) {
                    logger.info("A new version of LspSkript is available: $latest (you are running $currentVersion).")
                    logger.info("Download it here: https://github.com/$repo/releases")
                }
            }
    }

    /** Extracts the release tag (`vX.Y.Z`) from the GitHub API JSON body. */
    internal fun parseTagName(body: String): String? {
        val m = tagNamePattern.matcher(body)
        return if (m.find()) m.group(1) else null
    }

    /** True when [latest] is a strictly higher semver triple than [current]. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        return compareVersions(l, c) > 0
    }

    private fun parseVersion(v: String): Triple<Int, Int, Int>? {
        val m = versionPattern.matcher(v)
        if (!m.find()) return null
        return Triple(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt())
    }

    private fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int =
        compareValuesBy(a, b, Triple<Int, Int, Int>::first, Triple<Int, Int, Int>::second, Triple<Int, Int, Int>::third)

    private companion object {
        val tagNamePattern: Pattern = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
        val versionPattern: Pattern = Pattern.compile("""(\d+)\.(\d+)\.(\d+)""")
    }
}
