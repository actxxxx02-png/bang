package com.promptcut.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A free, Creative Commons song (from Jamendo, Freesound, Wikimedia… via Openverse). */
data class LibraryTrack(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val durationSec: Double,
    val license: String,
    val attribution: String,
    val genres: List<String>,
) {
    /** Short credit to put in the post/caption. CC BY and CC BY-SA require it. */
    val credit: String get() = "Music: \"$title\" by $artist (${license})"
    val needsCredit: Boolean get() = !license.startsWith("CC0") && !license.startsWith("PDM")
}

/**
 * Searches openly licensed music that may be used in videos you share (commercial use and editing allowed:
 * CC0, CC BY, CC BY-SA). Uses the public Openverse API, no key needed.
 */
object MusicLibrary {
    private const val API = "https://api.openverse.org/v1/audio/"
    private const val UA = "PromptCut/0.8 (Android video editor)"
    private val playable = setOf("mp3", "mp32", "mp31", "ogg", "flac", "wav", "m4a")

    fun defaultQuery(mood: Mood, occasion: String): String {
        val moodWords = when (mood) {
            Mood.HAPPY -> "happy upbeat"
            Mood.ENERGETIC -> "energetic dance"
            Mood.EMOTIONAL -> "emotional piano"
            Mood.CALM -> "calm acoustic"
            Mood.CINEMATIC -> "cinematic epic"
            Mood.FUNNY -> "funny quirky"
        }
        val occ = when (occasion) {
            "birthday", "party", "festival" -> " party"
            "wedding" -> " romantic"
            "travel" -> " travel"
            "kids" -> " kids"
            else -> ""
        }
        return "$moodWords$occ"
    }

    suspend fun search(query: String, minSec: Double): List<LibraryTrack> = withContext(Dispatchers.IO) {
        val base = query.ifBlank { "happy" }.trim()
        val musicQ = if (Regex("music|song|beat|melody|instrumental").containsMatchIn(base.lowercase())) base else "$base music"
        // Openverse's "music" category now holds only non-commercial songs, so combining it with the free-to-use
        // licence filter returns nothing. Search all audio with the licence filter (music is in the words), then
        // fall back to share-alike music as a last resort (credit is added when sharing).
        val attempts = listOf(
            "q=${enc(musicQ)}&license_type=commercial,modification",
            "q=${enc(base)}&license_type=commercial,modification",
            "q=${enc(base.split(' ').first())}%20music&license_type=commercial,modification",
            "q=${enc(base)}&license_type=modification&category=music",
        )
        for (a in attempts) {
            val tracks = runCatching { query(a) }.getOrElse { if (it is ApiException && it.code != 401) throw it; emptyList() }
            // Real songs only: long enough for the video (no looping) and not a short sound effect.
            val good = tracks.filter { it.durationSec == 0.0 || (it.durationSec >= maxOf(minSec, 20.0) && it.durationSec <= 900) }
            if (good.isNotEmpty()) return@withContext good.take(12)
        }
        emptyList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun query(params: String): List<LibraryTrack> {
        // Openverse allows at most 20 results per page without an API key (it answers 401 otherwise).
        val json = try {
            get("$API?$params&page_size=20&mature=false")
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            get("$API?$params&page_size=10&mature=false")
        }
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val type = r.optString("filetype").lowercase()
            if (type.isNotBlank() && type !in playable) return@mapNotNull null
            val durMs = r.optLong("duration", 0)
            val lic = r.optString("license").uppercase().let { l ->
                if (l == "CC0" || l == "PDM") l else "CC ${l} ${r.optString("license_version")}".trim()
            }
            val genres = r.optJSONArray("genres")?.let { g ->
                (0 until g.length()).mapNotNull { idx -> g.optString(idx).takeIf { it.isNotBlank() } }
            }.orEmpty()
            LibraryTrack(
                id = r.optString("id"),
                title = r.optString("title").replace(Regex("\\.(wav|mp3|flac|ogg|aiff?|m4a)$", RegexOption.IGNORE_CASE), "").ifBlank { "Untitled" },
                artist = r.optString("creator").ifBlank { "Unknown artist" },
                url = r.optString("url"),
                durationSec = durMs / 1000.0,
                license = lic,
                attribution = r.optString("attribution"),
                genres = genres,
            ).takeIf { it.url.startsWith("https://", ignoreCase = true) }
        }
    }

    internal fun safeCacheFileName(trackId: String): String {
        val sanitized = trackId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(36).ifBlank { "track" }
        return "$sanitized.audio"
    }

    internal fun validateHttpsUrl(rawUrl: String): URL {
        val u = runCatching { URL(rawUrl) }.getOrElse {
            throw ApiException("Invalid music URL.", 400)
        }
        if (!u.protocol.equals("https", ignoreCase = true) || u.host.isNullOrBlank()) {
            throw ApiException("Only secure HTTPS music URLs are allowed.", 400)
        }
        return u
    }

    suspend fun download(context: Context, t: LibraryTrack): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "songs").apply { mkdirs() }
        val f = File(dir, safeCacheFileName(t.id))
        require(f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Invalid track cache path." }
        if (f.exists() && f.length() > 10_000) return@withContext f
        val conn = open(t.url)
        try {
            if (conn.responseCode !in 200..299) throw ApiException("Couldn't download the song (${conn.responseCode}).", conn.responseCode)
            val max = 25L * 1024 * 1024
            conn.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > max) throw ApiException("Song file is too large.")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            f.delete(); throw if (e is ApiException) e else ApiException("Couldn't download the song: ${e.message}")
        } finally {
            conn.disconnect()
        }
        f
    }

    private fun open(url: String): HttpURLConnection {
        var u = validateHttpsUrl(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("User-Agent", UA)
            }
            if (c.responseCode in 300..399) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                val next = runCatching { URL(u, loc) }.getOrElse {
                    throw ApiException("Invalid music redirect URL.", 400)
                }
                if (!next.protocol.equals("https", ignoreCase = true) || next.host.isNullOrBlank()) {
                    throw ApiException("Blocked insecure music redirect.", 400)
                }
                u = next
            } else return c
        }
        throw ApiException("Too many redirects.")
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code == 429) throw ApiException("The free music search is busy. Try again in a minute.", 429)
            if (code == 401) throw ApiException("The free music search refused the request (401).", 401)
            if (code !in 200..299) throw ApiException("Music search failed ($code).", code)
            return body
        } catch (e: java.io.IOException) {
            throw ApiException("No internet connection for music search.")
        } finally {
            c.disconnect()
        }
    }
}
