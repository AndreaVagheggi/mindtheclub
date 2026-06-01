package com.bolimot.mindtheclub.dataModels

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

data class WebsiteInfo(
    val title: String,
    val description: String,
    val imageUrl: String
)

@Keep
@Serializable
data class TikTokReelResponse(
    val defaultScope: DefaultScope
)
@Keep
@Serializable
data class DefaultScope(
    val videoDetail: WebappReflowVideoDetail? = null
)
@Keep
@Serializable
data class WebappReflowVideoDetail(
    val shareMeta: ShareMeta? = null,
    val itemInfo: ItemInfo?
)
@Keep
@Serializable
data class ItemInfo(
    val itemStruct: ItemStruct? = null
)
@Keep
@Serializable
data class ItemStruct (
    val video: Video? = null
)
@Keep
@Serializable
data class Video (
    val cover: String? = null
)
@Keep
@Serializable
data class ShareMeta(
    val title: String,
    val desc: String,
    val coverUrl: String
)
@Keep
@Serializable
data class YouTubeReelResponse(
    val microformat: Microformat
)
@Keep
@Serializable
data class Microformat(
    val playerMicroformatRenderer: PlayerMicroformatRenderer,
    val lengthSeconds: String? = null,
    val ownerProfileUrl: String? = null,
    val externalChannelId: String? = null,
    val isFamilySafe: Boolean? = null,
    val availableCountries: List<String>? = null,
    val isUnlisted: Boolean? = null,
    val hasYpcMetadata: Boolean? = null,
    val viewCount: String? = null,
    val category: String? = null,
    val publishDate: String? = null,
    val ownerChannelName: String? = null,
    val uploadDate: String? = null,
    val isShortsEligible: Boolean? = null
)
@Keep
@Serializable
data class PlayerMicroformatRenderer(
    val thumbnail: Thumbnail? = null,
    val embed: Embed,
    val title: Title? = null,
    val description: Description? = null
)
@Keep
@Serializable
data class Thumbnail(
    val thumbnails: List<ThumbnailItem>,
    val isOriginalAspectRatio: Boolean
)
@Keep
@Serializable
data class ThumbnailItem(
    val url: String,
    val width: Int,
    val height: Int
)
@Keep
@Serializable
data class Embed(
    val iframeUrl: String,
    val width: Int,
    val height: Int
)
@Keep
@Serializable
data class Title(
    val runs: List<TextRun>
)
@Keep
@Serializable
data class Description(
    val runs: List<TextRun>
)
@Keep
@Serializable
data class TextRun(
    val text: String
)


