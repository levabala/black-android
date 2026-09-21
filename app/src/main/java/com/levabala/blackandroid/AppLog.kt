package com.levabala.blackandroid

import com.datadog.android.log.Logger

object AppLog {
    private var logger: Logger? = null

    fun initialize() {
        logger = Logger.Builder()
            .setName("black-android")
            .setService("black-android")
            .setNetworkInfoEnabled(true)
            .setLogcatLogsEnabled(BuildConfig.DEBUG)
            .setBundleWithRumEnabled(true)
            .setRemoteSampleRate(100f)
            .build()
            .also {
                it.addAttribute("version_code", BuildConfig.VERSION_CODE)
                it.addAttribute("version_name", BuildConfig.VERSION_NAME)
                it.addTag("build_type", BuildConfig.BUILD_TYPE)
            }
    }

    fun info(event: String, attributes: Map<String, Any?> = emptyMap()) {
        logger?.i(event, attributes = attributes + ("event.name" to event))
    }

    fun warn(event: String, attributes: Map<String, Any?> = emptyMap()) {
        logger?.w(event, attributes = attributes + ("event.name" to event))
    }

    fun error(event: String, error: Throwable, attributes: Map<String, Any?> = emptyMap()) {
        logger?.e(event, error, attributes + ("event.name" to event))
    }
}
