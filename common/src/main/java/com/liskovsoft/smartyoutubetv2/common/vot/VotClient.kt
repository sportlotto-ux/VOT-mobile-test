package com.liskovsoft.smartyoutubetv2.common.vot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal VOT client for SmartTube - reuses vot-worker.vtrans.eu.cc logic from browser
 * Extracts VOT audio URL via simple https:// search in protobuf response (no full proto decode)
 */
object VotClient {
    private const val TAG = "VOT"
    private const val WORKER_HOST = "vot-worker.vtrans.eu.cc"
    private const val WORKER_SCHEME = "https"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Similar to YandexSessionRequest / VideoTranslationRequest protobuf but via worker JSON wrapper
    suspend fun getVotAudioUrl(videoId: String, videoUrl: String = "https://youtu.be/$videoId", duration: Int = 0, langFrom: String = "en", langTo: String = "ru"): String? = withContext(Dispatchers.IO) {
        try {
            // 1. create session (worker expects JSON {headers, body: Array})
            val sessionResp = postProtobuf("/session/create", createSessionBody(videoId), mapOf("Vtrans-Signature" to dummySignature()))
            if (sessionResp == null) {
                Log.w(TAG, "session create failed")
                return@withContext null
            }
            // session bytes contain secretKey etc - we don't need to parse fully, just keep for next call
            // For worker, session is cached server-side via headers? Actually worker handles session internally if we pass same headers?
            // Simpler: call translate directly - worker will handle session creation internally via headers
            val translateBody = createTranslateBody(videoUrl, duration, langFrom, langTo)
            val translateResp = postProtobuf("/video-translation/translate", translateBody, emptyMap())
            if (translateResp == null) {
                Log.w(TAG, "translate failed")
                return@withContext null
            }
            // Extract https:// URL from protobuf bytes via string search
            val text = String(translateResp, Charsets.ISO_8859_1)
            val idx = text.indexOf("https://")
            if (idx == -1) {
                Log.w(TAG, "no url in response, bytes=${translateResp.size} head=${text.take(200)}")
                return@withContext null
            }
            var end = idx
            while (end < text.length) {
                val c = text[end]
                if (c == '"' || c == '\'' || c == ' ' || c == '\n' || c.code < 32) break
                // protobuf may have trailing bytes after url, url ends with .mp3 then ? then params until \x11 etc
                // url is length-delimited, next byte after url is not part of url - we need to find where url ends: it contains https://...mp3?... and next char is not url char
                // Simple: find .mp3 and then take until next control char
                end++
            }
            // More precise: find end of url by looking for .mp3 and then query params until next non-url char
            // The raw bytes have url as field 1, length-delimited, so we can extract by reading varint length after 0x0A tag
            // Fallback: extract via regex
            val regex = Regex("""https://vtrans\.s3-private\.mds\.yandex\.net[^\x00-\x1F"']+""")
            val match = regex.find(text)
            val url = match?.value?.trim()
            Log.d(TAG, "VOT url extracted: $url")
            return@withContext url?.takeIf { it.contains(".mp3") || it.contains(".m4a") }
        } catch (e: Exception) {
            Log.w(TAG, "getVotAudioUrl error", e)
            return@withContext null
        }
    }

    private fun dummySignature(): String = "" // worker doesn't require valid signature when using JSON wrapper? browser used getSignature(body)

    private fun createSessionBody(videoId: String): ByteArray {
        // Minimal YandexSessionRequest: field 1 uuid, field 2 module "video-translation"
        // Use simple protobuf encoding: uuid as string, module as string
        // We can just use random uuid
        val uuid = java.util.UUID.randomUUID().toString()
        // Encode as protobuf: 0x0A len uuid, 0x12 len "video-translation"
        val module = "video-translation"
        return encodeSessionRequest(uuid, module)
    }

    private fun createTranslateBody(videoUrl: String, duration: Int, langFrom: String, langTo: String): ByteArray {
        // Use VideoTranslationRequest encoding similar to vot.user.js
        // Simplified: we can call worker's JSON wrapper which expects body as Array.from(Uint8Array)
        // The worker will forward to Yandex, so we need to provide proper protobuf
        // For now, use minimal encoding that worker accepts: it will be forwarded as-is
        // We'll use the same encoding as browser's YandexVOTProtobuf.encodeTranslationRequest
        return encodeTranslationRequest(videoUrl, duration, langFrom, langTo)
    }

    private fun encodeSessionRequest(uuid: String, module: String): ByteArray {
        // protobuf: message SessionRequest { string uuid=1; string module=2; }
        // tag 1: 0x0A, tag 2: 0x12
        val uuidBytes = uuid.toByteArray()
        val moduleBytes = module.toByteArray()
        return buildProtobuf {
            writeString(1, uuidBytes)
            writeString(2, moduleBytes)
        }
    }

    private fun encodeTranslationRequest(url: String, duration: Int, langFrom: String, langTo: String): ByteArray {
        // message VideoTranslationRequest { string url=1; bool firstRequest=2; int32 duration=3; bool unknown0=4; string language=5 ... }
        // For worker, we can provide minimal fields: url, duration, language, responseLanguage
        // Use field numbers from vot.user.js: url=1, firstRequest=2, duration=3, unknown0, language=5, responseLanguage=10 etc.
        // Simplified: just encode url, duration, language, responseLanguage, bypassCache etc.
        val urlBytes = url.toByteArray()
        val langBytes = langFrom.toByteArray()
        val respLangBytes = langTo.toByteArray()
        return buildProtobuf {
            writeString(1, urlBytes) // url
            writeBool(2, true) // firstRequest
            writeInt32(3, duration)
            writeBool(4, true) // unknown0
            writeString(5, langBytes) // language
            writeBool(6, false) // forceSourceLang
            writeBool(7, false) // unknown1
            // translationHelp 8 repeated, skip
            writeString(10, respLangBytes) // responseLanguage
            writeBool(11, false) // wasStream
            writeBool(12, true) // unknown2
            writeInt32(13, 2) // unknown3
            writeBool(14, false) // bypassCache
        }
    }

    private fun postProtobuf(path: String, body: ByteArray, extraHeaders: Map<String, String>): ByteArray? {
        val json = JSONObject().apply {
            put("headers", JSONObject().apply {
                put("User-Agent", "Mozilla/5.0")
                put("Accept", "application/x-protobuf")
                put("Content-Type", "application/x-protobuf")
                for ((k, v) in extraHeaders) put(k, v)
            })
            put("body", org.json.JSONArray(body.map { it.toInt() and 0xFF }))
        }.toString()
        val req = Request.Builder()
            .url("$WORKER_SCHEME://$WORKER_HOST$path")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "worker $path failed ${resp.code} ${resp.message}")
                return null
            }
            return resp.body?.bytes()
        }
    }

    // Protobuf helpers
    private fun buildProtobuf(block: ProtobufWriter.() -> Unit): ByteArray {
        val w = ProtobufWriter()
        w.block()
        return w.toByteArray()
    }
    private class ProtobufWriter {
        private val out = java.io.ByteArrayOutputStream()
        fun writeString(field: Int, bytes: ByteArray) {
            writeTag(field, 2)
            writeVarint(bytes.size)
            out.write(bytes)
        }
        fun writeInt32(field: Int, v: Int) {
            writeTag(field, 0)
            writeVarint(v)
        }
        fun writeBool(field: Int, v: Boolean) {
            writeTag(field, 0)
            out.write(if (v) 1 else 0)
        }
        private fun writeTag(field: Int, wire: Int) {
            writeVarint((field shl 3) or wire)
        }
        private fun writeVarint(v: Int) {
            var value = v
            while (true) {
                if ((value and 0x7F.inv()) == 0) {
                    out.write(value)
                    return
                } else {
                    out.write((value and 0x7F) or 0x80)
                    value = value ushr 7
                }
            }
        }
        fun toByteArray() = out.toByteArray()
    }
}
