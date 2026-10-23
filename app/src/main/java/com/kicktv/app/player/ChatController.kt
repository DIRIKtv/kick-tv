package com.kicktv.app.player

import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.R
import com.kicktv.app.data.ChannelInfo
import com.kicktv.app.data.ChatClient
import com.kicktv.app.data.ChatMessage
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.SevenTv
import com.kicktv.app.ui.dp
import com.kicktv.app.ui.setVisible
import kotlinx.coroutines.launch

/** The chat panel: connecting, filtering and pacing messages, and placing it beside or over the video. */
internal class ChatController(private val act: PlayerActivity) {
    companion object {
        /** Calm chat shows one message per interval and lets the queue run at most this far behind. */
        private const val CALM_INTERVAL_MS = 450L
        private const val CALM_BACKLOG = 12
    }

    private val prefs = act.prefs
    private val panel: View = act.findViewById(R.id.chatPanel)
    private val list: RecyclerView = act.findViewById(R.id.chatList)
    private val stage: View = act.findViewById(R.id.stage)
    val adapter = ChatAdapter(act, prefs.chatTextSize)
    private val filter = ChatFilter(prefs)

    private var client: ChatClient? = null
    private var connecting = false
    private var emotesFor = -1

    /** Whether the viewer wants chat open; it only shows on live streams. */
    var wanted = false

    private val backlog = ArrayDeque<ChatMessage>()
    private var draining = false
    private val drainNext = Runnable { drain() }
    private var animator: ValueAnimator? = null

    init {
        list.layoutManager = LinearLayoutManager(act).apply { stackFromEnd = true }
        list.adapter = adapter
        // New messages slide in gently; changes (emotes arriving) redraw without flashing.
        list.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 140
            moveDuration = 140
            removeDuration = 0
            changeDuration = 0
            supportsChangeAnimations = false
        }
    }

    /** Chat is on screen in this mode (not a past broadcast, not in picture-in-picture). */
    val shown get() = wanted && act.mode != PlayerActivity.Mode.VOD && !act.inPip

    fun connect(c: ChannelInfo) {
        loadEmotes(c)
        if (c.chatroomId == 0 || client != null || connecting) return
        connecting = true
        act.lifecycleScope.launch {
            val (key, cluster) = KickApi.pusherConfig(c.id)
            connecting = false
            if (act.isDestroyed || client != null) return@launch
            client = ChatClient(
                chatroomId = c.chatroomId,
                appKey = key,
                cluster = cluster,
                onMessage = { receive(it) },
                onDeleted = { id -> backlog.removeAll { it.id == id }; adapter.remove(id) },
                onStatus = { add(ChatMessage("sys-${System.nanoTime()}", "", null, it, emptyList(), isSystem = true)) },
            ).also { it.connect() }
        }
    }

    /** (Re)loads 7TV emotes for [c], or clears them when they're turned off. */
    fun loadEmotes(c: ChannelInfo, force: Boolean = false) {
        if (!prefs.sevenTvEmotes) {
            emotesFor = -1
            adapter.thirdPartyEmotes = emptyMap()
            return
        }
        if (emotesFor == c.userId && !force) return
        emotesFor = c.userId
        act.lifecycleScope.launch { adapter.thirdPartyEmotes = SevenTv.emotes(c.userId) }
    }

    fun setSubscriberBadges(c: ChannelInfo) {
        if (adapter.subscriberBadges.isEmpty()) adapter.subscriberBadges = c.subscriberBadges
    }

    fun setTextSize(sp: Int) {
        adapter.textSizeSp = sp
        adapter.notifyDataSetChanged()
    }

    /** Drops the connection and messages (switching to another channel). */
    fun reset() {
        close()
        client = null
        connecting = false
        emotesFor = -1
        adapter.clear()
        adapter.subscriberBadges = emptyList()
        adapter.thirdPartyEmotes = emptyMap()
    }

    fun close() {
        act.ui.removeCallbacks(drainNext)
        backlog.clear()
        draining = false
        client?.close()
    }

    private fun receive(m: ChatMessage) {
        if (!filter.allows(m)) return
        if (!prefs.chatCalm) {
            add(m)
            return
        }
        backlog.addLast(m)
        // Far behind: skip the oldest so chat stays roughly in step with the stream.
        while (backlog.size > CALM_BACKLOG) backlog.removeFirst()
        if (!draining) drain()
    }

    private fun drain() {
        val m = backlog.removeFirstOrNull()
        if (m == null) {
            draining = false
            return
        }
        draining = true
        add(m)
        act.ui.postDelayed(drainNext, CALM_INTERVAL_MS)
    }

    private fun add(m: ChatMessage) {
        adapter.add(m)
        if (panel.visibility == View.VISIBLE) list.scrollToPosition(adapter.itemCount - 1)
    }

    /** With overlay chat, the panel steps aside while the controls are up. */
    fun onControlsShown() {
        if (prefs.chatMode != "side") panel.setVisible(false)
    }

    fun onControlsHidden() {
        panel.setVisible(shown)
    }

    /**
     * Places chat beside or over the video. With [animate], chat slides in from the right while
     * the video eases over to make room (and the reverse when closing).
     */
    fun applyLayout(animate: Boolean = false) {
        val show = shown
        val side = prefs.chatMode == "side"
        val panelVisible = show && (side || !act.controls.up)
        panel.setBackgroundColor(act.getColor(if (side) R.color.chat_bg else R.color.chat_overlay_bg))
        act.controls.btnChat.isActivated = show
        if (show) list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
        act.controls.setCompact(show && side)

        val lp = stage.layoutParams as FrameLayout.LayoutParams
        val targetMargin = if (show && side) act.dp(300) else 0
        animator?.cancel()
        if (!animate) {
            panel.setVisible(panelVisible)
            panel.translationX = 0f
            panel.alpha = 1f
            lp.marginEnd = targetMargin
            stage.layoutParams = lp
            return
        }
        val width = act.dp(300).toFloat()
        val startMargin = lp.marginEnd
        val startShift = if (panel.visibility == View.VISIBLE) panel.translationX else width
        val endShift = if (panelVisible) 0f else width
        panel.visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                panel.translationX = startShift + (endShift - startShift) * f
                panel.alpha = if (side) 1f else 1f - (panel.translationX / width)
                if (startMargin != targetMargin) {
                    lp.marginEnd = (startMargin + (targetMargin - startMargin) * f).toInt()
                    stage.layoutParams = lp
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!panelVisible) panel.visibility = View.GONE
                    panel.translationX = 0f
                    panel.alpha = 1f
                }
            })
            start()
        }
    }
}
