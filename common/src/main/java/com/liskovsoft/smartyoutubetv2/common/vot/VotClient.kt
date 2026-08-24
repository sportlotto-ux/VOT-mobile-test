package com.liskovsoft.smartyoutubetv2.common.vot

import android.util.Log
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

<<<<<<< HEAD
/**
 * Minimal VOT client - Yandex voice-over-translation via vot-worker JSON wrapper.
 * Uses OkHttp 3.x API (same as upstream Utils.java) for clean upstream merges.
 */
=======
>>>>>>> 135552f (feat(vot): integrate VOT into ExoPlayerController via MergingMediaSource)
object VotClient {
    private const val TAG = "VOT"
    private const val WORKER_HOST = "vot-worker.vtrans.eu.cc"
    private val JSON = MediaType.parse("application/json; charset=utf-8")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

<<<<<<< HEAD
    @JvmStatic
    @JvmOverloads
    fun getVotAudioUrlBlocking(videoId: String, duration: Int = 0, langFrom: String = "en", langTo: String = "ru"): String? {
        return try {
            val body = encodeTranslationRequest("https://youtu.be/$videoId", duration, langFrom, langTo)
            val resp = postProtobuf("/video-translation/translate", body) ?: return null
            extractUrl(resp)
        } catch (e: Exception) {
            Log.w(TAG, "getVotAudioUrlBlocking error", e)
=======
    fun getVotAudioUrlBlocking(videoId: String, duration: Int = 0, langFrom: String = "en", langTo: String = "ru"): String? {
        return try {
            val body = createTranslateBody("https://youtu.be/$videoId", duration, langFrom, langTo)
            val resp = postProtobuf("/video-translation/translate", body, emptyMap()) ?: return null
            extractUrl(resp)
        } catch (e: Exception) {
            Log.w(TAG, "blocking error", e)
>>>>>>> 135552f (feat(vot): integrate VOT into ExoPlayerController via MergingMediaSource)
            null
        }
    }

    private fun extractUrl(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val regex = Regex("""https://vtrans\.s3-private\.mds\.yandex\.net[^\x00-\x1F"']+""")
        return regex.find(text)?.value?.trim()?.takeIf { it.contains(".mp3") || it.contains(".m4a") }
    }

<<<<<<< HEAD
    private fun encodeTranslationRequest(url: String, duration: Int, langFrom: String, langTo: String): ByteArray {
        return buildProtobuf {
            writeString(1, url.toByteArray())   // url
            writeBool(2, true)                  // firstRequest
            writeInt32(3, duration)             // duration
            writeBool(4, true)                  // unknown0
            writeString(5, langFrom.toByteArray()) // language
            writeBool(6, false)                 // forceSourceLang
            writeBool(7, false)                 // unknown1
            writeString(10, langTo.toByteArray())  // responseLanguage
            writeBool(11, false)                // wasStream
            writeBool(12, true)                 // unknown2
            writeInt32(13, 2)                   // unknown3
            writeBool(14, false)                // bypassCache
=======
    suspend fun getVotAudioUrl(videoId: String, videoUrl: String = "https://youtu.be/$videoId", duration: Int = 0, langFrom: String = "en", langTo: String = "ru"): String? = withContext(Dispatchers.IO) {
        getVotAudioUrlBlocking(videoId, duration, langFrom, langTo)
    }

    private fun createTranslateBody(videoUrl: String, duration: Int, langFrom: String, langTo: String): ByteArray {
        return encodeTranslationRequest(videoUrl, duration, langFrom, langTo)
    }

    private fun encodeTranslationRequest(url: String, duration: Int, langFrom: String, langTo: String): ByteArray {
        val urlBytes = url.toByteArray()
        val langBytes = langFrom.toByteArray()
        val respLangBytes = langTo.toByteArray()
        return buildProtobuf {
            writeString(1, urlBytes)
            writeBool(2, true)
            writeInt32(3, duration)
            writeBool(4, true)
            writeString(5, langBytes)
            writeBool(6, false)
            writeBool(7, false)
            writeString(10, respLangBytes)
            writeBool(11, false)
            writeBool(12, true)
            writeInt32(13, 2)
            writeBool(14, false)
>>>>>>> 135552f (feat(vot): integrate VOT into ExoPlayerController via MergingMediaSource)
        }
    }

    private fun postProtobuf(path: String, body: ByteArray): ByteArray? {
        val json = JSONObject().apply {
            put("headers", JSONObject().apply {
                put("User-Agent", "Mozilla/5.0")
                put("Accept", "application/x-protobuf")
                put("Content-Type", "application/x-protobuf")
            })
            put("body", JSONArray(body.map { it.toInt() and 0xFF }))
        }.toString()
        val req = Request.Builder()
            .url("https://$WORKER_HOST$path")
            .post(RequestBody.create(JSON, json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "worker $path failed ${resp.code()}")
                return null
            }
            return resp.body()?.bytes()
        }
    }

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
