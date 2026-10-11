package com.example.expensetracker

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    const val DEFAULT_CATEGORIES = "Fuel,Food,Miscellaneous,Transport,ticket,chok"
    const val DEFAULT_PAYMENTS = "Card,GPay,Cash"
    const val DEFAULT_USERS = "User 1,User 2,User 3"

    private fun list(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun url(c: Context): String = sp(c).getString("url", "") ?: ""
    fun secret(c: Context): String = sp(c).getString("secret", "") ?: ""
    fun user(c: Context): String = sp(c).getString("user", "User 1")?.ifEmpty { "User 1" } ?: "User 1"
    fun setUser(c: Context, u: String) = sp(c).edit().putString("user", u.trim()).apply()

    fun rawCategories(c: Context): String = sp(c).getString("categories", DEFAULT_CATEGORIES) ?: DEFAULT_CATEGORIES
    fun rawPayments(c: Context): String = sp(c).getString("payments", DEFAULT_PAYMENTS) ?: DEFAULT_PAYMENTS
    fun rawUsers(c: Context): String = sp(c).getString("users", DEFAULT_USERS) ?: DEFAULT_USERS

    fun categories(c: Context): List<String> = list(rawCategories(c))
    fun payments(c: Context): List<String> = list(rawPayments(c))

    fun users(c: Context): List<String> {
        val l = list(rawUsers(c))
        val current = user(c)
        return if (l.contains(current)) l else (listOf(current) + l).distinct()
    }

    fun lastPayment(c: Context): String? = sp(c).getString("lastPayment", null)
    fun setLastPayment(c: Context, p: String) = sp(c).edit().putString("lastPayment", p).apply()

    fun save(c: Context, url: String, secret: String, user: String) {
        val trimmedUser = user.trim().ifEmpty { "User 1" }
        sp(c).edit()
            .putString("url", url.trim())
            .putString("secret", secret.trim())
            .putString("user", trimmedUser)
            .apply()
    }

    /** Called by Sync with the names currently in the sheet's Lists tab. */
    fun saveLists(c: Context, categories: String, payments: String, users: String = "") {
        val editor = sp(c).edit()
            .putString("categories", categories)
            .putString("payments", payments)
        if (users.isNotEmpty()) {
            editor.putString("users", users)
        }
        editor.apply()
    }
}
