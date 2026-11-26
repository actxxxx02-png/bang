package com.promptcut.app

import android.util.Base64
import com.promptcut.app.security.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A frame we show the model: label like "clip 0 at 3.2 s" and JPEG bytes. */
class Frame(val label: String, val jpeg: ByteArray)

/** [body] is the raw error reply (Groq puts the model's failed JSON attempt in it). */
class ApiException(message: String, val code: Int = 0, val body: String = "") : Exception(message)

/** The free LLM services the app supports. Users sign in there and create their own free key. */
enum class Provider(
    val displayName: String,
    val keyPageUrl: String,
    val tagline: String,
    val steps: List<String>,
    val seesVideo: Boolean,
) {
    GEMINI(
        displayName = "Google Gemini (free)",
        keyPageUrl = "https://aistudio.google.com/apikey",
        tagline = "Also looks at frames from your clips. Tip: save a Groq key too, so speech in your videos is understood.",
        steps = listOf(
            "Tap \"Get free key\". Google AI Studio opens.",
            "Sign in with any Google account and accept the terms.",
            "Tap \"Create API key\" and copy it.",
            "Come back here and tap \"Paste\".",
        ),
        seesVideo = true,
    ),
    GROQ(
        displayName = "Groq (free)",
        keyPageUrl = "https://console.groq.com/keys",
        tagline = "Very fast open models. Your phone describes the clips to it (people, smiles, sounds), and it also understands speech.",
        steps = listOf(
            "Tap \"Get free key\". The Groq console opens.",
            "Sign up with Google, GitHub or email.",
            "Tap \"Create API Key\", give it any name, and copy it.",
            "Come back here and tap \"Paste\".",
        ),
        seesVideo = false,
    );
}

interface LlmClient {
    /** Checks the key and returns the model the app will use. */
    suspend fun pickModel(): String
    suspend fun complete(system: String, user: String, frames: List<Frame>): String

    /** The model that answered last (after any fallback), so the app can remember it. */
    val activeModel: String?
}

/**
 * Free tiers often answer 503 ("overloaded") or 429 (per-model quota), especially for the newest model.
 * Try the current model twice, then fall back through other models from the live list (max 4 models).
 */
internal suspend fun tryModels(
    first: String?,
    ranked: suspend () -> List<String>,
    providerName: String,
    call: suspend (String) -> String,
): Pair<String, String> {
    val candidates = LinkedHashSet<String>()
    first?.let { candidates += it }
    var listed = false
    var lastErr: ApiException? = null
    val tried = mutableListOf<String>()
    var i = 0
    while (tried.size < 4) {
        if (i >= candidates.size) {
            if (listed) break
            listed = true
            try { candidates += ranked() } catch (e: ApiException) { if (lastErr == null) throw e }
            if (i >= candidates.size) break
        }
        val m = candidates.elementAt(i++)
        tried += m
        for (attempt in 0..1) {
            try {
                return m to call(m)
            } catch (e: ApiException) {
                lastErr = e
                val switchModel = e.code == 429 || e.code == 404 || e.code == 422 || e.code >= 500
                if (!switchModel) throw e              // bad key, bad request, no network: don't hammer
                if (e.code == 404) break               // model gone: go straight to the next one
                if (attempt == 0) delay(2500)
            }
        }
    }
    val e = lastErr ?: throw ApiException("No $providerName model is available for this key.")
    throw ApiException(
        if (e.code == 429) "Free limit reached on $providerName (tried ${tried.joinToString()}). Wait a minute, or switch service in Settings."
        else "$providerName's free servers are busy right now (tried ${tried.joinToString()}). Try again in a minute, or switch to the other service in Settings.",
        e.code,
    )
}

fun clientFor(provider: Provider, key: String, model: String?): LlmClient = when (provider) {
    Provider.GEMINI -> GeminiClient(key, model)
    Provider.GROQ -> GroqClient(key, model)
}

// ---------------------------------------------------------------- Gemini

class GeminiClient(private val key: String, private var model: String?) : LlmClient {
    private val base = "https://generativelanguage.googleapis.com/v1beta"
    override val activeModel: String? get() = model

    private suspend fun ranked(): List<String> {
        val collected = LinkedHashSet<String>()
        val seenTokens = HashSet<String>()
        var pageToken: String? = null
        var pages = 0
        while (pages < 10) {
            pages++
            val url = buildString {
                append("$base/models?pageSize=200")
                if (!pageToken.isNullOrBlank()) {
                    append("&pageToken=")
                    append(URLEncoder.encode(pageToken, "UTF-8"))
                }
            }
            val json = http("GET", url, mapOf("x-goog-api-key" to key), null)
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw ApiException("Gemini returned malformed model list JSON.", 422)
            }
            val models = root.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var supportsGenerate = false
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") {
                        supportsGenerate = true
                        break
                    }
                }
                if (supportsGenerate) {
                    val clean = m.optString("name", "").removePrefix("models/").trim()
                    if (clean.isNotEmpty()) collected += clean
                }
            }
            val next = root.optString("nextPageToken", "").trim()
            if (next.isEmpty() || !seenTokens.add(next)) break
            pageToken = next
        }
        return ModelPicker.geminiRanked(collected.toList())
    }

    override suspend fun pickModel(): String = ranked().first().also { model = it }

    override suspend fun complete(system: String, user: String, frames: List<Frame>): String {
        val parts = JSONArray()
        if (user.isNotBlank()) parts.put(JSONObject().put("text", user))
        frames.forEach { f ->
            if (f.label.isNotBlank()) parts.put(JSONObject().put("text", f.label))
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(f.jpeg, Base64.NO_WRAP)),
                )
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val bodyText = body.toString()
        val blockingFinishReasons = setOf(
            "SAFETY",
            "RECITATION",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
            "SPII",
            "IMAGE_SAFETY",
            "MALFORMED_FUNCTION_CALL",
        )
        val (used, text) = tryModels(model, ::ranked, "Gemini") { m ->
            val json = http("POST", "$base/models/$m:generateContent", mapOf("x-goog-api-key" to key), bodyText)
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw ApiException("Gemini returned malformed JSON.", 422)
            }
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason", "")?.trim().orEmpty()
            if (blockReason.isNotEmpty()) {
                throw ApiException("Gemini blocked the prompt ($blockReason). Try rephrasing.", 422)
            }
            val cand = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw ApiException("Gemini returned no answer (it may have been blocked). Try rephrasing.", 422)
            val finishReason = cand.optString("finishReason", "").trim()
            if (finishReason in blockingFinishReasons) {
                throw ApiException("Gemini stopped due to $finishReason. Try rephrasing.", 422)
            }
            val outParts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val joined = (0 until outParts.length()).joinToString("") { outParts.optJSONObject(it)?.optString("text").orEmpty() }
            if (joined.isBlank()) throw ApiException("Gemini returned an empty response.", 422)
            joined
        }
        model = used
        return text
    }
}

// ---------------------------------------------------------------- Groq (OpenAI-compatible)

class GroqClient(private val key: String, private var model: String?) : LlmClient {
    private val base = "https://api.groq.com/openai/v1"
    private val auth get() = mapOf("Authorization" to "Bearer $key")
    override val activeModel: String? get() = model

    private suspend fun ranked(): List<String> {
        val json = http("GET", "$base/models", auth, null)
        val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
        return ModelPicker.groqRanked((0 until data.length()).mapNotNull {
            data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
        })
    }

    override suspend fun pickModel(): String = ranked().first().also { model = it }

    override suspend fun complete(system: String, user: String, frames: List<Frame>): String {
        val (used, text) = tryModels(model, ::ranked, "Groq") { m -> chat(m, system, user) }
        model = used
        return text
    }

    /**
     * Strict JSON mode first. If Groq rejects the model's output ("Failed to validate JSON"), repair the attempt
     * it sends back; otherwise ask again without strict mode; if that also fails, [tryModels] moves to another model.
     */
    private suspend fun chat(m: String, system: String, user: String): String {
        try {
            return request(m, system, user, strict = true)
        } catch (e: ApiException) {
            if (e.code != 400 || "json_validate_failed" !in e.body && "Failed to validate JSON" !in e.body) throw e
            JsonRepair.failedGeneration(e.body)?.let { attempt -> JsonRepair.repairObject(attempt)?.let { return it } }
        }
        val plain = try {
            request(m, system, user + "\n\nReply with ONE valid JSON object only. No comments, no trailing commas, no text outside it.", strict = false)
        } catch (e: ApiException) {
            if (e.code == 400) throw ApiException("Model $m couldn't produce valid JSON.", 422) else throw e
        }
        return JsonRepair.repairObject(plain) ?: throw ApiException("Model $m couldn't produce valid JSON.", 422)
    }

    private suspend fun request(m: String, system: String, user: String, strict: Boolean): String {
        val body = JSONObject()
            .put("model", m)
            .put("temperature", 0.3)
            .put("max_completion_tokens", 8192)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (strict) body.put("response_format", JSONObject().put("type", "json_object"))
        // Reasoning models spend tokens thinking; keep that short so the answer isn't cut off.
        if ("gpt-oss" in m) body.put("reasoning_effort", "low")
        val json = http("POST", "$base/chat/completions", auth, body.toString())
        val choice = JSONObject(json).optJSONArray("choices")?.optJSONObject(0)
            ?: throw ApiException("Groq returned no choices.", 422)
        return choice.optJSONObject("message")?.optString("content").orEmpty()
    }
}

/** Model names change often, so we pick from the live list instead of hard-coding one. */
object ModelPicker {
    private val skip = listOf("tts", "image", "live", "audio", "embedding", "aqa", "vision", "robotics", "computer-use", "native")

    /**
     * Best first. Stable models beat previews (previews are the ones most often overloaded on free keys),
     * then newer versions, then full Flash before Flash-Lite. Lite models stay in the list as fallbacks.
     */
    fun geminiRanked(names: List<String>): List<String> {
        val flash = names.filter { n -> "flash" in n && "gemini" in n && skip.none { it in n } }
        if (flash.isEmpty()) return names.filter { "gemini" in it }.ifEmpty { listOf("gemini-2.5-flash") }
        return flash.sortedWith(
            compareByDescending<String> { "preview" !in it && "exp" !in it }
                .thenByDescending { version(it) }
                .thenByDescending { "lite" !in it }
                .thenByDescending { "latest" !in it }
        )
    }

    fun gemini(names: List<String>): String = geminiRanked(names).first()

    fun groqRanked(ids: List<String>): List<String> {
        val usable = ids.filter { id -> listOf("whisper", "guard", "tts", "playai", "orpheus", "prompt-guard", "compound").none { it in id } }
        val preferred = listOf("gpt-oss-120b", "llama-3.3-70b", "qwen3", "kimi", "llama-4", "gpt-oss-20b", "llama-3.1-8b")
        val ordered = preferred.flatMap { p -> usable.filter { p in it } } + usable
        return ordered.distinct().ifEmpty { listOf("llama-3.3-70b-versatile") }
    }

    fun groq(ids: List<String>): String = groqRanked(ids).first()

    private fun version(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}

// ---------------------------------------------------------------- HTTP

private suspend fun http(method: String, url: String, headers: Map<String, String>, body: String?): String =
    withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val rawText = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val safeText = KeyVault.redactSecrets(rawText)
                throw ApiException(friendlyError(code, safeText), code, safeText)
            }
            rawText
        } catch (e: IOException) {
            throw ApiException("Network problem: ${KeyVault.redactSecrets(e.message ?: "no connection")}. Check your internet and try again.")
        } finally {
            conn.disconnect()
        }
    }

private fun friendlyError(code: Int, body: String): String {
    val detail = KeyVault.redactSecrets(
        runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: o.optString("message")
        }.getOrNull().orEmpty().take(200)
    )
    return when (code) {
        400 -> if ("API key" in detail || "API_KEY" in detail) "This API key isn't valid. Open Settings and paste it again." else "The AI service rejected the request: $detail"
        401, 403 -> "This API key isn't valid or has no access. Open Settings and paste it again."
        404 -> "The chosen model isn't available for this key. Open Settings and tap \"Check key\" to pick another."
        429 -> "Free limit reached for now. Wait a minute and try again (free keys allow a limited number of requests per minute/day)."
        in 500..599 -> "The AI service is busy ($code). Try again in a moment."
        else -> "Error $code: $detail"
    }
}

// ---------------------------------------------------------------- Speech to text (Groq Whisper, free tier)

object SpeechToText {
    private val models = listOf("whisper-large-v3-turbo", "whisper-large-v3")

    /** Returns timed sentences. Uses the user's free Groq key. */
    suspend fun groq(key: String, file: java.io.File): List<Span> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (m in models) {
            try {
                val json = multipart(
                    "https://api.groq.com/openai/v1/audio/transcriptions",
                    mapOf("Authorization" to "Bearer $key"),
                    mapOf("model" to m, "response_format" to "verbose_json", "temperature" to "0"),
                    file,
                )
                val segs = JSONObject(json).optJSONArray("segments") ?: JSONArray()
                return@withContext (0 until segs.length()).map {
                    val s = segs.getJSONObject(it)
                    Span(s.optDouble("start"), s.optDouble("end"), s.optString("text").trim())
                }
            } catch (e: ApiException) {
                last = e
                if (e.code != 404 && e.code != 400) throw e
            }
        }
        throw last ?: ApiException("Speech recognition unavailable.")
    }

    private fun multipart(url: String, headers: Map<String, String>, fields: Map<String, String>, file: java.io.File): String {
        val boundary = "----PromptCut${System.nanoTime()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { os ->
                fun w(s: String) = os.write(s.toByteArray())
                fields.forEach { (k, v) -> w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
                w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n")
                file.inputStream().use { it.copyTo(os) }
                w("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val safeBody = KeyVault.redactSecrets(text)
                throw ApiException("Speech recognition error $code", code, safeBody)
            }
            return text
        } catch (e: IOException) {
            throw ApiException("Network problem during speech recognition.")
        } finally {
            conn.disconnect()
        }
    }
}
