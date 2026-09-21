package com.levabala.blackandroid

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class AppPickerActivity : Activity() {
    private lateinit var allApps: List<InstalledApp>
    private lateinit var adapter: AppAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        root.addView(TextView(this).apply { text = "Add app exception"; textSize = 30f })
        val search = EditText(this).apply {
            hint = "Search apps"
            setSingleLine(true)
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        allApps = AppCatalog.launchableApps(this)
        adapter = AppAdapter(allApps)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.apps[position]
            val added = SettingsStore(this).addException(app.packageName)
            AppLog.info("user.exception_added", mapOf(
                "source" to "app_picker",
                "package_name" to app.packageName,
                "already_present" to !added,
            ))
            Toast.makeText(this, if (added) "${app.label} added" else "${app.label} is already excepted", Toast.LENGTH_SHORT).show()
            finish()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                adapter.apps = allApps.mapNotNull { app ->
                    FuzzyMatcher.score(query, "${app.label} ${app.packageName}")?.let { it to app }
                }.sortedWith(compareByDescending<Pair<Int, InstalledApp>> { it.first }
                    .thenBy { it.second.label.lowercase() })
                    .map { it.second }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private inner class AppAdapter(var apps: List<InstalledApp>) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = apps[position]
            return LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                addView(TextView(this@AppPickerActivity).apply { text = app.label; textSize = 18f })
                addView(TextView(this@AppPickerActivity).apply { text = app.packageName; textSize = 12f })
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

object FuzzyMatcher {
    fun score(query: String, candidate: String): Int? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return 0
        val haystack = candidate.lowercase()
        val direct = haystack.indexOf(needle)
        if (direct >= 0) return 10_000 - direct

        var position = 0
        var gaps = 0
        for (character in needle) {
            val found = haystack.indexOf(character, position)
            if (found < 0) return null
            gaps += found - position
            position = found + 1
        }
        return 5_000 - gaps
    }
}
