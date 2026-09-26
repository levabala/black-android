package com.levabala.blackandroid

import android.app.Application
import com.datadog.android.Datadog
import com.datadog.android.DatadogSite
import com.datadog.android.core.configuration.Configuration
import com.datadog.android.privacy.TrackingConsent
import com.datadog.android.log.Logs
import com.datadog.android.log.LogsConfiguration
import com.datadog.android.rum.Rum
import com.datadog.android.rum.RumConfiguration
import com.datadog.android.rum.tracking.ActivityViewTrackingStrategy

class BlackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val clientToken = BuildConfig.DATADOG_RUM_CLIENT_TOKEN
        if (clientToken.isNotBlank()) {
            val configuration = Configuration.Builder(
                clientToken = clientToken,
                env = "production",
                variant = BuildConfig.BUILD_TYPE,
            ).useSite(DatadogSite.EU1).build()
            Datadog.initialize(this, configuration, TrackingConsent.GRANTED)

            Logs.enable(LogsConfiguration.Builder().build())
            AppLog.initialize()

            Rum.enable(
                RumConfiguration.Builder(RUM_APPLICATION_ID)
                    .trackUserInteractions()
                    .trackLongTasks(100L)
                    .useViewTrackingStrategy(ActivityViewTrackingStrategy(trackExtras = false))
                    .build()
            )
            AppLog.info("app.initialized")
        }
        UpdateNotifications.clearIfInstalled(this)
        UpdateCheckJobService.schedule(this)
    }

    private companion object {
        const val RUM_APPLICATION_ID = "2151fa26-2c6d-47ae-96a5-f4dabf4a6248"
    }
}
