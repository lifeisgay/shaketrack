package com.example.expensetracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*

/** Modern, clean settings screen: script URL, secret, user name, and permission shortcuts. */
class MainActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var urlInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var userInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        // Header Title
        val header = TextView(this).apply {
            text = "⚡ ShakeTrack Setup"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(header)

        val subtitle = TextView(this).apply {
            text = "Connect to your Google Sheet & configure triggers"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(subtitle)

        // --- SECTION 1: Sheet Configuration Card ---
        val configCard = makeCard().apply {
            addView(makeSectionTitle("1. GOOGLE SHEET BACKEND"))

            addView(makeFieldLabel("Apps Script Web App URL"))
            urlInput = makeEditText("https://script.google.com/macros/s/.../exec", InputType.TYPE_TEXT_VARIATION_URI).apply {
                setText(Prefs.url(this@MainActivity))
            }
            addView(urlInput)

            addView(makeFieldLabel("Secret Password"))
            secretInput = makeEditText("Enter secret matching Code.gs", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
                setText(Prefs.secret(this@MainActivity))
            }
            addView(secretInput)

            addView(makeFieldLabel("Your Name (User)"))
            userInput = makeEditText("e.g. Aneez", InputType.TYPE_TEXT_VARIATION_PERSON_NAME).apply {
                setText(Prefs.user(this@MainActivity))
            }
            addView(userInput)

            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
            }

            val saveBtn = makePrimaryButton("Save Config") {
                savePrefs()
                Sync.run(this@MainActivity)
                Toast.makeText(this@MainActivity, "✓ Saved. Syncing lists from Sheet...", Toast.LENGTH_SHORT).show()
            }
            btnRow.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })

            val testBtn = makeSecondaryButton("Test Popup") {
                savePrefs()
                startActivity(Intent(this@MainActivity, ExpenseActivity::class.java))
            }
            btnRow.addView(testBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(btnRow)
        }
        root.addView(configCard)

        // --- SECTION 2: Triggers & Permissions Card ---
        val permCard = makeCard().apply {
            addView(makeSectionTitle("2. REQUIRED PHONE PERMISSIONS"))

            val desc = TextView(this@MainActivity).apply {
                text = "To allow the Volume button triple-press to pop up over any app or lock screen, enable these 3 settings:"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 0, 0, dp(14))
            }
            addView(desc)

            // Permission 1: Overlay Permission (CRITICAL for background popup on Android 10+)
            val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity)
            val overlayBtnText = if (hasOverlay) "✓ 1. Display over other apps (Enabled)" else "1. Allow display over other apps (Required)"
            val overlayBtn = makeActionButton(overlayBtnText, hasOverlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Already supported", Toast.LENGTH_SHORT).show()
                }
            }
            addView(overlayBtn)

            // Permission 2: Accessibility Trigger
            val accessBtn = makeActionButton("2. Enable Volume Trigger (Accessibility)", false) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            addView(accessBtn)

            // Permission 3: Battery Optimization
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val isExempt = pm.isIgnoringBatteryOptimizations(packageName)
            val battText = if (isExempt) "✓ 3. Battery optimization (Exempt)" else "3. Disable battery optimization"
            val battBtn = makeActionButton(battText, isExempt) {
                if (!isExempt) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Already exempt", Toast.LENGTH_SHORT).show()
                }
            }
            addView(battBtn)

            val tip = TextView(this@MainActivity).apply {
                text = "💡 Tip: Triple-press Volume Down within 1 second to trigger the popup anywhere.\nOn Android 13+, if Accessibility says 'Restricted', tap App Info → ⋮ (3 dots) → 'Allow restricted settings'."
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(12), 0, 0)
            }
            addView(tip)
        }
        root.addView(permCard)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun savePrefs() {
        Prefs.save(
            this,
            urlInput.text.toString(),
            secretInput.text.toString(),
            userInput.text.toString()
        )
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
    }

    private fun makeSectionTitle(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#6366F1"))
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun makeFieldLabel(t: String): TextView {
        return TextView(this).apply {
            text = t
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, dp(8), 0, dp(4))
        }
    }

    private fun makeEditText(h: String, it: Int): EditText {
        return EditText(this).apply {
            hint = h
            setHintTextColor(Color.parseColor("#94A3B8"))
            textSize = 14f
            inputType = it
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
        }
    }

    private fun makePrimaryButton(t: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = t
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#4F46E5"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeSecondaryButton(t: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = t
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.parseColor("#4338CA"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EEF2FF"))
                setStroke(dp(1), Color.parseColor("#C7D2FE"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeActionButton(t: String, active: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = t
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (active) Color.parseColor("#15803D") else Color.parseColor("#1E293B"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(if (active) Color.parseColor("#F0FDF4") else Color.parseColor("#F1F5F9"))
                setStroke(dp(1), if (active) Color.parseColor("#BBF7D0") else Color.parseColor("#E2E8F0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { onClick() }
        }
    }
}
