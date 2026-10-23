package com.diriktv.sidekick

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.Updater
import com.diriktv.sidekick.tv.TvHome
import com.diriktv.sidekick.ui.LiveAlerts

class SidekickApp : Application(), SingletonImageLoader.Factory {
    companion object {
        private var meter: DefaultBandwidthMeter? = null

        /**
         * One bandwidth estimate for the whole app, so each new stream starts at the quality
         * the connection actually supports instead of re-learning it (and stalling) every time.
         */
        fun bandwidthMeter(context: Context): DefaultBandwidthMeter =
            meter ?: DefaultBandwidthMeter.Builder(context.applicationContext)
                .setInitialBitrateEstimate(5_000_000)
                .build()
                .also { meter = it }
    }

    override fun onCreate() {
        super.onCreate()
        Follows.init(this)
        LiveAlerts.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                // Coming to the foreground (not just moving between our own screens).
                if (started++ == 0) Updater.openedPending = true
            }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        TvHome.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { KickApi.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
            .crossfade(120)
            .build()
}
