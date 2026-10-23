package com.diriktv.sidekick.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.diriktv.sidekick.SidekickApp
import com.diriktv.sidekick.data.KickApi

/** Builds players and media items the same way for the full-screen player and multi-view. */
object PlayerFactory {
    /**
     * [maxHeight] caps the video quality (multi-view tiles don't need source); 0 = no cap.
     * [leveler] evens out loudness; keep a reference to switch it on and off while playing.
     */
    fun create(context: Context, maxHeight: Int = 0, leveler: LoudnessLeveler? = null): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(KickApi.http)
        // Start quickly, but keep a healthy buffer once playing so brief network dips don't stall.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 50_000, 1_500, 3_000)
            .build()
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(listOfNotNull<AudioProcessor>(leveler).toTypedArray())
                    .build()
        }
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setBandwidthMeter(SidekickApp.bandwidthMeter(context))
            .setLoadControl(loadControl)
            .build()
        // Captions stay off until the viewer picks a language.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .apply { if (maxHeight > 0) setMaxVideoSize(Int.MAX_VALUE, maxHeight) }
            .build()
        return player
    }

    /** Kick serves everything as HLS except the odd progressive clip. */
    fun item(url: String, live: MediaItem.LiveConfiguration? = null): MediaItem =
        MediaItem.Builder().setUri(url)
            .apply { if (!url.substringBefore('?').endsWith(".mp4")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .apply { live?.let { setLiveConfiguration(it) } }
            .build()

    /** Kick's segments are ~4s; staying a few segments back avoids constant rebuffering. */
    fun liveConfig(lowLatency: Boolean): MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(if (lowLatency) 8_000 else 12_000)
            .setMinOffsetMs(6_000)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.03f)
            .build()
}
