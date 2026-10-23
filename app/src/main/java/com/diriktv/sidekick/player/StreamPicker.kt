package com.diriktv.sidekick.player

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.KickApi
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.ui.Fmt
import com.diriktv.sidekick.ui.OptionSheet
import kotlinx.coroutines.launch

/** A sheet page of live channels to pick one from: live follows first, then the top streams. */
internal object StreamPicker {
    fun page(
        act: FragmentActivity,
        sheet: OptionSheet,
        title: String,
        exclude: Set<String>,
        onPick: (LiveStream) -> Unit,
    ): OptionSheet.Page {
        var options: List<Pair<LiveStream, Boolean>>? = null
        act.lifecycleScope.launch {
            val follows = runCatching { Follows.liveNow(act) }.getOrDefault(emptyList())
            val lang = Prefs(act).language.ifEmpty { null }
            val top = runCatching { KickApi.livestreams(language = lang, limit = 16).items }.getOrDefault(emptyList())
            val seen = exclude.map { it.lowercase() }.toHashSet()
            options = (follows.map { it to true } + top.map { it to false })
                .filter { seen.add(it.first.slug.lowercase()) }
                .take(24)
            if (sheet.isShowing) sheet.refresh()
        }
        return OptionSheet.Page(title, subtitle = "Live now • ♥ = following") {
            val list = options
            when {
                list == null -> listOf(OptionSheet.Item("Finding live channels…", shimmer = true) {})
                list.isEmpty() -> listOf(OptionSheet.Item("Couldn't find anyone else live", icon = R.drawable.ic_info) { it.back() })
                else -> list.map { (s, followed) ->
                    OptionSheet.Item(
                        s.username,
                        sub = listOfNotNull(s.category, s.title.takeIf { it.isNotBlank() }).joinToString(" • ").let { if (it.length > 48) it.take(47).trimEnd() + "…" else it },
                        value = Fmt.count(s.viewers),
                        icon = if (followed) R.drawable.ic_heart else R.drawable.ic_play,
                    ) { sh ->
                        sh.dismiss()
                        onPick(s)
                    }
                }
            }
        }
    }
}
