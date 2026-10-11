package com.example.expensetracker

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Pulls expense and income tags, payment types, and users from the backend sheet. */
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
                val exp = names("expense_categories").ifEmpty { names("categories") }
                val inc = names("income_categories")
                val pays = names("payments")
                val usrs = names("users")

                if (exp.isNotEmpty()) {
                    Prefs.saveLists(
                        app,
                        exp.joinToString(","),
                        inc.ifEmpty { listOf("Salary", "F99 payment", "Borrowed", "Income") }.joinToString(","),
                        pays.ifEmpty { listOf("GPay", "Card", "Cash") }.joinToString(","),
                        usrs.joinToString(",")
                    )
                }
            } catch (_: Exception) {
                // Keep local defaults if offline or backend is unreachable
            }
        }
    }
}
