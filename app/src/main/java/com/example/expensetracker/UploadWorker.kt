package com.example.expensetracker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Posts one entry to the sheet. WorkManager keeps it queued while offline and retries.
 * Each entry carries a client-made id, so a retry never creates a duplicate row.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        if (!Prefs.isSignedIn(applicationContext)) return Result.retry() // wait for sign-in
        val body = JSONObject().apply {
            put("id", inputData.getString("id"))
            put("amount", inputData.getDouble("amount", 0.0))
            put("type", inputData.getString("type") ?: "Expense")
            put("category", inputData.getString("category") ?: "Miscellaneous")
            put("payment", inputData.getString("payment") ?: "GPay")
            put("ts", inputData.getLong("ts", System.currentTimeMillis()))
            put("note", inputData.getString("note") ?: "")
        }
        return try {
            Api.callBlocking(applicationContext, "add", body)
            Result.success()
        } catch (e: ApiException) {
            // Signed out / removed: keep it queued so it uploads after signing in again.
            if (e.auth) Result.retry() else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val TAG = "upload"

        fun enqueue(ctx: Context, amount: Double, type: String, category: String, payment: String, note: String = "") {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        "id" to UUID.randomUUID().toString(),
                        "amount" to amount,
                        "type" to type,
                        "category" to category,
                        "payment" to payment,
                        "note" to note,
                        "ts" to System.currentTimeMillis()
                    )
                )
                .addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }

        /** Number of entries saved on the phone but not yet in the sheet. */
        fun pendingCount(ctx: Context): Int = try {
            WorkManager.getInstance(ctx).getWorkInfosByTag(TAG).get().count { !it.state.isFinished }
        } catch (_: Exception) { 0 }
    }
}
