package com.example.expensetracker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * TRIGGER: press Volume Down 3 times within the window below.
 * Event-driven, so it costs essentially no battery while idle.
 * Change PRESSES / WINDOW_MS to tune, or swap the key code.
 */
class VolumeTriggerService : AccessibilityService() {

    private val PRESSES = 3
    private val WINDOW_MS = 600L

    private var count = 0
    private var last = 0L
    private var volumeBefore = 0

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN ||
            e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return false

        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val fresh = e.eventTime - last >= WINDOW_MS
        if (fresh) {
            count = 1
            volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        } else count++
        last = e.eventTime

        if (count >= PRESSES) {
            count = 0
            // Undo the volume drop caused by the earlier presses.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeBefore, 0)
            startActivity(
                Intent(this, ExpenseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true // swallow the final press
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
