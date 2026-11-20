package com.example.expensetracker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/** ShakeTrack's dark theme: colors, sizes and small view builders shared by every screen. */
object Ui {
    // Surfaces
    val BG = 0xFF0B1220.toInt()
    val SURFACE = 0xFF131C2F.toInt()
    val SURFACE_2 = 0xFF1B2640.toInt()
    val LINE = 0xFF2A3754.toInt()
    // Text
    val TEXT = 0xFFEEF2FA.toInt()
    val MUTED = 0xFF9AA8C0.toInt()
    val FAINT = 0xFF64748B.toInt()
    // Accents
    val ACCENT = 0xFF8B93FF.toInt()
    val ACCENT_STRONG = 0xFF5B5BF0.toInt()
    val ACCENT_SOFT = 0xFF262E5C.toInt()
    val RED = 0xFFFF7A7A.toInt()
    val RED_SOFT = 0xFF3A1D27.toInt()
    val RED_LINE = 0xFF6B2C3A.toInt()
    val GREEN = 0xFF3DDC97.toInt()
    val GREEN_SOFT = 0xFF14332A.toInt()
    val GREEN_LINE = 0xFF1F5A44.toInt()
    val BLUE = 0xFF6CB2FF.toInt()
    val AMBER = 0xFFFBBF24.toInt()
    val AMBER_SOFT = 0xFF3A2E12.toInt()

    val BOLD: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun round(c: Context, color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(c, radiusDp).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    /** Rounded background that ripples when tapped. */
    fun pressable(c: Context, color: Int, radiusDp: Int, stroke: Int = 0, ripple: Int = 0x33FFFFFF) =
        RippleDrawable(ColorStateList.valueOf(ripple), round(c, color, radiusDp, stroke), round(c, 0xFF000000.toInt(), radiusDp))

    fun text(c: Context, t: CharSequence, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = t
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) BOLD else REGULAR
        includeFontPadding = true
    }

    fun label(c: Context, t: String, color: Int = MUTED) = text(c, t.uppercase(), 12f, color, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(c, 4), 0, dp(c, 10))
    }

    fun card(c: Context, topMarginDp: Int = 12) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18))
        background = round(c, SURFACE, 20, LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, topMarginDp)
        }
    }

    fun input(c: Context, hint: String, type: Int, sizeSp: Float = 16f) = EditText(c).apply {
        this.hint = hint
        setHintTextColor(FAINT)
        setTextColor(TEXT)
        textSize = sizeSp
        inputType = type
        setSingleLine()
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        background = round(c, SURFACE_2, 12, LINE)
    }

    fun button(c: Context, t: String, primary: Boolean = true, onClick: () -> Unit) = Button(c).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        typeface = BOLD
        minHeight = dp(c, 52)
        stateListAnimator = null
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else ACCENT)
        background = if (primary) pressable(c, ACCENT_STRONG, 14) else pressable(c, ACCENT_SOFT, 14, LINE)
        setOnClickListener { onClick() }
    }

    /** Pill/segment that can be selected. */
    fun chip(c: Context, t: String, selected: Boolean, onClick: () -> Unit) = TextView(c).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        minHeight = dp(c, 44)
        setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        typeface = if (selected) BOLD else MEDIUM
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else MUTED)
        background = if (selected) pressable(c, ACCENT_STRONG, 22) else pressable(c, SURFACE_2, 22, LINE)
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun space(c: Context, hDp: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, hDp)) }

    fun money(v: Double, sign: Boolean = false): String {
        val f = android.icu.text.NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = if (v % 1.0 == 0.0) 0 else 2
        }
        val prefix = when {
            v < 0 -> "−"
            sign -> "+"
            else -> ""
        }
        return prefix + "₹" + f.format(abs(v))
    }

    fun vibrate(c: Context, ms: Long) {
        try {
            val effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 31) {
                (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
            }
        } catch (_: Exception) {}
    }
}
