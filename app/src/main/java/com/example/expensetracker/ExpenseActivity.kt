package com.example.expensetracker

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/**
 * Quick-add bottom sheet (opened by a shake, the notification or the tile).
 * Type the amount, tap a tag: it's saved and the sheet closes. Two taps total.
 * Most-used tags are shown first; the last payment method is remembered.
 */
class ExpenseActivity : Activity() {

    private fun dp(v: Int) = Ui.dp(this, v)

    private var selectedPayment = "GPay"
    private var paymentNames = listOf<String>()
    private lateinit var payRow: LinearLayout
    private lateinit var amountInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        if (!Prefs.isSignedIn(this)) {
            Toast.makeText(this, "Open ShakeTrack and sign in with Google first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        Api.refreshLists(this)

        selectedPayment = Prefs.lastPayment(this).ifEmpty { "GPay" }
        val payments = Prefs.payments(this).let { if (selectedPayment in it) it else listOf(selectedPayment) + it }
        val expTags = Prefs.byUsage(this, Prefs.expenseCategories(this))
        val incTags = Prefs.byUsage(this, Prefs.incomeCategories(this))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Ui.SURFACE)
                setStroke(dp(1), Ui.LINE)
            }
        }

        // Grab handle
        sheet.addView(View(this).apply { background = Ui.round(this@ExpenseActivity, Ui.LINE, 3) },
            LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })

        // Top row: who is logging + close
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, "👤  " + Prefs.name(this).ifEmpty { "Me" }, 14f, Ui.ACCENT, bold = true).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = Ui.round(this@ExpenseActivity, Ui.ACCENT_SOFT, 16)
        })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(Ui.text(this, "✕", 20f, Ui.MUTED, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.pressable(this@ExpenseActivity, Ui.SURFACE_2, 20)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        sheet.addView(top)

        // Big amount
        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = Ui.round(this@ExpenseActivity, Ui.SURFACE_2, 18, Ui.LINE)
        }
        amountRow.addView(Ui.text(this, "₹", 34f, Ui.ACCENT, bold = true).apply { setPadding(0, 0, dp(8), 0) })
        amountInput = EditText(this).apply {
            hint = "0"
            setHintTextColor(Ui.FAINT)
            setTextColor(Ui.TEXT)
            textSize = 40f
            typeface = Ui.BOLD
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            setSingleLine()
        }
        amountRow.addView(amountInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(amountRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(14)
        })

        // Payment method: equal-width segments
        payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sheet.addView(payRow)
        paymentNames = payments
        renderPayments()

        // Tags
        sheet.addView(Ui.label(this, "Spent on", Ui.RED).apply { setPadding(dp(2), dp(18), 0, dp(8)) })
        sheet.addView(tagGrid(expTags, income = false))
        if (incTags.isNotEmpty()) {
            sheet.addView(Ui.label(this, "Money in", Ui.GREEN).apply { setPadding(dp(2), dp(16), 0, dp(8)) })
            sheet.addView(tagGrid(incTags, income = true))
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(sheet)
        }
        setContentView(scroll)

        // Full-width sheet anchored to the bottom of the screen, above the keyboard
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        amountInput.requestFocus()
    }

    private fun renderPayments() {
        payRow.removeAllViews()
        paymentNames.forEachIndexed { i, name ->
            val chip = Ui.chip(this, name, name == selectedPayment) {
                Ui.vibrate(this, 20)
                selectedPayment = name
                Prefs.setLastPayment(this, name)
                renderPayments()
            }
            payRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    private fun tagGrid(tags: List<String>, income: Boolean): GridLayout {
        val grid = GridLayout(this).apply { columnCount = 3 }
        tags.forEach { tag ->
            val btn = Ui.text(this, if (income) "+ $tag" else tag, 15f, if (income) Ui.GREEN else Ui.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                minHeight = dp(54)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = if (income) Ui.pressable(this@ExpenseActivity, Ui.GREEN_SOFT, 14, Ui.GREEN_LINE)
                else Ui.pressable(this@ExpenseActivity, Ui.SURFACE_2, 14, Ui.LINE)
                isClickable = true
                setOnClickListener { save(if (income) "Income" else "Expense", tag) }
            }
            grid.addView(btn, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        return grid
    }

    private fun save(type: String, category: String) {
        val value = amountInput.text.toString().trim().toDoubleOrNull()
        if (value == null || value <= 0.0) {
            Ui.vibrate(this, 90)
            amountInput.error = "Enter the amount first"
            amountInput.requestFocus()
            return
        }
        Ui.vibrate(this, 45)
        UploadWorker.enqueue(this, value, type, category, selectedPayment)
        Prefs.countUse(this, category)
        val signed = if (type == "Income") Ui.money(value, sign = true) else Ui.money(-value)
        Toast.makeText(this, "✓ $signed · $category · $selectedPayment", Toast.LENGTH_SHORT).show()
        finish()
    }
}
