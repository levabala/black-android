package com.levabala.blackandroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var interval: EditText
    private lateinit var warning: EditText
    private lateinit var blackout: EditText
    private lateinit var pauseMic: Switch
    private lateinit var resetLock: Switch
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = store.status
            startStop.text = if (store.enabled) "Stop" else "Start"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val saved = store.load()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Black"; textSize = 30f })
        status = TextView(this).apply { textSize = 18f; setPadding(0, dp(12), 0, dp(18)) }
        root.addView(status)

        interval = numberField(root, "Interval (minutes)", saved.intervalMillis / 60_000)
        warning = numberField(root, "Warning (seconds)", saved.warningMillis / 1_000)
        blackout = numberField(root, "Blackout (seconds)", saved.blackoutMillis / 1_000)
        pauseMic = Switch(this).apply { text = "Pause while microphone is in use"; isChecked = saved.pauseForMicrophone }
        resetLock = Switch(this).apply { text = "Reset timer after lock and unlock"; isChecked = saved.resetAfterLock }
        root.addView(pauseMic)
        root.addView(resetLock)

        val explanation = TextView(this).apply {
            text = "The blackout covers app content. Android may keep system bars and the keyboard visible. Tap the blackout three times quickly to dismiss it."
            setPadding(0, dp(18), 0, dp(18))
        }
        root.addView(explanation)

        startStop = button(root, "Start") {
            if (store.enabled) {
                store.enabled = false
                store.status = "Stopped"
                startService(BlackService.command(this, BlackService.ACTION_STOP))
            } else if (saveSettings()) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Allow Black to display over other apps, then tap Start again", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                    return@button
                }
                store.enabled = true
                store.status = "Starting"
                startForegroundService(BlackService.command(this, BlackService.ACTION_START))
                requestNotifications()
            }
        }
        button(root, "Save settings") {
            if (saveSettings() && store.enabled) startService(BlackService.command(this, BlackService.ACTION_UPDATE))
        }
        button(root, "Test now") {
            if (!store.enabled) {
                Toast.makeText(this, "Start the schedule first", Toast.LENGTH_SHORT).show()
            } else {
                startService(BlackService.command(this, BlackService.ACTION_TEST))
                moveTaskToBack(true)
            }
        }
    }

    private fun saveSettings(): Boolean {
        val minutes = interval.text.toString().toLongOrNull()
        val warningSeconds = warning.text.toString().toLongOrNull()
        val blackoutSeconds = blackout.text.toString().toLongOrNull()
        if (minutes == null || minutes !in 1..1440 || warningSeconds == null || warningSeconds !in 1..300 ||
            blackoutSeconds == null || blackoutSeconds !in 1..300) {
            Toast.makeText(this, "Use 1–1440 minutes and 1–300 seconds", Toast.LENGTH_LONG).show()
            return false
        }
        store.save(BlackSettings(minutes * 60_000, warningSeconds * 1_000,
            blackoutSeconds * 1_000, pauseMic.isChecked, resetLock.isChecked))
        return true
    }

    private fun numberField(parent: LinearLayout, label: String, value: Long): EditText {
        parent.addView(TextView(this).apply { text = label; setPadding(0, dp(12), 0, 0) })
        return EditText(this).also {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.setSingleLine(true)
            it.setText(value.toString())
            parent.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun button(parent: LinearLayout, title: String, click: () -> Unit): Button =
        Button(this).also {
            it.text = title
            it.gravity = Gravity.CENTER
            it.setOnClickListener { click() }
            parent.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun requestNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
