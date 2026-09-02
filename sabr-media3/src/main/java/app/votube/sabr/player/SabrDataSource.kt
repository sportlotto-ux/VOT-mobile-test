package app.votube.sabr.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.votube.sabr.parser.CompositeBuffer
import app.votube.sabr.parser.PlaybackRequest
import app.votube.sabr.parser.SabrClient
import java.io.IOException

@OptIn(UnstableApi::class)
class SabrDataSource(
    private val sabrClient: SabrClient,
) : BaseDataSource(true) {
    private var data: CompositeBuffer? = null

    /**
     * Duration in microseconds of the last served segment, or 0 if unknown.
     * Used by [DefaultSabrChunkSource] to schedule media chunks with correct end
     * times while the extractor has not produced a container index yet.
     */
    var lastSegmentDurationUs: Long = 0
        private set

    // ВАЖНО: значение живёт от успешного open() до СЛЕДУЮЩЕГО open(), а НЕ до close().
    // close() вызывается внутри Chunk.load() (finally) ДО того, как загрузчик уведомит
    // onChunkLoadCompleted — обнуление в close() навсегда прятало реальную длительность:
    // каждый чанк декларировался фолбэком 1с при реальных 3-4с, очередь отставала от
    // реальных сэмплов на ~2с за пару A/V → отрицательный readahead → периодические
    // flush/reposition → повторная отдача старых сегментов → зацикливание (лог 12:36).

    class Factory(
        private val sabrClient: SabrClient
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SabrDataSource(sabrClient)
    }

    override fun open(dataSpec: DataSpec): Long {
        val playbackRequest = dataSpec.customData as? PlaybackRequest
            ?: throw IOException("SABR data source requires PlaybackRequest")

        // Снимок сетевого счётчика ДО раундтрипа: метр пропускной способности (DefaultBandwidthMeter)
        // считает сэмпл по окну transferStarted→close, а окно это включает SABR-раундтрип
        // (getNextSegment блокируется на HTTP + ретраи + backoff). Раньше за это время метр видел
        // только байты одного сегмента => сэмпл «сегмент за раундтрип» => оценка канала рушилась
        // (иногда до кбит/с) => ABR сползал по лестнице в 144p (лог 11:35:59→11:36:29:
        // 399 -> 398 -> 394). Ниже докладываем метру РЕАЛЬНЫЕ байты ответа сервера.
        // Длительность предыдущего сегмента уже забрана/использована — сбрасываем ПЕРЕД
        // открытием, чтобы после неудачного open() не остался мусор из прошлой сессии.
        lastSegmentDurationUs = 0

        val networkBytesBefore = sabrClient.networkBytesSnapshot()

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        // Тег SABR-стрима, а не имя класса: иначе ошибки не видно под -s SabrStream:V.
        val segment = try {
            sabrClient.getNextSegment(playbackRequest)
                ?: throw IOException("SABR returned no segment ${playbackRequest.segment} for ${playbackRequest.format.itag}")
        } catch (e: IOException) {
            Log.e(
                "SabrStream",
                "open: failed to get segment ${playbackRequest.segment} (timeMs=${playbackRequest.segmentStartTimeMs}) " +
                    "for ${playbackRequest.format.itag}: $e"
            )
            throw e
        } catch (e: Exception) {
            Log.e(
                "SabrStream",
                "open: failed to get segment ${playbackRequest.segment} (timeMs=${playbackRequest.segmentStartTimeMs}) " +
                    "for ${playbackRequest.format.itag}: $e"
            )
            throw IOException("SABR segment request failed", e)
        }

        // Атрибуция: раундтрип вернул и другие сегменты (префетч аудио+видео одним ответом).
        // Байты самого сегмента посчитает read() (bytesTransferred), поэтому отдаём разницу.
        // Для сегмента из кэша дельта = 0 — сэмпл «почти мгновенной» передачи метр отбросит сам
        // (нулевой интервал не становится сэмплом), спайков вверх не будет.
        val networkDelta = sabrClient.networkBytesSnapshot() - networkBytesBefore
        if (networkDelta > segment.length()) {
            bytesTransferred((networkDelta - segment.length()).toInt())
        }

        data = CompositeBuffer(segment.data)
        lastSegmentDurationUs = segment.duration * 1000L
        return data?.remaining()?.toLong() ?: 0L
    }

    override fun getUri(): Uri? = Uri.parse("sabr://segment")

    override fun close() {
        // lastSegmentDurationUs НЕ обнуляем: onChunkLoadCompleted читает его уже ПОСЛЕ close()
        // (см. комментарий у поля). Сброс происходит в начале следующего open().
        data = null
        transferEnded()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val lengthToRead = minOf(maxOf(length, 0), data?.remaining() ?: 0)
        if (lengthToRead == 0) return C.RESULT_END_OF_INPUT
        data?.read(buffer, offset, lengthToRead) ?: return C.RESULT_END_OF_INPUT
        bytesTransferred(lengthToRead)
        return lengthToRead
    }
}
