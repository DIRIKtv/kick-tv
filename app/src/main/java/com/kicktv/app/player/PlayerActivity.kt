package com.kicktv.app.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.KickApp
import com.kicktv.app.R
import com.kicktv.app.data.ChannelInfo
import com.kicktv.app.data.ChatClient
import com.kicktv.app.data.ChatMessage
import com.kicktv.app.data.Follows
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.Prefs
import com.kicktv.app.ui.Fmt
import com.kicktv.app.ui.Nav
import com.kicktv.app.ui.OptionSheet
import com.kicktv.app.ui.dp
import com.kicktv.app.ui.fadeIn
import com.kicktv.app.ui.fadeOut
import com.kicktv.app.ui.loadAvatar
import com.kicktv.app.ui.setVisible
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : FragmentActivity() {
    companion object {
        const val EXTRA_SLUG = "slug"
        const val EXTRA_VOD_URL = "vod_url"
        const val EXTRA_VOD_UUID = "vod_uuid"
        const val EXTRA_VOD_TITLE = "vod_title"

        private const val HIDE_DELAY_MS = 5000L
        private const val SEEK_COMMIT_DELAY_MS = 700L
        /** Closer than this to the live edge counts as "live". */
        private const val LIVE_EDGE_MS = 20_000L
        /** Live pauses longer than this resume from the recording instead of the (short) live window. */
        private const val LIVE_PAUSE_LIMIT_MS = 25_000L
    }

    /** LIVE = live playlist; DVR = the in-progress recording of a live stream; VOD = a past broadcast. */
    private enum class Mode { LIVE, DVR, VOD }

    private lateinit var prefs: Prefs
    private lateinit var player: ExoPlayer
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var playerView: PlayerView
    private lateinit var stage: View
    private lateinit var controls: View
    private lateinit var buffering: View
    private lateinit var osd: TextView
    private lateinit var stats: TextView
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView
    private lateinit var chatPanel: View
    private lateinit var chatList: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var seekBar: SeekBar
    private lateinit var position: TextView
    private lateinit var durationText: TextView
    private lateinit var tooltip: TextView
    private lateinit var clock: TextView
    private lateinit var btnLive: TextView
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnCaptions: ImageButton
    private lateinit var btnChat: ImageButton
    private lateinit var btnFollow: ImageButton
    private lateinit var btnSettings: ImageButton

    private val sheet by lazy { OptionSheet(this).apply { onDismiss = { if (controlsUp) scheduleHide() } } }

    private var slug = ""
    private var mode = Mode.LIVE
    private var channel: ChannelInfo? = null
    private var vodUrl: String? = null
    private var vodUuid: String? = null
    private var vodTitle: String? = null
    private var dvrSource: String? = null
    private var streamStartMs = 0L

    private var chat: ChatClient? = null
    private var chatVisible = false

    private var qualityHeight = 0
    private var speed = 1f
    private var pendingSeek: Long? = null
    private var livePausedAt = 0L
    private var livePausedPos = 0L
    private var retries = 0
    private var droppedFrames = 0
    private var stoppedLive = false
    private var lastChannelRefresh = 0L
    private var lastPositionSave = 0L

    private val hideRunnable = Runnable { if (player.isPlaying && !sheet.isShowing) hideControls() else scheduleHide() }
    private val osdHide = Runnable { osd.fadeOut(200) }
    private val commitSeekRunnable = Runnable { commitPendingSeek() }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 500)
        }
    }

    // region lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        slug = intent.getStringExtra(EXTRA_SLUG).orEmpty()
        vodUrl = intent.getStringExtra(EXTRA_VOD_URL)
        vodUuid = intent.getStringExtra(EXTRA_VOD_UUID)
        vodTitle = intent.getStringExtra(EXTRA_VOD_TITLE)
        qualityHeight = prefs.preferredQuality
        mode = if (vodUrl != null) Mode.VOD else Mode.LIVE
        chatVisible = mode == Mode.LIVE && prefs.chatEnabled

        onBackPressedDispatcher.addCallback(this) { handleBack() }
        bindViews()
        initPlayer()
        setupControls()
        applyChatLayout()

        if (mode == Mode.VOD) {
            playVod()
            loadChannel(connectChat = false)
        } else {
            startLive()
        }
        ui.post(ticker)
    }

    override fun onStart() {
        super.onStart()
        if (stoppedLive) {
            stoppedLive = false
            startLive()
        }
    }

    override fun onStop() {
        super.onStop()
        saveVodPosition()
        player.pause()
        if (mode == Mode.LIVE) {
            player.stop()
            stoppedLive = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        chat?.close()
        player.release()
    }

    // endregion

    // region setup

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        stage = findViewById(R.id.stage)
        controls = findViewById(R.id.controls)
        buffering = findViewById(R.id.buffering)
        osd = findViewById(R.id.osd)
        stats = findViewById(R.id.stats)
        errorView = findViewById(R.id.errorView)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        chatPanel = findViewById(R.id.chatPanel)
        chatList = findViewById(R.id.chatList)
        seekBar = findViewById(R.id.seekBar)
        position = findViewById(R.id.position)
        durationText = findViewById(R.id.durationText)
        tooltip = findViewById(R.id.tooltip)
        clock = findViewById(R.id.clock)
        btnLive = findViewById(R.id.btnLive)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlay = findViewById(R.id.btnPlay)
        btnForward = findViewById(R.id.btnForward)
        btnCaptions = findViewById(R.id.btnCaptions)
        btnChat = findViewById(R.id.btnChat)
        btnFollow = findViewById(R.id.btnFollow)
        btnSettings = findViewById(R.id.btnSettings)

        chatAdapter = ChatAdapter(this, prefs.chatTextSize)
        chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatList.adapter = chatAdapter
        // New messages slide in gently; changes (emotes arriving) redraw without flashing.
        chatList.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 140
            moveDuration = 140
            removeDuration = 0
            changeDuration = 0
            supportsChangeAnimations = false
        }
    }

    private fun initPlayer() {
        val dataSource = OkHttpDataSource.Factory(KickApi.http)
        // Start quickly, but keep a healthy buffer once playing so brief network dips don't stall.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 50_000, 1_500, 3_000)
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(HlsMediaSource.Factory(dataSource))
            .setBandwidthMeter(KickApp.bandwidthMeter(this))
            .setLoadControl(loadControl)
            .build()
        // Captions stay off until the viewer picks a language.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.setVisible(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    retries = 0
                    errorView.setVisible(false)
                }
                if (state == Player.STATE_ENDED) onEnded()
                updatePlayButton()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayButton()
                if (controlsUp) scheduleHide()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyQualityPreference()
                updateCaptionButton()
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFrames += count
            }
        })
    }

    private fun setupControls() {
        btnPlay.setOnClickListener { togglePlayPause() }
        btnRewind.setOnClickListener { queueSeek(-seekStep(), 0) }
        btnForward.setOnClickListener { queueSeek(seekStep(), 0) }
        btnLive.setOnClickListener { goLive() }
        btnSettings.setOnClickListener { showSettings() }
        btnCaptions.setOnClickListener { showSettings(captionsPage()) }
        btnChat.setOnClickListener {
            chatVisible = !chatVisible
            if (mode != Mode.VOD) prefs.chatEnabled = chatVisible
            applyChatLayout(animate = true)
            updateTooltip(btnChat)
        }
        btnFollow.setOnClickListener { toggleFollow() }

        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { queueSeek(-seekStep(), event.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { queueSeek(seekStep(), event.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePlayPause(); true }
                else -> false
            }
        }

        tooltipLabels = mapOf(
            btnRewind to { "Back ${prefs.seekStepSeconds}s" },
            btnPlay to { if (player.playWhenReady) "Pause" else "Play" },
            btnForward to { "Forward ${prefs.seekStepSeconds}s" },
            btnLive to { if (isAtLive()) "You're live" else "Jump to live" },
            btnCaptions to { "Captions • ${currentCaptionLabel()}" },
            btnChat to { if (chatVisible) "Hide chat" else "Show chat" },
            btnFollow to { if (Follows.isFollowing(this, slug)) "Unfollow" else "Follow" },
            btnSettings to { "Settings" },
        )
        tooltipLabels.keys.forEach { v ->
            v.setOnFocusChangeListener { view, has -> if (has) updateTooltip(view) }
        }
        seekBar.setOnFocusChangeListener { _, has -> if (has) tooltip.visibility = View.INVISIBLE }
        retryButton.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
        updateModeUi()
    }

    private var tooltipLabels: Map<View, () -> String> = emptyMap()

    /** Logical controls state; the view itself may still be fading out. */
    private var controlsUp = false

    /** Show the label centered just above the focused control. */
    private fun updateTooltip(v: View) {
        val label = tooltipLabels[v]?.invoke() ?: return
        tooltip.text = label
        tooltip.visibility = View.INVISIBLE
        tooltip.post {
            if (!v.hasFocus() || !controlsUp) return@post
            val loc = IntArray(2)
            val base = IntArray(2)
            v.getLocationInWindow(loc)
            controls.getLocationInWindow(base)
            val cx = loc[0] - base[0] + v.width / 2f
            val maxX = (controls.width - tooltip.width - dp(8)).toFloat()
            tooltip.translationX = (cx - tooltip.width / 2f).coerceIn(dp(8).toFloat(), maxX)
            tooltip.translationY = (loc[1] - base[1] - tooltip.height - dp(4)).toFloat()
            tooltip.alpha = 0f
            tooltip.visibility = View.VISIBLE
            tooltip.animate().alpha(1f).translationYBy(-dp(4).toFloat()).setDuration(140).start()
        }
    }

    // endregion

    // region loading

    private fun startLive() {
        errorView.setVisible(false)
        buffering.setVisible(true)
        lifecycleScope.launch {
            val c = try {
                KickApi.channel(slug)
            } catch (e: Exception) {
                buffering.setVisible(false)
                showError("Couldn't load the stream.\n${e.message}", "Retry") { startLive() }
                return@launch
            }
            channel = c
            lastChannelRefresh = System.currentTimeMillis()
            bindHeader()
            if (!c.isLive || c.playbackUrl == null) {
                buffering.setVisible(false)
                player.stop()
                showError("${c.username} is offline right now.", "Open channel") {
                    Nav.channel(this@PlayerActivity, slug)
                    finish()
                }
                return@launch
            }
            streamStartMs = Fmt.parseUtc(c.liveStartTime) ?: 0L
            playLive(c.playbackUrl)
            if (chat == null) connectChat(c)
        }
    }

    private fun playLive(url: String) {
        mode = Mode.LIVE
        livePausedAt = 0
        // Kick's segments are ~4s; staying a few segments back avoids constant rebuffering.
        val live = MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(if (prefs.lowLatency) 8_000 else 12_000)
            .setMinOffsetMs(6_000)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.03f)
            .build()
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).setLiveConfiguration(live).build()
        )
        setSpeed(1f)
        player.prepare()
        player.play()
        updateModeUi()
    }

    /** Play the recording of the current broadcast from [positionMs] after stream start. */
    private fun playDvr(positionMs: Long, announce: Boolean = true) {
        lifecycleScope.launch {
            val src = dvrSource ?: runCatching {
                KickApi.videos(slug).firstOrNull { it.isLive }?.source
            }.getOrNull()
            if (src == null) {
                flashOsd("Rewind isn't available for this stream", R.drawable.ic_info)
                if (mode == Mode.LIVE) player.play()
                return@launch
            }
            dvrSource = src
            mode = Mode.DVR
            livePausedAt = 0
            player.setMediaItem(MediaItem.Builder().setUri(src).setMimeType(MimeTypes.APPLICATION_M3U8).build(), positionMs)
            player.prepare()
            player.play()
            updateModeUi()
            if (announce) flashOsd("Watching ${Fmt.time(liveDurationMs() - positionMs)} behind live", R.drawable.ic_replay)
        }
    }

    private fun goLive() {
        if (mode == Mode.VOD) return
        if (mode == Mode.LIVE) {
            player.seekToDefaultPosition()
            player.play()
            flashOsd("Live", R.drawable.ic_skip_live)
            return
        }
        flashOsd("Back to live", R.drawable.ic_skip_live)
        startLive()
    }

    private fun playVod() {
        val url = vodUrl ?: return
        val resume = vodUuid?.let { prefs.vodPosition(it) } ?: 0L
        player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build(), resume)
        player.prepare()
        player.play()
        if (resume > 10_000) flashOsd("Resuming from ${Fmt.time(resume)}", R.drawable.ic_play)
        updateModeUi()
    }

    private fun loadChannel(connectChat: Boolean) {
        lifecycleScope.launch {
            val c = runCatching { KickApi.channel(slug) }.getOrNull() ?: return@launch
            channel = c
            lastChannelRefresh = System.currentTimeMillis()
            if (c.isLive && streamStartMs == 0L) streamStartMs = Fmt.parseUtc(c.liveStartTime) ?: 0L
            bindHeader()
            if (connectChat && chat == null && c.isLive) connectChat(c)
            if (!c.isLive && mode == Mode.LIVE && !player.isPlaying) {
                showError("The stream has ended.", "Open channel") {
                    Nav.channel(this@PlayerActivity, slug)
                    finish()
                }
            }
        }
    }

    private fun connectChat(c: ChannelInfo) {
        if (c.chatroomId == 0) return
        lifecycleScope.launch {
            val (key, cluster) = KickApi.pusherConfig(c.id)
            if (isDestroyed || chat != null) return@launch
            chat = ChatClient(
                chatroomId = c.chatroomId,
                appKey = key,
                cluster = cluster,
                onMessage = { addChat(it) },
                onDeleted = { chatAdapter.remove(it) },
                onStatus = { addChat(ChatMessage("sys-${System.nanoTime()}", "", null, it, emptyList(), isSystem = true)) },
            ).also { it.connect() }
        }
    }

    private fun addChat(m: ChatMessage) {
        chatAdapter.add(m)
        if (chatPanel.visibility == View.VISIBLE) chatList.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun toggleFollow() {
        val c = channel ?: return
        if (Follows.isFollowing(this, c.slug)) {
            ui.removeCallbacks(hideRunnable)
            OptionSheet.confirm(this, "Unfollow ${c.username}?", "They'll be removed from Following.",
                "Unfollow", R.drawable.ic_heart_outline) { setFollow(c) }
        } else {
            setFollow(c)
        }
    }

    private fun setFollow(c: ChannelInfo) {
        lifecycleScope.launch {
            val on = Follows.toggle(this@PlayerActivity, c.slug, c.username, c.avatar)
            updateFollowButton()
            updateTooltip(btnFollow)
            flashOsd(if (on) "Following ${c.username}" else "Unfollowed ${c.username}", if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        }
    }

    // endregion

    // region playback control

    private fun togglePlayPause() {
        if (player.playbackState == Player.STATE_IDLE) {
            retry()
            return
        }
        if (player.playWhenReady) {
            player.pause()
            if (mode == Mode.LIVE) {
                livePausedAt = System.currentTimeMillis()
                livePausedPos = timelinePos()
            }
            if (!controlsUp) flashOsd("Paused", R.drawable.ic_pause)
        } else {
            if (player.playbackState == Player.STATE_ENDED && mode == Mode.VOD) player.seekTo(0)
            val pausedFor = if (livePausedAt > 0) System.currentTimeMillis() - livePausedAt else 0
            if (mode == Mode.LIVE && pausedFor > LIVE_PAUSE_LIMIT_MS && streamStartMs > 0) {
                // The live window is short; continue from the recording instead of skipping ahead.
                playDvr(livePausedPos, announce = true)
            } else {
                player.play()
            }
            livePausedAt = 0
            if (!controlsUp) flashOsd("Play", R.drawable.ic_play)
        }
        updatePlayButton()
        if (btnPlay.hasFocus()) updateTooltip(btnPlay)
    }

    private fun setSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    private fun seekStep() = prefs.seekStepSeconds * 1000L

    private fun liveDurationMs() = if (streamStartMs > 0) System.currentTimeMillis() - streamStartMs else 0L

    /** Current position on the visible timeline (for live: time since the stream started). */
    private fun timelinePos(): Long = when (mode) {
        Mode.VOD, Mode.DVR -> player.currentPosition
        Mode.LIVE -> {
            val off = player.currentLiveOffset
            if (streamStartMs > 0 && off != C.TIME_UNSET) (liveDurationMs() - off).coerceAtLeast(0) else player.currentPosition
        }
    }

    private fun timelineMax(): Long = when (mode) {
        Mode.VOD -> player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        Mode.LIVE, Mode.DVR -> if (streamStartMs > 0) liveDurationMs() else player.duration.coerceAtLeast(0L)
    }

    private fun isAtLive() = mode == Mode.LIVE && timelineMax() - (pendingSeek ?: timelinePos()) < LIVE_EDGE_MS

    private fun queueSeek(delta: Long, repeatCount: Int) {
        if (player.playbackState == Player.STATE_IDLE && mode != Mode.VOD) return
        val accel = 1 + repeatCount / 6
        val base = pendingSeek ?: timelinePos()
        val max = timelineMax()
        val target = (base + delta * accel).coerceIn(0L, max.coerceAtLeast(0L))
        pendingSeek = target
        val icon = if (delta < 0) R.drawable.ic_replay else R.drawable.ic_forward
        val label = if (mode == Mode.VOD) {
            "${Fmt.time(target)} / ${Fmt.time(max)}"
        } else {
            val behind = max - target
            if (behind < LIVE_EDGE_MS) "Live" else "${Fmt.time(target)}  •  −${Fmt.time(behind)}"
        }
        flashOsd(label, icon, 1500)
        updateProgressUi()
        ui.removeCallbacks(commitSeekRunnable)
        ui.postDelayed(commitSeekRunnable, SEEK_COMMIT_DELAY_MS)
    }

    private fun commitPendingSeek() {
        val target = pendingSeek ?: return
        pendingSeek = null
        ui.removeCallbacks(commitSeekRunnable)
        when (mode) {
            Mode.VOD -> player.seekTo(target)
            Mode.LIVE -> {
                if (streamStartMs <= 0) {
                    player.seekTo((player.currentPosition + (target - timelinePos())).coerceAtLeast(0))
                    return
                }
                val max = timelineMax()
                if (max - target < 3_000) {
                    player.seekToDefaultPosition()
                    return
                }
                val windowStart = timelinePos() - player.currentPosition
                if (target >= windowStart + 2_000) {
                    player.seekTo(target - windowStart)
                } else {
                    playDvr(target)
                }
            }
            Mode.DVR -> {
                val max = timelineMax()
                val recorded = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                when {
                    max - target < LIVE_EDGE_MS -> goLive()
                    target <= recorded - 5_000 -> player.seekTo(target)
                    else -> playDvr(target, announce = false) // reload to pick up newer segments
                }
            }
        }
    }

    private fun onEnded() {
        when (mode) {
            Mode.DVR -> {
                // Caught up with the end of the recording snapshot.
                val pos = player.currentPosition
                if (liveDurationMs() - pos < 60_000) goLive() else playDvr(pos, announce = false)
            }
            Mode.VOD -> {
                vodUuid?.let { prefs.saveVodPosition(it, 0) }
                showControls()
            }
            Mode.LIVE -> loadChannel(connectChat = false)
        }
    }

    private fun handleError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        retries++
        if (retries <= 4) {
            buffering.setVisible(true)
            ui.postDelayed({ retry() }, 1500L * retries)
            return
        }
        buffering.setVisible(false)
        showError("Playback problem: ${error.errorCodeName}", "Retry") {
            retries = 0
            retry()
        }
    }

    private fun retry() {
        errorView.setVisible(false)
        when (mode) {
            Mode.LIVE -> startLive()
            Mode.DVR -> playDvr(player.currentPosition, announce = false)
            Mode.VOD -> { player.prepare(); player.play() }
        }
    }

    private fun showError(message: String, action: String, onAction: () -> Unit) {
        hideControls()
        errorText.text = message
        retryButton.text = action
        retryButton.setOnClickListener { onAction() }
        errorView.setVisible(true)
        retryButton.requestFocus()
    }

    private fun saveVodPosition() {
        val uuid = vodUuid ?: return
        if (mode != Mode.VOD) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur > 0 && dur != C.TIME_UNSET && pos > dur - 60_000) prefs.saveVodPosition(uuid, 0)
        else if (pos > 5_000) prefs.saveVodPosition(uuid, pos)
    }

    // endregion

    // region tracks & settings menu

    private data class TrackChoice(val group: Tracks.Group, val index: Int, val format: Format)

    private fun videoTracks(): List<TrackChoice> = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { TrackChoice(g, it, g.getTrackFormat(it)) } }

    private fun textTracks(): List<TrackChoice> = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { g -> (0 until g.length).map { TrackChoice(g, it, g.getTrackFormat(it)) } }

    private fun qualityLabel(f: Format?): String {
        if (f == null || f.height <= 0) return "—"
        val fps = if (f.frameRate >= 49f) "60" else ""
        return "${f.height}p$fps"
    }

    private fun isQualityOverridden(): Boolean {
        val overrides = player.trackSelectionParameters.overrides
        return videoTracks().any { overrides.containsKey(it.group.mediaTrackGroup) }
    }

    private fun currentQualityLabel(): String {
        val playing = qualityLabel(player.videoFormat)
        return if (isQualityOverridden()) playing else "Auto • $playing"
    }

    /** Lock to the preferred height whenever a new set of tracks shows up (new stream, live ↔ DVR). */
    private fun applyQualityPreference() {
        if (qualityHeight <= 0) return
        val tracks = videoTracks()
        if (tracks.isEmpty() || isQualityOverridden()) return
        val pick = tracks.filter { it.format.height <= qualityHeight }
            .maxWithOrNull(compareBy<TrackChoice>({ it.format.height }, { it.format.frameRate }, { it.format.bitrate }))
            ?: tracks.minByOrNull { it.format.height }
            ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(pick.group.mediaTrackGroup, pick.index))
            .build()
    }

    private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private fun speedLabel(s: Float) = if (s == 1f) "Normal" else "${s}x".replace(".0x", "x")

    private fun currentCaptionLabel(): String {
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return "Off"
        val f = textTracks().firstOrNull { it.group.isTrackSelected(it.index) }?.format
        return f?.label ?: f?.language ?: "On"
    }

    private fun showSettings(start: OptionSheet.Page? = null) {
        ui.removeCallbacks(hideRunnable)
        sheet.show(settingsPage())
        if (start != null) sheet.push(start)
    }

    private fun settingsPage() = OptionSheet.Page("Settings", eyebrow = channel?.username ?: "Player") {
        buildList {
            add(OptionSheet.Item("Quality", value = currentQualityLabel(), icon = R.drawable.ic_hd, submenu = true) { it.push(qualityPage()) })
            if (mode != Mode.LIVE) {
                add(OptionSheet.Item("Playback speed", value = speedLabel(speed), icon = R.drawable.ic_speed, submenu = true) { it.push(speedPage()) })
            }
            if (textTracks().isNotEmpty()) {
                add(OptionSheet.Item("Captions", value = currentCaptionLabel(), icon = R.drawable.ic_cc, submenu = true) { it.push(captionsPage()) })
            }
            if (mode != Mode.VOD) {
                add(OptionSheet.Item("Chat", value = if (!chatVisible) "Hidden" else if (prefs.chatMode == "side") "Beside video" else "Over video",
                    icon = R.drawable.ic_chat, submenu = true) { it.push(chatPage()) })
            }
            add(OptionSheet.Item("Stats for nerds", value = if (stats.visibility == View.VISIBLE) "On" else "Off", icon = R.drawable.ic_info) {
                stats.setVisible(stats.visibility != View.VISIBLE)
                it.refresh()
            })
            add(OptionSheet.Item("Go to channel", sub = "Past broadcasts & info", icon = R.drawable.ic_person) {
                it.dismiss()
                Nav.channel(this@PlayerActivity, slug)
                finish()
            })
        }
    }

    private fun qualityPage(): OptionSheet.Page {
        val tracks = videoTracks()
            .groupBy { qualityLabel(it.format) }
            .map { (_, list) -> list.maxByOrNull { it.format.bitrate }!! }
            .sortedWith(compareByDescending<TrackChoice> { it.format.height }.thenByDescending { it.format.frameRate })
        return OptionSheet.Page("Quality", subtitle = "Your choice is remembered for next time.") {
            val overrides = player.trackSelectionParameters.overrides
            val selected = tracks.indexOfFirst { t -> overrides[t.group.mediaTrackGroup]?.trackIndices?.contains(t.index) == true }
            listOf(
                OptionSheet.Item("Auto", sub = "Adjusts to your connection • now ${qualityLabel(player.videoFormat)}", checked = selected < 0) {
                    setQuality(null); it.dismiss()
                }
            ) + tracks.mapIndexed { i, t ->
                val mbps = if (t.format.bitrate > 0) String.format(Locale.US, "%.1f Mbps", t.format.bitrate / 1_000_000f) else null
                OptionSheet.Item(qualityLabel(t.format), sub = if (i == 0) "Source" else null, value = mbps, checked = i == selected) {
                    setQuality(t); it.dismiss()
                }
            }
        }
    }

    private fun setQuality(t: TrackChoice?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        qualityHeight = t?.format?.height ?: 0
        if (t != null) builder.setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
        player.trackSelectionParameters = builder.build()
        prefs.preferredQuality = qualityHeight
        flashOsd("Quality: ${t?.let { qualityLabel(it.format) } ?: "Auto"}", R.drawable.ic_hd)
    }

    private fun speedPage() = OptionSheet.Page("Playback speed") {
        speeds.map { s ->
            OptionSheet.Item(speedLabel(s), checked = s == speed) {
                setSpeed(s); it.dismiss()
                flashOsd("Speed: ${speedLabel(s)}", R.drawable.ic_speed)
            }
        }
    }

    private fun captionsPage() = OptionSheet.Page("Captions", subtitle = "Auto-generated by Kick") {
        val tracks = textTracks()
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        listOf(OptionSheet.Item("Off", checked = disabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            updateCaptionButton(); it.dismiss()
        }) + tracks.map { t ->
            OptionSheet.Item(t.format.label ?: t.format.language ?: "Track ${t.index + 1}", checked = !disabled && t.group.isTrackSelected(t.index)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                    .build()
                btnCaptions.isActivated = true
                it.dismiss()
            }
        }
    }

    private fun updateCaptionButton() {
        btnCaptions.isActivated = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        btnCaptions.setVisible(compact != true && textTracks().isNotEmpty())
    }

    private fun chatPage() = OptionSheet.Page("Chat") {
        listOf(
            OptionSheet.Item("Show chat", value = if (chatVisible) "On" else "Off") {
                chatVisible = !chatVisible; prefs.chatEnabled = chatVisible; applyChatLayout(animate = true); it.refresh()
            },
            OptionSheet.Item("Beside the video", sub = "Video shrinks to make room", checked = prefs.chatMode == "side") {
                prefs.chatMode = "side"; applyChatLayout(animate = true); it.refresh()
            },
            OptionSheet.Item("Over the video", sub = "See-through panel on top", checked = prefs.chatMode != "side") {
                prefs.chatMode = "overlay"; applyChatLayout(animate = true); it.refresh()
            },
            OptionSheet.Item("Text size", value = "${prefs.chatTextSize}sp", submenu = true) { sheet ->
                sheet.push(OptionSheet.Page("Chat text size") {
                    listOf(13, 15, 17, 20).map { size ->
                        OptionSheet.Item(listOf("Small", "Medium", "Large", "Extra large")[listOf(13, 15, 17, 20).indexOf(size)],
                            value = "${size}sp", checked = prefs.chatTextSize == size) { s ->
                            prefs.chatTextSize = size
                            chatAdapter.textSizeSp = size
                            chatAdapter.notifyDataSetChanged()
                            s.back()
                        }
                    }
                })
            },
        )
    }

    // endregion

    // region UI

    private var chatAnimator: android.animation.ValueAnimator? = null

    /**
     * Places chat beside or over the video. With [animate], chat slides in from the right while
     * the video eases over to make room (and the reverse when closing).
     */
    private fun applyChatLayout(animate: Boolean = false) {
        val show = chatVisible && mode != Mode.VOD
        val side = prefs.chatMode == "side"
        val panelVisible = show && (side || !controlsUp)
        chatPanel.setBackgroundColor(getColor(if (side) R.color.chat_bg else R.color.chat_overlay_bg))
        btnChat.isActivated = show
        if (show) chatList.scrollToPosition((chatAdapter.itemCount - 1).coerceAtLeast(0))
        setCompactControls(show && side)

        val lp = stage.layoutParams as FrameLayout.LayoutParams
        val targetMargin = if (show && side) dp(300) else 0
        chatAnimator?.cancel()
        if (!animate) {
            chatPanel.setVisible(panelVisible)
            chatPanel.translationX = 0f
            chatPanel.alpha = 1f
            lp.marginEnd = targetMargin
            stage.layoutParams = lp
            return
        }
        val width = dp(300).toFloat()
        val startMargin = lp.marginEnd
        val startShift = if (chatPanel.visibility == View.VISIBLE) chatPanel.translationX else width
        val endShift = if (panelVisible) 0f else width
        chatPanel.visibility = View.VISIBLE
        chatAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                chatPanel.translationX = startShift + (endShift - startShift) * f
                chatPanel.alpha = if (side) 1f else 1f - (chatPanel.translationX / width)
                if (startMargin != targetMargin) {
                    lp.marginEnd = (startMargin + (targetMargin - startMargin) * f).toInt()
                    stage.layoutParams = lp
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!panelVisible) chatPanel.visibility = View.GONE
                    chatPanel.translationX = 0f
                    chatPanel.alpha = 1f
                }
            })
            start()
        }
    }

    private var compact: Boolean? = null

    /**
     * With chat beside the video the controls only get ~2/3 of the screen, so shrink the
     * buttons and padding and move captions into Settings (the CC key still works).
     */
    private fun setCompactControls(on: Boolean) {
        if (compact == on) return
        compact = on
        val side = dp(if (on) 24 else 40)
        findViewById<View>(R.id.bottomBar).apply { setPadding(side, paddingTop, side, paddingBottom) }
        val small = dp(if (on) 42 else 48)
        val gap = dp(if (on) 6 else 10)
        listOf(btnRewind, btnForward, btnChat, btnFollow, btnSettings, btnCaptions).forEach { b ->
            b.layoutParams = (b.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = small; height = small
                if (b.parent === findViewById(R.id.actions)) marginStart = gap
            }
            b.setPadding(dp(if (on) 11 else 12), dp(if (on) 11 else 12), dp(if (on) 11 else 12), dp(if (on) 11 else 12))
        }
        btnPlay.layoutParams = (btnPlay.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
            val big = dp(if (on) 54 else 64)
            width = big; height = big
            marginStart = dp(if (on) 10 else 14); marginEnd = dp(if (on) 10 else 14)
        }
        btnPlay.setPadding(dp(if (on) 14 else 16), dp(if (on) 14 else 16), dp(if (on) 14 else 16), dp(if (on) 14 else 16))
        btnLive.setPadding(dp(if (on) 12 else 16), 0, dp(if (on) 14 else 18), 0)
        btnLive.textSize = if (on) 13f else 14f
        updateCaptionButton()
    }

    private fun updateModeUi() {
        val isVod = mode == Mode.VOD
        btnLive.setVisible(!isVod)
        btnChat.setVisible(!isVod)
        if (mode == Mode.LIVE && speed != 1f) setSpeed(1f)
        applyChatLayout()
        updateFollowButton()
        updateProgressUi()
    }

    private fun updateFollowButton() {
        val on = Follows.isFollowing(this, slug)
        btnFollow.setImageResource(if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        btnFollow.isActivated = on
    }

    private fun updatePlayButton() {
        btnPlay.setImageResource(if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun bindHeader() {
        val c = channel
        if (c != null && chatAdapter.subscriberBadges.isEmpty()) chatAdapter.subscriberBadges = c.subscriberBadges
        findViewById<ImageView>(R.id.avatar).loadAvatar(c?.avatar)
        findViewById<TextView>(R.id.channelName).text = (c?.username ?: slug) + if (c?.verified == true) " ✓" else ""
        findViewById<TextView>(R.id.streamTitle).text = if (mode == Mode.VOD) vodTitle.orEmpty() else c?.liveTitle.orEmpty()
        updateMeta()
        updateFollowButton()
    }

    private fun updateMeta() {
        val c = channel
        val meta = if (mode == Mode.VOD) {
            listOfNotNull("Past broadcast", c?.let { "${Fmt.count(it.followers)} followers" })
        } else {
            listOfNotNull(
                c?.let { Fmt.viewers(it.liveViewers) },
                c?.liveCategory,
                Fmt.uptime(c?.liveStartTime)?.let { "live for $it" },
            )
        }
        findViewById<TextView>(R.id.streamMeta).text = meta.joinToString("  •  ")
    }

    private fun updateProgressUi() {
        val max = timelineMax()
        val pos = pendingSeek ?: timelinePos()
        seekBar.max = (max / 1000).toInt().coerceAtLeast(1)
        seekBar.progress = (pos / 1000).toInt()
        val buffered = when (mode) {
            Mode.LIVE -> pos + player.totalBufferedDuration
            else -> player.bufferedPosition
        }
        seekBar.secondaryProgress = (buffered / 1000).toInt()
        position.text = Fmt.time(pos)
        if (mode == Mode.VOD) {
            durationText.text = Fmt.time(max)
        } else {
            val atLive = isAtLive()
            durationText.text = if (atLive) Fmt.time(max) else "−${Fmt.time(max - pos)}"
            val label = if (atLive) "LIVE" else "GO LIVE"
            if (btnLive.text != label) {
                btnLive.text = label
                val dot = getDrawable(R.drawable.dot)!!.mutate().apply {
                    setBounds(0, 0, dp(8), dp(8))
                    setTint(getColor(if (atLive) R.color.live_red else R.color.text_secondary))
                }
                btnLive.setCompoundDrawablesRelative(dot, null, null, null)
                btnLive.compoundDrawablePadding = dp(8)
            }
        }
    }

    private fun tick() {
        if (controlsUp) {
            if (pendingSeek == null) updateProgressUi()
            clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            updateMeta()
        }
        if (stats.visibility == View.VISIBLE) updateStats()
        val now = System.currentTimeMillis()
        if (mode != Mode.VOD && now - lastChannelRefresh > 60_000 && !isFinishing) {
            lastChannelRefresh = now
            loadChannel(connectChat = true)
        }
        if (mode == Mode.VOD && now - lastPositionSave > 10_000) {
            lastPositionSave = now
            saveVodPosition()
        }
    }

    private fun updateStats() {
        val f = player.videoFormat
        val bw = KickApp.bandwidthMeter(this).bitrateEstimate
        val lines = mutableListOf(
            "Mode       ${mode.name}",
            "Video      ${f?.let { "${it.width}x${it.height} @${it.frameRate.toInt()}fps" } ?: "—"}",
            "Codec      ${f?.codecs ?: "—"}",
            "Bitrate    ${f?.bitrate?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f Mbps", it / 1e6) } ?: "—"}",
            "Bandwidth  ${String.format(Locale.US, "%.1f Mbps", bw / 1e6)}",
            "Buffer     ${String.format(Locale.US, "%.1f s", player.totalBufferedDuration / 1000f)}",
            "Dropped    $droppedFrames frames",
            "Speed      ${player.playbackParameters.speed}x",
        )
        if (mode == Mode.LIVE && player.currentLiveOffset != C.TIME_UNSET) {
            lines.add(5, "Latency    ${String.format(Locale.US, "%.1f s", player.currentLiveOffset / 1000f)}")
        }
        stats.text = lines.joinToString("\n")
    }

    private fun showControls(focus: View? = btnPlay) {
        val wasVisible = controlsUp
        controlsUp = true
        if (!wasVisible) {
            controls.fadeIn(180)
            findViewById<View>(R.id.bottomBar).apply {
                translationY = dp(16).toFloat()
                animate().translationY(0f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        if (prefs.chatMode != "side") chatPanel.setVisible(false)
        osd.fadeOut(120)
        updateProgressUi()
        updateMeta()
        clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        if (!wasVisible) focus?.requestFocus()
        currentFocus?.let { updateTooltip(it) }
        scheduleHide()
    }

    private fun hideControls() {
        controlsUp = false
        controls.fadeOut(200)
        tooltip.visibility = View.INVISIBLE
        chatPanel.setVisible(chatVisible && mode != Mode.VOD)
        ui.removeCallbacks(hideRunnable)
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideRunnable)
        ui.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun flashOsd(text: String, icon: Int, duration: Long = 1300) {
        if (controlsUp && pendingSeek != null) return // the seek bar already shows it
        osd.text = text
        osd.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        if (osd.visibility != View.VISIBLE) {
            osd.scaleX = 0.92f
            osd.scaleY = 0.92f
            osd.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        osd.fadeIn(140)
        ui.removeCallbacks(osdHide)
        ui.postDelayed(osdHide, duration)
    }

    // endregion

    // region keys

    // Overriding Activity.dispatchKeyEvent is standard; lint trips on AndroidX's restricted override of it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsShown = controlsUp
            val errorShown = errorView.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (event.repeatCount == 0) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (!player.playWhenReady) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (player.playWhenReady) togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    queueSeek(-seekStep() * 3, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    queueSeek(seekStep() * 3, event.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.repeatCount == 0) showSettings()
                    return true
                }
                KeyEvent.KEYCODE_CAPTIONS -> {
                    if (textTracks().isNotEmpty()) showSettings(captionsPage())
                    return true
                }
            }
            if (!controlsShown && !errorShown) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { queueSeek(-seekStep(), event.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { queueSeek(seekStep(), event.repeatCount); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.repeatCount == 0) showControls()
                        return true
                    }
                }
            }
            if (controlsShown) scheduleHide()
        } else if (event.action == KeyEvent.ACTION_UP && !controlsUp &&
            errorView.visibility != View.VISIBLE && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            )
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        when {
            pendingSeek != null -> commitPendingSeek()
            controlsUp -> hideControls()
            else -> finish()
        }
    }

    // endregion
}
