package com.levabala.blackandroid

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class ReliabilityActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var battery: TextView
    private lateinit var resume: Button
    private lateinit var exempt: Button
    private lateinit var store: SettingsStore
    private var pending = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        root.addView(TextView(this).apply { text = getString(R.string.reliability_title); textSize = 26f })
        state = text(root, "")
        resume = button(root, R.string.recovery_resume) {
            if (!store.enabled || pending) return@button
            if (!Settings.canDrawOverlays(this)) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                pending = true
                resume.isEnabled = false
                resume.setText(R.string.status_starting)
                ServiceCommands.send(this, BlackService.ACTION_START) { error ->
                    if (!isDestroyed) {
                        pending = false
                        if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
            }
        }
        battery = text(root, "")
        exempt = button(root, R.string.reliability_allow_background) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        button(root, R.string.reliability_battery_settings) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button(root, R.string.reliability_app_info) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val xiaomi = listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.lowercase() in setOf("xiaomi", "redmi", "poco")
        }
        text(root, getString(if (xiaomi) R.string.reliability_xiaomi_steps else R.string.reliability_other_steps))
        if (xiaomi) {
            button(root, R.string.reliability_autostart) {
                open(Intent().setComponent(ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            }
            button(root, R.string.reliability_security) {
                open(packageManager.getLaunchIntentForPackage("com.miui.securitycenter")
                    ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        text(root, getString(R.string.reliability_limits))
        button(root, R.string.tutorial_done) { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
        applySafeArea(root)
    }

    private fun refresh() {
        if (!::resume.isInitialized) return
        val power = getSystemService(PowerManager::class.java)
        val actual = getString(when {
            BlackService.running -> R.string.reliability_running
            store.enabled -> R.string.recovery_waiting
            else -> R.string.status_stopped
        })
        val heartbeat = if (store.lastServiceHeartbeat == 0L) getString(R.string.reliability_no_heartbeat)
            else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(Date(store.lastServiceHeartbeat))
        state.text = getString(R.string.reliability_state, actual, heartbeat)
        battery.text = getString(R.string.reliability_battery_state,
            getString(if (BackgroundRecovery.isExempt(this)) R.string.reliability_exempt else R.string.reliability_not_exempt),
            getString(if (power.isPowerSaveMode) R.string.reliability_on else R.string.reliability_off),
            getString(if (getSystemService(ActivityManager::class.java).isBackgroundRestricted)
                R.string.reliability_yes else R.string.reliability_no))
        exempt.isEnabled = !BackgroundRecovery.isExempt(this)
        resume.isEnabled = store.enabled && !BlackService.running && !pending
        if (!pending) resume.setText(R.string.recovery_resume)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: Exception) {
            AppLog.warn("recovery.settings_shortcut_unavailable", mapOf("action" to intent.action))
            Toast.makeText(this, R.string.reliability_shortcut_fallback, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("black", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    override fun onPause() {
        getSharedPreferences("black", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    private fun text(root: LinearLayout, value: String) = TextView(this).apply {
        text = value
        setPadding(0, dp(12), 0, dp(12))
        root.addView(this)
    }

    private fun button(root: LinearLayout, label: Int, clicked: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { clicked() }
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
