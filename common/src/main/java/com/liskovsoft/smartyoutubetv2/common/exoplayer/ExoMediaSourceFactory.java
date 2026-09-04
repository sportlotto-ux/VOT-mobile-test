package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.datasource.cronet.CronetEngineWrapper;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.dash.DashChunkSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.dashmanifest.DashManifestParser3;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement;
import androidx.media3.exoplayer.dash.manifest.ProgramInformation;
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.common.util.Util;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.youtubeapi.formatbuilders.utils.MediaFormatUtils;

import app.votube.sabr.manifest.SabrManifest;
import app.votube.sabr.manifest.SabrStreamInfo;
import app.votube.sabr.player.SabrMediaSource;
import app.votube.sabr.parser.PoTokenProvider;
import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.DashDefaultLoadErrorHandlingPolicy;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.SabrDefaultLoadErrorHandlingPolicy;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class ExoMediaSourceFactory {
    private static final String TAG = ExoMediaSourceFactory.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    //private static ExoMediaSourceFactory sInstance;
    private static final int MAX_SEGMENTS_PER_LOAD = 1; // default - 1 (1-5)
    private static final String USER_AGENT = DefaultHeaders.APP_USER_AGENT;
    @SuppressLint("StaticFieldLeak")
    private final Context mContext;
    private static final Uri DASH_MANIFEST_URI = Uri.parse("https://example.com/test.mpd");
    private static final String DASH_MANIFEST_EXTENSION = "mpd";
    private static final String HLS_PLAYLIST_EXTENSION = "m3u8";
    private static final boolean USE_BANDWIDTH_METER = false;
    private static final long FETCH_TIMEOUT_MS = 6000;
    // v27: native DASH-live ВЫКЛЮЧЕН обратно (возврат к SABR-UMP как в v23).
    // Вердикт по diag v26: свежие BaseURL (валидный sig/expire) получают мгновенный 403
    // по всем itag с первой секунды (лестница 137→…→160 за 0.5с), тренд дня — монотонное
    // ужесточение (30 мин утром → смерть на 40с → мгновенная смерть). Сервер отвергает
    // сам класс URL (нет клиентской телеметрии met/mm/mn/ms под lsig), а не их форму:
    // nsig-теория мертва (n в live-MPD нет), ротация баз реальна, но не причина.
    // Live остаётся на SABR (poToken-сессии доверяют); следующий шаг — качество SABR-live.
    // Код v24-v26 (rewriteNsig, absolutize, DiagLoggingDataSource) живёт под этим флагом.
    private static final boolean DASH_LIVE_ENABLED = false;
    private TrackErrorFixer mTrackErrorFixer;
    private DataSource.Factory mMediaDataSourceFactory;

    public ExoMediaSourceFactory(Context context) {
        mContext = context;
    }

    public MediaSource fromSabrFormatInfo(MediaItemFormatInfo formatInfo) {
        return buildSabrMediaSource(formatInfo);
    }

    public MediaSource fromDashFormatInfo(MediaItemFormatInfo formatInfo) {
        return buildDashMediaSource(formatInfo);
    }

    public MediaSource fromDashManifest(InputStream dashManifest) {
        return buildMPDMediaSource(DASH_MANIFEST_URI, dashManifest);
    }

    public MediaSource fromDashManifestUrl(String dashManifestUrl) {
        return buildMediaSource(Uri.parse(dashManifestUrl), DASH_MANIFEST_EXTENSION);
    }

    // v21-dash-live spike: минимальный ANDROID player-запрос ради streamingData.dashManifestUrl.
    // Контекст 1:1 как в Constants.kt (ANDROID 21.26.364), ключ — тот же API_KEY_NEW.
    // TODO(spike): при успехе перенести в экстрактор (InnertubeService) и кешировать на сессию.
    @Nullable
    private String fetchAndroidDashManifestUrlBg(@Nullable String videoId) {
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<String> future = exec.submit(() -> fetchAndroidDashManifestUrl(videoId));
        exec.shutdown();
        try {
            return future.get(FETCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            Log.w(TAG, "fetchAndroidDashManifestUrlBg failed: %s", e);
            return null;
        }
    }

    @Nullable
    private String fetchAndroidDashManifestUrl(@Nullable String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    "https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false&alt=json")
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent",
                    "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip");
            String body = "{\"videoId\":\"" + videoId + "\",\"context\":{\"client\":{" +
                    "\"clientName\":\"ANDROID\",\"clientVersion\":\"21.26.364\"," +
                    "\"androidSdkVersion\":30,\"osName\":\"Android\",\"osVersion\":\"11\"," +
                    "\"hl\":\"en\",\"gl\":\"US\"}}}";
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "fetchAndroidDashManifestUrl: HTTP %s", conn.getResponseCode());
                return null;
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONObject streamingData = root.optJSONObject("streamingData");
            String dashUrl = streamingData != null ? streamingData.optString("dashManifestUrl", null) : null;
            Log.i(TAG, "fetchAndroidDashManifestUrl: videoId=%s, dashUrl=%s", videoId, !TextUtils.isEmpty(dashUrl));
            return dashUrl;
        } catch (Exception e) {
            Log.w(TAG, "fetchAndroidDashManifestUrl failed: %s", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public MediaSource fromHlsPlaylist(String hlsPlaylist) {
        return buildMediaSource(Uri.parse(hlsPlaylist), HLS_PLAYLIST_EXTENSION);
    }

    public MediaSource fromUrlList(List<String> urlList) {
        MediaSource[] mediaSources = new MediaSource[urlList.size()];

        for (int i = 0; i < urlList.size(); i++) {
            mediaSources[i] = buildMediaSource(Uri.parse(urlList.get(i)), null);
        }

        //return mediaSources.length == 1 ? mediaSources[0] : new ConcatenatingMediaSource(mediaSources); // or playlist
        return mediaSources[0]; // item with max resolution
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to attach the shared bandwidth meter to the new DataSource
     *                          factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        DefaultBandwidthMeter bandwidthMeter = useBandwidthMeter
                ? DefaultBandwidthMeter.getSingletonInstance(mContext)
                : null;
        return new DefaultDataSource.Factory(mContext, buildHttpDataSourceFactory(bandwidthMeter))
                .setTransferListener(bandwidthMeter);
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @param bandwidthMeter Bandwidth meter to attach to the new HTTP DataSource factory, or null.
     * @return A new HttpDataSource factory.
     */
    private HttpDataSource.Factory buildHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);
        int source = tweaksData.getPlayerDataSource();
        return source == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP ? buildOkHttpDataSourceFactory(bandwidthMeter) :
                        source == PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET && CronetManager.getEngine(mContext) != null ? buildCronetDataSourceFactory(bandwidthMeter) :
                                buildDefaultHttpDataSourceFactory(bandwidthMeter);
    }

    @SuppressWarnings("deprecation")
    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        int type = TextUtils.isEmpty(overrideExtension) ? Util.inferContentType(uri) : Util.inferContentType("." + overrideExtension);
        switch (type) {
            case C.TYPE_SS:
                SsMediaSource ssSource =
                        new SsMediaSource.Factory(
                                getSsChunkSourceFactory(),
                                getMediaDataSourceFactory()
                        )
                                .createMediaSource(MediaItem.fromUri(uri));
                if (mTrackErrorFixer != null) {
                    ssSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return ssSource;
            case C.TYPE_DASH:
                // v26-diag (ВРЕМЕННО): обёртка логирует URL упавших с 4xx/5xx запросов
                // (тег DiagLoggingDataSource) — отличить 403 на sq/ от 403 на refresh.
                DataSource.Factory loggingFactory = new DiagLoggingDataSource.Factory(getMediaDataSourceFactory());
                DashMediaSource dashSource =
                        new DashMediaSource.Factory(
                                getDashChunkSourceFactory(loggingFactory),
                                loggingFactory
                        )
                                .setManifestParser(new LiveDashManifestParser()) // Don't make static! Need state reset for each live source.
                                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                                .createMediaSource(MediaItem.fromUri(uri));
                if (mTrackErrorFixer != null) {
                    dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return dashSource;
            case C.TYPE_HLS:
                HlsMediaSource hlsSource = new HlsMediaSource.Factory(getMediaDataSourceFactory()).createMediaSource(MediaItem.fromUri(uri));
                if (mTrackErrorFixer != null) {
                    hlsSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return hlsSource;
            case C.TYPE_OTHER:
                ProgressiveMediaSource extractorSource = new ProgressiveMediaSource.Factory(
                        getMediaDataSourceFactory(), new DefaultExtractorsFactory())
                        .createMediaSource(MediaItem.fromUri(uri));
                if (mTrackErrorFixer != null) {
                    extractorSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
                }
                return extractorSource;
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private MediaSource buildSabrMediaSource(MediaItemFormatInfo formatInfo) {
        boolean hasSabrUrl = !TextUtils.isEmpty(formatInfo.getServerAbrStreamingUrl());
        boolean hasSabrConfig = !TextUtils.isEmpty(formatInfo.getVideoPlaybackUstreamerConfig());
        int adaptiveFormatCount = formatInfo.getAdaptiveFormats() != null
                ? formatInfo.getAdaptiveFormats().size() : -1;

        Log.i(TAG, "SABR selection: videoId=%s, url=%s, config=%s, adaptiveFormats=%s, dashUrl=%s",
                formatInfo.getVideoId(), hasSabrUrl, hasSabrConfig, adaptiveFormatCount,
                !TextUtils.isEmpty(formatInfo.getDashManifestUrl()));

        if (!hasSabrUrl || !hasSabrConfig) {
            Log.w(TAG, "buildSabrMediaSource: SABR data is missing, falling back to DASH");
            return buildDashMediaSource(formatInfo);
        }

        long durationMs = getDurationMs(formatInfo);
        Log.i(TAG, "buildSabrMediaSource: building SABR source, formats=%s, durationMs=%s (live=%s)", adaptiveFormatCount, durationMs, durationMs == C.TIME_UNSET);
        // v19-dash-live spike (sabr-dash-poc findings: live — это не SABR-UMP, а нативный
        // dynamic-DASH YouTube; их headless-SabrStream тоже не довёл live до выхода).
        // Играем dashManifestUrl штатным DashMediaSource: live edge, DVR-окно, refresh
        // манифеста и ABR — кодом Exo, а не нашими якорями. Прокси не нужен: качаем сегменты
        // и играем с одного IP устройства. Нет dash-урла — старый путь через SABR-UMP ниже.
        if (DASH_LIVE_ENABLED && durationMs == C.TIME_UNSET && formatInfo.containsDashUrl()
                && !TextUtils.isEmpty(formatInfo.getDashManifestUrl())) {
            Log.i(TAG, "buildSabrMediaSource: live + dashManifestUrl — native DASH instead of SABR-UMP: %s",
                    formatInfo.getDashManifestUrl());
            return fromDashManifestUrl(formatInfo.getDashManifestUrl());
        }
        // v21-dash-live spike, шаг 2: у победителя dash-урла нет (dashUrl=false) — тянем его
        // из ANDROID-ответа (curl 09-04: ANDROID для live отдаёт dashManifestUrl + sabr).
        // Один POST на старт стрима; провал/таймаут — молча старый путь через SABR-UMP ниже.
        // v22: фабрика вызывается на main thread (NetworkOnMainThread!) — запрос в фоне
        // с ограниченным ожиданием FETCH_TIMEOUT_MS.
        if (DASH_LIVE_ENABLED && durationMs == C.TIME_UNSET) {
            String androidDashUrl = fetchAndroidDashManifestUrlBg(formatInfo.getVideoId());
            if (!TextUtils.isEmpty(androidDashUrl)) {
                Log.i(TAG, "buildSabrMediaSource: live + ANDROID dashManifestUrl — native DASH instead of SABR-UMP: %s",
                        androidDashUrl);
                return fromDashManifestUrl(androidDashUrl);
            }
        }
        SabrManifest manifest = new SabrManifest(
                formatInfo.getVideoId(),
                formatInfo.getServerAbrStreamingUrl(),
                formatInfo.getVideoPlaybackUstreamerConfig(),
                durationMs,
                toSabrStreamInfos(formatInfo));

        final byte[] poToken = decodePoToken(formatInfo.getPoToken());
        PoTokenProvider poTokenProvider = videoId -> poToken;

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri("sabr://" + formatInfo.getVideoId())
                .build();

        return new SabrMediaSource.Factory(mContext, manifest, poTokenProvider)
                .setLoadErrorHandlingPolicy(new SabrDefaultLoadErrorHandlingPolicy())
                .createMediaSource(mediaItem);
    }

    private static long getDurationMs(MediaItemFormatInfo formatInfo) {
        long lenSeconds = Helpers.parseLong(formatInfo.getLengthSeconds());
        return lenSeconds > 0 ? lenSeconds * 1_000 : C.TIME_UNSET;
    }

    private static List<SabrStreamInfo> toSabrStreamInfos(MediaItemFormatInfo formatInfo) {
        List<SabrStreamInfo> result = new ArrayList<>();
        List<MediaFormat> formats = formatInfo.getAdaptiveFormats();
        if (formats == null) {
            return result;
        }
        for (MediaFormat fmt : formats) {
            try {
                Integer fps = null;
                float parsedFps = Helpers.parseFloat(fmt.getFps(), -1);
                if (parsedFps > 0) {
                    fps = Math.round(parsedFps);
                }
                Long durationMs = fmt.getApproxDurationMs() > 0 ? (long) fmt.getApproxDurationMs() : null;
                result.add(new SabrStreamInfo(
                        Helpers.parseInt(fmt.getITag(), -1),
                        Helpers.parseLong(fmt.getLmt()),
                        fmt.getXtags(),
                        MediaFormatUtils.extractMimeType(fmt),
                        extractCodecsSafe(fmt),
                        Helpers.parseInt(fmt.getBitrate(), -1),
                        fps,
                        fmt.getWidth() > 0 ? fmt.getWidth() : null,
                        fmt.getHeight() > 0 ? fmt.getHeight() : null,
                        durationMs,
                        !TextUtils.isEmpty(fmt.getAudioTrackId()) ? fmt.getAudioTrackId() : null,
                        null,
                        !TextUtils.isEmpty(fmt.getLanguage()) ? fmt.getLanguage() : null,
                        fmt.isDrc()));
            } catch (Exception e) {
                Log.e(TAG, "toSabrStreamInfos: broken media format: %s", e.getMessage());
            }
        }
        return result;
    }

    private static String extractCodecsSafe(MediaFormat fmt) {
        try {
            String codecs = MediaFormatUtils.extractCodecs(fmt);
            return TextUtils.isEmpty(codecs) ? null : codecs;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static byte[] decodePoToken(String poToken) {
        if (TextUtils.isEmpty(poToken)) {
            return null;
        }
        try {
            return android.util.Base64.decode(poToken, android.util.Base64.URL_SAFE);
        } catch (Exception e) {
            Log.e(TAG, "decodePoToken failed: %s", e.getMessage());
            return null;
        }
    }

    private MediaSource buildDashMediaSource(MediaItemFormatInfo formatInfo) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                getDashChunkSourceFactory(),
                null
        )
                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                .createMediaSource(getManifest(formatInfo));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private MediaSource buildMPDMediaSource(Uri uri, InputStream mpdContent) {
        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                getDashChunkSourceFactory(),
                null
        )
                .setLoadErrorHandlingPolicy(new DashDefaultLoadErrorHandlingPolicy())
                .createMediaSource(getManifest(uri, mpdContent));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private MediaSource buildMPDMediaSource(Uri uri, String mpdContent) {
        if (mpdContent == null || mpdContent.isEmpty()) {
            Log.e(TAG, "Can't build media source. MpdContent is null or empty. " + mpdContent);
            return null;
        }

        // Are you using FrameworkSampleSource or ExtractorSampleSource when you build your player?
        DashMediaSource dashSource = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(getMediaDataSourceFactory()),
                null
        )
                .createMediaSource(getManifest(uri, mpdContent));
        if (mTrackErrorFixer != null) {
            dashSource.addEventListener(Utils.sHandler, mTrackErrorFixer);
        }
        return dashSource;
    }

    private DashManifest getManifest(MediaItemFormatInfo formatInfo) {
        DashManifestParser3 parser = new DashManifestParser3();
        return parser.parse(formatInfo);
    }

    private DashManifest getManifest(Uri uri, InputStream mpdContent) {
        DashManifestParser parser = new StaticDashManifestParser();
        DashManifest result;
        try {
            result = parser.parse(uri, mpdContent);
        } catch (IOException e) {
            throw new IllegalStateException("Malformed mpd file:\n" + mpdContent, e);
        }
        return result;
    }

    private DashManifest getManifest(Uri uri, String mpdContent) {
        DashManifestParser parser = new StaticDashManifestParser();
        DashManifest result;
        try {
            result = parser.parse(uri, FileHelpers.toStream(mpdContent));
        } catch (IOException e) {
            throw new IllegalStateException("Malformed mpd file:\n" + mpdContent, e);
        }
        return result;
    }

    /**
     * Use OkHttp for networking
     */
    private HttpDataSource.Factory buildOkHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        OkHttpDataSource.Factory dataSourceFactory = new OkHttpDataSource.Factory(OkHttpManager.instance().getClient())
                .setUserAgent(USER_AGENT)
                .setTransferListener(bandwidthMeter);
        addCommonHeaders(dataSourceFactory);
        return dataSourceFactory;
    }

    private HttpDataSource.Factory buildCronetDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        CronetDataSource.Factory dataSourceFactory =
                new CronetDataSource.Factory(
                        new CronetEngineWrapper(CronetManager.getEngine(mContext)),
                        Executors.newSingleThreadExecutor())
                        .setTransferListener(bandwidthMeter)
                        .setConnectionTimeoutMs((int) OkHttpManager.getConnectTimeoutMs())
                        .setReadTimeoutMs((int) OkHttpManager.getReadTimeoutMs())
                        .setHandleSetCookieRequests(true)
                        .setUserAgent(USER_AGENT);
        addCommonHeaders(dataSourceFactory);
        return dataSourceFactory;
    }

    /**
     * Use built-in component for networking
     */
    private HttpDataSource.Factory buildDefaultHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setTransferListener(bandwidthMeter)
                .setConnectTimeoutMs((int) OkHttpManager.getConnectTimeoutMs())
                .setReadTimeoutMs((int) OkHttpManager.getReadTimeoutMs())
                .setAllowCrossProtocolRedirects(true); // allowCrossProtocolRedirects = true

        addCommonHeaders(dataSourceFactory); // cause troubles for some users
        return dataSourceFactory;
    }

    private static void addCommonHeaders(HttpDataSource.Factory dataSourceFactory) {
        // Doesn't work
        // Trying to fix 429 error (too many requests)
        //String authorization = RetrofitOkHttpHelper.getAuthHeaders().get("Authorization");
        //
        //if (authorization != null) {
        //    dataSourceFactory.getDefaultRequestProperties().set("Authorization", authorization);
        //}

        //HeaderManager headerManager = new HeaderManager(context);
        //HashMap<String, String> headers = headerManager.getHeaders();

        // NOTE: "Accept-Encoding" should not be set manually (gzip is added by default).

        //for (String header : headers.keySet()) {
        //    if (EXO_HEADERS.contains(header)) {
        //        dataSourceFactory.getDefaultRequestProperties().set(header, headers.get(header));
        //    }
        //}

        // Emulate browser request
        //dataSourceFactory.getDefaultRequestProperties().set("accept", "*/*");
        //dataSourceFactory.getDefaultRequestProperties().set("accept-encoding", "identity"); // Next won't work: gzip, deflate, br
        //dataSourceFactory.getDefaultRequestProperties().set("accept-language", "en-US,en;q=0.9");
        //dataSourceFactory.getDefaultRequestProperties().set("dnt", "1");
        //dataSourceFactory.getDefaultRequestProperties().set("origin", "https://www.youtube.com");
        //dataSourceFactory.getDefaultRequestProperties().set("referer", "https://www.youtube.com/");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-dest", "empty");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-mode", "cors");
        //dataSourceFactory.getDefaultRequestProperties().set("sec-fetch-site", "cross-site");

        // WARN: Compression won't work with legacy streams.
        // "Accept-Encoding" should not be set manually (gzip is added by default).
        // Otherwise you should do decompression yourself.
        // Source: https://stackoverflow.com/questions/18898959/httpurlconnection-not-decompressing-gzip/42346308#42346308
        //dataSourceFactory.getDefaultRequestProperties().set("Accept-Encoding", AppConstants.ACCEPT_ENCODING_DEFAULT);
    }

    public void setTrackErrorFixer(TrackErrorFixer trackErrorFixer) {
        mTrackErrorFixer = trackErrorFixer;
    }

    public void release() {
        mMediaDataSourceFactory = null;
    }

    @NonNull
    private DefaultSsChunkSource.Factory getSsChunkSourceFactory() {
        return new DefaultSsChunkSource.Factory(getMediaDataSourceFactory());
    }

    @NonNull
    private DashChunkSource.Factory getDashChunkSourceFactory() {
        return getDashChunkSourceFactory(getMediaDataSourceFactory());
    }

    @NonNull
    private DashChunkSource.Factory getDashChunkSourceFactory(DataSource.Factory mediaDataSourceFactory) {
        return new DefaultDashChunkSource.Factory(mediaDataSourceFactory, MAX_SEGMENTS_PER_LOAD);
    }

    private DataSource.Factory getMediaDataSourceFactory() {
        if (mMediaDataSourceFactory == null) {
            mMediaDataSourceFactory = buildDataSourceFactory(USE_BANDWIDTH_METER);
        }

        return mMediaDataSourceFactory;
    }

    // EXO: 2.10 - 2.12
    private static class StaticDashManifestParser extends DashManifestParser {
        @Override
        protected DashManifest buildMediaPresentationDescription(
                long availabilityStartTime,
                long durationMs,
                long minBufferTimeMs,
                boolean dynamic,
                long minUpdateTimeMs,
                long timeShiftBufferDepthMs,
                long suggestedPresentationDelayMs,
                long publishTimeMs,
                @Nullable ProgramInformation programInformation,
                @Nullable UtcTimingElement utcTiming,
                @Nullable ServiceDescriptionElement serviceDescription,
                @Nullable Uri location,
                List<Period> periods) {
            return new DashManifest(
                    availabilityStartTime,
                    durationMs,
                    minBufferTimeMs,
                    false,
                    minUpdateTimeMs,
                    timeShiftBufferDepthMs,
                    suggestedPresentationDelayMs,
                    publishTimeMs,
                    programInformation,
                    utcTiming,
                    serviceDescription,
                    location,
                    periods);
        }
    }

    // EXO: 2.13
    //private static class StaticDashManifestParser extends DashManifestParser {
    //    @Override
    //    protected DashManifest buildMediaPresentationDescription(
    //            long availabilityStartTime,
    //            long durationMs,
    //            long minBufferTimeMs,
    //            boolean dynamic,
    //            long minUpdateTimeMs,
    //            long timeShiftBufferDepthMs,
    //            long suggestedPresentationDelayMs,
    //            long publishTimeMs,
    //            @Nullable ProgramInformation programInformation,
    //            @Nullable UtcTimingElement utcTiming,
    //            @Nullable ServiceDescriptionElement serviceDescription,
    //            @Nullable Uri location,
    //            List<Period> periods) {
    //        return new DashManifest(
    //                availabilityStartTime,
    //                durationMs,
    //                minBufferTimeMs,
    //                false,
    //                minUpdateTimeMs,
    //                timeShiftBufferDepthMs,
    //                suggestedPresentationDelayMs,
    //                publishTimeMs,
    //                programInformation,
    //                utcTiming,
    //                serviceDescription,
    //                location,
    //                periods);
    //    }
    //}
}
