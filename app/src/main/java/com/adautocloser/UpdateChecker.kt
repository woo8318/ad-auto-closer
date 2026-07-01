package com.adautocloser

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of a successful "newer version available" check. */
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String
)

/**
 * Checks the project's GitHub Releases for a version newer than the installed one.
 * Uses the public "latest release" API (no auth needed for public repos).
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Runs the check off the main thread and delivers the result back on it. */
    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        Thread {
            val info = try {
                fetchLatest()
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                null
            }
            mainHandler.post { onResult(info) }
        }.start()
    }

    private fun fetchLatest(): UpdateInfo? {
        val api = "https://api.github.com/repos/" +
            "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

        val conn = (URL(api).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", BuildConfig.GITHUB_REPO)
        }

        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                return null
            }

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val latest = json.getString("tag_name").removePrefix("v")
            if (!isNewer(latest, BuildConfig.VERSION_NAME)) return null

            val apkUrl = findApkAsset(json) ?: return null
            return UpdateInfo(latest, apkUrl, json.optString("body", ""))
        } finally {
            conn.disconnect()
        }
    }

    private fun findApkAsset(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.getString("browser_download_url")
            }
        }
        return null
    }

    /** True if dotted version [a] is strictly greater than [b] (e.g. "0.2.0" > "0.1.3"). */
    private fun isNewer(a: String, b: String): Boolean {
        val pa = a.split(".")
        val pb = b.split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }
}
