package com.kicktv.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kicktv.app.R
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.Prefs
import com.kicktv.app.ui.Nav
import com.kicktv.app.ui.OptionSheet
import com.kicktv.app.ui.TraceLoader
import com.kicktv.app.ui.dp
import com.kicktv.app.ui.setVisible
import kotlinx.coroutines.launch

/**
 * Two to four live streams at once. The focused tile plays sound; OK opens it full screen and
 * ☰ adds, swaps or removes streams.
 */
class MultiViewActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_SLUGS = "slugs"
        const val MAX_STREAMS = 4

        fun start(ctx: Context, slugs: List<String>) {
            ctx.startActivity(Intent(ctx, MultiViewActivity::class.java).putExtra(EXTRA_SLUGS, ArrayList(slugs)))
        }
    }

    private inner class Tile(var slug: String) {
        val frame = FrameLayout(this@MultiViewActivity)
        val video = PlayerView(this@MultiViewActivity)
        val label = TextView(this@MultiViewActivity)
        val status = TextView(this@MultiViewActivity)
        val loader = TraceLoader(this@MultiViewActivity)
        var player: ExoPlayer? = null
        var name = slug
        var retries = 0

        init {
            video.useController = false
            video.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            video.setShutterBackgroundColor(0xFF000000.toInt())
            video.isFocusable = false
            frame.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(loader, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            status.setTextColor(getColor(R.color.text_secondary))
            status.textSize = 15f
            status.gravity = Gravity.CENTER
            frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            label.setTextColor(0xFFFFFFFF.toInt())
            label.textSize = 13f
            label.setBackgroundResource(R.drawable.bg_chip_dark)
            label.setPadding(dp(8), dp(4), dp(8), dp(4))
            label.compoundDrawablePadding = dp(6)
            frame.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(10), dp(10), dp(10), dp(10))
            })
            frame.isFocusable = true
            frame.foreground = GradientDrawable().apply { setStroke(dp(3), 0x00000000); cornerRadius = dp(6).toFloat() }
            frame.setOnFocusChangeListener { _, has -> onTileFocus(this, has) }
            frame.setOnClickListener { openFull(this) }
        }

        fun start() {
            release()
            loader.setVisible(true)
            status.text = ""
            val p = PlayerFactory.create(this@MultiViewActivity, maxHeight = if (tiles.size <= 2) 720 else 480,
                leveler = LoudnessLeveler(Prefs(this@MultiViewActivity).loudnessEq))
            player = p
            video.player = p
            p.volume = if (frame.hasFocus()) 1f else 0f
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loader.setVisible(state == Player.STATE_BUFFERING)
                    if (state == Player.STATE_READY) retries = 0
                    if (state == Player.STATE_ENDED) showStatus("Stream ended")
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        p.seekToDefaultPosition(); p.prepare(); return
                    }
                    if (++retries <= 3) frame.postDelayed({ if (player === p) start() }, 2000L * retries)
                    else showStatus("Playback problem")
                }
            })
            lifecycleScope.launch {
                val c = runCatching { KickApi.channel(slug, maxAgeMs = 0) }.getOrNull()
                if (player !== p) return@launch
                name = c?.username ?: slug
                updateLabel()
                val url = c?.playbackUrl
                if (c == null) return@launch showStatus("Couldn't load $slug")
                if (!c.isLive || url == null) return@launch showStatus("${c.username} is offline")
                p.setMediaItem(PlayerFactory.item(url, PlayerFactory.liveConfig(Prefs(this@MultiViewActivity).lowLatency)))
                p.prepare()
                p.play()
            }
        }

        fun showStatus(text: String) {
            loader.setVisible(false)
            status.text = text
        }

        fun updateLabel() {
            label.text = name
            val audible = frame.hasFocus()
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(if (audible) R.drawable.ic_volume else 0, 0, 0, 0)
            label.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.kick_green))
        }

        fun release() {
            video.player = null
            player?.release()
            player = null
        }
    }

    private lateinit var grid: FrameLayout
    private val tiles = ArrayList<Tile>()
    private val sheet by lazy { OptionSheet(this) }
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        grid = FrameLayout(this).apply { keepScreenOn = true; setBackgroundColor(0xFF000000.toInt()) }
        setContentView(grid)
        val slugs = intent.getStringArrayListExtra(EXTRA_SLUGS).orEmpty().distinctBy { it.lowercase() }.take(MAX_STREAMS)
        if (slugs.isEmpty()) return finish()
        slugs.forEach { addTile(it, focus = false) }
        grid.post { tiles.firstOrNull()?.frame?.requestFocus() }
        hint()
    }

    private fun hint() {
        val t = TextView(this).apply {
            text = "OK  full screen     ☰  add, swap or remove     The focused stream plays sound"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            setBackgroundResource(R.drawable.bg_osd)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        grid.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18)
        })
        t.postDelayed({ t.animate().alpha(0f).setDuration(400).withEndAction { grid.removeView(t) }.start() }, 6000)
    }

    private fun addTile(slug: String, focus: Boolean) {
        val tile = Tile(slug)
        tiles += tile
        grid.addView(tile.frame, 0)
        layoutTiles()
        // Each new tile may lower everyone's quality cap, so (re)start them all.
        tiles.forEach { it.start() }
        if (focus) tile.frame.requestFocus()
    }

    private fun removeTile(tile: Tile) {
        val idx = tiles.indexOf(tile)
        tile.release()
        grid.removeView(tile.frame)
        tiles.remove(tile)
        if (tiles.size == 1) return openFull(tiles[0])
        layoutTiles()
        tiles.getOrNull(idx.coerceAtMost(tiles.size - 1))?.frame?.requestFocus()
    }

    /** Two side by side, three or four in a 2×2 grid, each 16:9 and centered. */
    private fun layoutTiles() {
        grid.post {
            val w = grid.width
            val h = grid.height
            if (w == 0 || h == 0) return@post layoutTiles()
            val gap = dp(6)
            val cols = 2
            val rows = if (tiles.size <= 2) 1 else 2
            val cellW = minOf((w - gap * (cols + 1)) / cols, ((h - gap * (rows + 1)) / rows) * 16 / 9)
            val cellH = cellW * 9 / 16
            val left = (w - (cellW * cols + gap * (cols - 1))) / 2
            val top = (h - (cellH * rows + gap * (rows - 1))) / 2
            tiles.forEachIndexed { i, t ->
                val r = i / cols
                // A lone tile on the bottom row sits centered.
                val lastAlone = tiles.size == 3 && i == 2
                val c = i % cols
                val x = if (lastAlone) (w - cellW) / 2 else left + c * (cellW + gap)
                t.frame.layoutParams = FrameLayout.LayoutParams(cellW, cellH).apply {
                    leftMargin = x
                    topMargin = top + r * (cellH + gap)
                }
            }
            grid.requestLayout()
        }
    }

    private fun onTileFocus(tile: Tile, has: Boolean) {
        (tile.frame.foreground as GradientDrawable).setStroke(dp(3), if (has) getColor(R.color.kick_green) else 0x00000000)
        tile.player?.volume = if (has) 1f else 0f
        tile.updateLabel()
    }

    private fun openFull(tile: Tile) {
        Nav.watchLive(this, tile.slug)
        finish()
    }

    private fun focusedTile() = tiles.firstOrNull { it.frame.hasFocus() } ?: tiles.firstOrNull()

    private fun showMenu() {
        val tile = focusedTile() ?: return
        sheet.show(OptionSheet.Page("Multi-view", eyebrow = "${tiles.size} streams", subtitle = tile.name) {
            buildList {
                if (tiles.size < MAX_STREAMS) {
                    add(OptionSheet.Item("Add a stream", sub = if (tiles.size >= 2) "More streams means lower quality for each" else null,
                        icon = R.drawable.ic_multiview, submenu = true) {
                        it.push(StreamPicker.page(this@MultiViewActivity, it, "Add a stream", exclude = tiles.map { t -> t.slug }.toSet()) { s ->
                            addTile(s.slug, focus = true)
                        })
                    })
                }
                add(OptionSheet.Item("Swap ${tile.name}", sub = "Replace it with another live channel", icon = R.drawable.ic_refresh, submenu = true) {
                    it.push(StreamPicker.page(this@MultiViewActivity, it, "Swap ${tile.name} for…", exclude = tiles.map { t -> t.slug }.toSet()) { s ->
                        tile.slug = s.slug
                        tile.name = s.slug
                        tile.retries = 0
                        tile.start()
                    })
                })
                add(OptionSheet.Item("Watch ${tile.name} full screen", icon = R.drawable.ic_play) { it.dismiss(); openFull(tile) })
                add(OptionSheet.Item(if (tiles.size > 2) "Remove ${tile.name}" else "Remove ${tile.name} (back to one stream)",
                    icon = R.drawable.ic_close) {
                    it.dismiss()
                    removeTile(tile)
                })
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            tiles.forEach { it.start() }
        }
    }

    override fun onStop() {
        super.onStop()
        // Live streams can't usefully pause; start fresh at the live edge when we come back.
        tiles.forEach { it.release() }
        stopped = true
    }

    override fun onDestroy() {
        super.onDestroy()
        tiles.forEach { it.release() }
    }

    // Overriding Activity.dispatchKeyEvent is standard; lint trips on AndroidX's restricted override of it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
