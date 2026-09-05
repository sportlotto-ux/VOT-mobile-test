package app.votube.sabr.parser

import video_streaming.NextRequestPolicyOuterClass.NextRequestPolicy

/**
 * Чистые функции планирования/сопоставления сегментов SABR, вынесенные из [SabrClient],
 * чтобы их можно было юнит-тестировать без Android, сети и состояния (stateLock).
 *
 * Вся подстройка констант (толерантности, кэпы) остаётся в [SabrClient] — сюда передаются
 * готовые значения параметрами, поэтому дублирования «подобранных» чисел нет и дрейфа не будет.
 */
object SabrSegmentMatcher {

    /**
     * Ближайший ПО ВРЕМЕНИ ВПЕРЁД сегмент из кэша формата или null.
     * См. детальный контракт в [SabrClient.findTimeMatch].
     */
    fun findTimeMatch(
        cached: Collection<Segment>,
        lastServedSeq: Long?,
        requestedTimeMs: Long,
        sameTimeEpsMs: Long,
        liveTimeToleranceMs: Long,
    ): Segment? {
        return cached.filter {
            it.sequenceNumber != lastServedSeq &&
                it.header.startMs >= requestedTimeMs - sameTimeEpsMs &&
                it.header.startMs <= requestedTimeMs + liveTimeToleranceMs
        }.minByOrNull { it.header.startMs }
    }

    /**
     * Скип вперёд при дырке в серии (не прыжок к голове): ближайший доступный сегмент
     * с startMs в [requested − eps, requested + cap], берём САМЫЙ РАННИЙ (минимальный скип).
     * См. [SabrClient.findSkipMatch].
     */
    fun findSkipMatch(
        cached: Collection<Segment>,
        lastServedSeq: Long?,
        requestedTimeMs: Long,
        capMs: Long,
        sameTimeEpsMs: Long,
    ): Segment? {
        return cached.filter {
            it.sequenceNumber != lastServedSeq &&
                it.header.startMs >= requestedTimeMs - sameTimeEpsMs &&
                it.header.startMs <= requestedTimeMs + capMs
        }.minByOrNull { it.header.startMs }
    }

    /**
     * Индекс время→seq: точное попадание → seq; иначе интерполяция внутри известной
     * скобки соседей (ошибка ограничена скобкой). За краями известного — null.
     * См. [SabrClient.estimateSeqForTimeMs].
     *
     * @param points startMs → sequenceNumber (накопленная карта точки времени).
     * @param realStepMs наблюдаемый реальный шаг стартов эфира (null → фолбэк 2000).
     */
    fun estimateSeqForTimeMs(
        points: Map<Long, Long>,
        realStepMs: Long?,
        timeMs: Long,
    ): Long? {
        if (points.isEmpty()) return null
        points[timeMs]?.let { return it }
        val sorted = points.keys.sorted()
        val lower = sorted.lastOrNull { it <= timeMs }
        val upper = sorted.firstOrNull { it >= timeMs }
        if (lower != null && upper != null && upper > lower) {
            val lowerSeq = points[lower] ?: return null
            val upperSeq = points[upper] ?: return null
            if (upperSeq > lowerSeq) {
                val stepMs = realStepMs ?: 2_000L
                if (stepMs > 0) {
                    val est = lowerSeq + (timeMs - lower) / stepMs
                    if (est in lowerSeq..upperSeq) return est
                }
            }
            // Скобка вырождена (перенумерация) — ближайший сосед по времени.
            return if (timeMs - lower <= upper - timeMs) lowerSeq else upperSeq
        }
        return null
    }

    /**
     * Серверный readahead-таргет/минимум для трека (аудио/видео), если присутствует.
     * Отрицательные значения игнорируются. null — значения нет.
     */
    fun selectReadahead(
        policy: NextRequestPolicy?,
        isAudio: Boolean,
        minimum: Boolean,
    ): Int? {
        val p = policy ?: return null
        val value = when {
            isAudio && minimum && p.hasMinAudioReadaheadMs() -> p.minAudioReadaheadMs
            isAudio && !minimum && p.hasTargetAudioReadaheadMs() -> p.targetAudioReadaheadMs
            !isAudio && minimum && p.hasMinVideoReadaheadMs() -> p.minVideoReadaheadMs
            !isAudio && !minimum && p.hasTargetVideoReadaheadMs() -> p.targetVideoReadaheadMs
            else -> null
        }
        return value?.takeIf { it >= 0 }
    }
}
