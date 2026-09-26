package com.levabala.blackandroid

import android.app.Activity
import android.view.View
import android.view.WindowInsets

/** Keep app controls outside system bars and camera cutouts on edge-to-edge Android. */
fun Activity.applySafeArea(view: View) {
    window.setDecorFitsSystemWindows(false)
    val left = view.paddingLeft
    val top = view.paddingTop
    val right = view.paddingRight
    val bottom = view.paddingBottom
    view.setOnApplyWindowInsetsListener { target, insets ->
        val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        target.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
        insets
    }
    view.requestApplyInsets()
}
