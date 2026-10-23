package com.diriktv.sidekick.data

/** Turns a GitHub release's markdown notes into the short plain text the TV's update prompt shows. */
object ReleaseNotes {
    private const val MAX_CHARS = 360

    /**
     * Just the "- " items above the install instructions (which follow a "---" line), as "• "
     * bullets without markdown. Notes with no bullets show line by line, minus headings.
     */
    fun forTv(markdown: String): String {
        val lines = markdown.lines().map { it.trim() }.takeWhile { it != "---" }.filter { it.isNotEmpty() }
        val bullets = lines.filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(2) }
        val text = bullets.ifEmpty { lines.filterNot { it.startsWith("#") } }.joinToString("\n") { "• " + plain(it) }
        return if (text.length > MAX_CHARS) text.take(MAX_CHARS - 3) + "…" else text.ifEmpty { "A newer version of Sidekick is ready." }
    }

    /** Strips **bold**, `code` and [links](url) down to their text. */
    private fun plain(s: String) = s
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("**", "")
        .replace("`", "")
}
