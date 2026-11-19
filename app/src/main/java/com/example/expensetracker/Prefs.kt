package com.example.expensetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local settings, the signed-in session and cached category lists. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private const val DEFAULT_EXPENSES = "Food,Fuel,Transport,Groceries,Rent,Snacks and tea,Medicine,F99 expense,Card payment,Miscellaneous"
    private const val DEFAULT_INCOMES = "Salary,F99 payment,Borrowed,Income"
    private const val DEFAULT_PAYMENTS = "GPay,Card,Cash"

    private fun list(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // --- Server config (baked into the build via res/values/config.xml) ---
    fun url(c: Context): String = c.getString(R.string.script_url).trim()
    fun webClientId(c: Context): String = c.getString(R.string.google_web_client_id).trim()
    fun isConfigured(c: Context): Boolean =
        url(c).startsWith("https://") && !webClientId(c).startsWith("PASTE_")

    // --- Session ---
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun isSignedIn(c: Context): Boolean = token(c).isNotEmpty()
    fun name(c: Context): String = sp(c).getString("name", "") ?: ""
    fun email(c: Context): String = sp(c).getString("email", "") ?: ""
    fun isAdmin(c: Context): Boolean = sp(c).getBoolean("admin", false)

    fun saveSession(c: Context, token: String?, me: JSONObject?) {
        val e = sp(c).edit()
        if (token != null) e.putString("token", token)
        if (me != null) {
            e.putString("name", me.optString("name"))
                .putString("email", me.optString("email"))
                .putBoolean("admin", me.optBoolean("admin"))
        }
        e.apply()
    }

    fun clearSession(c: Context) {
        sp(c).edit().remove("token").remove("name").remove("email").remove("admin").apply()
    }

    // --- Category lists cached from the sheet ---
    fun expenseCategories(c: Context): List<String> = list(sp(c).getString("expense_categories", DEFAULT_EXPENSES) ?: DEFAULT_EXPENSES)
    fun incomeCategories(c: Context): List<String> = list(sp(c).getString("income_categories", DEFAULT_INCOMES) ?: DEFAULT_INCOMES)
    fun payments(c: Context): List<String> = list(sp(c).getString("payments", DEFAULT_PAYMENTS) ?: DEFAULT_PAYMENTS)

    /** Saves lists from a "login" or "lists" response. */
    fun saveLists(c: Context, j: JSONObject) {
        fun join(key: String): String? {
            val a: JSONArray = j.optJSONArray(key) ?: return null
            val l = (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() }
            return if (l.isEmpty()) null else l.joinToString(",")
        }
        val e = sp(c).edit()
        join("expense_categories")?.let { e.putString("expense_categories", it) }
        join("income_categories")?.let { e.putString("income_categories", it) }
        join("payments")?.let { e.putString("payments", it) }
        e.apply()
    }

    // --- Quick entry ---
    fun lastPayment(c: Context): String = sp(c).getString("lastPayment", "GPay") ?: "GPay"
    fun setLastPayment(c: Context, p: String) = sp(c).edit().putString("lastPayment", p).apply()

    // --- Shake trigger ---
    fun shakeEnabled(c: Context): Boolean = sp(c).getBoolean("shake", true)
    fun setShakeEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("shake", on).apply()
}
