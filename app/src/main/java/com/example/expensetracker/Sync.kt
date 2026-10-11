package com.example.expensetracker

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Pulls categories, payment types, and users from the sheet's Lists tab. */
object Sync {
    fun run(ctx: Context) {
        val app = ctx.applicationContext
        thread {
            try {
                val base = Prefs.url(app)
                if (base.isEmpty()) return@thread
                val sep = if (base.contains("?")) "&" else "?"
                val u = URL(base + sep + "secret=" + URLEncoder.encode(Prefs.secret(app), "UTF-8"))
                val conn = u.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.instanceFollowRedirects = true
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val j = JSONObject(text)
                fun names(key: String): List<String> {
                    val a = j.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() }
                }
                val cats = names("categories")
                val pays = names("payments")
                val usrs = names("users")

                if (cats.isNotEmpty() && pays.isNotEmpty()) {
                    Prefs.saveLists(app, cats.joinToString(","), pays.joinToString(","), usrs.joinToString(","))
                }
            } catch (_: Exception) {
                // Offline or script not deployed yet: keep cached lists.
            }
        }
    }
}
