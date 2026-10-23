package com.kicktv.app.player

import com.kicktv.app.data.ChatMessage
import com.kicktv.app.data.Prefs

/** Decides which chat messages to show, from the chat settings. */
class ChatFilter(private val prefs: Prefs) {
    companion object {
        /** Bots that don't always carry Kick's bot badge. */
        private val KNOWN_BOTS = setOf(
            "botrix", "botrixoficial", "kickbot", "kicklet", "fossabot", "nightbot",
            "streamelements", "moobot", "wizebot", "sery_bot",
        )
    }

    fun allows(m: ChatMessage): Boolean {
        if (m.isSystem) return true
        if (prefs.chatHideBots && (m.badges.any { it.type == "bot" } || m.username.lowercase() in KNOWN_BOTS)) return false
        if (prefs.chatHideCommands && m.content.trimStart().startsWith("!")) return false
        return true
    }
}
