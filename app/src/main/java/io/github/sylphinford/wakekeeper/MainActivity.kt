package io.github.sylphinford.wakekeeper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.sylphinford.wakekeeper.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy {
        getSharedPreferences(WakeKeeperService.PREFS_NAME, MODE_PRIVATE)
    }
    private val handler = Handler(Looper.getMainLooper())

    /** Toggles land faster than the service can start; trust the stored intent briefly. */
    private var lastToggleAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The service runs either way; a denied permission only hides the notification.
        }

    private val onMainToggled = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) ensureNotificationPermission()
        lastToggleAt = SystemClock.elapsedRealtime()
        prefs.edit().putBoolean(WakeKeeperService.KEY_AWAKE, checked).apply()
        syncService()
        handler.postDelayed({ render() }, 300L)
    }

    private val onScreenToggled = CompoundButton.OnCheckedChangeListener { _, checked ->
        lastToggleAt = SystemClock.elapsedRealtime()
        if (checked) {
            requestScreenOn()
        } else {
            prefs.edit().putBoolean(WakeKeeperService.KEY_SCREEN, false).apply()
            syncService()
            handler.postDelayed({ render() }, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowInsets()

        binding.mainSwitch.setOnCheckedChangeListener(onMainToggled)
        binding.screenSwitch.setOnCheckedChangeListener(onScreenToggled)
        binding.batteryHint.setOnClickListener { openBatterySettings() }
        binding.licensesLink.setOnClickListener { showLicenses() }

        render()
    }

    override fun onResume() {
        super.onResume()
        resolvePendingOverlayRequest()
        render()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun applyWindowInsets() {
        binding.scroll.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // ---------------------------------------------------------------- screen-on flow

    /**
     * Keeping the screen on across other apps needs an overlay window: a window flag only holds
     * while its own window is visible. So this path is gated on the "draw over other apps"
     * permission, which has no runtime dialog and must be granted from system settings.
     */
    private fun requestScreenOn() {
        if (Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean(WakeKeeperService.KEY_SCREEN, true).apply()
            syncService()
            handler.postDelayed({ render() }, 300L)
            return
        }

        // Bounce the switch back; it flips on again once the user grants the permission.
        setSwitchSilently(binding.screenSwitch, false, onScreenToggled)
        prefs.edit().putBoolean(PENDING_OVERLAY, true).apply()

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", packageName, null)
        )
        if (runCatching { startActivity(intent) }.isFailure) {
            prefs.edit().putBoolean(PENDING_OVERLAY, false).apply()
            Toast.makeText(this, R.string.overlay_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun resolvePendingOverlayRequest() {
        if (!prefs.getBoolean(PENDING_OVERLAY, false)) return
        prefs.edit().putBoolean(PENDING_OVERLAY, false).apply()

        if (Settings.canDrawOverlays(this)) {
            lastToggleAt = SystemClock.elapsedRealtime()
            prefs.edit().putBoolean(WakeKeeperService.KEY_SCREEN, true).apply()
            syncService()
            Toast.makeText(this, R.string.overlay_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.overlay_denied, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- service plumbing

    private fun syncService() {
        val anyOn = prefs.getBoolean(WakeKeeperService.KEY_AWAKE, false) ||
            prefs.getBoolean(WakeKeeperService.KEY_SCREEN, false)
        if (anyOn) WakeKeeperService.start(this) else WakeKeeperService.stop(this)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Mirrors the live service state onto the ring, the timer and both switches. */
    private fun render() {
        val settled = SystemClock.elapsedRealtime() - lastToggleAt > 1500L
        val awake = if (settled) {
            WakeKeeperService.awakeHeld
        } else {
            prefs.getBoolean(WakeKeeperService.KEY_AWAKE, false)
        }
        val screen = if (settled) {
            WakeKeeperService.screenHeld
        } else {
            prefs.getBoolean(WakeKeeperService.KEY_SCREEN, false)
        }

        if (awake || screen) {
            binding.ring.setBackgroundResource(R.drawable.ring_on)
            binding.stateText.setTextColor(ContextCompat.getColor(this, R.color.accent))
            binding.durationText.setTextColor(ContextCompat.getColor(this, R.color.accent_soft))
            binding.stateText.setText(
                when {
                    awake && screen -> R.string.state_both
                    screen -> R.string.state_screen
                    else -> R.string.state_on
                }
            )
            binding.durationText.text = formatDuration(WakeKeeperService.elapsedMillis())
        } else {
            binding.ring.setBackgroundResource(R.drawable.ring_off)
            binding.stateText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.durationText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.stateText.setText(R.string.state_off)
            binding.durationText.setText(R.string.duration_idle)
        }

        setSwitchSilently(binding.mainSwitch, awake, onMainToggled)
        setSwitchSilently(binding.screenSwitch, screen, onScreenToggled)
    }

    private fun setSwitchSilently(
        view: CompoundButton,
        checked: Boolean,
        listener: CompoundButton.OnCheckedChangeListener
    ) {
        if (view.isChecked == checked) return
        view.setOnCheckedChangeListener(null)
        view.isChecked = checked
        view.setOnCheckedChangeListener(listener)
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            totalSeconds / 3600L,
            (totalSeconds % 3600L) / 60L,
            totalSeconds % 60L
        )
    }

    private fun openBatterySettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    // ---------------------------------------------------------------- licenses

    /**
     * Apache-2.0 section 4(a) requires handing a copy of the license to whoever receives the
     * app, so the full text ships as an asset and is readable without a network connection.
     * The asset is produced by tools/generate_notices.py from the resolved dependency graph.
     */
    private fun showLicenses() {
        val text = runCatching {
            assets.open(LICENSES_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { getString(R.string.licenses_missing) }

        val content = layoutInflater.inflate(R.layout.dialog_licenses, null)
        content.findViewById<TextView>(R.id.licensesText).text = text

        // The dialog panel measures its custom view with MATCH_PARENT, so the ScrollView fills
        // the window and the window grows to the available height by itself. Nothing to size here.
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.licenses_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private companion object {
        const val PENDING_OVERLAY = "pending_overlay_grant"
        const val LICENSES_ASSET = "third_party_licenses.txt"
    }
}
