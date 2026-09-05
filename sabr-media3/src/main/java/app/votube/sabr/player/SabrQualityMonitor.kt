package app.votube.sabr.player

/**
 * v28: фактическое качество, отдаваемое SABR-лоадерами.
 *
 * Проблема: ABR внутри [DefaultSabrChunkSource] (включая hysteresis-override holder'а)
 * двигается молча — Exo-селекция и [TrackSelectorManager] об этом не узнают, и UI
 * (диалог качества, stats-overlay) навсегда показывает стартовый трек (обычно 144p).
 * Монитор — нейтральный канал sabr→UI: чанк-сорс пишет сюда каждый отданный holder,
 * читатель в common перемаркирует selectedTrack.
 *
 * Свежесть 30с: после остановки сорса данные протухают и UI возвращается к селекции Exo.
 * Ручной пин пользователя проверяется читателем отдельно (selection override).
 */
object SabrQualityMonitor {
    private const val FRESH_MS = 30_000L

    @Volatile private var videoId: String? = null
    @Volatile private var videoItag: Int = -1
    @Volatile private var videoWidth: Int = -1
    @Volatile private var videoHeight: Int = -1
    @Volatile private var audioItag: Int = -1
    @Volatile private var lastServeMs: Long = 0L
    // v32: пресет качества пользователя (Настройки → Плеер → Видео-пресеты / HQ-диалог).
    // 0 = авто. Глобален (не per-video), поэтому reset() его НЕ трогает; пишется с
    // common-стороны (фабрика сорса + TrackSelectorManager.selectTrack).
    // v32.1: пресет — это высота + fps + семейство кодека (1080p60 ≠ 1080p30).
    @Volatile private var presetVideoHeight: Int = 0
    @Volatile private var presetVideoFps: Float = 0f
    @Volatile private var presetVideoCodecs: String? = null

    @Synchronized
    fun reset(videoId: String?) {
        this.videoId = videoId
        videoItag = -1
        videoWidth = -1
        videoHeight = -1
        audioItag = -1
        lastServeMs = 0L
    }

    @Synchronized
    fun onVideoServed(videoId: String?, itag: Int, width: Int, height: Int) {
        this.videoId = videoId
        videoItag = itag
        videoWidth = width
        videoHeight = height
        lastServeMs = System.currentTimeMillis()
    }

    @Synchronized
    fun onAudioServed(videoId: String?, itag: Int) {
        this.videoId = videoId
        audioItag = itag
        lastServeMs = System.currentTimeMillis()
    }

    fun isFresh(): Boolean =
        videoHeight > 0 && System.currentTimeMillis() - lastServeMs < FRESH_MS

    /** v32: пресет как закон (height 0 = авто, ABR решает сам). Пишется из common, читается чанк-сорсом каждый чанк. */
    fun setPreset(height: Int, fps: Float = 0f, codecs: String? = null) {
        if (presetVideoHeight != height || presetVideoFps != fps || presetVideoCodecs != codecs) {
            presetVideoHeight = height
            presetVideoFps = fps
            presetVideoCodecs = codecs
            android.util.Log.i("SabrQualityMonitor",
                "quality preset: ${if (height > 0) "${height}p@${if (fps > 0) fps.toInt().toString() else "?"} ${codecs ?: "any"} (locked)" else "auto"}")
        }
    }

    fun getPresetHeight(): Int = presetVideoHeight
    fun getPresetFps(): Float = presetVideoFps
    fun getPresetCodecs(): String? = presetVideoCodecs

    fun getVideoHeight(): Int = videoHeight
    fun getVideoWidth(): Int = videoWidth
    fun getVideoItag(): Int = videoItag
}
