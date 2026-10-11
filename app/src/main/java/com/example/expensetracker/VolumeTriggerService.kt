package com.example.expensetracker

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * TRIGGER: press Volume Down 3 times within 1 second.
 * Event-driven, uses negligible battery.
 */
class VolumeTriggerService : AccessibilityService() {

    private val PRESSES = 3
    private val WINDOW_MS = 1000L // 1.0s window between consecutive presses

    private var count = 0
    private var last = 0L
    private var volumeBefore = 0

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN ||
            e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0
        ) return false

        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val fresh = e.eventTime - last >= WINDOW_MS
        if (fresh) {
            count = 1
            volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        } else {
            count++
        }
        last = e.eventTime

        if (count >= PRESSES) {
            count = 0
            // Undo the volume drop caused by the key presses
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0)

            // Provide crisp tactile haptic confirmation
            vibrateFeedback()

            // Launch ExpenseActivity
            launchPopup()
            return true // Swallow the 3rd key press
        }
        return false
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(60)
                }
            }
        } catch (_: Exception) {}
    }

    private fun launchPopup() {
        val intent = Intent(this, ExpenseActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val flags = if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = PendingIntent.getActivity(this, 999, intent, flags)
                pi.send()
            } catch (_: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
