package com.kicktv.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.R
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.Prefs
import kotlinx.coroutines.launch

/** All live streams in one category, most watched first. */
class CategoryActivity : FragmentActivity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
    }

    private lateinit var adapter: GridAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: View
    private var cursor: String? = null
    private var done = false
    private var busy = false
    private var categoryId = 0
    private var allLanguages = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.page_grid)
        categoryId = intent.getIntExtra(EXTRA_ID, 0)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list.setPadding(dp(32), dp(24), dp(32), dp(40))
        adapter = GridAdapter(
            onClick = { Nav.onCardClick(this, it) },
            onLongClick = { Nav.onCardLongClick(this, it) },
            onNearEnd = { load() },
        )
        list.layoutManager = CenterGridLayoutManager(this, 4).apply { spanSizeLookup = adapter.spanLookup(4) }
        list.adapter = adapter
        list.itemAnimator = null
        adapter.append(listOf(GridItem.Header(intent.getStringExtra(EXTRA_NAME).orEmpty())))
        load()
    }

    private fun load() {
        if (busy || done) return
        busy = true
        progress.setVisible(adapter.itemCount <= 1)
        val lang = if (allLanguages) null else Prefs(this).language.ifEmpty { null }
        lifecycleScope.launch {
            try {
                var page = KickApi.livestreams(cursor = cursor, categoryId = categoryId, language = lang)
                // Nothing in the chosen language: fall back to every language.
                if (page.items.isEmpty() && cursor == null && lang != null) {
                    allLanguages = true
                    page = KickApi.livestreams(categoryId = categoryId)
                }
                val first = adapter.itemCount <= 1
                adapter.append(page.items.map { GridItem.Stream(it) })
                cursor = page.next
                done = page.next == null
                if (first) {
                    if (page.items.isEmpty()) status.text = "Nobody is live here right now."
                    list.post { list.layoutManager?.findViewByPosition(1)?.requestFocus() }
                }
            } catch (e: Exception) {
                if (adapter.itemCount <= 1) status.text = "Couldn't load streams.\n${e.message}"
                done = true
            } finally {
                busy = false
                progress.setVisible(false)
            }
        }
    }
}
