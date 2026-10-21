package com.kicktv.app

import android.app.Application
import android.content.Context
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.kicktv.app.data.Follows
import com.kicktv.app.data.KickApi

class KickApp : Application(), SingletonImageLoader.Factory {
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
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { KickApi.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
            .crossfade(120)
            .build()
}
