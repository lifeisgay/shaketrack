package com.example.expensetracker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts one expense to the Apps Script web app.
 * WorkManager persists the job, so entries made offline are sent later and retried on failure.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val url = Prefs.url(applicationContext)
        if (url.isEmpty()) return Result.failure()

        val userParam = inputData.getString("user")?.ifEmpty { null }
            ?: Prefs.user(applicationContext)

        val body = JSONObject().apply {
            put("amount", inputData.getDouble("amount", 0.0))
            put("category", inputData.getString("category") ?: "Miscellaneous")
            put("payment", inputData.getString("payment") ?: "Cash")
            put("user", userParam)
            put("ts", inputData.getLong("ts", System.currentTimeMillis()))
            put("secret", Prefs.secret(applicationContext))
        }.toString()

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = conn.responseCode
            val text = (if (responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }?.trim() ?: ""
            conn.disconnect()

            when {
                text == "ok" || responseCode == 200 || responseCode == 302 -> Result.success()
                text == "unauthorized" || responseCode == 401 -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
