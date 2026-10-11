package com.example.expensetracker

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.work.*

/**
 * Modern 3-Section Financial Quick Entry Popup:
 * 1. Payment Method (Default: GPay, Card, Cash)
 * 2. Expenditure Tags (Outgoing -)
 * 3. Income Tags (Incoming +)
 * One-tap on any tag instantly logs the amount and closes.
 */
class ExpenseActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var selectedPayment: String = "GPay"
    private var selectedUser: String = "Aneez"
    private val paymentButtons = mutableListOf<TextView>()
    private lateinit var amountInput: EditText
    private lateinit var userPill: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        Sync.run(this)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val payments = Prefs.payments(this)
        val expCategories = Prefs.expenseCategories(this)
        val incCategories = Prefs.incomeCategories(this)
        val users = Prefs.users(this)

        selectedPayment = Prefs.lastPayment(this).ifEmpty { "GPay" }
        selectedUser = Prefs.user(this)

        // Card Container
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            if (Build.VERSION.SDK_INT >= 21) {
                elevation = dp(16).toFloat()
            }
        }

        // --- TOP BAR: User Pill & Close Button ---
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        userPill = TextView(this).apply {
            text = "👤 $selectedUser ▾"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#4338CA"))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = makePillDrawable(Color.parseColor("#EEF2FF"), dp(14).toFloat())
            isClickable = true
            setOnClickListener {
                vibrate(25)
                val currentIdx = users.indexOf(selectedUser)
                val nextIdx = if (currentIdx in 0 until users.size - 1) currentIdx + 1 else 0
                selectedUser = users.getOrElse(nextIdx) { "Aneez" }
                Prefs.setUser(this@ExpenseActivity, selectedUser)
                text = "👤 $selectedUser ▾"
                Toast.makeText(this@ExpenseActivity, "User: $selectedUser", Toast.LENGTH_SHORT).show()
            }
        }
        val userParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_START) }
        topBar.addView(userPill, userParams)

        val title = TextView(this).apply {
            text = "⚡ ShakeTrack"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
        }
        val titleParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }
        topBar.addView(title, titleParams)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { finish() }
        }
        val closeParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
        topBar.addView(closeBtn, closeParams)
        card.addView(topBar)

        // --- HERO AMOUNT INPUT ---
        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val currencySymbol = TextView(this).apply {
            text = "₹"
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#4F46E5"))
            setPadding(0, 0, dp(8), 0)
        }
        amountRow.addView(currencySymbol)

        amountInput = EditText(this).apply {
            hint = "0"
            setHintTextColor(Color.parseColor("#CBD5E1"))
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        amountRow.addView(amountInput)
        card.addView(amountRow)

        // ====================================================================
        // SECTION 1: PAYMENT METHOD (Default GPay, Card, Cash)
        // ====================================================================
        val payLabel = makeSectionHeader("1. PAYMENT METHOD", "#475569")
        card.addView(payLabel)

        val paymentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        val paymentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        payments.forEach { pay ->
            val pill = TextView(this).apply {
                text = pay
                textSize = 12f
                setPadding(dp(16), dp(6), dp(16), dp(6))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                isClickable = true
                setOnClickListener {
                    vibrate(25)
                    selectedPayment = pay
                    Prefs.setLastPayment(this@ExpenseActivity, pay)
                    refreshPaymentPills()
                }
            }
            paymentButtons.add(pill)
            paymentRow.addView(pill)
        }
        paymentScroll.addView(paymentRow)
        card.addView(paymentScroll)
        refreshPaymentPills()

        // ====================================================================
        // SECTION 2: EXPENDITURE TAGS (Outgoing -)
        // ====================================================================
        val expLabel = makeSectionHeader("2. EXPENDITURES (OUTGOING -)", "#E11D48")
        card.addView(expLabel)

        val expGrid = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        expCategories.forEach { cat ->
            val btn = TextView(this).apply {
                text = cat
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.parseColor("#991B1B"))
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(11), dp(6), dp(11))
                background = makeTagDrawable(Color.parseColor("#FEF2F2"), Color.parseColor("#FECACA"), Color.parseColor("#FCA5A5"))
                isClickable = true
                setOnClickListener {
                    submitTransaction("Expense", cat)
                }
            }
            val p = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            expGrid.addView(btn, p)
        }
        card.addView(expGrid)

        // ====================================================================
        // SECTION 3: INCOME TAGS (Incoming +)
        // ====================================================================
        val incLabel = makeSectionHeader("3. INCOME (INCOMING +)", "#059669")
        card.addView(incLabel)

        val incGrid = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        incCategories.forEach { cat ->
            val btn = TextView(this).apply {
                text = "+ $cat"
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.parseColor("#065F46"))
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(11), dp(6), dp(11))
                background = makeTagDrawable(Color.parseColor("#ECFDF5"), Color.parseColor("#A7F3D0"), Color.parseColor("#6EE7B7"))
                isClickable = true
                setOnClickListener {
                    submitTransaction("Income", cat)
                }
            }
            val p = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            incGrid.addView(btn, p)
        }
        card.addView(incGrid)

        // ScrollView container in case of smaller displays
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(card)
        }

        val outer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(scroll)
        }

        setContentView(outer)
        amountInput.requestFocus()
    }

    private fun makeSectionHeader(text: String, hexColor: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10.5f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor(hexColor))
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun refreshPaymentPills() {
        paymentButtons.forEach { pill ->
            val isSelected = pill.text.toString() == selectedPayment
            if (isSelected) {
                pill.background = makePillDrawable(Color.parseColor("#4F46E5"), dp(18).toFloat())
                pill.setTextColor(Color.WHITE)
                pill.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                pill.background = makePillDrawable(Color.parseColor("#F1F5F9"), dp(18).toFloat(), Color.parseColor("#E2E8F0"), 1f)
                pill.setTextColor(Color.parseColor("#475569"))
                pill.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }
    }

    private fun submitTransaction(type: String, category: String) {
        val raw = amountInput.text.toString().trim()
        val value = raw.toDoubleOrNull()
        if (value == null || value <= 0.0) {
            vibrate(80)
            Toast.makeText(this, "Please enter an amount first", Toast.LENGTH_SHORT).show()
            amountInput.requestFocus()
            return
        }

        vibrate(45)

        val sign = if (type == "Income") "+" else "-"
        val data = workDataOf(
            "amount" to value,
            "type" to type,
            "category" to category,
            "payment" to selectedPayment,
            "user" to selectedUser,
            "ts" to System.currentTimeMillis()
        )

        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueue(req)

        Toast.makeText(
            this,
            "✓ Logged $sign₹$value · $category · $selectedPayment ($selectedUser)",
            Toast.LENGTH_SHORT
        ).show()

        finish()
    }

    private fun vibrate(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    v?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(durationMs)
                }
            }
        } catch (_: Exception) {}
    }

    private fun makePillDrawable(bgColor: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bgColor)
            if (strokeWidth > 0f) setStroke(dp(strokeWidth.toInt()), strokeColor)
        }
    }

    private fun makeTagDrawable(bgColor: Int, strokeColor: Int, rippleColor: Int): RippleDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(bgColor)
            setStroke(dp(1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.BLACK)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), normal, mask)
    }
}
