package com.levabala.blackandroid

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock

/** A bounded acknowledgement keeps UI feedback tied to completed service work. */
internal object ServiceCommands {
    const val RESULT_RECEIVER = "command_result"
    const val ERROR = "error"

    fun send(context: Context, action: String, completed: (String?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val startedAt = SystemClock.elapsedRealtime()
        var finished = false
        fun finish(error: String?) {
            if (finished) return
            finished = true
            AppLog.info("service.command_acknowledged", mapOf(
                "command" to action,
                "duration_ms" to (SystemClock.elapsedRealtime() - startedAt),
                "success" to (error == null),
            ))
            completed(error)
        }
        val timeout = Runnable { finish(context.getString(R.string.action_timeout)) }
        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                handler.removeCallbacks(timeout)
                finish(resultData?.getString(ERROR))
            }
        }
        handler.postDelayed(timeout, 10_000)
        try {
            val command = BlackService.command(context, action).putExtra(RESULT_RECEIVER, receiver)
            if (action == BlackService.ACTION_START || action == BlackService.ACTION_UPDATE) {
                context.startForegroundService(command)
            } else context.startService(command)
        } catch (error: Exception) {
            handler.removeCallbacks(timeout)
            AppLog.error("service.command_failed", error, mapOf("command" to action))
            finish(context.getString(R.string.action_failed))
        }
    }
}
