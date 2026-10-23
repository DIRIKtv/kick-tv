package com.diriktv.sidekick.player

import android.os.Handler
import android.os.Looper
import com.diriktv.sidekick.ui.Fmt

/** Stops playback after a while, or at the end of the current video. */
class SleepTimer(
    private val onWarn: () -> Unit,
    private val onExpire: () -> Unit,
) {
    companion object {
        private const val WARN_BEFORE_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var endsAt = 0L
    var endOfVideo = false
        private set

    val active get() = endsAt > 0 || endOfVideo

    private val warn = Runnable { onWarn() }
    private val expire = Runnable { endsAt = 0; onExpire() }

    fun start(minutes: Int) {
        cancel()
        val ms = minutes * 60_000L
        endsAt = System.currentTimeMillis() + ms
        handler.postDelayed(expire, ms)
        if (ms > WARN_BEFORE_MS * 2) handler.postDelayed(warn, ms - WARN_BEFORE_MS)
    }

    fun startEndOfVideo() {
        cancel()
        endOfVideo = true
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        endsAt = 0
        endOfVideo = false
    }

    /** Call when the video finishes; returns true if that was the timer's cue. */
    fun onVideoEnded(): Boolean {
        if (!endOfVideo) return false
        endOfVideo = false
        onExpire()
        return true
    }

    /** "Off", "End of video" or the time left. */
    fun label(): String = when {
        endOfVideo -> "End of video"
        endsAt > 0 -> "${Fmt.time(endsAt - System.currentTimeMillis())} left"
        else -> "Off"
    }
}
