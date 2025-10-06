package net.bunny.api

import android.content.Context
import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import net.bunny.api.api.ManageCollectionsApi
import net.bunny.api.api.ManageVideosApi
import net.bunny.api.ktor.initHttpClient
import net.bunny.api.progress.DefaultProgressRepository
import net.bunny.api.settings.data.DefaultSettingsRepository
import net.bunny.api.settings.domain.model.PlayerSettings
import net.bunny.api.upload.DefaultVideoUploader
import net.bunny.api.upload.service.basic.BasicUploaderService
import net.bunny.api.upload.service.tus.TusUploaderService
import org.openapitools.client.infrastructure.ApiClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class BunnyStreamApi private constructor(
    context: Context,
    accessKey: String?,
    referer: String?,
) : StreamApi {

    companion object {
        private const val TUS_PREFS_FILE = "tusPrefs"

        const val baseApi = BuildConfig.BASE_API

        lateinit var cdnHostname: String
            private set

        var libraryId: Long = -1
            private set

        @Volatile
        private var instance: StreamApi? = null

        fun initialize(context: Context, accessKey: String?, libraryId: Long, referer: String? = null) {
            instance = BunnyStreamApi(
                context.applicationContext,
                accessKey,
                referer,
            )

            this.libraryId = libraryId
            accessKey?.let {
                ApiClient.apiKey["AccessKey"] = it
            }
        }

        fun getInstance(): StreamApi {
            return instance!!
        }

        fun isInitialized(): Boolean {
            return instance != null
        }

        fun release() {
            instance = null
        }
    }

    override val collectionsApi = ManageCollectionsApi(baseApi)

    // OkHttp client that injects Referer for the /play endpoint
    private val okHttpClientWithReferer: OkHttpClient = ApiClient.defaultClient
        .newBuilder()
        .addInterceptor(Interceptor { chain ->
            val originalRequest = chain.request()
            val path = originalRequest.url.encodedPath
            val isPlayEndpoint = path.endsWith("/play")
            val requestBuilder = originalRequest.newBuilder()

            if (isPlayEndpoint) {
                requestBuilder.header("Referer", referer ?: "https://iframe.mediadelivery.net/")
            }

            chain.proceed(requestBuilder.build())
        })
        .build()

    override val videosApi = ManageVideosApi(baseApi, okHttpClientWithReferer)

    private val prefs = context.getSharedPreferences(TUS_PREFS_FILE, Context.MODE_PRIVATE)

    private val ktorClient = initHttpClient(accessKey)

    private val basicUploaderService = BasicUploaderService(
        ktorClient,
        Dispatchers.IO
    )
    override val progressRepository = DefaultProgressRepository(
        httpClient = ktorClient,
        coroutineDispatcher = Dispatchers.IO
    )
    private val tusVideoUploaderService = TusUploaderService(
        preferences = prefs,
        chunkSize = 1024,
        accessKey = accessKey ?: run {
            /**
             * AccessKey is required for TusUploaderService, if not provided fallback to
             * BasicUploaderService which will be used instead.
             */
            throw IllegalStateException("AccessKey must be provided for TusUploaderService")
        },
        dispatcher = Dispatchers.IO
    )

    override val videoUploader = DefaultVideoUploader(
        context = context,
        videoUploadService = basicUploaderService,
        ioDispatcher = Dispatchers.IO,
        videosApi
    )

    override val tusVideoUploader = DefaultVideoUploader(
        context = context,
        videoUploadService = tusVideoUploaderService,
        ioDispatcher = Dispatchers.IO,
        videosApi
    )

    override val settingsRepository = DefaultSettingsRepository(
        httpClient = ktorClient,
        coroutineDispatcher = Dispatchers.IO
    )

    override suspend fun fetchPlayerSettings(libraryId: Long, videoId: String): Either<String, PlayerSettings> {
        return settingsRepository.fetchSettings(libraryId, videoId)
    }

    override suspend fun fetchPlayerSettingsWithToken(libraryId: Long, videoId: String, token: String?, expires: Long?): Either<String, PlayerSettings> {
        return settingsRepository.fetchSettingsWithToken(libraryId, videoId, token, expires)
    }
}
