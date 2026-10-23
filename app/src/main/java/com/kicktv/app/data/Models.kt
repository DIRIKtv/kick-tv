package com.kicktv.app.data

/** A live stream as shown on a browse card. */
data class LiveStream(
    val slug: String,
    val username: String,
    val title: String,
    val viewers: Int,
    val thumbnail: String?,
    val avatar: String?,
    val category: String?,
    val language: String?,
    val startTime: String?,
)

data class Category(
    val id: Int,
    val name: String,
    val slug: String,
    val viewers: Int,
    val banner: String?,
)

data class ChannelInfo(
    val id: Int,
    /** The owner's user id (7TV keys channels by it). */
    val userId: Int = 0,
    val slug: String,
    val username: String,
    val bio: String?,
    val avatar: String?,
    val banner: String?,
    val followers: Int,
    val verified: Boolean,
    val chatroomId: Int,
    val playbackUrl: String?,
    val isLive: Boolean,
    val liveTitle: String?,
    val liveViewers: Int,
    val liveCategory: String?,
    val liveStartTime: String?,
    val liveThumbnail: String?,
    /** Channel-specific subscriber badges: months required -> image URL. */
    val subscriberBadges: List<Pair<Int, String>> = emptyList(),
)

data class Video(
    val uuid: String,
    val title: String,
    val source: String,
    val thumbnail: String?,
    val durationMs: Long,
    val views: Int,
    val createdAt: String?,
    val category: String?,
    /** True for the recording of a broadcast that is still live. */
    val isLive: Boolean = false,
)

data class Clip(
    val id: String,
    val title: String,
    val source: String,
    val thumbnail: String?,
    val durationMs: Long,
    val views: Int,
    val createdAt: String?,
    val category: String?,
    val channelSlug: String,
    val channelName: String,
    val channelAvatar: String?,
)

data class SearchChannel(
    val slug: String,
    val username: String,
    val avatar: String?,
    val followers: Int,
    val isLive: Boolean,
    val verified: Boolean,
)

data class Page<T>(val items: List<T>, val next: String?)
