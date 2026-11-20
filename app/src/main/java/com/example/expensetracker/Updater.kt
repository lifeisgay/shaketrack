package com.example.expensetracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases. Every CI build publishes a release "build-<N>" with
 * ShakeTrack.apk, and N is also the app's versionCode. The app compares, downloads the APK
 * into its cache and hands it to Android's installer (the user confirms "Update").
 * The APK is signed with the same key every time, so it installs over the current app.
 */
object Updater {

    data class Release(val build: Int, val apkUrl: String, val notes: String)

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun currentBuild(c: Context): Int = try {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) { 0 }

    /** Latest published release, or null if there's none or GitHub can't be reached. */
    fun latest(c: Context): Release? = try {
        val repo = c.getString(R.string.update_repo).trim()
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) null else {
                val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val build = j.optString("tag_name").substringAfter("build-").toIntOrNull()
                val assets = j.optJSONArray("assets")
                var url: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                if (build == null || url == null) null else Release(build, url, j.optString("body"))
            }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

    /**
     * On app start: if a newer build exists and the user hasn't said "Later" to it, offer it.
     * [manual] (from Settings) always shows the result, including "you're up to date".
     */
    fun check(activity: Activity, scope: CoroutineScope, manual: Boolean = false) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { latest(activity) }
            if (activity.isFinishing) return@launch
            val current = currentBuild(activity)
            when {
                r == null -> if (manual) toast(activity, "Couldn't check for updates. Try again later.")
                r.build <= current -> if (manual) toast(activity, "You're on the latest version (build $current).")
                !manual && prefs(activity).getInt("skipBuild", 0) >= r.build -> Unit
                else -> offer(activity, scope, r, current)
            }
        }
    }

    /** An update waiting for the "install apps" permission. */
    private var pending: Release? = null

    /** Call from onResume: finishes an update the user just granted the permission for. */
    fun resume(activity: Activity, scope: CoroutineScope) {
        val r = pending ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        pending = null
        download(activity, scope, r)
    }

    private fun offer(activity: Activity, scope: CoroutineScope, r: Release, current: Int) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Build ${r.build} is ready (you have build $current).\n\n${r.notes.lineSequence().firstOrNull().orEmpty()}")
            .setPositiveButton("Update") { _, _ -> download(activity, scope, r) }
            .setNegativeButton("Later") { _, _ -> prefs(activity).edit().putInt("skipBuild", r.build).apply() }
            .show()
    }

    private fun download(activity: Activity, scope: CoroutineScope, r: Release) {
        // Android only lets an app start an install once the user allows it ("Install unknown apps")
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            pending = r // continued by resume() when the user comes back with the permission
            AlertDialog.Builder(activity)
                .setTitle("One-time permission")
                .setMessage("To install updates, allow ShakeTrack to install apps, then come back. The update continues by itself.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { text = "Starting…"; setTextColor(Ui.MUTED) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(activity, 24)
            setPadding(p, p / 2, p, 0)
            addView(bar)
            addView(label)
        }
        val dlg = AlertDialog.Builder(activity).setTitle("Downloading build ${r.build}").setView(box).setCancelable(false).show()

        scope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, "ShakeTrack-${r.build}.apk")
                    val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                    }
                    try {
                        val total = conn.contentLength.toLong()
                        conn.inputStream.use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        withContext(Dispatchers.Main) {
                                            bar.progress = pct
                                            label.text = "$pct%  ·  ${done / 1024 / 1024} of ${total / 1024 / 1024} MB"
                                        }
                                    }
                                }
                            }
                        }
                    } finally { conn.disconnect() }
                    out
                } catch (_: Exception) { null }
            }
            dlg.dismiss()
            if (file == null) { toast(activity, "Download failed. Check your internet and try again."); return@launch }
            install(activity, file)
        }
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            toast(activity, "Couldn't open the installer.")
        }
    }

    private fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_LONG).show()
}
