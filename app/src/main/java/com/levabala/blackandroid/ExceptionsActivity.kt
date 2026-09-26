package com.levabala.blackandroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class ExceptionsActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var accessStatus: TextView
    private lateinit var restrictedHelp: TextView
    private lateinit var appInfoButton: Button
    private lateinit var usageButton: Button
    private lateinit var empty: TextView
    private lateinit var list: ListView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        root.addView(TextView(this).apply { text = getString(R.string.exceptions_title); textSize = 26f })
        root.addView(TextView(this).apply {
            text = getString(R.string.exceptions_explanation)
            setPadding(0, dp(12), 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = getString(R.string.exceptions_tutorial)
            setOnClickListener { startActivity(Intent(this@ExceptionsActivity, ExceptionsTutorialActivity::class.java)) }
        }, matchWidth())
        accessStatus = TextView(this).apply { setPadding(0, 0, 0, dp(6)) }
        root.addView(accessStatus)
        restrictedHelp = TextView(this).apply {
            text = getString(R.string.exceptions_restricted_help)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(restrictedHelp)
        appInfoButton = Button(this).apply {
            text = getString(R.string.exceptions_open_app_info)
            setOnClickListener {
                AppLog.info("permission.restricted_settings_help_opened")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
        }
        root.addView(appInfoButton, matchWidth())
        usageButton = Button(this).apply {
            text = getString(R.string.exceptions_grant_usage)
            setOnClickListener {
                AppLog.info("permission.usage_access_requested")
                startActivity(AppCatalog.usageAccessIntent())
            }
        }
        root.addView(usageButton, matchWidth())
        root.addView(Button(this).apply {
            text = getString(R.string.exceptions_add_app)
            setOnClickListener { startActivity(Intent(this@ExceptionsActivity, AppPickerActivity::class.java)) }
        }, matchWidth())
        empty = TextView(this).apply {
            text = getString(R.string.exceptions_empty)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        root.addView(empty, matchWidth())
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = AppCatalog.usageAccessGranted(this)
        accessStatus.text = if (granted) {
            getString(R.string.exceptions_granted)
        } else {
            getString(R.string.exceptions_required)
        }
        restrictedHelp.visibility = if (granted) View.GONE else View.VISIBLE
        appInfoButton.visibility = if (granted) View.GONE else View.VISIBLE
        usageButton.text = getString(if (granted) R.string.exceptions_usage_settings else R.string.exceptions_grant_usage)
        val packages = store.exceptionPackages.sortedBy { AppCatalog.label(this, it).lowercase() }
        empty.visibility = if (packages.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = ExceptionAdapter(packages)
    }

    private inner class ExceptionAdapter(private val packages: List<String>) : BaseAdapter() {
        override fun getCount() = packages.size
        override fun getItem(position: Int) = packages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val packageName = packages[position]
            return LinearLayout(this@ExceptionsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(LinearLayout(this@ExceptionsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@ExceptionsActivity).apply {
                        text = AppCatalog.label(this@ExceptionsActivity, packageName)
                        textSize = 18f
                    })
                    addView(TextView(this@ExceptionsActivity).apply {
                        text = packageName
                        textSize = 12f
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@ExceptionsActivity).apply {
                    text = getString(R.string.exceptions_remove)
                    contentDescription = getString(R.string.exceptions_remove_description,
                        AppCatalog.label(this@ExceptionsActivity, packageName))
                    setOnClickListener {
                        store.removeException(packageName)
                        AppLog.info("user.exception_removed", mapOf("package_name" to packageName))
                        refresh()
                    }
                })
            }
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun themedContext(base: Context): Context {
    val night = when (SettingsStore(base).appearance) {
        Appearance.SYSTEM -> return base
        Appearance.LIGHT -> Configuration.UI_MODE_NIGHT_NO
        Appearance.DARK -> Configuration.UI_MODE_NIGHT_YES
    }
    val config = Configuration(base.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    }
    return base.createConfigurationContext(config)
}
