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

    fun getVideoHeight(): Int = videoHeight
    fun getVideoWidth(): Int = videoWidth
    fun getVideoItag(): Int = videoItag
}
