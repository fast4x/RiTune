package it.fast4x.ritune.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale

data class SyncedLyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsResult(
    val synced: List<SyncedLyricLine>,
    val plainText: String? = null
)

object LyricsResolver {

    private val client = OkHttpClient()

    private val cache = Collections.synchronizedMap(mutableMapOf<String, LyricsResult>())

    private const val LRCLIB_URL =
        "https://lrclib.net/api/get?artist_name=%s&track_name=%s&album_name=%s&duration=%s"

    suspend fun getLyrics(
        artist: String,
        title: String,
        durationSeconds: Float,
        album: String? = null
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanArtist = artist.trim()
        val cleanTitle = cleanTitle(title)
        val cleanAlbum = album.orEmpty().trim()

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) {
            return@withContext null
        }

        val cacheKey = "$cleanArtist|$cleanTitle|$cleanAlbum|${durationSeconds.toInt()}"

        if (cache.containsKey(cacheKey)) {
            return@withContext cache[cacheKey]
        }

        val result = runCatching {
            fetchFromLrcLib(
                artist = cleanArtist,
                title = cleanTitle,
                album = cleanAlbum,
                durationSeconds = durationSeconds
            )
        }.getOrNull()

        if (result != null) {
            cache[cacheKey] = result
        }

        result
    }

    private fun fetchFromLrcLib(
        artist: String,
        title: String,
        album: String,
        durationSeconds: Float
    ): LyricsResult? {
        val url = String.format(
            Locale.US,
            LRCLIB_URL,
            encode(artist),
            encode(title),
            encode(album),
            durationSeconds.coerceAtLeast(0f).toInt()
        )

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RiTune/1.0 Android TV")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null

            val json = JSONObject(body)

            val syncedLyrics = json
                .optString("syncedLyrics")
                .takeIf { it.isNotBlank() }

            val plainLyrics = json
                .optString("plainLyrics")
                .takeIf { it.isNotBlank() }

            if (syncedLyrics == null) {
                return if (plainLyrics != null) {
                    LyricsResult(
                        synced = emptyList(),
                        plainText = plainLyrics
                    )
                } else {
                    null
                }
            }

            val lines = parseLrc(syncedLyrics)

            if (lines.isEmpty()) {
                return LyricsResult(
                    synced = emptyList(),
                    plainText = plainLyrics
                )
            }

            return LyricsResult(
                synced = lines,
                plainText = plainLyrics
            )
        }
    }

    private fun parseLrc(value: String): List<SyncedLyricLine> {
        val result = mutableListOf<SyncedLyricLine>()

        value.lineSequence().forEach { originalLine ->
            val line = originalLine.trim()

            if (line.isBlank()) return@forEach

            val timestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
            val timestamps = timestampRegex
                .findAll(line)
                .map { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = match.groupValues[3]
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            when (it.length) {
                                1 -> it.toLong() * 100L
                                2 -> it.toLong() * 10L
                                else -> it.take(3).toLong()
                            }
                        }
                        ?: 0L

                    minutes * 60_000L + seconds * 1_000L + fraction
                }
                .toList()

            if (timestamps.isEmpty()) return@forEach

            val text = line
                .replace(timestampRegex, "")
                .trim()

            timestamps.forEach { timestamp ->
                result += SyncedLyricLine(
                    timeMs = timestamp,
                    text = text
                )
            }
        }

        return result.sortedBy { it.timeMs }
    }

    private fun cleanTitle(value: String): String {
        return value
            .replace(Regex("""\s*\[[^]]*]"""), "")
            .replace(Regex("""\s*\([^)]*(official|video|audio|lyrics)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(official|lyrics|audio|video)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}