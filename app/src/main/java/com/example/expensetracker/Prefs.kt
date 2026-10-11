package com.example.expensetracker

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    const val DEFAULT_EXPENSES = "Food,Fuel,Transport,Groceries,Rent,Snacks and tea,Medicine,F99 expense,Card payment,Miscellaneous"
    const val DEFAULT_INCOMES = "Salary,F99 payment,Borrowed,Income"
    const val DEFAULT_PAYMENTS = "GPay,Card,Cash"
    const val DEFAULT_USERS = "Aneez,User 1"

    private fun list(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun url(c: Context): String = sp(c).getString("url", "") ?: ""
    fun secret(c: Context): String = sp(c).getString("secret", "") ?: ""
    fun user(c: Context): String = sp(c).getString("user", "Aneez")?.ifEmpty { "Aneez" } ?: "Aneez"
    fun setUser(c: Context, u: String) = sp(c).edit().putString("user", u.trim()).apply()

    fun rawExpenses(c: Context): String = sp(c).getString("expense_categories", DEFAULT_EXPENSES) ?: DEFAULT_EXPENSES
    fun rawIncomes(c: Context): String = sp(c).getString("income_categories", DEFAULT_INCOMES) ?: DEFAULT_INCOMES
    fun rawPayments(c: Context): String = sp(c).getString("payments", DEFAULT_PAYMENTS) ?: DEFAULT_PAYMENTS
    fun rawUsers(c: Context): String = sp(c).getString("users", DEFAULT_USERS) ?: DEFAULT_USERS

    fun expenseCategories(c: Context): List<String> = list(rawExpenses(c))
    fun incomeCategories(c: Context): List<String> = list(rawIncomes(c))
    fun payments(c: Context): List<String> = list(rawPayments(c))

    fun users(c: Context): List<String> {
        val l = list(rawUsers(c))
        val current = user(c)
        return if (l.contains(current)) l else (listOf(current) + l).distinct()
    }

    fun lastPayment(c: Context): String = sp(c).getString("lastPayment", "GPay") ?: "GPay"
    fun setLastPayment(c: Context, p: String) = sp(c).edit().putString("lastPayment", p).apply()

    fun save(c: Context, url: String, secret: String, user: String) {
        val trimmedUser = user.trim().ifEmpty { "Aneez" }
        sp(c).edit()
            .putString("url", url.trim())
            .putString("secret", secret.trim())
            .putString("user", trimmedUser)
            .apply()
    }

    /** Saves categorized lists from sheet backend */
    fun saveLists(c: Context, expenses: String, incomes: String, payments: String, users: String = "") {
        val editor = sp(c).edit()
            .putString("expense_categories", expenses)
            .putString("income_categories", incomes)
            .putString("payments", payments)
        if (users.isNotEmpty()) {
            editor.putString("users", users)
        }
        editor.apply()
    }
}
