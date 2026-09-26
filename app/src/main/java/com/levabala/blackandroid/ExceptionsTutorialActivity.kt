package com.levabala.blackandroid

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Screenshot guide for the system permission flow used by app exceptions. */
class ExceptionsTutorialActivity : Activity() {
    private var step = 0
    private lateinit var count: TextView
    private lateinit var heading: TextView
    private lateinit var explanation: TextView
    private lateinit var screenshot: ImageView
    private lateinit var back: Button
    private lateinit var next: Button

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt("step")?.coerceIn(0, STEPS.lastIndex) ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.tutorial_title)
            textSize = 26f
        })
        count = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(count)
        heading = TextView(this).apply { textSize = 20f }
        root.addView(heading)
        explanation = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(explanation)
        screenshot = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(screenshot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        back = Button(this).apply {
            setOnClickListener {
                step--
                render()
            }
        }
        next = Button(this).apply {
            setOnClickListener {
                if (step == STEPS.lastIndex) finish() else {
                    step++
                    render()
                }
            }
        }
        controls.addView(back, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(next, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        setContentView(root)
        applySafeArea(root)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("step", step)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val item = STEPS[step]
        count.text = getString(R.string.tutorial_step_count, step + 1, STEPS.size)
        heading.text = getString(item.title)
        explanation.text = getString(item.body)
        screenshot.setImageResource(item.image)
        screenshot.contentDescription = getString(item.title)
        back.text = getString(R.string.tutorial_back)
        back.isEnabled = step > 0
        next.text = getString(if (step == STEPS.lastIndex) R.string.tutorial_done else R.string.tutorial_next)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class Step(val title: Int, val body: Int, val image: Int)

    private companion object {
        val STEPS = listOf(
            Step(R.string.tutorial_step1_title, R.string.tutorial_step1_body, R.drawable.guide_denied),
            Step(R.string.tutorial_step2_title, R.string.tutorial_step2_body, R.drawable.guide_exceptions),
            Step(R.string.tutorial_step3_title, R.string.tutorial_step3_body, R.drawable.guide_app_info),
            Step(R.string.tutorial_step4_title, R.string.tutorial_step4_body, R.drawable.guide_usage_blocked),
            Step(R.string.tutorial_step5_title, R.string.tutorial_step5_body, R.drawable.guide_exceptions_active),
        )
    }
}
