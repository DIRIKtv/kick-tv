package com.diriktv.sidekick.player

import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.RollingCounter
import com.diriktv.sidekick.ui.dp
import com.diriktv.sidekick.ui.fadeIn
import com.diriktv.sidekick.ui.fadeOut
import com.diriktv.sidekick.ui.loadAvatar
import com.diriktv.sidekick.ui.setVisible
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The controls overlay: header, seek bar, buttons and their tooltip, plus the on-screen notices. */
internal class PlayerControls(private val act: PlayerActivity) {
    companion object {
        private const val HIDE_DELAY_MS = 5000L
    }

    private val player get() = act.player
    private val prefs = act.prefs

    private val root: View = act.findViewById(R.id.controls)
    private val bottomBar: View = act.findViewById(R.id.bottomBar)
    private val osd: TextView = act.findViewById(R.id.osd)
    private val seekBar: SeekBar = act.findViewById(R.id.seekBar)
    private val position: TextView = act.findViewById(R.id.position)
    private val durationText: TextView = act.findViewById(R.id.durationText)
    private val tooltip: TextView = act.findViewById(R.id.tooltip)
    private val clock: TextView = act.findViewById(R.id.clock)
    private val btnLive: TextView = act.findViewById(R.id.btnLive)
    private val btnRewind: ImageButton = act.findViewById(R.id.btnRewind)
    val btnPlay: ImageButton = act.findViewById(R.id.btnPlay)
    private val btnForward: ImageButton = act.findViewById(R.id.btnForward)
    private val btnCaptions: ImageButton = act.findViewById(R.id.btnCaptions)
    val btnChat: ImageButton = act.findViewById(R.id.btnChat)
    val btnFollow: ImageButton = act.findViewById(R.id.btnFollow)
    private val btnSettings: ImageButton = act.findViewById(R.id.btnSettings)

    /** Logical controls state; the view itself may still be fading out. */
    var up = false
        private set

    private var compact: Boolean? = null
    private val hideRunnable = Runnable { if (player.isPlaying && !act.menu.isShowing) hide() else scheduleHide() }
    private val osdHide = Runnable { osd.fadeOut(200) }
    private val tooltipLabels: Map<View, () -> String>

    init {
        btnPlay.setOnClickListener { act.togglePlayPause() }
        btnRewind.setOnClickListener { act.queueSeek(-act.seekStep(), 0) }
        btnForward.setOnClickListener { act.queueSeek(act.seekStep(), 0) }
        btnLive.setOnClickListener { act.goLive() }
        btnSettings.setOnClickListener { act.menu.show() }
        btnCaptions.setOnClickListener { act.menu.show(act.menu.captionsPage()) }
        btnChat.setOnClickListener {
            act.chat.wanted = !act.chat.wanted
            if (act.mode != PlayerActivity.Mode.VOD) prefs.chatEnabled = act.chat.wanted
            act.chat.applyLayout(animate = true)
            updateTooltip(btnChat)
        }
        btnFollow.setOnClickListener { act.toggleFollow() }

        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { act.queueSeek(-act.seekStep(), event.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { act.queueSeek(act.seekStep(), event.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { act.togglePlayPause(); true }
                else -> false
            }
        }

        tooltipLabels = mapOf(
            btnRewind to { "Back ${prefs.seekStepSeconds}s" },
            btnPlay to { if (player.playWhenReady) "Pause" else "Play" },
            btnForward to { "Forward ${prefs.seekStepSeconds}s" },
            btnLive to { if (act.isAtLive()) "You're live" else "Jump to live" },
            btnCaptions to { "Captions • ${act.menu.currentCaptionLabel()}" },
            btnChat to { if (act.chat.wanted) "Hide chat" else "Show chat" },
            btnFollow to { if (Follows.isFollowing(act, act.slug)) "Unfollow" else "Follow" },
            btnSettings to { "Settings" },
        )
        tooltipLabels.keys.forEach { v ->
            v.setOnFocusChangeListener { view, has -> if (has) updateTooltip(view) }
        }
        seekBar.setOnFocusChangeListener { _, has -> if (has) tooltip.visibility = View.INVISIBLE }
    }

    /** Show the label centered just above the focused control. */
    fun updateTooltip(v: View) {
        val label = tooltipLabels[v]?.invoke() ?: return
        tooltip.text = label
        tooltip.visibility = View.INVISIBLE
        tooltip.post {
            if (!v.hasFocus() || !up) return@post
            val loc = IntArray(2)
            val base = IntArray(2)
            v.getLocationInWindow(loc)
            root.getLocationInWindow(base)
            val cx = loc[0] - base[0] + v.width / 2f
            val maxX = (root.width - tooltip.width - act.dp(8)).toFloat()
            tooltip.translationX = (cx - tooltip.width / 2f).coerceIn(act.dp(8).toFloat(), maxX)
            tooltip.translationY = (loc[1] - base[1] - tooltip.height - act.dp(4)).toFloat()
            tooltip.alpha = 0f
            tooltip.visibility = View.VISIBLE
            tooltip.animate().alpha(1f).translationYBy(-act.dp(4).toFloat()).setDuration(140).start()
        }
    }

    fun refreshTooltip(v: View) {
        if (v.hasFocus()) updateTooltip(v)
    }

    fun show(focus: View? = btnPlay) {
        if (act.inPip) return
        val wasVisible = up
        up = true
        if (!wasVisible) {
            root.fadeIn(180)
            bottomBar.apply {
                translationY = act.dp(16).toFloat()
                animate().translationY(0f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        act.chat.onControlsShown()
        osd.fadeOut(120)
        updateProgress()
        updateMeta()
        clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        if (!wasVisible) focus?.requestFocus()
        act.currentFocus?.let { updateTooltip(it) }
        scheduleHide()
    }

    fun hide() {
        up = false
        root.fadeOut(200)
        tooltip.visibility = View.INVISIBLE
        act.chat.onControlsHidden()
        act.ui.removeCallbacks(hideRunnable)
    }

    fun scheduleHide() {
        act.ui.removeCallbacks(hideRunnable)
        act.ui.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    fun cancelHide() {
        act.ui.removeCallbacks(hideRunnable)
    }

    fun flashOsd(text: String, icon: Int, duration: Long = 1300) {
        if (act.inPip) return
        if (up && act.pendingSeek != null) return // the seek bar already shows it
        osd.text = text
        osd.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        if (osd.visibility != View.VISIBLE) {
            osd.scaleX = 0.92f
            osd.scaleY = 0.92f
            osd.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        osd.fadeIn(140)
        act.ui.removeCallbacks(osdHide)
        act.ui.postDelayed(osdHide, duration)
    }

    fun hideOsd() {
        act.ui.removeCallbacks(osdHide)
        osd.setVisible(false)
    }

    /** Called twice a second. */
    fun tick() {
        if (!up) return
        if (act.pendingSeek == null) updateProgress()
        clock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        updateMeta()
    }

    /**
     * With chat beside the video the controls only get ~2/3 of the screen, so shrink the
     * buttons and padding and move captions into Settings (the CC key still works).
     */
    fun setCompact(on: Boolean) {
        if (compact == on) return
        compact = on
        val side = act.dp(if (on) 24 else 40)
        bottomBar.setPadding(side, bottomBar.paddingTop, side, bottomBar.paddingBottom)
        val small = act.dp(if (on) 42 else 48)
        val gap = act.dp(if (on) 6 else 10)
        val actions = act.findViewById<View>(R.id.actions)
        listOf(btnRewind, btnForward, btnChat, btnFollow, btnSettings, btnCaptions).forEach { b ->
            b.layoutParams = (b.layoutParams as LinearLayout.LayoutParams).apply {
                width = small; height = small
                if (b.parent === actions) marginStart = gap
            }
            val pad = act.dp(if (on) 11 else 12)
            b.setPadding(pad, pad, pad, pad)
        }
        btnPlay.layoutParams = (btnPlay.layoutParams as LinearLayout.LayoutParams).apply {
            val big = act.dp(if (on) 54 else 64)
            width = big; height = big
            marginStart = act.dp(if (on) 10 else 14); marginEnd = act.dp(if (on) 10 else 14)
        }
        val playPad = act.dp(if (on) 14 else 16)
        btnPlay.setPadding(playPad, playPad, playPad, playPad)
        btnLive.setPadding(act.dp(if (on) 12 else 16), 0, act.dp(if (on) 14 else 18), 0)
        btnLive.textSize = if (on) 13f else 14f
        updateCaptionButton()
    }

    fun updateMode() {
        val isVod = act.mode == PlayerActivity.Mode.VOD
        btnLive.setVisible(!isVod)
        btnChat.setVisible(!isVod)
        updateFollowButton()
        updateProgress()
    }

    fun updateFollowButton() {
        val on = Follows.isFollowing(act, act.slug)
        btnFollow.setImageResource(if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        btnFollow.isActivated = on
    }

    fun updatePlayButton() {
        btnPlay.setImageResource(
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    fun updateCaptionButton() {
        btnCaptions.isActivated = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        btnCaptions.setVisible(compact != true && act.menu.textTracks().isNotEmpty())
    }

    fun bindHeader() {
        val c = act.channel
        act.findViewById<ImageView>(R.id.avatar).loadAvatar(c?.avatar)
        act.findViewById<TextView>(R.id.channelName).text = (c?.username ?: act.slug) + if (c?.verified == true) " ✓" else ""
        act.findViewById<TextView>(R.id.streamTitle).text =
            if (act.mode == PlayerActivity.Mode.VOD) act.vodTitle.orEmpty() else c?.liveTitle.orEmpty()
        updateMeta()
        updateFollowButton()
    }

    fun updateMeta() {
        val c = act.channel
        val live = act.mode != PlayerActivity.Mode.VOD && c != null
        act.findViewById<RollingCounter>(R.id.viewerCount).apply {
            setVisible(live)
            if (c != null && live) setValue(c.liveViewers)
        }
        val meta = if (act.mode == PlayerActivity.Mode.VOD) {
            listOfNotNull(if (act.isClip) "Clip" else "Past broadcast", c?.let { "${Fmt.count(it.followers)} followers" })
        } else {
            listOfNotNull(
                c?.let { "watching" },
                c?.liveCategory,
                Fmt.uptime(c?.liveStartTime)?.let { "live for $it" },
            )
        } + listOfNotNull(if (act.sleepTimer.active) "sleep ${act.sleepTimer.label()}" else null)
        act.findViewById<TextView>(R.id.streamMeta).apply {
            text = meta.joinToString("  •  ")
            setPaddingRelative(act.dp(if (live) 5 else 10), 0, 0, 0)
        }
    }

    fun updateProgress() {
        val max = act.timelineMax()
        val pos = act.pendingSeek ?: act.timelinePos()
        seekBar.max = (max / 1000).toInt().coerceAtLeast(1)
        seekBar.progress = (pos / 1000).toInt()
        val buffered = when (act.mode) {
            PlayerActivity.Mode.LIVE -> pos + player.totalBufferedDuration
            else -> player.bufferedPosition
        }
        seekBar.secondaryProgress = (buffered / 1000).toInt()
        position.text = Fmt.time(pos)
        if (act.mode == PlayerActivity.Mode.VOD) {
            durationText.text = Fmt.time(max)
        } else {
            val atLive = act.isAtLive()
            durationText.text = if (atLive) Fmt.time(max) else "−${Fmt.time(max - pos)}"
            val label = if (atLive) "LIVE" else "GO LIVE"
            if (btnLive.text != label) {
                btnLive.text = label
                val dot = act.getDrawable(R.drawable.dot)!!.mutate().apply {
                    setBounds(0, 0, act.dp(8), act.dp(8))
                    setTint(act.getColor(if (atLive) R.color.live_red else R.color.text_secondary))
                }
                btnLive.setCompoundDrawablesRelative(dot, null, null, null)
                btnLive.compoundDrawablePadding = act.dp(8)
            }
        }
    }
}
