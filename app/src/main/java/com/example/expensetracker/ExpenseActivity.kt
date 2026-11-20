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
        val trTags = Prefs.transferCategories(this)
        val expTags = Prefs.byUsage(this, Prefs.expenseCategories(this).filter { it !in trTags })
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
        val who = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(14), dp(6))
            background = Ui.round(this@ExpenseActivity, Ui.ACCENT_SOFT, 16)
            addView(Ui.icon(this@ExpenseActivity, R.drawable.ic_person, Ui.ACCENT, 18))
            addView(Ui.text(this@ExpenseActivity, Prefs.name(this@ExpenseActivity).ifEmpty { "Me" }, 14f, Ui.ACCENT).apply {
                typeface = Ui.MEDIUM
                setPadding(dp(6), 0, 0, 0)
            })
        }
        top.addView(who)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_close, "Close", Ui.MUTED) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
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
        sheet.addView(Ui.label(this, "Expense").apply { setPadding(dp(2), dp(18), 0, dp(8)) })
        sheet.addView(tagGrid(expTags, "Expense"))
        if (incTags.isNotEmpty()) {
            sheet.addView(Ui.label(this, "Income").apply { setPadding(dp(2), dp(16), 0, dp(8)) })
            sheet.addView(tagGrid(incTags, "Income"))
        }
        if (trTags.isNotEmpty()) {
            sheet.addView(Ui.label(this, "Card bill").apply { setPadding(dp(2), dp(16), 0, dp(2)) })
            sheet.addView(Ui.text(this, "Paying your card bill isn't counted as spending; the card purchases already were.", 13f, Ui.FAINT).apply {
                setPadding(dp(2), 0, 0, dp(8))
            })
            sheet.addView(tagGrid(trTags, "Transfer"))
        }

        // Full-screen translucent layer: dim scrim (tap to close) with the sheet at the bottom.
        // Because the window is full-screen, adjustResize shrinks it above the keyboard and the
        // sheet scrolls, so every tag stays reachable while typing the amount.
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(sheet)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { finish() }
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        sheet.isClickable = true // taps on the sheet don't reach the scrim
        setContentView(root)

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

    /** Grid of one-tap tags. [type] is "Expense", "Income" or "Transfer" (card bill). */
    private fun tagGrid(tags: List<String>, type: String): GridLayout {
        val grid = GridLayout(this).apply { columnCount = 3 }
        val (fg, bg, line) = when (type) {
            "Income" -> Triple(Ui.GREEN, Ui.GREEN_SOFT, Ui.GREEN_LINE)
            "Transfer" -> Triple(Ui.BLUE, Ui.BLUE_SOFT, Ui.BLUE_LINE)
            else -> Triple(Ui.TEXT, Ui.SURFACE_2, Ui.LINE)
        }
        tags.forEach { tag ->
            val btn = Ui.text(this, tag, 15f, fg).apply {
                typeface = Ui.MEDIUM
                gravity = Gravity.CENTER
                maxLines = 2
                minHeight = dp(54)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = Ui.pressable(this@ExpenseActivity, bg, 14, line)
                isClickable = true
                setOnClickListener { save(type, tag) }
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
        val signed = when (type) {
            "Income" -> Ui.money(value, sign = true)
            "Transfer" -> Ui.money(value)
            else -> Ui.money(-value)
        }
        Toast.makeText(this, "Saved  $signed  ·  $category  ·  $selectedPayment", Toast.LENGTH_SHORT).show()
        finish()
    }
}
