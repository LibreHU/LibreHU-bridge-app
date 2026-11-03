package org.librehu.bridge.data

import org.librehu.bridge.BuildConfig
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * This app's newest build on GitHub: `release.txt` of the CI's rolling prerelease `latest-<branch>` (versionCode =
 * number of commits, as in app/build.gradle.kts). Read as a release download, not through the GitHub API: no limit of
 * requests per hour.
 */
object AppUpdate {
    private const val REPO = "https://github.com/LibreHU/LibreHU-bridge-app"

    class Release(
        val versionCode: Long,
        val versionName: String,
        /** The APK, opened in the browser to download it. */
        val apkUrl: String,
    ) {
        val newer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
    }

    /** The release of this build's edition (branch), or main's when that branch publishes none. Network: off the main thread. */
    fun latest(): Release {
        var missing: IOException? = null
        for (branch in listOf(BuildConfig.BRANCH, "main").distinct()) {
            try {
                return read(branch.replace('/', '-'))
            } catch (e: FileNotFoundException) {
                missing = e
            }
        }
        throw missing ?: IOException("no release")
    }

    private fun read(slug: String): Release {
        val base = "$REPO/releases/download/latest-$slug"
        val c = URL("$base/release.txt").openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.useCaches = false
        try {
            if (c.responseCode == HttpURLConnection.HTTP_NOT_FOUND) throw FileNotFoundException("latest-$slug")
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            // key=value lines, then a blank line and the notes (last commit's title).
            val kv =
                c.inputStream
                    .bufferedReader()
                    .readText()
                    .lineSequence()
                    .takeWhile { it.isNotBlank() }
                    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0].trim() to it[1].trim() }
            val code = kv["versionCode"]?.toLongOrNull() ?: throw IOException("release.txt without versionCode")
            return Release(code, kv["versionName"].orEmpty(), "$base/${kv["apk"] ?: "LibreHU-Bridge-$slug.apk"}")
        } finally {
            c.disconnect()
        }
    }

    private const val TIMEOUT_MS = 15_000
}
