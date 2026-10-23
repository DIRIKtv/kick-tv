package com.kicktv.app.player

import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.kicktv.app.R
import com.kicktv.app.ui.LiveAlerts
import com.kicktv.app.ui.Nav
import com.kicktv.app.ui.OptionSheet
import com.kicktv.app.ui.setVisible
import java.util.Locale

/** The player's settings sheet (☰) and the track choices behind it. */
internal class PlayerMenu(private val act: PlayerActivity) {
    private val player get() = act.player
    private val prefs = act.prefs
    private val sheet by lazy { OptionSheet(act).apply { onDismiss = { if (act.controls.up) act.controls.scheduleHide() } } }

    val isShowing get() = sheet.isShowing

    fun show(start: OptionSheet.Page? = null) {
        act.controls.cancelHide()
        sheet.show(settingsPage())
        if (start != null) sheet.push(start)
    }

    fun dismiss() = sheet.dismiss()

    // region tracks

    data class TrackChoice(val group: Tracks.Group, val index: Int, val format: Format)

    fun videoTracks(): List<TrackChoice> = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { TrackChoice(g, it, g.getTrackFormat(it)) } }

    fun textTracks(): List<TrackChoice> = player.currentTracks.groups
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
    fun applyQualityPreference() {
        if (act.qualityHeight <= 0) return
        val tracks = videoTracks()
        if (tracks.isEmpty() || isQualityOverridden()) return
        val pick = tracks.filter { it.format.height <= act.qualityHeight }
            .maxWithOrNull(compareBy<TrackChoice>({ it.format.height }, { it.format.frameRate }, { it.format.bitrate }))
            ?: tracks.minByOrNull { it.format.height }
            ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(pick.group.mediaTrackGroup, pick.index))
            .build()
    }

    fun currentCaptionLabel(): String {
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return "Off"
        val f = textTracks().firstOrNull { it.group.isTrackSelected(it.index) }?.format
        return f?.label ?: f?.language ?: "On"
    }

    // endregion

    // region pages

    private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private fun speedLabel(s: Float) = if (s == 1f) "Normal" else "${s}x".replace(".0x", "x")

    private fun settingsPage() = OptionSheet.Page("Settings", eyebrow = act.channel?.username ?: "Player") {
        buildList {
            LiveAlerts.recent(exclude = act.slug)?.let { s ->
                add(OptionSheet.Item("Watch ${s.username}", sub = "Just went live • ${s.title}".take(70), icon = R.drawable.ic_skip_live) {
                    it.dismiss()
                    Nav.watchLive(act, s.slug)
                })
            }
            add(OptionSheet.Item("Quality", value = currentQualityLabel(), icon = R.drawable.ic_hd, submenu = true) { it.push(qualityPage()) })
            if (act.mode != PlayerActivity.Mode.LIVE) {
                add(OptionSheet.Item("Playback speed", value = speedLabel(act.speed), icon = R.drawable.ic_speed, submenu = true) { it.push(speedPage()) })
            }
            if (textTracks().isNotEmpty()) {
                add(OptionSheet.Item("Captions", value = currentCaptionLabel(), icon = R.drawable.ic_cc, submenu = true) { it.push(captionsPage()) })
            }
            if (act.mode != PlayerActivity.Mode.VOD) {
                add(OptionSheet.Item("Chat", value = if (!act.chat.wanted) "Hidden" else if (prefs.chatMode == "side") "Beside video" else "Over video",
                    icon = R.drawable.ic_chat, submenu = true) { it.push(chatPage()) })
            }
            add(OptionSheet.Item("Loudness equalization", sub = "Evens out quiet and loud streams",
                value = if (act.leveler.enabled) "On" else "Off", icon = R.drawable.ic_volume) {
                prefs.loudnessEq = !prefs.loudnessEq
                act.leveler.enabled = prefs.loudnessEq
                act.controls.flashOsd(if (prefs.loudnessEq) "Loudness equalization on" else "Loudness equalization off", R.drawable.ic_volume)
                it.refresh()
            })
            add(OptionSheet.Item("Sleep timer", value = act.sleepTimer.label(), icon = R.drawable.ic_timer, submenu = true) { it.push(sleepPage()) })
            if (act.mode != PlayerActivity.Mode.VOD) {
                add(OptionSheet.Item("Multi-view", sub = "Watch another live channel alongside", icon = R.drawable.ic_multiview, submenu = true) {
                    it.push(StreamPicker.page(act, it, "Add a stream", exclude = setOf(act.slug)) { picked ->
                        MultiViewActivity.start(act, listOf(act.slug, picked.slug))
                        act.finish()
                    })
                })
            }
            if (act.canPip) {
                add(OptionSheet.Item("Picture-in-picture", sub = "Keep watching in a corner while you browse", icon = R.drawable.ic_pip) {
                    it.dismiss()
                    act.enterPip()
                })
            }
            add(OptionSheet.Item("Stats for nerds", value = if (act.stats.visibility == View.VISIBLE) "On" else "Off", icon = R.drawable.ic_info) {
                act.stats.setVisible(act.stats.visibility != View.VISIBLE)
                it.refresh()
            })
            add(OptionSheet.Item("Go to channel", sub = "Past broadcasts, clips & info", icon = R.drawable.ic_person) {
                it.dismiss()
                Nav.channel(act, act.slug)
                act.finish()
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
        act.qualityHeight = t?.format?.height ?: 0
        if (t != null) builder.setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
        player.trackSelectionParameters = builder.build()
        prefs.preferredQuality = act.qualityHeight
        act.controls.flashOsd("Quality: ${t?.let { qualityLabel(it.format) } ?: "Auto"}", R.drawable.ic_hd)
    }

    private fun speedPage() = OptionSheet.Page("Playback speed") {
        speeds.map { s ->
            OptionSheet.Item(speedLabel(s), checked = s == act.speed) {
                act.setSpeed(s); it.dismiss()
                act.controls.flashOsd("Speed: ${speedLabel(s)}", R.drawable.ic_speed)
            }
        }
    }

    fun captionsPage() = OptionSheet.Page("Captions", subtitle = "Auto-generated by Kick") {
        val tracks = textTracks()
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        listOf(OptionSheet.Item("Off", checked = disabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            act.controls.updateCaptionButton(); it.dismiss()
        }) + tracks.map { t ->
            OptionSheet.Item(t.format.label ?: t.format.language ?: "Track ${t.index + 1}", checked = !disabled && t.group.isTrackSelected(t.index)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                    .build()
                act.controls.updateCaptionButton()
                it.dismiss()
            }
        }
    }

    private fun chatPage() = OptionSheet.Page("Chat") {
        listOf(
            OptionSheet.Item("Show chat", value = if (act.chat.wanted) "On" else "Off") {
                act.chat.wanted = !act.chat.wanted; prefs.chatEnabled = act.chat.wanted; act.chat.applyLayout(animate = true); it.refresh()
            },
            OptionSheet.Item("Beside the video", sub = "Video shrinks to make room", checked = prefs.chatMode == "side") {
                prefs.chatMode = "side"; act.chat.applyLayout(animate = true); it.refresh()
            },
            OptionSheet.Item("Over the video", sub = "See-through panel on top", checked = prefs.chatMode != "side") {
                prefs.chatMode = "overlay"; act.chat.applyLayout(animate = true); it.refresh()
            },
            OptionSheet.Item("Filters", value = filterSummary(), submenu = true) { it.push(filtersPage()) },
            OptionSheet.Item("Text size", value = "${prefs.chatTextSize}sp", submenu = true) { sheet ->
                sheet.push(OptionSheet.Page("Chat text size") {
                    listOf(13, 15, 17, 20).map { size ->
                        OptionSheet.Item(listOf("Small", "Medium", "Large", "Extra large")[listOf(13, 15, 17, 20).indexOf(size)],
                            value = "${size}sp", checked = prefs.chatTextSize == size) { s ->
                            prefs.chatTextSize = size
                            act.chat.setTextSize(size)
                            s.back()
                        }
                    }
                })
            },
        )
    }

    private fun filterSummary(): String {
        val on = listOf(prefs.chatHideBots, prefs.chatHideCommands, prefs.chatCalm).count { it }
        return if (on == 0) "Off" else "$on on"
    }

    private fun filtersPage() = OptionSheet.Page("Chat filters", subtitle = "Applies to new messages.") {
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        listOf(
            OptionSheet.Item("Hide bot messages", sub = "Botrix, Kickbot and anyone with a bot badge", value = onOff(prefs.chatHideBots)) {
                prefs.chatHideBots = !prefs.chatHideBots; it.refresh()
            },
            OptionSheet.Item("Hide !commands", sub = "Messages that start with !", value = onOff(prefs.chatHideCommands)) {
                prefs.chatHideCommands = !prefs.chatHideCommands; it.refresh()
            },
            OptionSheet.Item("Calm busy chats", sub = "Steady pace; skips some messages when chat floods", value = onOff(prefs.chatCalm)) {
                prefs.chatCalm = !prefs.chatCalm; it.refresh()
            },
            OptionSheet.Item("7TV emotes", sub = "Show the channel's 7TV emotes as images", value = onOff(prefs.sevenTvEmotes)) {
                prefs.sevenTvEmotes = !prefs.sevenTvEmotes
                act.channel?.let { c -> act.chat.loadEmotes(c, force = true) }
                it.refresh()
            },
        )
    }

    private fun sleepPage() = OptionSheet.Page("Sleep timer", subtitle = "Playback stops and the TV can go to sleep.") {
        val timer = act.sleepTimer
        buildList {
            add(OptionSheet.Item("Off", checked = !timer.active) { timer.cancel(); act.controls.updateMeta(); it.back() })
            for (m in listOf(15, 30, 45, 60, 90, 120)) {
                val label = if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "${m / 60.0} hours".replace(".0 ", " ")
                add(OptionSheet.Item(label) {
                    timer.start(m)
                    act.controls.updateMeta()
                    act.controls.flashOsd("Sleep timer: $label", R.drawable.ic_timer)
                    it.dismiss()
                })
            }
            if (act.mode == PlayerActivity.Mode.VOD) {
                add(OptionSheet.Item("End of this video", checked = timer.endOfVideo) {
                    timer.startEndOfVideo()
                    act.controls.updateMeta()
                    act.controls.flashOsd("Stopping at the end of this video", R.drawable.ic_timer)
                    it.dismiss()
                })
            }
        }
    }

    // endregion
}
