package com.kicktv.app.ui

import android.os.Parcelable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.R
import com.kicktv.app.data.Follows
import com.kicktv.app.data.HistoryEntry
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.LiveStream
import com.kicktv.app.data.Page
import com.kicktv.app.data.Prefs
import com.kicktv.app.data.Video
import com.kicktv.app.tv.TvHome
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Pages hosted by the main screen next to the nav rail. */
interface MainPage {
    val view: View
    fun onShown() {}
    /** Move focus into the page. Returns false if there's nothing focusable yet. */
    fun focusContent(): Boolean
}

/** Home: a hero carousel of live followed channels, then rows of live streams. */
class HomePage(private val act: FragmentActivity) : MainPage {
    private val prefs = Prefs(act)
    private val list = RecyclerView(act)
    private val status = TextView(act)
    private val progress = TraceLoader(act)
    private val rows = mutableListOf<Row>()
    private var lastLoad = 0L
    private var loadedVersion = -1
    private var loading = false
    /** Grab focus as soon as content arrives (startup, or a reload while focused here). */
    private var pendingFocus = true
    private val cardWidth = act.dp(200)
    /** Rows get a fixed height; wrap_content made them re-measure (and rebind) on every image load. */
    private val rowListHeight: Int by lazy {
        val probe = FrameLayout(act).inflate(R.layout.item_stream_card)
        val lp = probe.layoutParams as ViewGroup.MarginLayoutParams
        probe.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        probe.measuredHeight + lp.topMargin + lp.bottomMargin + act.dp(16)
    }

    // Hero state
    private var heroItems: List<LiveStream> = emptyList()
    private var heroFromFollows = false
    private var heroIndex = 0
    private var heroView: View? = null

    override val view: View = FrameLayout(act).apply {
        addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        addView(progress, FrameLayout.LayoutParams(act.dp(48), act.dp(48), android.view.Gravity.CENTER))
    }

    private inner class Row(
        val title: String,
        /** Built from this TV's watch history rather than loaded from Kick. */
        val local: Boolean = false,
        val loader: suspend (String?) -> Page<GridItem>,
    ) {
        var cursor: String? = null
        var done = false
        var busy = false
        var scrollState: Parcelable? = null
        val adapter = GridAdapter(
            cardWidthPx = cardWidth,
            onClick = { Nav.onCardClick(act, it) },
            onLongClick = { Nav.onCardLongClick(act, it) },
            onNearEnd = { loadMore() },
        )

        fun loadMore() {
            if (busy || done) return
            busy = true
            act.lifecycleScope.launch {
                try {
                    val page = loader(cursor)
                    val seen = adapter.items.map { it.key }.toSet()
                    adapter.append(page.items.filter { it.key !in seen })
                    cursor = page.next
                    done = page.next == null
                    if (pendingFocus && page.items.isNotEmpty()) list.post { if (focusContent()) pendingFocus = false }
                } catch (e: Exception) {
                    done = true
                } finally {
                    busy = false
                }
            }
        }
    }

    private val heroOffset get() = if (heroItems.isNotEmpty()) 1 else 0

    private val rowAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size + heroOffset

        override fun getItemViewType(position: Int) = if (position < heroOffset) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val v = parent.inflate(R.layout.item_hero)
                setupHero(v)
                return object : RecyclerView.ViewHolder(v) {}
            }
            val v = parent.inflate(R.layout.item_row)
            v.findViewById<RecyclerView>(R.id.rowList).apply {
                layoutParams.height = rowListHeight
                layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
                itemAnimator = null
                setHasFixedSize(true)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position < heroOffset) {
                heroView = holder.itemView
                bindHero(animate = false)
                return
            }
            val row = rows[position - heroOffset]
            holder.itemView.findViewById<TextView>(R.id.rowTitle).text = row.title
            val rv = holder.itemView.findViewById<RecyclerView>(R.id.rowList)
            rv.adapter = row.adapter
            rv.layoutManager?.onRestoreInstanceState(row.scrollState)
            holder.itemView.tag = row
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            val row = holder.itemView.tag as? Row ?: return
            row.scrollState = holder.itemView.findViewById<RecyclerView>(R.id.rowList).layoutManager?.onSaveInstanceState()
        }
    }

    init {
        list.layoutManager = TopAlignLayoutManager(act, act.dp(4))
        list.adapter = rowAdapter
        list.setPadding(0, act.dp(18), 0, act.dp(200))
        list.clipToPadding = false
        list.setItemViewCacheSize(12)
        list.itemAnimator = null
        status.setTextColor(act.getColor(R.color.text_secondary))
        status.textSize = 17f
    }

    // region hero

    private fun setupHero(v: View) {
        val hero = v.findViewById<View>(R.id.hero)
        hero.clipToOutline = true
        hero.setOnClickListener { heroItems.getOrNull(heroIndex)?.let { Nav.watchLive(act, it.slug) } }
        hero.setOnLongClickListener { heroItems.getOrNull(heroIndex)?.let { Nav.channel(act, it.slug) }; true }
        hero.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener keyCode == KeyEvent.KEYCODE_MENU
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (heroIndex < heroItems.size - 1) { heroIndex++; bindHero(animate = true, direction = 1) }
                    true // stay on the hero at the end
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (heroIndex > 0) { heroIndex--; bindHero(animate = true, direction = -1); true }
                    else false // first item: let focus move to the nav rail
                }
                KeyEvent.KEYCODE_MENU -> { heroItems.getOrNull(heroIndex)?.let { Nav.channel(act, it.slug) }; true }
                else -> false
            }
        }
        // The step player fills the current bar and moves the carousel on, but only while the
        // viewer is looking elsewhere; focusing the hero holds it.
        val steps = v.findViewById<StepPlayerView>(R.id.heroSteps)
        steps.onAdvance = { i ->
            if (i in heroItems.indices) { heroIndex = i; bindHero(animate = true) }
        }
        hero.setOnFocusChangeListener { _, has ->
            v.findViewById<TextView>(R.id.heroHint).alpha = if (has) 1f else 0.6f
            steps.playing = !has
        }
    }

    private fun bindHero(animate: Boolean, direction: Int = 1) {
        val v = heroView ?: return
        val s = heroItems.getOrNull(heroIndex) ?: return
        val content = listOf<View>(
            v.findViewById(R.id.heroName), v.findViewById(R.id.heroTitle),
            v.findViewById(R.id.heroMeta), v.findViewById(R.id.heroAvatar),
        )
        v.findViewById<ImageView>(R.id.heroImage).loadThumb(Img.thumb(s.thumbnail, 1080))
        v.findViewById<ImageView>(R.id.heroAvatar).loadAvatar(s.avatar)
        v.findViewById<TextView>(R.id.heroName).text = s.username
        v.findViewById<TextView>(R.id.heroTitle).text = s.title
        v.findViewById<TextView>(R.id.heroMeta).text = listOfNotNull(
            Fmt.viewers(s.viewers), s.category, Fmt.uptime(s.startTime)?.let { "live for $it" },
        ).joinToString("  •  ")
        v.findViewById<TextView>(R.id.heroEyebrow).text =
            if (heroFromFollows) "●  FOLLOWING  •  LIVE NOW" else "●  FEATURED  •  LIVE NOW"
        v.findViewById<TextView>(R.id.heroHint).text =
            if (heroItems.size > 1) "Watch     ◀ ▶  ${heroIndex + 1} of ${heroItems.size}" else "Watch"
        v.findViewById<View>(R.id.heroPrev).setVisible(heroIndex > 0)
        v.findViewById<View>(R.id.heroNext).setVisible(heroIndex < heroItems.size - 1)

        v.findViewById<StepPlayerView>(R.id.heroSteps).apply {
            bind(heroItems.size, heroIndex)
            setVisible(heroItems.size > 1)
        }
        if (animate) {
            content.forEach {
                it.alpha = 0f
                it.translationX = act.dp(24) * direction.toFloat()
                it.animate().alpha(1f).translationX(0f).setDuration(220).start()
            }
        }
    }

    // endregion

    override fun onShown() {
        if (System.currentTimeMillis() - lastLoad > 3 * 60_000 || loadedVersion != Follows.version) reload()
        else if (historyStamp() != loadedHistory) refreshHistoryRows()
    }

    // region watch history rows

    private var loadedHistory = 0L
    private var liveSlugs: Set<String> = emptySet()

    private fun historyStamp() = prefs.history.firstOrNull()?.watchedAt ?: 0L

    /** Past broadcasts started but not finished, most recent first. */
    private fun continueWatching(): List<GridItem> = prefs.history
        .filter { it.kind == HistoryEntry.VOD && it.source != null && it.videoId != null }
        .mapNotNull { e ->
            val pos = prefs.vodPosition(e.videoId!!)
            if (pos < 10_000 || (e.durationMs > 0 && pos > e.durationMs - 60_000)) return@mapNotNull null
            val video = Video(e.videoId, e.title, e.source!!, e.thumbnail, e.durationMs, 0, e.createdAt, e.category)
            GridItem.Vod(video, e.slug, if (e.durationMs > 0) pos.toFloat() / e.durationMs else 0f, channelName = e.username)
        }
        .take(20)

    /** Channels watched recently, live ones marked. */
    private fun recentlyWatched(): List<GridItem> {
        val seen = HashSet<String>()
        return prefs.history.filter { seen.add(it.slug.lowercase()) }.take(20).map { e ->
            val live = e.slug.lowercase() in liveSlugs
            GridItem.Channel(e.slug, e.username, e.avatar, live, if (live) "Live now" else "Watched ${Fmt.ago(e.watchedAt)}")
        }
    }

    private fun historyRows(): List<Row> = buildList {
        val cont = continueWatching()
        if (cont.isNotEmpty()) add(Row("Continue watching", local = true) { Page(cont, null) })
        val recent = recentlyWatched()
        if (recent.isNotEmpty()) add(Row("Recently watched", local = true) { Page(recent, null) })
    }

    /** After watching something, update just the history rows instead of reloading Home. */
    private fun refreshHistoryRows() {
        loadedHistory = historyStamp()
        val keepFocus = list.hasFocus()
        val firstNetwork = rows.indexOfFirst { !it.local }.let { if (it < 0) rows.size else it }
        repeat(firstNetwork) { rows.removeAt(0) }
        rows.addAll(0, historyRows())
        rows.filter { it.local }.forEach { it.loadMore() }
        rowAdapter.notifyDataSetChanged()
        if (keepFocus && !list.hasFocus()) list.post { focusContent() }
    }

    // endregion

    fun reload() {
        if (loading) return
        loading = true
        lastLoad = System.currentTimeMillis()
        loadedVersion = Follows.version
        if (list.hasFocus()) pendingFocus = true
        progress.setVisible(rows.isEmpty())
        status.text = ""
        act.lifecycleScope.launch {
            try {
                val lang = prefs.language.ifEmpty { null }

                val liveFollowsJob = async { runCatching { Follows.liveNow(act) }.getOrDefault(emptyList()) }
                val categoriesJob = async { runCatching { KickApi.categories(1, 8).items }.getOrDefault(emptyList()) }
                val topJob = async { runCatching { KickApi.livestreams(language = lang, limit = 24) }.getOrNull() }

                val liveFollows = liveFollowsJob.await()
                val top = topJob.await()
                liveSlugs = liveFollows.map { it.slug.lowercase() }.toSet()
                TvHome.publishAsync(act, liveFollows, top?.items.orEmpty())

                // Hero: live follows, or the biggest streams when none of them are on.
                heroFromFollows = liveFollows.isNotEmpty()
                heroItems = liveFollows.ifEmpty { top?.items?.take(6).orEmpty() }
                heroIndex = heroIndex.coerceAtMost((heroItems.size - 1).coerceAtLeast(0))

                val newRows = mutableListOf<Row>()
                loadedHistory = historyStamp()
                newRows += historyRows()
                newRows += Row(if (lang == null) "Top live" else "Top live • ${Languages.label(lang)}") { cursor ->
                    (if (cursor == null && top != null) top else KickApi.livestreams(cursor = cursor, language = lang)).asCards()
                }
                if (lang != null) {
                    newRows += Row("Top live • All languages") { KickApi.livestreams(cursor = it).asCards() }
                }
                newRows += Row("Top clips today") { cursor ->
                    val page = KickApi.clips(cursor = cursor, time = "day")
                    Page(page.items.map { GridItem.Clip(it) }, page.next)
                }
                for (cat in categoriesJob.await()) {
                    newRows += Row(cat.name) { KickApi.livestreams(cursor = it, categoryId = cat.id, language = lang).asCards() }
                }

                rows.clear()
                rows += newRows
                rowAdapter.notifyDataSetChanged()
                rows.forEach { it.loadMore() }
                if (pendingFocus && heroItems.isNotEmpty()) list.post { if (focusContent()) pendingFocus = false }
            } catch (e: Exception) {
                status.text = "Couldn't reach Kick. Check your connection.\n\nPress ☰ on the remote to try again."
            } finally {
                progress.setVisible(false)
                loading = false
            }
        }
    }

    override fun focusContent(): Boolean {
        val lm = list.layoutManager as LinearLayoutManager
        val first = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        val holder = list.findViewHolderForAdapterPosition(first) ?: return false
        holder.itemView.findViewById<View?>(R.id.hero)?.let { return it.requestFocus() }
        val inner = holder.itemView.findViewById<RecyclerView>(R.id.rowList) ?: return false
        val innerLm = inner.layoutManager as LinearLayoutManager
        val target = innerLm.findViewByPosition(innerLm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0))
            ?: inner.getChildAt(0)
        return target?.requestFocus() ?: false
    }
}

private fun Page<LiveStream>.asCards(): Page<GridItem> = Page(items.map { GridItem.Stream(it) }, next)

object Languages {
    val all = listOf(
        "" to "All languages", "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "de" to "German",
        "fr" to "French", "it" to "Italian", "ar" to "Arabic", "tr" to "Turkish", "ru" to "Russian",
        "pl" to "Polish", "nl" to "Dutch", "ja" to "Japanese", "ko" to "Korean",
    )

    fun label(code: String) = all.firstOrNull { it.first == code }?.second ?: code
}
