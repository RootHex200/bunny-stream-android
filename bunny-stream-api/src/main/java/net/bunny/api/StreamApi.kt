package net.bunny.api

import arrow.core.Either
import net.bunny.api.api.ManageCollectionsApi
import net.bunny.api.api.ManageVideosApi
import net.bunny.api.progress.ProgressRepository
import net.bunny.api.settings.domain.SettingsRepository
import net.bunny.api.settings.domain.model.PlayerSettings
import net.bunny.api.upload.VideoUploader

interface StreamApi {
    /**
     * API endpoints for managing video collections
     * @see ManageVideosApi
     */
    val collectionsApi: ManageCollectionsApi

    /**
     * API endpoints for managing videos
     * @see ManageVideosApi
     */
    val videosApi: ManageVideosApi

    /**
     * Component for managing video uploads
     * @see VideoUploader
     */
    val videoUploader: VideoUploader

    /**
     * Component for managing TUS video uploads
     * @see VideoUploader
     */
    val tusVideoUploader: VideoUploader

    val settingsRepository: SettingsRepository

    /**
     * Component for managing progress of video
     * @see ProgressRepository
     */
    val progressRepository: ProgressRepository

    suspend fun fetchPlayerSettings(
        libraryId: Long,
        videoId: String,
        refererValue: String? = null
    ): Either<String, PlayerSettings>
    
    /**
     * Fetches player settings with token-based authentication
     * @param libraryId The video library ID
     * @param videoId The video ID
     * @param token Authentication token for accessing the video
     * @param expires Expiration timestamp for the token
     * @param refererValue Custom referer value for API calls, null to use default
     * @return Either error message or player settings
     */
    suspend fun fetchPlayerSettingsWithToken(
        libraryId: Long,
        videoId: String,
        token: String?,
        expires: Long?,
        refererValue: String? = null
    ): Either<String, PlayerSettings>
}