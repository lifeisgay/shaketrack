package com.example.expensetracker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*

/** Plain settings screen: script URL, secret, tags, and shortcuts to the setup screens. */
class MainActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, 0) }

        val url = EditText(this).apply {
            hint = "https://script.google.com/macros/s/.../exec"
            setText(Prefs.url(this@MainActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val secret = EditText(this).apply { setText(Prefs.secret(this@MainActivity)) }
        val user = EditText(this).apply { setText(Prefs.user(this@MainActivity)) }

        col.addView(label("Apps Script web app URL")); col.addView(url)
        col.addView(label("Secret (must match the script)")); col.addView(secret)
        col.addView(label("Your name (goes in the User column)")); col.addView(user)
        col.addView(TextView(this).apply {
            text = "Categories and payment types come from the Lists tab of your sheet. Add a name there and it appears here."
            setPadding(0, dp(12), 0, 0)
        })

        fun button(t: String, action: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { action() }
        }

        col.addView(button("Save") {
            Prefs.save(this, url.text.toString(), secret.text.toString(), user.text.toString())
            Sync.run(this)
            Toast.makeText(this, "Saved. Syncing tags from the sheet.", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("Sync tags now") {
            Prefs.save(this, url.text.toString(), secret.text.toString(), user.text.toString())
            Sync.run(this)
            Toast.makeText(this, "Syncing. Reopen the entry screen in a few seconds.", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("Test entry screen") {
            Prefs.save(this, url.text.toString(), secret.text.toString(), user.text.toString())
            startActivity(Intent(this, ExpenseActivity::class.java))
        })
        col.addView(label("One-time setup"))
        col.addView(button("1. Enable volume trigger (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("2. Disable battery optimization") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } else Toast.makeText(this, "Already exempt", Toast.LENGTH_SHORT).show()
        })
        col.addView(TextView(this).apply {
            text = "Tip: add the \"Add expense\" tile from the Quick Settings edit menu."
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
