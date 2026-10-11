package com.example.expensetracker

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.work.*

/** Popup: type amount, pick payment type, tap a category, done. */
class ExpenseActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var payGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        Sync.run(this) // refresh tags for next time
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val amount = EditText(this).apply {
            hint = "Amount"
            textSize = 28f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(amount)

        // Payment type (remembers the last one used)
        payGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val last = Prefs.lastPayment(this)
        val pays = Prefs.payments(this)
        pays.forEachIndexed { i, p ->
            val rb = RadioButton(this).apply {
                text = p; id = 1000 + i
                isChecked = if (last != null) p == last else i == 0
            }
            payGroup.addView(rb)
        }
        root.addView(payGroup)

        // Category buttons: tapping one submits
        val grid = GridLayout(this).apply { columnCount = 3 }
        Prefs.categories(this).forEach { cat ->
            val b = Button(this).apply {
                text = cat
                setOnClickListener { submit(amount.text.toString(), cat) }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0 }
            grid.addView(b, lp)
        }
        root.addView(grid)
        setContentView(root)
        amount.requestFocus()
    }

    private fun submit(raw: String, category: String) {
        val value = raw.toDoubleOrNull()
        if (value == null) {
            Toast.makeText(this, "Enter an amount", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = payGroup.findViewById<RadioButton>(payGroup.checkedRadioButtonId)
        val payment = checked?.text?.toString() ?: Prefs.payments(this).firstOrNull() ?: ""
        Prefs.setLastPayment(this, payment)

        val data = workDataOf(
            "amount" to value,
            "category" to category,
            "payment" to payment,
            "ts" to System.currentTimeMillis()
        )
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueue(req)
        Toast.makeText(this, "Saved ₹$value · $category · $payment", Toast.LENGTH_SHORT).show()
        finish()
    }
}
