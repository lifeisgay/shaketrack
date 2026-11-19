package com.example.expensetracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Thrown when the server answered with ok=false. [auth] means the session is no longer valid. */
class ApiException(message: String, val auth: Boolean) : Exception(message)

/**
 * Minimal client for the Apps Script web app. Every call is a JSON POST
 * {"action": ..., "token": ..., ...}; the reply is JSON with "ok".
 */
object Api {

    /** Blocking call; use from a worker thread. Throws IOException (network) or ApiException. */
    fun callBlocking(ctx: Context, action: String, params: JSONObject = JSONObject()): JSONObject {
        val app = ctx.applicationContext
        val url = Prefs.url(app)
        if (!Prefs.isConfigured(app)) throw ApiException("App is not configured (see config.xml)", false)

        params.put("action", action)
        if (action != "login") params.put("token", Prefs.token(app))
        val text = post(url, params.toString())

        val j = try { JSONObject(text) } catch (_: Exception) {
            throw IOException("Unexpected reply from the sheet")
        }
        if (!j.optBoolean("ok")) {
            val auth = j.optBoolean("auth")
            if (auth) Prefs.clearSession(app)
            throw ApiException(j.optString("error", "Something went wrong"), auth)
        }
        return j
    }

    suspend fun call(ctx: Context, action: String, params: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) { callBlocking(ctx, action, params) }

    /** Apps Script answers a POST with a 302 to the real output, which must be fetched with GET. */
    private fun post(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                return get(loc)
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Refreshes categories/payments and the user's admin flag in the background. */
    fun refreshLists(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs.isSignedIn(app)) return
        Thread {
            try {
                val j = callBlocking(app, "lists")
                Prefs.saveLists(app, j)
                Prefs.saveSession(app, null, j.optJSONObject("me"))
            } catch (_: Exception) {
                // Keep cached lists when offline
            }
        }.start()
    }
}
