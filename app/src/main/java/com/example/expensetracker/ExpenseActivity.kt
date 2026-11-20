package com.example.expensetracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*

/**
 * Quick-add sheet (opened by a shake, the notification, the tile or the + button).
 * Type the amount, tap a tag: it's saved and the sheet closes.
 *
 *  Payment: GPay · Cash · Card  (Card reveals the user's cards; the specific card is stored)
 *  Expense: everyday tags, most-used first ("Things bought" asks what was bought)
 *  Income, Savings
 *  Fixed expenses: one button that opens the rarely used list, plus card bills
 */
class ExpenseActivity : Activity() {

    private fun dp(v: Int) = Ui.dp(this, v)

    /** "GPay", "Cash" or a card name such as "ICICI card". */
    private var payment = "GPay"
    private var cards = listOf<String>()
    private lateinit var payRow: LinearLayout
    private lateinit var cardRow: LinearLayout
    private lateinit var amountInput: EditText
    private lateinit var noteBox: LinearLayout
    private lateinit var noteInput: EditText
    private lateinit var fixedPanel: LinearLayout
    private lateinit var fixedToggle: LinearLayout

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

        cards = Prefs.cards(this)
        payment = Prefs.lastPayment(this).ifEmpty { "GPay" }
        if (payment == "Card") payment = cards.firstOrNull() ?: "GPay"
        val plain = Prefs.payments(this).filter { it != "Card" && it !in cards }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(22))
            isClickable = true // taps on the sheet don't reach the scrim
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
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(14), dp(6))
            background = Ui.round(this@ExpenseActivity, Ui.ACCENT_SOFT, 16)
            addView(Ui.icon(this@ExpenseActivity, R.drawable.ic_person, Ui.ACCENT, 18))
            addView(Ui.text(this@ExpenseActivity, Prefs.name(this@ExpenseActivity).ifEmpty { "Me" }, 14f, Ui.ACCENT).apply {
                typeface = Ui.MEDIUM
                setPadding(dp(6), 0, 0, 0)
            })
        })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_close, "Close", Ui.MUTED) { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
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

        // Payment: GPay · Cash · Card, and the cards when Card is chosen
        payRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sheet.addView(payRow)
        sheet.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(cardRow)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        renderPayments(plain)

        // "Things bought": what was bought, then save
        noteBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        noteInput = Ui.input(this, "What did you buy?", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        noteBox.addView(noteInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        noteBox.addView(Ui.button(this, "Save") {
            save("Expense", Prefs.THINGS_TAG, noteInput.text.toString().trim())
        }.apply { setPadding(dp(22), 0, dp(22), 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)))

        // Expense (most-used first)
        val cardSet = cards.toSet()
        sheet.addView(section("Expense"))
        sheet.addView(tagGrid(Prefs.byUsage(this, Prefs.expenseCategories(this)), "Expense"))
        sheet.addView(noteBox)

        // Income
        val incTags = Prefs.incomeCategories(this)
        if (incTags.isNotEmpty()) {
            sheet.addView(section("Income"))
            sheet.addView(tagGrid(incTags, "Income"))
        }

        // Savings
        val savTags = Prefs.savingsCategories(this)
        if (savTags.isNotEmpty()) {
            sheet.addView(section("Savings"))
            sheet.addView(tagGrid(savTags, "Savings"))
        }

        // Fixed expenses: one button that opens the list
        fixedPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fixedPanel.addView(tagGrid(Prefs.fixedCategories(this).filter { it !in cardSet }, "Fixed"))
        if (cards.isNotEmpty()) {
            fixedPanel.addView(section("Card bill paid"))
            fixedPanel.addView(Ui.text(this, "Not counted as spending: the purchases on the card already were.", 13f, Ui.FAINT).apply {
                setPadding(dp(2), 0, 0, dp(8))
            })
            fixedPanel.addView(tagGrid(cards, "Transfer"))
        }
        fixedToggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(16), dp(8), dp(12), dp(8))
            background = Ui.pressable(this@ExpenseActivity, Ui.SURFACE_2, 14, Ui.LINE)
            isClickable = true
            addView(Ui.text(this@ExpenseActivity, "Fixed expenses", 15f).apply { typeface = Ui.MEDIUM },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(this@ExpenseActivity, "Rent, bills, card bills…", 13f, Ui.FAINT).apply { setPadding(0, 0, dp(8), 0) })
            addView(Ui.icon(this@ExpenseActivity, R.drawable.ic_chevron_right, Ui.MUTED, 22))
            setOnClickListener { toggleFixed() }
        }
        sheet.addView(fixedToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18); leftMargin = dp(4); rightMargin = dp(4)
        })
        sheet.addView(fixedPanel)

        // Full-screen translucent layer: dim scrim (tap to close) with the sheet at the bottom.
        // Being full-screen, adjustResize shrinks it above the keyboard and the sheet scrolls.
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(sheet)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { finish() }
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        amountInput.requestFocus()
    }

    private fun section(title: String) = Ui.label(this, title).apply { setPadding(dp(2), dp(18), 0, dp(8)) }

    private fun toggleFixed() {
        val open = fixedPanel.visibility != View.VISIBLE
        fixedPanel.visibility = if (open) View.VISIBLE else View.GONE
        (fixedToggle.getChildAt(2) as ImageView).rotation = if (open) 90f else 0f
        if (open) fixedToggle.post { (fixedToggle.parent.parent as? ScrollView)?.smoothScrollTo(0, fixedToggle.top - dp(12)) }
    }

    private fun renderPayments(plain: List<String>) {
        val onCard = payment in cards
        payRow.removeAllViews()
        val options = plain + (if (cards.isNotEmpty()) listOf("Card") else emptyList())
        options.forEachIndexed { i, name ->
            val selected = if (name == "Card") onCard else payment == name
            payRow.addView(Ui.chip(this, name, selected) {
                Ui.vibrate(this, 20)
                payment = if (name == "Card") (Prefs.lastCard(this).takeIf { it in cards } ?: cards.first()) else name
                Prefs.setLastPayment(this, payment)
                renderPayments(plain)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        cardRow.removeAllViews()
        (cardRow.parent as View).visibility = if (onCard) View.VISIBLE else View.GONE
        if (onCard) cards.forEachIndexed { i, card ->
            cardRow.addView(Ui.chip(this, card, payment == card) {
                Ui.vibrate(this, 20)
                payment = card
                Prefs.setLastPayment(this, card)
                Prefs.setLastCard(this, card)
                renderPayments(plain)
            }.apply { textSize = 14f; minHeight = dp(40) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    /** One-tap tags. [type]: Expense, Income, Savings, Fixed or Transfer (card bill). */
    private fun tagGrid(tags: List<String>, type: String): GridLayout {
        val grid = GridLayout(this).apply { columnCount = 3 }
        val (fg, bg, line) = when (type) {
            "Income" -> Triple(Ui.GREEN, Ui.GREEN_SOFT, Ui.GREEN_LINE)
            "Savings" -> Triple(Ui.VIOLET, Ui.VIOLET_SOFT, Ui.VIOLET_LINE)
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
                setOnClickListener {
                    if (type == "Expense" && tag.equals(Prefs.THINGS_TAG, ignoreCase = true)) askWhatWasBought()
                    else save(type, tag, "")
                }
            }
            grid.addView(btn, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL) // same height as a two-line neighbour in the row
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        return grid
    }

    private fun askWhatWasBought() {
        if (!hasAmount()) return
        noteBox.visibility = View.VISIBLE
        noteInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(noteInput, 0)
        noteBox.post { (noteBox.parent.parent as? ScrollView)?.smoothScrollTo(0, noteBox.top - dp(80)) }
    }

    private fun hasAmount(): Boolean {
        val value = amountInput.text.toString().trim().toDoubleOrNull()
        if (value == null || value <= 0.0) {
            Ui.vibrate(this, 90)
            amountInput.error = "Enter the amount first"
            amountInput.requestFocus()
            return false
        }
        return true
    }

    private fun save(type: String, category: String, note: String) {
        if (!hasAmount()) return
        val value = amountInput.text.toString().trim().toDouble()
        Ui.vibrate(this, 45)
        UploadWorker.enqueue(this, value, type, category, payment, note)
        Prefs.countUse(this, category)
        val shown = when (type) {
            "Income" -> Ui.money(value, sign = true)
            "Expense", "Fixed" -> Ui.money(-value)
            else -> Ui.money(value)
        }
        val what = if (note.isNotEmpty()) "$category ($note)" else category
        Toast.makeText(this, "Saved  $shown  ·  $what  ·  $payment", Toast.LENGTH_SHORT).show()
        finish()
    }
}
