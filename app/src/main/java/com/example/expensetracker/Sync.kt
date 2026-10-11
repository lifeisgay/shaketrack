package com.example.expensetracker

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Pulls the category and payment lists from the sheet's Lists tab and caches them for the popup. */
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
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val j = JSONObject(text)
                fun names(key: String): List<String> {
                    val a = j.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it) }
                }
                val cats = names("categories")
                val pays = names("payments")
                if (cats.isNotEmpty() && pays.isNotEmpty()) {
                    Prefs.saveLists(app, cats.joinToString(","), pays.joinToString(","))
                }
            } catch (e: Exception) {
                // Offline or script not deployed yet: keep the cached lists.
            }
        }
    }
}
