package com.example.expensetracker

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    const val DEFAULT_CATEGORIES = "Fuel,Food,Miscellaneous"
    const val DEFAULT_PAYMENTS = "Card,GPay,Cash"

    private fun list(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun url(c: Context) = sp(c).getString("url", "") ?: ""
    fun secret(c: Context) = sp(c).getString("secret", "") ?: ""
    fun user(c: Context) = sp(c).getString("user", "User 1") ?: "User 1"
    fun rawCategories(c: Context) = sp(c).getString("categories", DEFAULT_CATEGORIES) ?: DEFAULT_CATEGORIES
    fun rawPayments(c: Context) = sp(c).getString("payments", DEFAULT_PAYMENTS) ?: DEFAULT_PAYMENTS
    fun categories(c: Context) = list(rawCategories(c))
    fun payments(c: Context) = list(rawPayments(c))
    fun lastPayment(c: Context) = sp(c).getString("lastPayment", null)
    fun setLastPayment(c: Context, p: String) = sp(c).edit().putString("lastPayment", p).apply()

    fun save(c: Context, url: String, secret: String, user: String) {
        sp(c).edit()
            .putString("url", url.trim())
            .putString("secret", secret.trim())
            .putString("user", user.trim())
            .apply()
    }

    /** Called by Sync with the names currently in the sheet's Lists tab. */
    fun saveLists(c: Context, categories: String, payments: String) {
        sp(c).edit().putString("categories", categories).putString("payments", payments).apply()
    }
}
