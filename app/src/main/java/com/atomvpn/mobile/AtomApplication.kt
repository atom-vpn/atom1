package com.atomvpn.mobile

import android.app.Application
import com.posthog.PersonProfiles
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/**
 * Initializes privacy-minimized product analytics.
 *
 * Only anonymous lifecycle and VPN connection events are captured.
 * Screen views, deep links, push subscriptions and session replay are disabled.
 */
class AtomApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = PostHogAndroidConfig(
            projectToken = "phc_nHWi2UzdSoX87VS9ipzR6mrAouWoiVheRfC5y3bjrAur",
            host = "https://eu.i.posthog.com"
        ).apply {
            captureApplicationLifecycleEvents = true
            captureScreenViews = false
            captureDeepLinks = false
            capturePushNotificationSubscriptions = false
            capturePushNotificationOpened = false
            personProfiles = PersonProfiles.NEVER
            sessionReplayConfig = com.posthog.android.PostHogSessionReplayConfig()
        }

        PostHogAndroid.setup(this, config)
    }
}
