package net.bunny.api.settings.domain

import arrow.core.Either
import net.bunny.api.settings.domain.model.PlayerSettings

interface SettingsRepository {
    suspend fun fetchSettings(libraryId: Long, videoId: String): Either<String, PlayerSettings>
    
    /**
     * Fetches player settings with token-based authentication
     * @param libraryId The video library ID
     * @param videoId The video ID
     * @param token Authentication token for accessing the video
     * @param expires Expiration timestamp for the token
     * @return Either error message or player settings
     */
    suspend fun fetchSettingsWithToken(libraryId: Long, videoId: String, token: String?, expires: Long?): Either<String, PlayerSettings>
}