package com.example.expensetracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * TRIGGER: shake the phone firmly (a few quick back-and-forth jolts) to open the quick entry popup.
 * Runs as a foreground service and only listens to the accelerometer while the screen is on.
 */
class ShakeService : Service(), SensorEventListener {

    private lateinit var sensors: SensorManager
    private var accel: Sensor? = null
    private var listening = false

    // A "shake" = SHAKES_NEEDED jolts above THRESHOLD_G, each at least JOLT_GAP_MS apart,
    // all within WINDOW_MS. Then wait COOLDOWN_MS before another shake can trigger.
    private val THRESHOLD_G = 2.5f
    private val SHAKES_NEEDED = 3
    private val JOLT_GAP_MS = 120L
    private val WINDOW_MS = 1200L
    private val COOLDOWN_MS = 3000L

    private val jolts = ArrayDeque<Long>()
    private var lastTrigger = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> startListening()
                Intent.ACTION_SCREEN_OFF -> stopListening()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        goForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.shakeEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (listening || accel == null) return
        listening = sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopListening() {
        if (!listening) return
        sensors.unregisterListener(this)
        listening = false
        jolts.clear()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        if (jolts.isNotEmpty() && now - jolts.last() < JOLT_GAP_MS) return // same jolt, still peaking

        jolts.addLast(now)
        while (jolts.isNotEmpty() && now - jolts.first() > WINDOW_MS) jolts.removeFirst()

        if (jolts.size >= SHAKES_NEEDED) {
            jolts.clear()
            lastTrigger = now
            vibrate()
            openPopup(this)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibrate() {
        try {
            val effect = VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
            }
        } catch (_: Exception) {}
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Shake to add", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps shake detection running"
                setShowBadge(false)
            }
        )
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val add = PendingIntent.getActivity(
            this, 2, Intent(this, ExpenseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ShakeTrack is ready")
            .setContentText("Shake your phone to add an expense")
            .setContentIntent(openApp)
            .addAction(0, "Add now", add)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    companion object {
        private const val CHANNEL = "shake"
        private const val NOTIF_ID = 7

        fun start(ctx: Context) {
            if (!Prefs.shakeEnabled(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ShakeService::class.java))
            } catch (_: Exception) {
                // Android may refuse to start it from the background; it starts next time the app opens.
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ShakeService::class.java))
        }

        fun openPopup(ctx: Context) {
            val i = Intent(ctx, ExpenseActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            try {
                ctx.startActivity(i) // allowed from the background because of "Display over other apps"
            } catch (_: Exception) {}
        }
    }
}

/** Restarts shake detection after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ShakeService.start(c)
        }
    }
}
