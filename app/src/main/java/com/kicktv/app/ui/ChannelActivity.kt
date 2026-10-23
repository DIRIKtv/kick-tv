package com.kicktv.app.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.R
import com.kicktv.app.data.ChannelInfo
import com.kicktv.app.data.Follows
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.Prefs
import com.kicktv.app.data.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Channel page: live status, favorite toggle and past broadcasts. */
class ChannelActivity : FragmentActivity() {
    companion object {
        const val EXTRA_SLUG = "slug"
    }

    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private var channel: ChannelInfo? = null
    private var videos: List<Video> = emptyList()
    private var clips: List<com.kicktv.app.data.Clip> = emptyList()
    private lateinit var vodAdapter: GridAdapter
    private var header: View? = null

    private val headerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (channel != null) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(parent.inflate(R.layout.item_channel_header)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            header = holder.itemView
            bindHeader(holder.itemView)
            holder.itemView.alpha = 0f
            holder.itemView.translationY = dp(14).toFloat()
            holder.itemView.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)
        prefs = Prefs(this)
        list = findViewById(R.id.list)
        vodAdapter = GridAdapter(onClick = { Nav.onCardClick(this, it) })
        val span = 4
        list.layoutManager = CenterGridLayoutManager(this, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val offset = headerAdapter.itemCount
                    if (position < offset) return span
                    return if (vodAdapter.items.getOrNull(position - offset) is GridItem.Header) span else 1
                }
            }
        }
        list.adapter = ConcatAdapter(headerAdapter, vodAdapter)
        list.itemAnimator = null
        load()
    }

    override fun onResume() {
        super.onResume()
        // Refresh resume bars after watching a VOD.
        if (videos.isNotEmpty()) submitVideos()
    }

    private fun load() {
        val slug = intent.getStringExtra(EXTRA_SLUG) ?: return finish()
        lifecycleScope.launch {
            val vids = async { runCatching { KickApi.videos(slug) }.getOrDefault(emptyList()) }
            val clipsJob = async { runCatching { KickApi.clips(slug, time = "all").items }.getOrDefault(emptyList()) }
            try {
                channel = KickApi.channel(slug)
            } catch (e: Exception) {
                findViewById<View>(R.id.progress).setVisible(false)
                android.widget.Toast.makeText(this@ChannelActivity, "Couldn't load channel: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            findViewById<ImageView>(R.id.bannerBg).loadThumb(channel?.banner)
            headerAdapter.notifyDataSetChanged()
            videos = vids.await()
            clips = clipsJob.await()
            submitVideos()
            findViewById<View>(R.id.progress).setVisible(false)
            list.post {
                val h = header ?: return@post
                val watch = h.findViewById<View>(R.id.btnWatch)
                if (watch.visibility == View.VISIBLE) watch.requestFocus() else h.findViewById<View>(R.id.btnFav).requestFocus()
            }
        }
    }

    private fun submitVideos() {
        val slug = channel?.slug ?: return
        val items = videos.map<Video, GridItem> { v ->
            val pos = prefs.vodPosition(v.uuid)
            GridItem.Vod(v, slug, if (v.durationMs > 0) pos.toFloat() / v.durationMs else 0f)
        }.toMutableList()
        if (clips.isNotEmpty()) {
            items += GridItem.Header("Popular clips")
            items += clips.map { GridItem.Clip(it) }
        }
        if (vodAdapter.items.size == items.size) vodAdapter.update(items) else vodAdapter.submit(items)
        header?.findViewById<TextView>(R.id.vodHeader)?.text =
            if (videos.isEmpty()) "No past broadcasts" else "Past broadcasts"
    }

    private fun bindHeader(v: View) {
        val c = channel ?: return
        v.findViewById<ImageView>(R.id.avatar).loadAvatar(c.avatar)
        v.findViewById<TextView>(R.id.name).text = c.username + if (c.verified) "  ✓" else ""
        v.findViewById<TextView>(R.id.followers).text = "${Fmt.count(c.followers)} followers"
        v.findViewById<TextView>(R.id.bio).apply { text = c.bio.orEmpty(); setVisible(!c.bio.isNullOrBlank()) }
        v.findViewById<TextView>(R.id.liveInfo).apply {
            setVisible(c.isLive)
            text = listOfNotNull("● LIVE", Fmt.viewers(c.liveViewers), c.liveCategory, c.liveTitle).joinToString("  •  ")
        }
        v.findViewById<TextView>(R.id.btnWatch).apply {
            setVisible(c.isLive)
            setOnClickListener { Nav.watchLive(this@ChannelActivity, c.slug) }
            focusZoom(1.05f)
        }
        val fav = v.findViewById<TextView>(R.id.btnFav)
        fun updateFav() {
            val on = Follows.isFollowing(this, c.slug)
            fav.text = if (on) "Following" else "Follow"
            fav.setCompoundDrawablesRelativeWithIntrinsicBounds(if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline, 0, 0, 0)
        }
        fun toggle() = lifecycleScope.launch {
            Follows.toggle(this@ChannelActivity, c.slug, c.username, c.avatar)
            updateFav()
        }
        updateFav()
        fav.focusZoom(1.05f)
        fav.setOnClickListener {
            if (Follows.isFollowing(this, c.slug)) {
                OptionSheet.confirm(this, "Unfollow ${c.username}?", "They'll be removed from Following.",
                    "Unfollow", R.drawable.ic_heart_outline) { toggle() }
            } else {
                toggle()
            }
        }
    }
}
