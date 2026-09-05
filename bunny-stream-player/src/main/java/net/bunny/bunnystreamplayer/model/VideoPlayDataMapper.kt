package net.bunny.bunnystreamplayer.model

import org.openapitools.client.models.VideoModel
import org.openapitools.client.models.VideoPlayDataModelVideo

/**
 * Maps Bunny's play-config video payload onto the player's model.
 *
 * Top-level rather than a member of the player because the offline download
 * path needs the same mapping: a download captures this payload so playback
 * can be replayed with no network.
 */
internal fun VideoPlayDataModelVideo.toVideoModel(): VideoModel = VideoModel(
    videoLibraryId = this.videoLibraryId,
    guid = this.guid,
    title = this.title,
    dateUploaded = this.dateUploaded,
    views = this.views,
    isPublic = this.isPublic,
    length = this.length,
    status = this.status,
    framerate = this.framerate,
    rotation = this.rotation,
    width = this.width,
    height = this.height,
    availableResolutions = this.availableResolutions,
    outputCodecs = this.outputCodecs,
    thumbnailCount = this.thumbnailCount,
    encodeProgress = this.encodeProgress,
    storageSize = this.storageSize,
    captions = this.captions,
    hasMP4Fallback = this.hasMP4Fallback,
    collectionId = this.collectionId,
    thumbnailFileName = this.thumbnailFileName,
    averageWatchTime = this.averageWatchTime,
    totalWatchTime = this.totalWatchTime,
    category = this.category,
    chapters = this.chapters,
    moments = this.moments,
    metaTags = this.metaTags,
    transcodingMessages = this.transcodingMessages
)

