package com.levabala.blackandroid

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle

/** Receives installer callbacks privately, then opens Android's confirmation screen. */
class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        AppLog.info("update.installer_status", mapOf(
            "status" to status,
            "status_message" to (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""),
        ))
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    AppLog.warn("update.confirmation_missing")
                    showResult(getString(R.string.installer_prompt_missing))
                } else {
                    try {
                        AppLog.info("update.confirmation_opened")
                        startActivity(confirmation)
                    } catch (error: Exception) {
                        AppLog.error("update.confirmation_failed", error)
                        showResult(getString(R.string.installer_open_failed,
                            error.message ?: getString(R.string.main_unknown_error)))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> showResult(getString(R.string.installer_installed))
            else -> showResult(getString(R.string.installer_failed,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: getString(R.string.installer_unknown_reason)))
        }
        finish()
    }

    private fun showResult(message: String) {
        SettingsStore(this).updateResult = message
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
