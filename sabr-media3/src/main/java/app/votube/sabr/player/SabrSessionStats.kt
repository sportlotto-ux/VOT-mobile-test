package app.votube.sabr.player

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Счётчики SABR-сессии: сколько чанков реально отдано, сколько дыр апстрима
 * (no segment / propagate / skip'ы). Цель — за 10 секунд лога отличать
 * «битый эфир/CDN» от «сломанный клиент»: тихая сводка не чаще раза в 30с
 * плюс финальная сводка при старте следующей сессии.
 *
 * Вызывается из hot path (getNextSegment, onChunkLoadError) — только
 * atomic-инкременты и редкий лог, без синхронизации и аллокаций.
 */
object SabrSessionStats {
    private const val TAG = "SabrSessionStats"
    private const val SUMMARY_INTERVAL_MS = 30_000L

    @Volatile private var videoId: String = ""
    @Volatile private var sessionStartMs: Long = 0L
    @Volatile private var lastSummaryMs: Long = 0L

    private val servedVideo = AtomicLong()
    private val servedAudio = AtomicLong()
    private val noSegment = AtomicLong()
    private val gapWaits = AtomicLong()
    private val propagateNoFallback = AtomicLong()
    private val propagateDeclined = AtomicLong()
    private val fallbackSkip = AtomicLong()
    private val fallbackSkipMs = AtomicLong()
    private val nearestHead = AtomicLong()
    private val holeSkipAttempt = AtomicLong()
    private val holeSkipServed = AtomicLong()
    // v38: отклонённые древние кандидаты nearest-head (призраки типа seq 8 при head 816).
    private val staleRejected = AtomicLong()

    /** Новая сессия (создание сорса): печатаем итог прошлой и обнуляемся. */
    fun startSession(newVideoId: String) {
        if (sessionStartMs != 0L) {
            Log.i(TAG, "session end [$videoId]: ${summary()}")
        }
        videoId = newVideoId
        sessionStartMs = SystemClock.elapsedRealtime()
        lastSummaryMs = sessionStartMs
        servedVideo.set(0)
        servedAudio.set(0)
        noSegment.set(0)
        gapWaits.set(0)
        propagateNoFallback.set(0)
        propagateDeclined.set(0)
        fallbackSkip.set(0)
        fallbackSkipMs.set(0)
        nearestHead.set(0)
        holeSkipAttempt.set(0)
        holeSkipServed.set(0)
        staleRejected.set(0)
    }

    fun onVideoServed() { servedVideo.incrementAndGet(); maybeSummary() }
    fun onAudioServed() { servedAudio.incrementAndGet(); maybeSummary() }
    fun onNoSegment() { noSegment.incrementAndGet(); maybeSummary() }
    // v34: ожидание головы внутри open() (500мс-кванты, без лога — только счётчик).
    fun onGapWait() { gapWaits.incrementAndGet() }
    fun onPropagateNoFallback() { propagateNoFallback.incrementAndGet(); maybeSummary() }
    fun onPropagateDeclined() { propagateDeclined.incrementAndGet(); maybeSummary() }
    fun onFallbackSkip(skippedMs: Long) {
        fallbackSkip.incrementAndGet()
        fallbackSkipMs.addAndGet(skippedMs)
        maybeSummary()
    }
    fun onNearestHead() { nearestHead.incrementAndGet(); maybeSummary() }
    fun onHoleSkipAttempt() { holeSkipAttempt.incrementAndGet(); maybeSummary() }
    fun onHoleSkipServed() { holeSkipServed.incrementAndGet(); maybeSummary() }
    // v38: nearest-head остался без допустимых кандидатов (все древние) — идём в hole-skip.
    fun onStaleRejected() { staleRejected.incrementAndGet(); maybeSummary() }

    private fun maybeSummary() {
        val now = SystemClock.elapsedRealtime()
        if (sessionStartMs == 0L) {
            sessionStartMs = now
            lastSummaryMs = now
            return
        }
        if (now - lastSummaryMs >= SUMMARY_INTERVAL_MS) {
            lastSummaryMs = now
            Log.i(TAG, "session [$videoId]: ${summary()}")
        }
    }

    private fun summary(): String {
        val secs = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000
        return "+${secs}s servedV=${servedVideo.get()} servedA=${servedAudio.get()} " +
            "noSegment=${noSegment.get()} (gapWait=${gapWaits.get()}) " +
            "propagate=${propagateNoFallback.get()}+${propagateDeclined.get()}declined " +
            "fallbackSkip=${fallbackSkip.get()}(${fallbackSkipMs.get()}ms) " +
            "nearestHead=${nearestHead.get()} " +
            "holeSkip=${holeSkipServed.get()}/${holeSkipAttempt.get()} " +
            "staleRejected=${staleRejected.get()}"
    }

    // v37: интегратор беды для error-driven downgrade: каждый мисс/ретрай +1, каждый
    // отданный чанк −1 (читатель держит дельты сам). Видит и propagate, и hole-skip'ы.
    fun getTrouble(): Long =
        noSegment.get() + propagateNoFallback.get() + propagateDeclined.get() + holeSkipAttempt.get()

    fun getServedTotal(): Long = servedVideo.get() + servedAudio.get()
}
