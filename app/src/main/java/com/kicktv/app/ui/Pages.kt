package com.kicktv.app.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.R
import com.kicktv.app.data.Follows
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared plumbing for a page that is a single grid with a status line. */
abstract class GridPage(protected val act: FragmentActivity, layout: Int, spanCount: Int) : MainPage {
    override val view: View = android.view.LayoutInflater.from(act).inflate(layout, null)
    protected val list: RecyclerView = view.findViewById(R.id.list)
    protected val status: TextView = view.findViewById(R.id.status)
    protected val progress: View = view.findViewById(R.id.progress)
    protected val adapter = GridAdapter(
        onClick = { Nav.onCardClick(act, it) },
        onLongClick = { Nav.onCardLongClick(act, it) },
        onNearEnd = { onNearEnd() },
    )

    init {
        list.layoutManager = CenterGridLayoutManager(act, spanCount).apply { spanSizeLookup = adapter.spanLookup(spanCount) }
        list.adapter = adapter
        list.itemAnimator = null
    }

    open fun onNearEnd() {}

    override fun focusContent(): Boolean {
        val lm = list.layoutManager as CenterGridLayoutManager
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        for (i in first until adapter.itemCount) {
            val v = lm.findViewByPosition(i) ?: continue
            if (v.isFocusable && v.requestFocus()) return true
        }
        return false
    }
}

class FollowingPage(act: FragmentActivity) : GridPage(act, R.layout.page_grid, 4) {
    private var job: Job? = null

    override fun onShown() = refresh()

    fun refresh() {
        job?.cancel()
        status.text = ""
        progress.setVisible(adapter.itemCount == 0)
        job = act.lifecycleScope.launch {
            val favs = Follows.all(act)
            if (favs.isEmpty()) {
                progress.setVisible(false)
                adapter.submit(emptyList())
                status.text = "Nothing followed yet.\n\nSign in under Settings to bring your Kick follows,\nor press ♥ in the player or on a channel page."
                return@launch
            }
            val infos = favs.map { f -> async { f to runCatching { KickApi.channelForCard(f.slug) }.getOrNull() } }.awaitAll()
            val live = infos.mapNotNull { it.second }.filter { it.isLive }.sortedByDescending { it.liveViewers }
            val offline = infos.filter { it.second?.isLive != true }
            val items = mutableListOf<GridItem>()
            if (live.isNotEmpty()) {
                items += GridItem.Header("Live now")
                items += live.map {
                    GridItem.Stream(
                        com.kicktv.app.data.LiveStream(it.slug, it.username, it.liveTitle.orEmpty(), it.liveViewers,
                            it.liveThumbnail, it.avatar, it.liveCategory, null, it.liveStartTime)
                    )
                }
            }
            if (offline.isNotEmpty()) {
                items += GridItem.Header("Offline")
                items += offline.map { (fav, info) ->
                    GridItem.Channel(fav.slug, info?.username ?: fav.username, info?.avatar ?: fav.avatar, false,
                        info?.let { "${Fmt.count(it.followers)} followers" } ?: "")
                }
            }
            val hadFocus = list.hasFocus()
            adapter.submit(items)
            progress.setVisible(false)
            if (hadFocus) list.post { focusContent() }
        }
    }
}

class BrowsePage(act: FragmentActivity) : GridPage(act, R.layout.page_grid, 7) {
    private var page = 1
    private var done = false
    private var busy = false

    /** Load the first page in the background so the grid is instant when opened. */
    fun preload() {
        if (adapter.itemCount == 0) onNearEnd()
    }

    private fun prefetch(urls: List<String>) {
        val loader = coil3.SingletonImageLoader.get(act)
        urls.forEach { url ->
            loader.enqueue(coil3.request.ImageRequest.Builder(act).data(url).size(240, 320).build())
        }
    }

    override fun onShown() {
        if (adapter.itemCount == 0) onNearEnd()
    }

    override fun onNearEnd() {
        if (busy || done) return
        busy = true
        progress.setVisible(adapter.itemCount == 0)
        act.lifecycleScope.launch {
            try {
                val res = KickApi.categories(page, 36)
                if (page == 1) adapter.append(listOf(GridItem.Header("Browse categories")))
                adapter.append(res.items.map { GridItem.Cat(it) })
                prefetch(res.items.mapNotNull { it.banner })
                page++
                done = res.next == null
                status.text = ""
            } catch (e: Exception) {
                if (adapter.itemCount == 0) status.text = "Couldn't load categories.\n${e.message}"
                done = true
            } finally {
                busy = false
                progress.setVisible(false)
            }
        }
    }
}

class SearchPage(act: FragmentActivity) : GridPage(act, R.layout.page_search, 6) {
    private val query: EditText = view.findViewById(R.id.query)
    private var job: Job? = null

    init {
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = search(s.toString(), 450)
        })
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(query.text.toString(), 0)
                hideKeyboard()
                list.postDelayed({ super.focusContent() }, 300)
                true
            } else false
        }
        query.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && adapter.itemCount > 0) {
                hideKeyboard()
                super.focusContent()
            } else false
        }
    }

    private fun hideKeyboard() {
        (act.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(query.windowToken, 0)
    }

    private fun search(q: String, debounce: Long) {
        job?.cancel()
        if (q.isBlank()) {
            adapter.submit(emptyList())
            status.text = "Search for a streamer by name"
            return
        }
        job = act.lifecycleScope.launch {
            delay(debounce)
            progress.setVisible(true)
            status.text = ""
            try {
                val results = KickApi.search(q.trim())
                adapter.submit(results.map {
                    GridItem.Channel(it.slug, it.username, it.avatar, it.isLive, "${Fmt.count(it.followers)} followers")
                })
                if (results.isEmpty()) status.text = "No channels match “${q.trim()}”"
            } catch (e: Exception) {
                status.text = "Search failed.\n${e.message}"
            } finally {
                progress.setVisible(false)
            }
        }
    }

    override fun focusContent(): Boolean = query.requestFocus()
}

class SettingsPage(private val act: FragmentActivity, private val onChanged: () -> Unit) : MainPage {
    private val prefs = Prefs(act)
    private val container = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(36), act.dp(28), act.dp(36), act.dp(40))
    }
    override val view: View = ScrollView(act).apply {
        addView(container)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }
    private val sheet by lazy { OptionSheet(act) }

    private sealed class Row {
        class Header(val text: String) : Row()
        class Setting(
            val title: String,
            val summary: () -> String,
            val icon: Int,
            val value: () -> String?,
            /** Toggles show their state as a pill and flip in place instead of opening a menu. */
            val toggle: (() -> Boolean)? = null,
            val onClick: () -> Unit,
        ) : Row()
    }

    private val qualities = listOf(0, 1080, 720, 480, 360, 160)
    private fun qualityLabel(h: Int) = if (h == 0) "Auto" else "${h}p"

    private fun rows(): List<Row> = listOf(
        Row.Header("ACCOUNT"),
        Row.Setting(
            title = prefs.accountName?.let { "Signed in as $it" } ?: "Sign in to Kick",
            summary = { if (Follows.signedIn) "Your followed channels show up in Following and on Home" else "Bring your followed channels to the TV" },
            icon = R.drawable.ic_account,
            value = { null },
        ) { accountMenu() },

        Row.Header("PLAYBACK"),
        Row.Setting("Default quality", { "Used when a stream starts. Auto adapts to your connection." }, R.drawable.ic_hd,
            { qualityLabel(prefs.preferredQuality) }) {
            choose("Default quality", "Pick the quality new streams start at.", qualities.map { qualityLabel(it) },
                subs = listOf("Recommended", "Source on most channels", null, null, null, "Data saver"),
                selected = qualities.indexOf(prefs.preferredQuality)) { prefs.preferredQuality = qualities[it] }
        },
        Row.Setting("Low latency", { "Closer to live, but more likely to buffer on slower connections" }, R.drawable.ic_bolt,
            { if (prefs.lowLatency) "On" else "Off" }, toggle = { prefs.lowLatency }) {
            prefs.lowLatency = !prefs.lowLatency
        },
        Row.Setting("Seek step", { "How far ◀ ▶ jump in the player" }, R.drawable.ic_forward,
            { "${prefs.seekStepSeconds}s" }) {
            val steps = listOf(5, 10, 15, 30)
            choose("Seek step", null, steps.map { "$it seconds" }, selected = steps.indexOf(prefs.seekStepSeconds)) {
                prefs.seekStepSeconds = steps[it]
            }
        },

        Row.Header("CHAT"),
        Row.Setting("Show chat", { "Open chat automatically on live streams" }, R.drawable.ic_chat,
            { if (prefs.chatEnabled) "On" else "Off" }, toggle = { prefs.chatEnabled }) {
            prefs.chatEnabled = !prefs.chatEnabled
        },
        Row.Setting("Chat position", { "Where chat sits next to the stream" }, R.drawable.ic_layout,
            { if (prefs.chatMode == "side") "Beside video" else "Over video" }) {
            val modes = listOf("side", "overlay")
            choose("Chat position", null, listOf("Beside the video", "Over the video"),
                subs = listOf("Video shrinks to make room", "See-through panel on top"),
                selected = modes.indexOf(prefs.chatMode)) { prefs.chatMode = modes[it] }
        },
        Row.Setting("Chat text size", { "" }, R.drawable.ic_text, { "${prefs.chatTextSize}sp" }) {
            val sizes = listOf(13, 15, 17, 20)
            choose("Chat text size", null, listOf("Small", "Medium", "Large", "Extra large"),
                values = sizes.map { "${it}sp" }, selected = sizes.indexOf(prefs.chatTextSize)) { prefs.chatTextSize = sizes[it] }
        },

        Row.Header("APP"),
        Row.Setting("Check for updates", { "Updates come from github.com/${com.kicktv.app.data.Updater.REPO}" }, R.drawable.ic_refresh,
            { "v${com.kicktv.app.BuildConfig.VERSION_NAME}" }) { checkForUpdates() },

        Row.Header("BROWSING"),
        Row.Setting("Stream language", { "Filters the rows on Home" }, R.drawable.ic_language,
            { Languages.label(prefs.language) }) {
            choose("Stream language", "Home shows streams in this language.", Languages.all.map { it.second },
                selected = Languages.all.indexOfFirst { it.first == prefs.language }) {
                prefs.language = Languages.all[it].first
                onChanged()
            }
        },
    )

    init {
        render()
    }

    /**
     * Builds the rows once, then only updates their contents. Rebuilding would remove the focused
     * row, and Android would hand focus to the first nav item (switching the page to Home).
     */
    private fun render() {
        val list = rows()
        if (container.childCount == 0) build(list)
        list.forEachIndexed { i, row ->
            if (row is Row.Setting) bindRow(container.getChildAt(i + 1), row)
        }
    }

    private fun build(list: List<Row>) {
        container.addView(TextView(act).apply {
            setTextAppearance(R.style.PageTitle)
            text = "Settings"
            setPadding(act.dp(6), 0, 0, act.dp(2))
        })
        for (row in list) {
            container.addView(
                when (row) {
                    is Row.Header -> container.inflate(R.layout.item_setting_header).also { (it as TextView).text = row.text }
                    is Row.Setting -> container.inflate(R.layout.item_setting)
                }
            )
        }
        container.addView(TextView(act).apply {
            text = "Kick TV ${com.kicktv.app.BuildConfig.VERSION_NAME} • unofficial app, not affiliated with Kick\n" +
                "Tips: hold OK (or press ☰) on a stream to open its channel.\n" +
                "In the player: ◀ ▶ seek (hold to speed up), ▲ ▼ show controls, ☰ opens settings."
            setTextColor(act.getColor(R.color.text_secondary))
            textSize = 12f
            setLineSpacing(0f, 1.3f)
            setPadding(act.dp(6), act.dp(22), 0, 0)
        })
    }

    private fun bindRow(v: View, row: Row.Setting) {
        v.findViewById<ImageView>(R.id.icon).setImageResource(row.icon)
        v.findViewById<TextView>(R.id.title).text = row.title
        val summary = row.summary()
        v.findViewById<TextView>(R.id.summary).apply { text = summary; setVisible(summary.isNotEmpty()) }
        val value = row.value()
        v.findViewById<TextView>(R.id.value).apply {
            text = value.orEmpty()
            setVisible(value != null)
            if (row.toggle != null) {
                setBackgroundResource(if (row.toggle.invoke()) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off)
            }
        }
        v.findViewById<View>(R.id.chevron).setVisible(row.toggle == null)
        v.setOnClickListener {
            row.onClick()
            if (row.toggle != null) render()
        }
    }

    private fun choose(
        title: String,
        subtitle: String?,
        options: List<String>,
        subs: List<String?> = emptyList(),
        values: List<String?> = emptyList(),
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        sheet.show(OptionSheet.Page(title, eyebrow = "Settings", subtitle = subtitle) {
            options.mapIndexed { i, label ->
                OptionSheet.Item(label, sub = subs.getOrNull(i), value = values.getOrNull(i), checked = i == selected) {
                    onPick(i)
                    it.dismiss()
                    render()
                }
            }
        })
    }

    private fun checkForUpdates() {
        android.widget.Toast.makeText(act, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
        act.lifecycleScope.launch {
            try {
                val release = com.kicktv.app.data.Updater.checkNow()
                if (release != null) UpdateFlow(act, release).show()
                else android.widget.Toast.makeText(act, "You're on the latest version.", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(act, "Couldn't check for updates: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun accountMenu() {
        if (!Follows.signedIn) {
            act.startActivity(android.content.Intent(act, LoginActivity::class.java))
            return
        }
        sheet.show(OptionSheet.Page(prefs.accountName ?: "Account", eyebrow = "Kick account") {
            listOf(
                OptionSheet.Item("Refresh followed channels", icon = R.drawable.ic_refresh) {
                    Follows.invalidate(); onChanged(); it.dismiss()
                },
                OptionSheet.Item("Sign out", sub = "Favorites saved on this TV are kept", icon = R.drawable.ic_logout) {
                    Follows.signOut(act); it.dismiss(); render(); onChanged()
                },
            )
        })
    }

    fun onResume() = render()

    override fun onShown() = render()

    override fun focusContent(): Boolean {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c.isFocusable && c.requestFocus()) return true
        }
        return false
    }
}
