package com.levabala.blackandroid

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

class OverlayController(private val context: Context, private val onCancel: () -> Unit) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var currentView: View? = null
    private var currentPhase: Phase? = null
    private var warningText: TextView? = null
    private var tapCount = 0
    private var firstTapAt = 0L

    fun showWarning(secondsRemaining: Long, instruction: String? = null) {
        if (currentPhase != Phase.WARNING) {
            remove()
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setBackgroundColor(Color.BLACK)
                elevation = dp(8).toFloat()
            }
            warningText = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
            }
            layout.addView(warningText)
            layout.addView(Button(context).apply {
                text = "Cancel blackout"
                setOnClickListener { onCancel() }
            })
            add(layout, false)
            currentPhase = Phase.WARNING
        }
        warningText?.text = listOfNotNull(instruction, "Blackout in $secondsRemaining seconds").joinToString("\n")
    }

    fun showBlackout() {
        if (currentPhase == Phase.BLACKOUT) return
        remove()
        tapCount = 0
        firstTapAt = 0
        val black = View(context).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Blackout. Tap three times quickly to cancel."
            setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - firstTapAt > 1_200) {
                    firstTapAt = now
                    tapCount = 0
                }
                tapCount++
                if (tapCount >= 3) {
                    tapCount = 0
                    onCancel()
                }
            }
        }
        add(black, true)
        currentPhase = Phase.BLACKOUT
    }

    private fun add(view: View, fullScreen: Boolean) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (fullScreen) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
            if (fullScreen) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (fullScreen) Gravity.FILL else Gravity.TOP
            if (fullScreen) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        windowManager.addView(view, params)
        currentView = view
    }

    fun remove() {
        currentView?.let { windowManager.removeViewImmediate(it) }
        currentView = null
        warningText = null
        currentPhase = null
    }

    private fun dp(value: Int): Int = max(1, (value * density).toInt())
}
