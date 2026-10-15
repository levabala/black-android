package com.levabala.blackandroid

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.SharedPreferences
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var summary: TextView
    private lateinit var testButton: Button
    private lateinit var testHelp: TextView
    private var pendingAction: String? = null
    private var actionGeneration = 0
    private var pendingStartPermission = false
    private var pendingTestPermission = false
    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshControls() }
    private lateinit var updateButton: Button
    private lateinit var updateStatus: TextView
    private var pendingInstallerPermission = false
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themedContext(newBase))
    }

    private fun refreshControls() {
        if (!::startStop.isInitialized) return
        status.text = when (pendingAction) {
            BlackService.ACTION_START -> getString(R.string.status_starting)
            BlackService.ACTION_STOP -> getString(R.string.status_stopping)
            BlackService.ACTION_TEST -> getString(R.string.status_test_starting)
            else -> store.status
        }
        startStop.text = getString(if (store.enabled) R.string.action_stop else R.string.action_start)
        startStop.isEnabled = pendingAction != BlackService.ACTION_STOP &&
            (pendingAction == null || store.enabled)
        testButton.isEnabled = store.enabled && !store.testing && pendingAction == null
        testHelp.text = getString(when {
            !store.enabled -> R.string.main_test_start_first
            store.testing -> R.string.main_test_running
            else -> R.string.main_test_explanation
        })
        val saved = store.load()
        summary.text = getString(R.string.main_settings_summary,
            BlackService.formatTime(saved.intervalMillis),
            BlackService.formatTime(saved.warningMillis),
            BlackService.formatTime(saved.blackoutMillis)) +
            if (store.settingsPending) "\n" + getString(R.string.settings_waiting) else ""
    }

    private fun sendAction(action: String) {
        if (pendingAction != null && action != BlackService.ACTION_STOP) return
        val generation = ++actionGeneration
        val wasEnabled = store.enabled
        pendingAction = action
        if (action == BlackService.ACTION_START) store.enabled = true
        refreshControls()
        ServiceCommands.send(this, action) { error ->
            if (isDestroyed || generation != actionGeneration) return@send
            pendingAction = null
            if (error != null) {
                if (action == BlackService.ACTION_START) store.enabled = wasEnabled
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
            refreshControls()
            if (error == null && action == BlackService.ACTION_TEST) moveTaskToBack(true)
        }
    }

    private fun startSchedule() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStartPermission = true
            AppLog.info("permission.overlay_requested")
            Toast.makeText(this, R.string.main_overlay_permission_request, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        AppLog.info("user.schedule_start")
        sendAction(BlackService.ACTION_START)
        requestNotifications()
    }

    private fun startTest() {
        if (!store.enabled) {
            Toast.makeText(this, R.string.main_test_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingTestPermission = true
            requestNotifications()
            return
        }
        AppLog.info("user.test_now")
        sendAction(BlackService.ACTION_TEST)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstallerPermission = savedInstanceState?.getBoolean("pendingInstallerPermission") ?: false
        store = SettingsStore(this)
        pendingStartPermission = savedInstanceState?.getBoolean("pendingStartPermission") ?: false
        pendingTestPermission = savedInstanceState?.getBoolean("pendingTestPermission") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        applySafeArea(root)

        root.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 30f })
        status = TextView(this).apply { textSize = 18f; setPadding(0, dp(12), 0, dp(18)) }
        root.addView(status)

        startStop = button(root, getString(R.string.action_start)) {
            if (store.enabled) {
                AppLog.info("user.schedule_stop")
                sendAction(BlackService.ACTION_STOP)
            } else startSchedule()
        }
        summary = TextView(this).apply { setPadding(0, dp(16), 0, dp(8)); textSize = 16f }
        root.addView(summary)
        button(root, getString(R.string.main_settings)) {
            AppLog.info("user.settings_opened")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        testButton = button(root, getString(R.string.main_test_all)) { startTest() }
        testHelp = TextView(this).apply { setPadding(0, 0, 0, dp(16)) }
        root.addView(testHelp)
        refreshControls()
        button(root, getString(R.string.main_app_exceptions)) {
            AppLog.info("user.exceptions_opened")
            startActivity(Intent(this, ExceptionsActivity::class.java))
        }
        button(root, getString(R.string.reliability_title)) {
            startActivity(Intent(this, ReliabilityActivity::class.java))
        }
        updateStatus = TextView(this).apply {
            text = getString(R.string.main_updates_explanation)
            setPadding(0, dp(18), 0, dp(6))
        }
        root.addView(updateStatus)
        updateButton = button(root, getString(R.string.main_check_updates)) { checkForUpdates() }

        root.addView(TextView(this).apply { text = getString(R.string.main_appearance); setPadding(0, dp(18), 0, 0) })
        val appearance = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.main_theme_system), getString(R.string.main_theme_light), getString(R.string.main_theme_dark)))
            setSelection(store.appearance.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = Appearance.entries[position]
                    if (store.appearance != selected) {
                        AppLog.info("user.appearance_changed", mapOf("appearance" to selected.name.lowercase()))
                        store.appearance = selected
                        recreate()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(appearance)
        if (!store.updateNotificationPermissionAsked) {
            store.updateNotificationPermissionAsked = true
            requestNotifications()
        }
        consumeUpdateNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeUpdateNotificationIntent(intent)
    }

    private fun consumeUpdateNotificationIntent(intent: Intent) {
        if (intent.action != ACTION_UPDATE_FROM_NOTIFICATION) return
        intent.action = Intent.ACTION_MAIN
        UpdateNotifications.dismiss(this)
        AppLog.info("user.update_notification_tapped")
        checkForUpdates()
    }

    private fun checkForUpdates() {
        AppLog.info("user.update_check")
        updateButton.isEnabled = false
        updateStatus.text = getString(R.string.main_checking_updates)
        val updater = AppUpdater(applicationContext)
        Thread {
            try {
                val release = updater.latestUpdate()
                if (release == null) {
                    AppLog.info("update.up_to_date")
                    runOnUiThread {
                        if (!isDestroyed) {
                            updateStatus.text = getString(R.string.main_up_to_date)
                            updateButton.isEnabled = true
                        }
                    }
                    return@Thread
                }
                AppLog.info("update.available", mapOf("target_version" to release.version.toString()))
                runOnUiThread {
                    if (!isDestroyed) updateStatus.text = getString(R.string.main_downloading_update, release.version.toString())
                }
                updater.downloadAndVerify(release)
                AppLog.info("update.download_verified", mapOf("target_version" to release.version.toString()))
                runOnUiThread {
                    if (!isDestroyed) {
                        updateStatus.text = getString(R.string.main_update_ready, release.version.toString())
                        installDownloadedUpdate()
                    }
                }
            } catch (error: Exception) {
                AppLog.error("update.failed", error)
                runOnUiThread {
                    if (!isDestroyed) {
                        updateStatus.text = getString(R.string.main_update_failed,
                            error.message ?: getString(R.string.main_unknown_error))
                        updateButton.isEnabled = true
                    }
                }
            }
        }.start()
    }

    private fun installDownloadedUpdate() {
        val file = File(cacheDir, "black-update.apk")
        if (!file.isFile) {
            AppLog.warn("update.apk_missing")
            updateStatus.text = getString(R.string.main_apk_missing)
            updateButton.isEnabled = true
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            AppLog.info("permission.install_packages_requested")
            pendingInstallerPermission = true
            updateStatus.text = getString(R.string.main_allow_installs_return)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
            return
        }
        pendingInstallerPermission = false
        AppLog.info("update.installer_preparing", mapOf("apk_bytes" to file.length()))
        updateStatus.text = getString(R.string.main_preparing_installer)
        val callbackOptions = ActivityOptions.makeBasic().apply {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            setPendingIntentCreatorBackgroundActivityStartMode(mode)
        }.toBundle()
        val callback = PendingIntent.getActivity(this, INSTALL_UPDATE_REQUEST,
            Intent(this, UpdateInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, callbackOptions)
        val updater = AppUpdater(applicationContext)
        Thread {
            try {
                updater.stageInstall(file, callback.intentSender)
                AppLog.info("update.installer_staged")
            } catch (error: Exception) {
                AppLog.error("update.installer_failed", error)
                runOnUiThread {
                    if (!isDestroyed) {
                        updateStatus.text = getString(R.string.main_installer_failed,
                            error.message ?: getString(R.string.main_unknown_error))
                        updateButton.isEnabled = true
                    }
                }
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingInstallerPermission", pendingInstallerPermission)
        outState.putBoolean("pendingStartPermission", pendingStartPermission)
        outState.putBoolean("pendingTestPermission", pendingTestPermission)
        super.onSaveInstanceState(outState)
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
            AppLog.info("service.recovery_requested", mapOf("source" to "activity_resume"))
            startForegroundService(BlackService.command(this, BlackService.ACTION_START))
        }
        getSharedPreferences("black", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(stateListener)
        refreshControls()
        if (pendingStartPermission) {
            pendingStartPermission = false
            if (Settings.canDrawOverlays(this)) startSchedule()
            else Toast.makeText(this, R.string.status_overlay_permission, Toast.LENGTH_LONG).show()
        }
        store.updateResult?.let {
            updateStatus.text = it
            updateButton.isEnabled = true
            store.updateResult = null
        }
        if (pendingInstallerPermission) {
            pendingInstallerPermission = false
            if (packageManager.canRequestPackageInstalls()) {
                AppLog.info("permission.install_packages_granted")
                installDownloadedUpdate()
            } else {
                AppLog.warn("permission.install_packages_denied")
                updateStatus.text = getString(R.string.main_allow_installs)
                updateButton.isEnabled = true
            }
        }
    }

    override fun onPause() {
        getSharedPreferences("black", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && pendingTestPermission) {
            pendingTestPermission = false
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startTest()
            else Toast.makeText(this, R.string.main_test_notifications, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_UPDATE_FROM_NOTIFICATION = "com.levabala.blackandroid.UPDATE_FROM_NOTIFICATION"
        private const val INSTALL_UPDATE_REQUEST = 2
    }
}
