package io.github.sylphinford.wakekeeper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns two independent capabilities:
 *
 * 1. **Keep awake** — a [PowerManager.PARTIAL_WAKE_LOCK]. The CPU keeps running while the
 *    screen is off; the screen itself is free to turn off.
 * 2. **Keep screen on** — a one-pixel overlay window carrying
 *    [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]. A window flag only lives as long as its
 *    window is visible, so parking the flag on an always-on-top overlay is what makes "don't
 *    let the screen sleep" work while other apps are in front.
 *
 * The user's intent lives in SharedPreferences ([KEY_AWAKE] / [KEY_SCREEN]) so the service can
 * rebuild itself after being killed ([START_STICKY] restarts us with a null intent).
 */
class WakeKeeperService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> prefs().edit()
                .putBoolean(KEY_AWAKE, false)
                .putBoolean(KEY_SCREEN, false)
                .apply()

            ACTION_STOP_AWAKE -> prefs().edit().putBoolean(KEY_AWAKE, false).apply()

            ACTION_STOP_SCREEN -> prefs().edit().putBoolean(KEY_SCREEN, false).apply()

            // ACTION_START, or a sticky restart with a null intent: prefs already hold the truth.
            else -> Unit
        }

        return if (applyState()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        detachOverlay()
        awakeHeld = false
        screenHeld = false
        startedAtElapsed = 0L
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Reconciles the real device state with the stored intent. Returns whether to keep running. */
    private fun applyState(): Boolean {
        val wantAwake = prefs().getBoolean(KEY_AWAKE, false)
        var wantScreen = prefs().getBoolean(KEY_SCREEN, false)

        // The overlay permission can be revoked at any time from system settings.
        if (wantScreen && !Settings.canDrawOverlays(this)) {
            wantScreen = false
            prefs().edit().putBoolean(KEY_SCREEN, false).apply()
        }

        if (!wantAwake && !wantScreen) {
            stopSelf()
            return false
        }

        if (wantAwake) acquireWakeLock() else releaseWakeLock()
        if (wantScreen) attachOverlay() else detachOverlay()

        awakeHeld = wantAwake
        screenHeld = wantScreen && overlayView != null

        if (startedAtElapsed == 0L) {
            startedAtElapsed = SystemClock.elapsedRealtime()
        }

        // Also refreshes the notification, so its actions always match the live state.
        goForeground()
        return true
    }

    // ---------------------------------------------------------------- wake lock

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    // ---------------------------------------------------------------- screen overlay

    private fun attachOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager

        // A hair above fully transparent: some OEM skins skip compositing a zero-alpha overlay,
        // which would silently drop the keep-screen-on flag.
        val view = View(this).apply { setBackgroundColor(0x01000000) }

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        if (runCatching { manager.addView(view, params) }.isSuccess) {
            overlayView = view
        }
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        overlayView = null
    }

    // ---------------------------------------------------------------- notification

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val awake = awakeHeld
        val screen = screenHeld

        val title: String
        val text: String
        when {
            awake && screen -> {
                title = getString(R.string.notif_title_both)
                text = getString(R.string.notif_text_both)
            }

            screen -> {
                title = getString(R.string.notif_title_screen)
                text = getString(R.string.notif_text_screen)
            }

            else -> {
                title = getString(R.string.notif_title_awake)
                text = getString(R.string.notif_text_awake)
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Each capability gets its own off switch; the combined one only shows up when both run.
        if (screen) {
            builder.addAction(
                0,
                getString(R.string.notif_stop_screen),
                commandIntent(ACTION_STOP_SCREEN, 12)
            )
        }
        if (awake) {
            builder.addAction(
                0,
                getString(R.string.notif_stop_awake),
                commandIntent(ACTION_STOP_AWAKE, 11)
            )
        }
        if (awake && screen) {
            builder.addAction(
                0,
                getString(R.string.notif_stop_all),
                commandIntent(ACTION_STOP_ALL, 13)
            )
        }

        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun commandIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, WakeKeeperService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        const val ACTION_START = "io.github.sylphinford.wakekeeper.action.START"
        const val ACTION_STOP_ALL = "io.github.sylphinford.wakekeeper.action.STOP_ALL"
        const val ACTION_STOP_AWAKE = "io.github.sylphinford.wakekeeper.action.STOP_AWAKE"
        const val ACTION_STOP_SCREEN = "io.github.sylphinford.wakekeeper.action.STOP_SCREEN"

        const val PREFS_NAME = "wakekeeper_prefs"
        const val KEY_AWAKE = "keep_awake"
        const val KEY_SCREEN = "keep_screen"

        private const val CHANNEL_ID = "keep_awake"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "WakeKeeper::keepAwake"

        /** Single source of truth for the UI: what is actually in effect right now. */
        @Volatile
        var awakeHeld: Boolean = false
            private set

        @Volatile
        var screenHeld: Boolean = false
            private set

        @Volatile
        private var startedAtElapsed: Long = 0L

        fun elapsedMillis(): Long =
            if (startedAtElapsed > 0L) {
                SystemClock.elapsedRealtime() - startedAtElapsed
            } else {
                0L
            }

        fun start(context: Context) {
            val intent = Intent(context, WakeKeeperService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeKeeperService::class.java))
        }
    }
}
