package com.levabala.blackandroid

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var interval: EditText
    private lateinit var intervalUnit: Spinner
    private lateinit var warning: EditText
    private lateinit var blackout: EditText
    private lateinit var pauseMic: Switch
    private lateinit var updateButton: Button
    private lateinit var updateStatus: TextView
    private var pendingInstallerPermission = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = store.status
            startStop.text = if (store.enabled) "Stop" else "Start"
            handler.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val night = when (SettingsStore(newBase).appearance) {
            Appearance.SYSTEM -> null
            Appearance.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Appearance.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        if (night == null) {
            super.attachBaseContext(newBase)
        } else {
            val config = Configuration(newBase.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstallerPermission = savedInstanceState?.getBoolean("pendingInstallerPermission") ?: false
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
        root.addView(pauseMic)

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
        updateStatus = TextView(this).apply {
            text = "Updates are downloaded from GitHub Releases."
            setPadding(0, dp(18), 0, dp(6))
        }
        root.addView(updateStatus)
        updateButton = button(root, "Check for updates") { checkForUpdates() }

        root.addView(TextView(this).apply { text = "Appearance"; setPadding(0, dp(18), 0, 0) })
        val appearance = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("System", "Light", "Dark"))
            setSelection(store.appearance.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = Appearance.entries[position]
                    if (store.appearance != selected) {
                        store.appearance = selected
                        recreate()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(appearance)
    }

    private fun checkForUpdates() {
        updateButton.isEnabled = false
        updateStatus.text = "Checking GitHub Releases…"
        val updater = AppUpdater(applicationContext)
        Thread {
            try {
                val release = updater.latestUpdate()
                if (release == null) {
                    runOnUiThread {
                        if (!isDestroyed) {
                            updateStatus.text = "Black is up to date."
                            updateButton.isEnabled = true
                        }
                    }
                    return@Thread
                }
                runOnUiThread {
                    if (!isDestroyed) updateStatus.text = "Downloading Black ${release.version}…"
                }
                updater.downloadAndVerify(release)
                runOnUiThread {
                    if (!isDestroyed) {
                        updateStatus.text = "Black ${release.version} is ready to install."
                        installDownloadedUpdate()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        updateStatus.text = "Update failed: ${error.message ?: "unknown error"}"
                        updateButton.isEnabled = true
                    }
                }
            }
        }.start()
    }

    private fun installDownloadedUpdate() {
        val file = File(cacheDir, "black-update.apk")
        if (!file.isFile) {
            updateStatus.text = "The downloaded APK is gone. Check for updates again."
            updateButton.isEnabled = true
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallerPermission = true
            updateStatus.text = "Allow installs from Black in Android settings, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
            return
        }
        pendingInstallerPermission = false
        updateStatus.text = "Preparing Android's installer…"
        val callback = PendingIntent.getActivity(this, INSTALL_UPDATE_REQUEST,
            Intent(this, UpdateInstallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val updater = AppUpdater(applicationContext)
        Thread {
            try {
                updater.stageInstall(file, callback.intentSender)
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) {
                        updateStatus.text = "Could not start installation: ${error.message ?: "unknown error"}"
                        updateButton.isEnabled = true
                    }
                }
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingInstallerPermission", pendingInstallerPermission)
        super.onSaveInstanceState(outState)
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
            blackoutSeconds * 1_000, pauseMic.isChecked))
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
        // Package installers and device-specific process management can kill the foreground
        // service while leaving the persisted enabled flag intact. Starting an already-running
        // service is harmless; if it was lost, this restores the schedule while the app is visible.
        if (store.enabled && Settings.canDrawOverlays(this)) {
            startForegroundService(BlackService.command(this, BlackService.ACTION_START))
        }
        handler.post(refresh)
        store.updateResult?.let {
            updateStatus.text = it
            updateButton.isEnabled = true
            store.updateResult = null
        }
        if (pendingInstallerPermission) {
            pendingInstallerPermission = false
            if (packageManager.canRequestPackageInstalls()) {
                installDownloadedUpdate()
            } else {
                updateStatus.text = "Allow installs from Black to install the downloaded update."
                updateButton.isEnabled = true
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INSTALL_UPDATE_REQUEST = 2
    }
}
