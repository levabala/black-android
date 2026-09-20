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
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var interval: EditText
    private lateinit var intervalUnit: Spinner
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
        window.decorView.windowInsetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
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

        val intervalIsMinutes = saved.intervalMillis % 60_000L == 0L
        interval = numberField(root, "Interval", if (intervalIsMinutes) saved.intervalMillis / 60_000 else saved.intervalMillis / 1_000)
        intervalUnit = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Minutes", "Seconds"))
            setSelection(if (intervalIsMinutes) 0 else 1)
        }
        root.addView(intervalUnit)
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
        val intervalValue = interval.text.toString().toLongOrNull()
        val warningSeconds = warning.text.toString().toLongOrNull()
        val blackoutSeconds = blackout.text.toString().toLongOrNull()
        val intervalLimit = if (intervalUnit.selectedItemPosition == 0) 1440L else 86_400L
        if (intervalValue == null || intervalValue !in 1..intervalLimit || warningSeconds == null || warningSeconds !in 10..300 ||
            blackoutSeconds == null || blackoutSeconds !in 1..300) {
            Toast.makeText(this, "Use 1–1440 minutes or 1–86400 seconds, a 10–300 second warning, and a 1–300 second blackout", Toast.LENGTH_LONG).show()
            return false
        }
        val intervalMillis = intervalValue * if (intervalUnit.selectedItemPosition == 0) 60_000L else 1_000L
        store.save(BlackSettings(intervalMillis, warningSeconds * 1_000,
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
