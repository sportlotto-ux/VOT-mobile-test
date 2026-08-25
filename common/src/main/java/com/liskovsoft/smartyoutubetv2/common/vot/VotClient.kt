package com.liskovsoft.smartyoutubetv2.common.vot

import android.util.Log
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Yandex VOT client - full protocol like vot.user.js:
 * 1) session/create with Vtrans-Signature -> secretKey+expires
 * 2) video-translation/translate with Vtrans-Signature + Sec-Vtrans-Sk + Sec-Vtrans-Token
 */
object VotClient {
    private const val TAG = "VOT"
    private const val WORKER_HOST = "vot-worker.vtrans.eu.cc"
    private const val HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf"
    private const val COMPONENT_VERSION = "26.6.4.760"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 YaBrowser/26.6.0.0 Safari/537.36"
    private val JSON = MediaType.parse("application/json; charset=utf-8")
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var secretKey: String? = null

    @JvmStatic
    @JvmOverloads
    fun getVotAudioUrlBlocking(videoId: String, duration: Int = 310, langFrom: String = "en", langTo: String = "ru"): String? {
        return try {
            ensureSession()
            val path = "/video-translation/translate"
            val body = encodeTranslationRequest("https://youtu.be/$videoId", duration, langFrom, langTo)
            val resp = postProtobuf(path, body, secHeaders(path, body)) ?: return null
            extractUrl(resp)
        } catch (e: Exception) {
            Log.w(TAG, "getVotAudioUrlBlocking error", e)
            null
        }
    }

    // ---- session ----
    private fun ensureSession() {
        if (secretKey != null) return
        synchronized(this) {
            if (secretKey != null) return
            val uuid = getUuid()
            val body = buildProtobuf {
                writeString(1, uuid.toByteArray())
                writeString(2, "video-translation".toByteArray())
            }
            val resp = postProtobuf("/session/create", body, mapOf("Vtrans-Signature" to hexHmac(HMAC_KEY, body)))
                ?: throw IllegalStateException("session create failed")
            secretKey = parseSessionSecret(resp)
            Log.i(TAG, "session ok, secretKey len=${secretKey?.length}")
        }
    }

    /** SessionResponse proto: field1 string secretKey, field2 int32 expires */
    private fun parseSessionSecret(b: ByteArray): String? {
        var i = 0
        while (i < b.size) {
            val tag = readVarint(b, i); i += tag.second
            val field = tag.first ushr 3
            val wire = tag.first and 7
            when {
                wire == 2 -> {
                    val len = readVarint(b, i); i += len.second
                    if (field == 1) return String(b, i, len.first, Charsets.UTF_8)
                    i += len.first
                }
                wire == 0 -> { val v = readVarint(b, i); i += v.second }
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(b: ByteArray, off: Int): Pair<Int, Int> {
        var result = 0; var shift = 0; var i = off
        while (i < b.size) {
            result = result or ((b[i].toInt() and 0x7F) shl shift)
            if (b[i].toInt() and 0x80 == 0) break
            shift += 7; i++
        }
        return Pair(result, i - off + 1)
    }

    // ---- sec headers like getSecYaHeaders ----
    private fun secHeaders(path: String, body: ByteArray): Map<String, String> {
        val sk = secretKey ?: ""
        val uuid = lastUuid
        val token = "$uuid:$path:$COMPONENT_VERSION"
        val tokenSign = hexHmac(HMAC_KEY, token.toByteArray(Charsets.UTF_8))
        return mapOf(
            "Vtrans-Signature" to hexHmac(HMAC_KEY, body),
            "Sec-Vtrans-Sk" to sk,
            "Sec-Vtrans-Token" to "$tokenSign:$token"
        )
    }

    @Volatile private var lastUuid: String = ""

    private fun getUuid(): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(32)
        for (i in 0 until 32) sb.append(hex[(Math.random() * 16).toInt()])
        lastUuid = sb.toString()
        return lastUuid
    }

    // ---- crypto ----
    private fun hexHmac(key: String, data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    // ---- extraction ----
    private fun extractUrl(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val regex = Regex("""https://vtrans\.s3-private\.mds\.yandex\.net[^\x00-\x1F"']+""")
        return regex.find(text)?.value?.trim()?.takeIf { it.contains(".mp3") || it.contains(".m4a") }
    }

    // ---- request encoding ----
    private fun encodeTranslationRequest(url: String, duration: Int, langFrom: String, langTo: String): ByteArray {
        return buildProtobuf {
            writeString(1, url.toByteArray())       // url
            writeBool(2, true)                      // firstRequest
            writeInt32(3, duration)                 // duration
            writeBool(4, true)                      // unknown0
            writeString(5, langFrom.toByteArray())  // language
            writeBool(6, false)                     // forceSourceLang
            writeBool(7, false)                     // unknown1
            writeString(10, langTo.toByteArray())   // responseLanguage
            writeBool(11, false)                    // wasStream
            writeBool(12, true)                     // unknown2
            writeInt32(13, 2)                       // unknown3
            writeBool(14, false)                    // bypassCache
        }
    }

    // ---- transport (worker JSON wrapper) ----
    private fun postProtobuf(path: String, body: ByteArray, extraHeaders: Map<String, String>): ByteArray? {
        val json = JSONObject().apply {
            put("headers", JSONObject().apply {
                put("User-Agent", USER_AGENT)
                put("Accept", "application/x-protobuf")
                put("Content-Type", "application/x-protobuf")
                for ((k, v) in extraHeaders) put(k, v)
            })
            put("body", JSONArray(body.map { it.toInt() and 0xFF }))
        }.toString()
        val req = Request.Builder()
            .url("https://$WORKER_HOST$path")
            .post(RequestBody.create(JSON, json))
            .build()
        client.newCall(req).execute().use { resp ->
            val bytes = resp.body()?.bytes()
            if (!resp.isSuccessful) {
                Log.w(TAG, "worker $path failed ${resp.code()} head=${bytes?.let { String(it, 0, Math.min(it.size, 200)) }}")
                return null
            }
            return bytes
        }
    }

    // ---- protobuf writer ----
    private fun buildProtobuf(block: ProtobufWriter.() -> Unit): ByteArray {
        val w = ProtobufWriter(); w.block(); return w.toByteArray()
    }
    private class ProtobufWriter {
        private val out = java.io.ByteArrayOutputStream()
        fun writeString(field: Int, bytes: ByteArray) { writeTag(field, 2); writeVarint(bytes.size); out.write(bytes) }
        fun writeInt32(field: Int, v: Int) { writeTag(field, 0); writeVarint(v) }
        fun writeBool(field: Int, v: Boolean) { writeTag(field, 0); out.write(if (v) 1 else 0) }
        private fun writeTag(field: Int, wire: Int) { writeVarint((field shl 3) or wire) }
        private fun writeVarint(v: Int) {
            var value = v
            while (true) {
                if ((value and 0x7F.inv()) == 0) { out.write(value); return }
                out.write((value and 0x7F) or 0x80); value = value ushr 7
            }
        }
        fun toByteArray() = out.toByteArray()
    }
}
