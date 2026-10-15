package com.levabala.blackandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

class SettingsActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var interval: EditText
    private lateinit var intervalUnit: Spinner
    private lateinit var warning: EditText
    private lateinit var blackout: EditText
    private lateinit var pauseMic: Switch
    private lateinit var paintedOverlay: Switch
    private lateinit var done: Button
    private lateinit var originalDraft: List<String>
    private lateinit var feedback: TextView
    private var submitting = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val saved = store.load()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        root.addView(TextView(this).apply { text = getString(R.string.main_settings); textSize = 26f })
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(fields) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val minutes = saved.intervalMillis % 60_000L == 0L
        interval = numberField(fields, R.string.main_interval,
            saved.intervalMillis / if (minutes) 60_000 else 1_000, 101)
        intervalUnit = Spinner(this).apply {
            id = 102
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.main_minutes), getString(R.string.main_seconds)))
            setSelection(if (minutes) 0 else 1)
        }
        fields.addView(intervalUnit)
        warning = numberField(fields, R.string.main_warning_seconds, saved.warningMillis / 1_000, 103)
        blackout = numberField(fields, R.string.main_blackout_seconds, saved.blackoutMillis / 1_000, 104)
        pauseMic = Switch(this).apply {
            id = 105
            text = getString(R.string.main_pause_microphone)
            isChecked = saved.pauseForMicrophone
        }
        fields.addView(pauseMic)
        paintedOverlay = Switch(this).apply {
            id = 106
            text = getString(R.string.main_painted_cancel_overlay)
            isChecked = saved.paintedCancelOverlay
        }
        fields.addView(paintedOverlay)
        originalDraft = draft()
        fields.addView(TextView(this).apply {
            text = getString(R.string.settings_timing_explanation)
            setPadding(0, dp(16), 0, dp(8))
        })
        fields.addView(TextView(this).apply {
            text = getString(R.string.main_blackout_explanation)
            setPadding(0, dp(8), 0, dp(16))
        })
        feedback = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(feedback)
        done = Button(this).apply {
            text = getString(R.string.tutorial_done)
            setOnClickListener { save() }
        }
        root.addView(done, LinearLayout.LayoutParams(-1, -2))
        blackout.imeOptions = EditorInfo.IME_ACTION_DONE
        blackout.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        setContentView(root)
        applySafeArea(root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (!submitting) {
                if (draft() == originalDraft) finish()
                else AlertDialog.Builder(this)
                    .setMessage(R.string.settings_discard_question)
                    .setNegativeButton(R.string.settings_keep_editing, null)
                    .setPositiveButton(R.string.settings_discard) { _, _ -> finish() }
                    .show()
            }
        }
    }

    private fun draft(): List<String> = listOf(interval.text.toString(),
        intervalUnit.selectedItemPosition.toString(), warning.text.toString(), blackout.text.toString(),
        pauseMic.isChecked.toString(), paintedOverlay.isChecked.toString())

    private fun save() {
        if (submitting) return
        val limit = if (intervalUnit.selectedItemPosition == 0) 1440L else 86_400L
        val checks = listOf(Triple(interval, 1L, limit), Triple(warning, 10L, 300L), Triple(blackout, 1L, 300L))
        var firstInvalid: EditText? = null
        for ((field, minimum, maximum) in checks) {
            val value = field.text.toString().toLongOrNull()
            field.error = if (value == null || value !in minimum..maximum) {
                if (firstInvalid == null) firstInvalid = field
                getString(R.string.settings_range_error, minimum, maximum)
            } else null
        }
        if (firstInvalid != null) {
            firstInvalid.requestFocus()
            AppLog.warn("settings.validation_failed")
            return
        }
        val updated = BlackSettings(
            interval.text.toString().toLong() * if (intervalUnit.selectedItemPosition == 0) 60_000L else 1_000L,
            warning.text.toString().toLong() * 1_000,
            blackout.text.toString().toLong() * 1_000,
            pauseMic.isChecked,
            paintedOverlay.isChecked,
        )
        if (updated == store.load() && !store.settingsPending) { finish(); return }
        submitting = true
        done.isEnabled = false
        done.setText(R.string.settings_saving)
        listOf(interval, intervalUnit, warning, blackout, pauseMic, paintedOverlay).forEach { it.isEnabled = false }
        store.save(updated)
        originalDraft = draft()
        AppLog.info("settings.saved", mapOf(
            "interval_ms" to updated.intervalMillis,
            "warning_ms" to updated.warningMillis,
            "blackout_ms" to updated.blackoutMillis,
            "pause_for_microphone" to updated.pauseForMicrophone,
            "painted_cancel_overlay" to updated.paintedCancelOverlay,
        ))
        if (store.enabled && android.provider.Settings.canDrawOverlays(this)) {
            ServiceCommands.send(this, BlackService.ACTION_UPDATE) { error ->
                if (!isDestroyed) {
                    if (error == null) {
                        Toast.makeText(this, R.string.settings_updated, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        submitting = false
                        done.isEnabled = true
                        done.setText(R.string.tutorial_done)
                        listOf(interval, intervalUnit, warning, blackout, pauseMic, paintedOverlay).forEach { it.isEnabled = true }
                        feedback.text = getString(R.string.settings_apply_failed, error)
                    }
                }
            }
        } else {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun numberField(parent: LinearLayout, label: Int, value: Long, viewId: Int): EditText {
        val title = TextView(this).apply { setText(label); setPadding(0, dp(12), 0, 0) }
        parent.addView(title)
        return EditText(this).apply {
            id = viewId
            title.labelFor = id
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(value.toString())
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
