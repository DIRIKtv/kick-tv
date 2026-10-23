package com.diriktv.sidekick.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.diriktv.sidekick.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Checks GitHub Releases for a newer APK and hands it to Android's package installer. */
object Updater {
    const val REPO = "DIRIKtv/sidekick"
    private const val DAILY_MS = 24 * 60 * 60 * 1000L
    /** Even in "every open" mode, don't ask GitHub again within this long (GitHub rate limits by IP). */
    private const val MIN_GAP_MS = 2 * 60 * 1000L

    /** How often installed apps look for a new release on their own. */
    enum class Schedule(val key: String, val label: String, val sub: String?) {
        OPEN("open", "Every time the app opens", "Recommended"),
        DAILY("daily", "Once a day", null),
        OFF("off", "Off", "Only when you check in Settings");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OPEN
        }
    }

    /** Set when the app comes to the foreground; the main screen runs the check and clears it. */
    @Volatile var openedPending = true

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    /** The latest release if it's newer than this build, else null. Throws on network errors. */
    suspend fun checkNow(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        KickApi.http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null // no releases yet
            if (!resp.isSuccessful) throw IOException("GitHub returned ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            val version = o.optString("tag_name").removePrefix("v")
            val assets = o.optJSONArray("assets")
            var apk: JSONObject? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a; break }
            }
            if (apk == null || !isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
            Release(version, o.optString("body").trim(), apk.getString("browser_download_url"), apk.optLong("size"))
        }
    }

    /**
     * The automatic check when the app opens, following the viewer's schedule (every open, daily
     * or off), skipping a version they chose to skip.
     */
    suspend fun checkIfDue(context: Context): Release? {
        val sp = context.getSharedPreferences("sidekick", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val gap = when (Prefs(context).updateSchedule) {
            Schedule.OFF -> return null
            Schedule.DAILY -> DAILY_MS
            Schedule.OPEN -> MIN_GAP_MS
        }
        if (now - sp.getLong("update_checked", 0) < gap) return null
        val release = runCatching { checkNow() }.getOrNull()
        sp.edit().putLong("update_checked", now).apply()
        return release?.takeIf { it.version != sp.getString("update_skipped", null) }
    }

    fun skip(context: Context, version: String) {
        context.getSharedPreferences("sidekick", Context.MODE_PRIVATE).edit().putString("update_skipped", version).apply()
    }

    /** Downloads the APK, reporting progress 0–100. */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "Sidekick-${release.version}.apk")
        KickApi.http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; withContext(Dispatchers.Main) { onProgress(pct) } }
                    }
                }
            }
        }
        file
    }

    /** Whether Android will let this app open the installer (Android 8+ asks per app). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the viewer allows this app to install updates. */
    fun openInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            true
        } catch (e: ActivityNotFoundException) {
            false // Fire OS hides this screen; the viewer enables it under Developer Options instead
        }
    }

    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    /** Compares dotted versions numerically: 1.10.0 > 1.9.2. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
