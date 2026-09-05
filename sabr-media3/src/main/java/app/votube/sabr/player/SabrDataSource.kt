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
import app.votube.sabr.parser.Segment
import java.io.IOException
import java.io.InterruptedIOException

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

    companion object {
        // v34: толерантность к дырам головы live. «Нет сегмента» считаем transient и ждём
        // внутри open() (бюджетом), а не бросаем мгновенно в Exo: каждый мисс раньше шёл
        // в propagate → бэкофф лоадеров Exo рос → треки умирали поодиночке и не воскресали
        // (лог 13:13: servedA встал на 55 за минуту до смерти всего стрима). Фатальные
        // ошибки (исключения) пробрасываем сразу; VOD ждёт тоже 0 (там мисс — не transient).
        // Отмена (seek/стоп) прилетает прерыванием — выходим мгновенно через
        // InterruptedIOException (Exo понимает его как cancel, не как ошибку).
        private const val GAP_TOLERANCE_MS = 8000L
        private const val GAP_WAIT_STEP_MS = 500L
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
        val openStartMs = android.os.SystemClock.elapsedRealtime()
        var gapLogged = false
        val segment = try {
            var result: Segment? = null
            while (result == null) {
                result = sabrClient.getNextSegment(playbackRequest)
                if (result == null) {
                    // VOD-мисс — сразу наружу (там это не transient).
                    // Live-мисс в пределах бюджета — спим и переспрашиваем.
                    val elapsed = android.os.SystemClock.elapsedRealtime() - openStartMs
                    if (!sabrClient.isLive() || elapsed >= GAP_TOLERANCE_MS) {
                        SabrSessionStats.onNoSegment()
                        throw IOException("SABR returned no segment ${playbackRequest.segment} for ${playbackRequest.format.itag}")
                    }
                    if (!gapLogged) {
                        gapLogged = true
                        Log.w("SabrStream",
                            "live gap: waiting for segment ${playbackRequest.segment} " +
                                "(timeMs=${playbackRequest.segmentStartTimeMs}) for ${playbackRequest.format.itag}")
                    }
                    SabrSessionStats.onGapWait()
                    try {
                        Thread.sleep(GAP_WAIT_STEP_MS)
                    } catch (e: InterruptedException) {
                        throw InterruptedIOException("SABR open cancelled while waiting for segment")
                    }
                }
            }
            result
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
