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

object VotClient {
    private const val TAG = "VOT"
    private const val WORKER_HOST = "vot-worker.vtrans.eu.cc"
    private const val WORKER_SCHEME = "https"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getVotAudioUrlBlocking(videoId: String, duration: Int = 0, langFrom: String = "en", langTo: String = "ru"): String? {
        return try {
            val body = createTranslateBody("https://youtu.be/$videoId", duration, langFrom, langTo)
            val resp = postProtobuf("/video-translation/translate", body, emptyMap()) ?: return null
            extractUrl(resp)
        } catch (e: Exception) {
            Log.w(TAG, "blocking error", e)
            null
        }
    }

    private fun extractUrl(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val regex = Regex("""https://vtrans\.s3-private\.mds\.yandex\.net[^\x00-\x1F"']+""")
        return regex.find(text)?.value?.trim()?.takeIf { it.contains(".mp3") || it.contains(".m4a") }
    }

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
