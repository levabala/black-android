package com.levabala.blackandroid

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle

/** Receives installer callbacks privately, then opens Android's confirmation screen. */
class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    showResult("Android did not provide an installation prompt.")
                } else {
                    try {
                        startActivity(confirmation)
                    } catch (error: Exception) {
                        showResult("Could not open Android's installer: ${error.message ?: "unknown error"}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> showResult("Update installed.")
            else -> showResult("Installation failed or was canceled: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"))
        }
        finish()
    }

    private fun showResult(message: String) {
        SettingsStore(this).updateResult = message
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
