package net.bunny.api.progress

import android.util.Log
import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.bunny.api.BunnyStreamApi
import net.bunny.api.ktor.defaultJson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-side playback progress.
 *
 * `/library/{id}/videos/{guid}/progress` is only present on libraries that
 * have it enabled, and it is an authenticated route: without an AccessKey, and
 * on a library that does not expose it, Bunny answers 404. The player calls
 * [saveProgress] on a timer, so a repository that treated that 404 as success
 * produced a POST plus a stack of Ktor logs every save interval, forever, and
 * still reported progress as saved.
 *
 * Two things follow from that:
 *
 *  - Every call reports the real HTTP status. Ktor does not throw on 4xx
 *    unless `expectSuccess` is set, so a status check is the only thing that
 *    makes a failure visible.
 *  - A 404 latches [unsupported] for the process. The route will not appear
 *    mid-session, and retrying it every 30 seconds buys nothing but noise.
 */
class DefaultProgressRepository(
    private val httpClient: HttpClient,
    private val coroutineDispatcher: CoroutineDispatcher,
    private val accessKey: String? = null,
) : ProgressRepository {

    private val unsupported = AtomicBoolean(false)

    override suspend fun saveProgress(libraryId: Long, videoId: String, position: Long): Either<String, Unit> =
        withContext(coroutineDispatcher) {
            precondition()?.let { return@withContext Either.Left(it) }

            try {
                val response = httpClient.post(endpoint(libraryId, videoId)) {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("position" to position))
                }
                response.failureOrNull()?.let { return@withContext Either.Left(it) }
                Either.Right(Unit)
            } catch (e: Exception) {
                Either.Left("Failed to save progress: ${e.message}")
            }
        }

    override suspend fun getProgress(libraryId: Long, videoId: String): Either<String, Long> =
        withContext(coroutineDispatcher) {
            precondition()?.let { return@withContext Either.Left(it) }

            try {
                val response = httpClient.get(endpoint(libraryId, videoId))
                response.failureOrNull()?.let { return@withContext Either.Left(it) }

                // The route is optional and its payload has varied, so read the
                // one field that matters instead of binding a whole model that
                // would fail the request over an unexpected shape.
                val body = response.bodyAsText()
                val position = runCatching {
                    defaultJson.parseToJsonElement(body)
                        .jsonObject["position"]
                        ?.jsonPrimitive
                        ?.longOrNull
                }.getOrNull()

                if (position == null) {
                    Either.Left("Progress response had no usable position: $body")
                } else {
                    Either.Right(position)
                }
            } catch (e: Exception) {
                Either.Left("Failed to get progress: ${e.message}")
            }
        }

    override suspend fun clearProgress(libraryId: Long, videoId: String): Either<String, Unit> =
        withContext(coroutineDispatcher) {
            precondition()?.let { return@withContext Either.Left(it) }

            try {
                val response = httpClient.delete(endpoint(libraryId, videoId))
                response.failureOrNull()?.let { return@withContext Either.Left(it) }
                Either.Right(Unit)
            } catch (e: Exception) {
                Either.Left("Failed to clear progress: ${e.message}")
            }
        }

    private fun endpoint(libraryId: Long, videoId: String) =
        "${BunnyStreamApi.baseApi}/library/$libraryId/videos/$videoId/progress"

    /**
     * Why this call should not be attempted, or null to go ahead. Keeps the
     * three methods from each repeating the same two guards.
     */
    private fun precondition(): String? = when {
        unsupported.get() ->
            "Progress endpoint is not available on this library"
        accessKey.isNullOrBlank() ->
            "Progress requires an AccessKey; BunnyStreamApi was initialized without one"
        else -> null
    }

    /**
     * The failure message for a non-2xx response, or null when it succeeded.
     * A 404 also latches the endpoint off for the rest of the process.
     */
    private fun HttpResponse.failureOrNull(): String? {
        if (status.isSuccess()) return null

        if (status == HttpStatusCode.NotFound && unsupported.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "Progress endpoint returned 404 — the library does not expose it. " +
                    "Skipping further progress calls this session.",
            )
        }
        return "Progress request failed: ${status.value}"
    }

    private companion object {
        const val TAG = "BunnyProgress"
    }
}
