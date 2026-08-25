// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.vot;

import okhttp3.Response;
import java.io.Serializable;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.RequestBody;
import okhttp3.Request;
import okhttp3.Headers;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.Key;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Mac;
import java.util.Iterator;
import java.util.Comparator;
import java.util.function.ToLongFunction;
import java.util.function.ToIntFunction;
import kotlin.UInt$$ExternalSyntheticBackport0;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Locale;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import android.content.Context;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;

public class VotApiServiceImpl implements VotApiService
{
    private static final int AUDIO_CHUNK_SIZE = 5295308;
    private static final String AUDIO_DOWNLOAD_TYPE = "web_api_steal_sig_and_n";
    private static final String AUDIO_REQUEST_FALLBACK_FILE_ID = "web_api_get_all_generating_urls_data_from_iframe";
    private static final int AUDIO_UPLOAD_VERSION = 1;
    private static final String BASE_URL = "https://api.browser.yandex.ru";
    private static final String CHROMIUM_VERSION = "142.0.7444.291";
    private static final String COMPONENT_VERSION = "25.12.4.1198";
    private static final int CONNECT_TIMEOUT_MS;
    private static final int FAST_POLL_ATTEMPTS = 12;
    private static final int FAST_POLL_DELAY_MS;
    private static final OkHttpClient HTTP_CLIENT;
    private static final MediaType JSON_MEDIA_TYPE;
    private static final int MAX_BACKOFF_MS;
    private static final int MAX_POLL_ATTEMPTS = 3;
    private static final int MIN_RETRY_DELAY_MS;
    private static final int NO_ETA_POLL_MS;
    private static final MediaType PROTOBUF_MEDIA_TYPE;
    private static final int READ_TIMEOUT_MS;
    private static final String SHARED_HMAC = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String TAG = "VotApiServiceImpl";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36";
    private static final int WRITE_TIMEOUT_MS;
    private Session mSession;
    private String mSessionBaseUrl;
    private final VotSettings mSettings;
    
    static {
        final int n = CONNECT_TIMEOUT_MS = (int)TimeUnit.SECONDS.toMillis(20L);
        final int n2 = READ_TIMEOUT_MS = (int)TimeUnit.SECONDS.toMillis(120L);
        final int n3 = WRITE_TIMEOUT_MS = (int)TimeUnit.SECONDS.toMillis(180L);
        MIN_RETRY_DELAY_MS = (int)TimeUnit.SECONDS.toMillis(1L);
        FAST_POLL_DELAY_MS = (int)TimeUnit.SECONDS.toMillis(1L);
        MAX_BACKOFF_MS = (int)TimeUnit.SECONDS.toMillis(15L);
        NO_ETA_POLL_MS = (int)TimeUnit.SECONDS.toMillis(5L);
        PROTOBUF_MEDIA_TYPE = MediaType.parse("application/x-protobuf");
        JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
        HTTP_CLIENT = new OkHttpClient.Builder().connectTimeout(n, TimeUnit.MILLISECONDS).readTimeout(n2, TimeUnit.MILLISECONDS).writeTimeout(n3, TimeUnit.MILLISECONDS).build();
    }
    
    public VotApiServiceImpl(final Context context) {
        this.mSettings = VotSettings.instance(context);
    }
    
    private static String appendAttemptMessage(final String s, final int n, final int n2) {
        if (s != null && !s.isEmpty()) {
            return s;
        }
        return null;
    }
    
    private int audioFormatPriority(final MediaFormat mediaFormat, final String anObject) {
        final String sanitizeLanguage = sanitizeLanguage(mediaFormat.getLanguage(), "auto");
        String lowerCase;
        if (mediaFormat.getLanguage() != null) {
            lowerCase = mediaFormat.getLanguage().toLowerCase(Locale.US);
        }
        else {
            lowerCase = "";
        }
        final String lowerCase2 = mediaFormat.getMimeType().toLowerCase(Locale.US);
        int n = 0;
        Label_0099: {
            if (!"auto".equals(anObject)) {
                if (anObject.equals(sanitizeLanguage)) {
                    n = -40;
                    break Label_0099;
                }
                if (!"auto".equals(sanitizeLanguage)) {
                    n = 40;
                    break Label_0099;
                }
            }
            n = 0;
        }
        int n2 = 0;
        Label_0130: {
            if (!lowerCase.contains("dubbed")) {
                n2 = n;
                if (!lowerCase.contains("translation")) {
                    break Label_0130;
                }
            }
            n2 = n + 60;
        }
        int n3 = n2;
        if (mediaFormat.isDrc()) {
            n3 = n2 + 25;
        }
        if (!lowerCase2.contains("audio/mp4") && !lowerCase2.contains("mp4a")) {
            if (!lowerCase2.contains("audio/webm") && !lowerCase2.contains("opus")) {
                n3 += 20;
            }
            else {
                n3 += 10;
            }
        }
        else {
            n3 += 0;
        }
        return n3;
    }
    
    private static String buildDebugLine(final VotProtoUtils.TranslationResponse translationResponse, final String str) {
        if (translationResponse == null) {
            return str;
        }
        final StringBuilder sb = new StringBuilder();
        if (str != null && !str.isEmpty()) {
            sb.append(str);
            sb.append(' ');
        }
        sb.append(statusToString(translationResponse.status));
        if (translationResponse.remainingTime >= 0) {
            sb.append(" eta=");
            sb.append(translationResponse.remainingTime);
            sb.append('s');
        }
        if (translationResponse.shouldRetry >= 0) {
            sb.append(" retry=");
            sb.append(translationResponse.shouldRetry);
            sb.append('s');
        }
        if (translationResponse.translationId != null && !translationResponse.translationId.isEmpty()) {
            sb.append(" id=");
            sb.append(translationResponse.translationId);
        }
        if (translationResponse.url != null && !translationResponse.url.isEmpty()) {
            sb.append(" url=ok");
        }
        if (translationResponse.isLivelyVoice) {
            sb.append(" lively=1");
        }
        return sb.toString();
    }
    
    private static VotProtoUtils.TranslationResponse buildFailedResponse(final String message) {
        final VotProtoUtils.TranslationResponse translationResponse = new VotProtoUtils.TranslationResponse();
        translationResponse.status = 0;
        translationResponse.message = message;
        translationResponse.translationId = null;
        translationResponse.remainingTime = 0;
        translationResponse.shouldRetry = -1;
        translationResponse.language = null;
        translationResponse.url = null;
        return translationResponse;
    }
    
    private static String buildFileId(final int i, final String str) {
        final StringBuilder sb = new StringBuilder("{\"downloadType\":\"web_api_steal_sig_and_n\",\"itag\":");
        sb.append(i);
        sb.append(",\"minChunkSize\":5295308,\"fileSize\":\"");
        sb.append(str);
        sb.append("\"}");
        return sb.toString();
    }
    
    private static String buildSecChUa() {
        final StringBuilder sb = new StringBuilder("\"Chromium\";v=\"134\", \"YaBrowser\";v=\"");
        sb.append("25.12");
        sb.append("\", \"Not?A_Brand\";v=\"24\", \"Yowser\";v=\"2.5\"");
        return sb.toString();
    }
    
    private static String buildSecChUaFull() {
        return "\"Chromium\";v=\"142.0.7444.291\", \"YaBrowser\";v=\"25.12.4.1198\", \"Not?A_Brand\";v=\"24.0.0.0\", \"Yowser\";v=\"2.5\"";
    }
    
    private Map<String, String> buildVtransHeaders(final String s, final String str, final byte[] array) throws Exception {
        final Session session = this.getSession(s);
        final StringBuilder sb = new StringBuilder();
        sb.append(session.uuid);
        sb.append(":");
        sb.append(str);
        sb.append(":25.12.4.1198");
        final String string = sb.toString();
        final String hmacSha256Hex = hmacSha256Hex(string.getBytes(StandardCharsets.UTF_8));
        final HashMap hashMap = new HashMap();
        hashMap.put("Vtrans-Signature", hmacSha256Hex(array));
        hashMap.put("Sec-Vtrans-Sk", session.secretKey);
        final StringBuilder sb2 = new StringBuilder();
        sb2.append(hmacSha256Hex);
        sb2.append(":");
        sb2.append(string);
        hashMap.put("Sec-Vtrans-Token", sb2.toString());
        hashMap.put("sec-ch-ua", buildSecChUa());
        hashMap.put("sec-ch-ua-full-version-list", buildSecChUaFull());
        hashMap.put("Sec-Fetch-Mode", "no-cors");
        return hashMap;
    }
    
    private void clearSession() {
        synchronized (this) {
            this.mSession = null;
            this.mSessionBaseUrl = null;
        }
    }
    
    private List<MediaFormat> findAudioFormatsForUpload(final List<MediaFormat> list, String sanitizeLanguage) {
        if (list == null) {
            return Collections.emptyList();
        }
        final ArrayList list2 = new ArrayList();
        final HashSet set = new HashSet();
        sanitizeLanguage = sanitizeLanguage(sanitizeLanguage, "auto");
        for (final MediaFormat mediaFormat : list) {
            if (mediaFormat != null && mediaFormat.getMimeType() != null) {
                if (mediaFormat.getUrl() == null) {
                    continue;
                }
                if (!mediaFormat.getMimeType().toLowerCase(Locale.US).startsWith("audio")) {
                    continue;
                }
                if (!set.add(mediaFormat.getUrl())) {
                    continue;
                }
                list2.add(mediaFormat);
            }
        }
        list2.sort(UInt$$ExternalSyntheticBackport0.m(new VotApiServiceImpl$$ExternalSyntheticLambda1(this, sanitizeLanguage)).thenComparingLong(new VotApiServiceImpl$$ExternalSyntheticLambda2()).thenComparingLong(new VotApiServiceImpl$$ExternalSyntheticLambda3()));
        return list2;
    }
    
    private static String generateSessionUuid() {
        final char[] charArray = "0123456789ABCDEF".toCharArray();
        final char[] value = new char[32];
        for (int i = 0; i < 32; ++i) {
            final double random = Math.random();
            final double v = charArray.length;
            Double.isNaN(v);
            value[i] = charArray[(int)(random * v)];
        }
        return new String(value);
    }
    
    private Session getSession(final String mSessionBaseUrl) throws Exception {
        synchronized (this) {
            final long n = System.currentTimeMillis() / 1000L;
            if (this.mSession != null && mSessionBaseUrl.equals(this.mSessionBaseUrl) && this.mSession.createdAtSec + this.mSession.expiresSec > n) {
                return this.mSession;
            }
            final String generateSessionUuid = generateSessionUuid();
            final byte[] encodeSessionRequest = VotProtoUtils.encodeSessionRequest(generateSessionUuid, "video-translation");
            final HashMap hashMap = new HashMap();
            hashMap.put("Vtrans-Signature", hmacSha256Hex(encodeSessionRequest));
            hashMap.put("sec-ch-ua", buildSecChUa());
            hashMap.put("sec-ch-ua-full-version-list", buildSecChUaFull());
            hashMap.put("Sec-Fetch-Mode", "no-cors");
            final RawResponse requestBinary = this.requestBinary(mSessionBaseUrl, "/session/create", "POST", encodeSessionRequest, hashMap);
            if (requestBinary.code == 200) {
                final VotProtoUtils.SessionResponse decodeSessionResponse = VotProtoUtils.decodeSessionResponse(requestBinary.body);
                final Session mSession = new Session(generateSessionUuid, decodeSessionResponse.secretKey, decodeSessionResponse.expires, n);
                this.mSession = mSession;
                this.mSessionBaseUrl = mSessionBaseUrl;
                return mSession;
            }
            final StringBuilder sb = new StringBuilder("Failed to create Yandex session: HTTP ");
            sb.append(requestBinary.code);
            throw new IllegalStateException(sb.toString());
        }
    }
    
    private static String hmacSha256Hex(byte[] doFinal) throws Exception {
        final Mac instance = Mac.getInstance("HmacSHA256");
        instance.init(new SecretKeySpec("bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        doFinal = instance.doFinal(doFinal);
        final StringBuilder sb = new StringBuilder(doFinal.length * 2);
        for (int length = doFinal.length, i = 0; i < length; ++i) {
            sb.append(String.format(Locale.US, "%02x", doFinal[i]));
        }
        return sb.toString();
    }
    
    private static boolean isSessionRequiredMessage(String lowerCase) {
        boolean b = false;
        if (lowerCase == null) {
            return false;
        }
        lowerCase = lowerCase.toLowerCase(Locale.US);
        if (lowerCase.contains("session_required") || lowerCase.contains("session required")) {
            b = true;
        }
        return b;
    }
    
    private static boolean isWorkerBaseUrl(final String anotherString) {
        return anotherString != null && !"https://api.browser.yandex.ru".equalsIgnoreCase(anotherString);
    }
    
    private static int parseInt(final String s, final int n) {
        int int1 = n;
        if (s == null) {
            return int1;
        }
        try {
            int1 = Integer.parseInt(s);
            return int1;
        }
        catch (final Exception ex) {
            int1 = n;
            return int1;
        }
    }
    
    private static long parseLong(final String s, final long n) {
        long long1 = n;
        if (s == null) {
            return long1;
        }
        try {
            long1 = Long.parseLong(s);
            return long1;
        }
        catch (final Exception ex) {
            long1 = n;
            return long1;
        }
    }
    
    private static byte[] readFullyBytes(final InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return new byte[0];
        }
        try {
            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try {
                final byte[] array = new byte[16384];
                while (true) {
                    final int read = inputStream.read(array);
                    if (read == -1) {
                        break;
                    }
                    byteArrayOutputStream.write(array, 0, read);
                }
                final byte[] byteArray = byteArrayOutputStream.toByteArray();
                byteArrayOutputStream.close();
                if (inputStream != null) {
                    inputStream.close();
                }
                return byteArray;
            }
            finally {
                try {
                    byteArrayOutputStream.close();
                }
                finally {
                    final Throwable t;
                    final Throwable t2;
                    UInt$$ExternalSyntheticBackport0.m(t, t2);
                }
            }
        }
        finally {
            Label_0096: {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                        break Label_0096;
                    }
                    finally {
                        final Throwable t3;
                        final Throwable t4;
                        UInt$$ExternalSyntheticBackport0.m(t3, t4);
                    }
                    break Label_0096;
                }
                break Label_0096;
            }
            while (true) {}
        }
    }
    
    private static void reportProgress(final ProgressListener progressListener, final String s) {
        if (progressListener != null && s != null && !s.isEmpty()) {
            progressListener.onProgress(s);
        }
    }
    
    private RawResponse requestBinary(final String str, String execute, final String anotherString, final byte[] array, final Map<String, String> map) throws Exception {
        final HashMap hashMap = new HashMap();
        hashMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36");
        hashMap.put("Accept", "application/x-protobuf");
        hashMap.put("Accept-Language", "en");
        hashMap.put("Content-Type", "application/x-protobuf");
        hashMap.put("Cache-Control", "no-cache");
        hashMap.put("Pragma", "no-cache");
        if (map != null) {
            hashMap.putAll(map);
        }
        if (isWorkerBaseUrl(str)) {
            return this.requestBinaryViaWorker(str, execute, anotherString, array, hashMap);
        }
        final Headers.Builder builder = new Headers.Builder();
        for (final Map.Entry<String, V> entry : hashMap.entrySet()) {
            builder.set(entry.getKey(), (String)entry.getValue());
        }
        final Request.Builder builder2 = new Request.Builder();
        final StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(execute);
        final Request.Builder headers = builder2.url(sb.toString()).headers(builder.build());
        byte[] array2;
        if (array != null) {
            array2 = array;
        }
        else {
            array2 = new byte[0];
        }
        if ("POST".equalsIgnoreCase(anotherString)) {
            headers.post(RequestBody.create(VotApiServiceImpl.PROTOBUF_MEDIA_TYPE, array2));
        }
        else if ("PUT".equalsIgnoreCase(anotherString)) {
            headers.put(RequestBody.create(VotApiServiceImpl.PROTOBUF_MEDIA_TYPE, array2));
        }
        else if ("GET".equalsIgnoreCase(anotherString)) {
            headers.get();
        }
        else {
            RequestBody create;
            if (array != null) {
                create = RequestBody.create(VotApiServiceImpl.PROTOBUF_MEDIA_TYPE, array2);
            }
            else {
                create = null;
            }
            headers.method(anotherString, create);
        }
        execute = (String)VotApiServiceImpl.HTTP_CLIENT.newCall(headers.build()).execute();
        try {
            byte[] bytes;
            if (((Response)execute).body() != null) {
                bytes = ((Response)execute).body().bytes();
            }
            else {
                bytes = new byte[0];
            }
            final RawResponse rawResponse = new RawResponse(((Response)execute).code(), bytes, new String(bytes, StandardCharsets.UTF_8));
            if (execute != null) {
                ((Response)execute).close();
            }
            return rawResponse;
        }
        finally {
            Label_0452: {
                if (execute != null) {
                    try {
                        ((Response)execute).close();
                        break Label_0452;
                    }
                    finally {
                        final Throwable t;
                        UInt$$ExternalSyntheticBackport0.m((Throwable)str, t);
                    }
                    break Label_0452;
                }
                break Label_0452;
            }
            while (true) {}
        }
    }
    
    private RawResponse requestBinaryViaWorker(final String str, String execute, final String s, byte[] array, final Map<String, String> map) throws Exception {
        final JSONObject jsonObject = new JSONObject();
        final JSONObject jsonObject2 = new JSONObject();
        for (final Map.Entry<String, V> entry : map.entrySet()) {
            jsonObject2.put((String)entry.getKey(), (Object)entry.getValue());
        }
        jsonObject.put("headers", (Object)jsonObject2);
        final JSONArray jsonArray = new JSONArray();
        if (array == null) {
            array = new byte[0];
        }
        for (int length = array.length, i = 0; i < length; ++i) {
            jsonArray.put(array[i] & 0xFF);
        }
        jsonObject.put("body", (Object)jsonArray);
        final RequestBody create = RequestBody.create(VotApiServiceImpl.JSON_MEDIA_TYPE, jsonObject.toString().getBytes(StandardCharsets.UTF_8));
        final Request.Builder builder = new Request.Builder();
        final StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(execute);
        execute = (String)VotApiServiceImpl.HTTP_CLIENT.newCall(builder.url(sb.toString()).addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36").addHeader("Accept", "application/x-protobuf").addHeader("Accept-Language", "en").addHeader("Content-Type", "application/json").addHeader("Cache-Control", "no-cache").addHeader("Pragma", "no-cache").addHeader("sec-ch-ua", buildSecChUa()).addHeader("sec-ch-ua-full-version-list", buildSecChUaFull()).addHeader("Sec-Fetch-Mode", "no-cors").method(s, create).build()).execute();
        try {
            byte[] bytes;
            if (((Response)execute).body() != null) {
                bytes = ((Response)execute).body().bytes();
            }
            else {
                bytes = new byte[0];
            }
            final RawResponse rawResponse = new RawResponse(((Response)execute).code(), bytes, new String(bytes, StandardCharsets.UTF_8));
            if (execute != null) {
                ((Response)execute).close();
            }
            return rawResponse;
        }
        finally {
            Label_0403: {
                if (execute != null) {
                    try {
                        ((Response)execute).close();
                        break Label_0403;
                    }
                    finally {
                        final Throwable t;
                        UInt$$ExternalSyntheticBackport0.m((Throwable)str, t);
                    }
                    break Label_0403;
                }
                break Label_0403;
            }
            while (true) {}
        }
    }
    
    private void requestEmptyAudioFallback(final String s, final String s2, final String s3) throws Exception {
        if (s3 != null && !s3.isEmpty()) {
            this.uploadSingleAudio(s, s2, s3, "web_api_get_all_generating_urls_data_from_iframe", new byte[0]);
            return;
        }
        throw new IllegalStateException("Fallback audio request needs translationId");
    }
    
    private void requestFailAudio(final String s, final String s2) throws Exception {
        final JSONObject jsonObject = new JSONObject();
        jsonObject.put("video_url", (Object)s2);
        final RawResponse requestJson = this.requestJson(s, "/video-translation/fail-audio-js", "PUT", jsonObject.toString().getBytes(StandardCharsets.UTF_8));
        if (requestJson.code == 200) {
            return;
        }
        final StringBuilder sb = new StringBuilder("Failed fail-audio request: HTTP ");
        sb.append(requestJson.code);
        throw new IllegalStateException(sb.toString());
    }
    
    private RawResponse requestJson(final String str, String execute, final String s, byte[] array) throws Exception {
        if (isWorkerBaseUrl(str)) {
            return this.requestJsonViaWorker(str, execute, s, array);
        }
        final MediaType json_MEDIA_TYPE = VotApiServiceImpl.JSON_MEDIA_TYPE;
        if (array == null) {
            array = new byte[0];
        }
        final RequestBody create = RequestBody.create(json_MEDIA_TYPE, array);
        final Request.Builder builder = new Request.Builder();
        final StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(execute);
        execute = (String)VotApiServiceImpl.HTTP_CLIENT.newCall(builder.url(sb.toString()).addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36").addHeader("Accept", "application/json").addHeader("Accept-Language", "en").addHeader("Content-Type", "application/json").addHeader("Cache-Control", "no-cache").addHeader("Pragma", "no-cache").method(s, create).build()).execute();
        try {
            byte[] bytes;
            if (((Response)execute).body() != null) {
                bytes = ((Response)execute).body().bytes();
            }
            else {
                bytes = new byte[0];
            }
            final RawResponse rawResponse = new RawResponse(((Response)execute).code(), bytes, new String(bytes, StandardCharsets.UTF_8));
            if (execute != null) {
                ((Response)execute).close();
            }
            return rawResponse;
        }
        finally {
            if (execute != null) {
                try {
                    ((Response)execute).close();
                }
                finally {
                    final Throwable t;
                    UInt$$ExternalSyntheticBackport0.m((Throwable)str, t);
                }
            }
        }
    }
    
    private RawResponse requestJsonViaWorker(final String str, String execute, final String s, final byte[] bytes) throws Exception {
        final JSONObject jsonObject = new JSONObject();
        final JSONObject jsonObject2 = new JSONObject();
        jsonObject2.put("User-Agent", (Object)"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36");
        jsonObject2.put("Accept", (Object)"application/json");
        jsonObject2.put("Accept-Language", (Object)"en");
        jsonObject2.put("Content-Type", (Object)"application/json");
        jsonObject2.put("Cache-Control", (Object)"no-cache");
        jsonObject2.put("Pragma", (Object)"no-cache");
        jsonObject2.put("sec-ch-ua", (Object)buildSecChUa());
        jsonObject2.put("sec-ch-ua-full-version-list", (Object)buildSecChUaFull());
        jsonObject2.put("Sec-Fetch-Mode", (Object)"no-cors");
        jsonObject.put("headers", (Object)jsonObject2);
        Object null;
        if (bytes != null) {
            null = new String(bytes, StandardCharsets.UTF_8);
        }
        else {
            null = JSONObject.NULL;
        }
        jsonObject.put("body", null);
        final RequestBody create = RequestBody.create(VotApiServiceImpl.JSON_MEDIA_TYPE, jsonObject.toString().getBytes(StandardCharsets.UTF_8));
        final Request.Builder builder = new Request.Builder();
        final StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(execute);
        execute = (String)VotApiServiceImpl.HTTP_CLIENT.newCall(builder.url(sb.toString()).addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36").addHeader("Accept", "application/json").addHeader("Accept-Language", "en").addHeader("Content-Type", "application/json").addHeader("Cache-Control", "no-cache").addHeader("Pragma", "no-cache").addHeader("sec-ch-ua", buildSecChUa()).addHeader("sec-ch-ua-full-version-list", buildSecChUaFull()).addHeader("Sec-Fetch-Mode", "no-cors").method(s, create).build()).execute();
        try {
            byte[] bytes2;
            if (((Response)execute).body() != null) {
                bytes2 = ((Response)execute).body().bytes();
            }
            else {
                bytes2 = new byte[0];
            }
            final RawResponse rawResponse = new RawResponse(((Response)execute).code(), bytes2, new String(bytes2, StandardCharsets.UTF_8));
            if (execute != null) {
                ((Response)execute).close();
            }
            return rawResponse;
        }
        finally {
            if (execute != null) {
                try {
                    ((Response)execute).close();
                }
                finally {
                    final Throwable t;
                    UInt$$ExternalSyntheticBackport0.m((Throwable)str, t);
                }
            }
        }
    }
    
    private String resolveBaseUrl(final boolean b) {
        if (b && this.mSettings.isAudioProxyEnabled()) {
            final StringBuilder sb = new StringBuilder("https://");
            sb.append(this.mSettings.getAudioProxyHost());
            return sb.toString();
        }
        return "https://api.browser.yandex.ru";
    }
    
    private boolean resolveUseLivelyVoice(final String anObject, final String anObject2, final boolean b) {
        return b && "ru".equals(anObject2) && "en".equals(anObject);
    }
    
    private VotProtoUtils.TranslationResponse runTranslationFlow(final String s, final int n, String tag, final String s2, final MediaItemFormatInfo mediaItemFormatInfo, final String s3, final ProgressListener progressListener, final boolean b) throws Exception {
        int n2 = 0;
        int j;
        for (int i = 0; i < 3; i = j) {
            final boolean resolveUseLivelyVoice = this.resolveUseLivelyVoice(tag, s2, b);
            final String resolveBaseUrl = this.resolveBaseUrl(resolveUseLivelyVoice);
            final byte[] encodeTranslationRequest = VotProtoUtils.encodeTranslationRequest(s, n, tag, s2, s3, true, resolveUseLivelyVoice);
            final RawResponse requestBinary = this.requestBinary(resolveBaseUrl, "/video-translation/translate", "POST", encodeTranslationRequest, this.buildVtransHeaders(resolveBaseUrl, "/video-translation/translate", encodeTranslationRequest));
            final StringBuilder sb = new StringBuilder("poll=");
            j = i + 1;
            sb.append(j);
            sb.append(" http=");
            sb.append(requestBinary.code);
            reportProgress(progressListener, sb.toString());
            int n6 = 0;
            Label_1107: {
                int n4 = 0;
                int n5 = 0;
                Label_0662: {
                    if (requestBinary.code != 200) {
                        final String tag2 = VotApiServiceImpl.TAG;
                        final StringBuilder sb2 = new StringBuilder("Yandex translate HTTP ");
                        sb2.append(requestBinary.code);
                        sb2.append(": ");
                        sb2.append(requestBinary.textBody);
                        Log.w(tag2, (Object)sb2.toString(), new Object[0]);
                        if ((requestBinary.code != 401 && requestBinary.code != 403) || i >= 2) {
                            final StringBuilder sb3 = new StringBuilder("translate_http_error ");
                            sb3.append(requestBinary.code);
                            reportProgress(progressListener, sb3.toString());
                            final StringBuilder sb4 = new StringBuilder("attempt=");
                            sb4.append(j);
                            sb4.append(" http=");
                            sb4.append(requestBinary.code);
                            sb4.append(" ");
                            sb4.append(safeText(requestBinary.textBody));
                            return buildFailedResponse(sb4.toString());
                        }
                        this.clearSession();
                        final StringBuilder sb5 = new StringBuilder("translate_http_auth_error ");
                        sb5.append(requestBinary.code);
                        sb5.append(" retry_with_new_session");
                        reportProgress(progressListener, sb5.toString());
                        final int n3 = j;
                        n4 = n2;
                        n5 = n3;
                    }
                    else {
                        final VotProtoUtils.TranslationResponse decodeTranslationResponse = VotProtoUtils.decodeTranslationResponse(requestBinary.body);
                        Serializable translationId = new StringBuilder("poll=");
                        ((StringBuilder)translationId).append(j);
                        reportProgress(progressListener, buildDebugLine(decodeTranslationResponse, ((StringBuilder)translationId).toString()));
                        final int status = decodeTranslationResponse.status;
                        Label_0504: {
                            if (status != 0) {
                                if (status != 1) {
                                    if (status == 2 || status == 3) {
                                        decodeTranslationResponse.message = appendAttemptMessage(decodeTranslationResponse.message, i, decodeTranslationResponse.status);
                                        final StringBuilder sb6 = new StringBuilder("waiting eta=");
                                        sb6.append(decodeTranslationResponse.remainingTime);
                                        sb6.append("s");
                                        reportProgress(progressListener, sb6.toString());
                                        return decodeTranslationResponse;
                                    }
                                    if (status != 5) {
                                        if (status == 6) {
                                            if (n2 == 0) {
                                                if (mediaItemFormatInfo != null) {
                                                    try {
                                                        translationId = decodeTranslationResponse.translationId;
                                                        try {
                                                            this.uploadYoutubeAudio(resolveBaseUrl, s, (String)translationId, mediaItemFormatInfo, tag, progressListener);
                                                            reportProgress(progressListener, "audio_uploaded");
                                                        }
                                                        catch (final Exception translationId) {}
                                                    }
                                                    catch (final Exception ex) {}
                                                    final StringBuilder sb7 = new StringBuilder("audio_upload_failed ");
                                                    sb7.append(safeText(((Throwable)translationId).getMessage()));
                                                    reportProgress(progressListener, sb7.toString());
                                                    this.requestFailAudio(resolveBaseUrl, s);
                                                    this.requestEmptyAudioFallback(resolveBaseUrl, s, decodeTranslationResponse.translationId);
                                                    reportProgress(progressListener, "audio_fallback_sent_after_upload_error");
                                                }
                                                else {
                                                    this.requestFailAudio(resolveBaseUrl, s);
                                                    this.requestEmptyAudioFallback(resolveBaseUrl, s, decodeTranslationResponse.translationId);
                                                    reportProgress(progressListener, "audio_fallback_sent");
                                                }
                                                if (i < 2) {
                                                    n5 = j;
                                                    n4 = 1;
                                                    break Label_0662;
                                                }
                                            }
                                            decodeTranslationResponse.remainingTime = Math.max(1, decodeTranslationResponse.remainingTime);
                                            decodeTranslationResponse.message = appendAttemptMessage(decodeTranslationResponse.message, i, decodeTranslationResponse.status);
                                            return decodeTranslationResponse;
                                        }
                                        if (status != 7) {
                                            decodeTranslationResponse.message = appendAttemptMessage(decodeTranslationResponse.message, i, decodeTranslationResponse.status);
                                            return decodeTranslationResponse;
                                        }
                                        break Label_0504;
                                    }
                                }
                                if (decodeTranslationResponse.url != null && VotUrlValidator.validate(decodeTranslationResponse.url)) {
                                    decodeTranslationResponse.message = appendAttemptMessage(decodeTranslationResponse.message, i, decodeTranslationResponse.status);
                                    return decodeTranslationResponse;
                                }
                                tag = VotApiServiceImpl.TAG;
                                final StringBuilder sb8 = new StringBuilder("Yandex returned invalid audio URL on attempt ");
                                sb8.append(j);
                                sb8.append(", status=");
                                sb8.append(statusToString(decodeTranslationResponse.status));
                                sb8.append(", translationId=");
                                sb8.append(decodeTranslationResponse.translationId);
                                Log.w(tag, (Object)sb8.toString(), new Object[0]);
                                decodeTranslationResponse.message = "Invalid translation audio URL";
                                final StringBuilder sb9 = new StringBuilder("invalid_audio_url poll=");
                                sb9.append(j);
                                reportProgress(progressListener, sb9.toString());
                                return decodeTranslationResponse;
                            }
                        }
                        if (decodeTranslationResponse.status == 0 && isSessionRequiredMessage(decodeTranslationResponse.message)) {
                            this.clearSession();
                            if (i < 2) {
                                reportProgress(progressListener, "failed_session_required retry_with_new_session");
                                n6 = n2;
                                break Label_1107;
                            }
                        }
                        if (decodeTranslationResponse.status == 0 && decodeTranslationResponse.shouldRetry > 0 && i < 2) {
                            final StringBuilder sb10 = new StringBuilder("server_retry eta=");
                            sb10.append(decodeTranslationResponse.shouldRetry);
                            sb10.append("s");
                            reportProgress(progressListener, sb10.toString());
                            decodeTranslationResponse.remainingTime = Math.max(decodeTranslationResponse.remainingTime, decodeTranslationResponse.shouldRetry);
                            decodeTranslationResponse.message = appendAttemptMessage(decodeTranslationResponse.message, i, decodeTranslationResponse.status);
                            return decodeTranslationResponse;
                        }
                        if (decodeTranslationResponse.status == 7) {
                            this.clearSession();
                            if (i < 2) {
                                reportProgress(progressListener, "session_required retry_with_new_session");
                                n6 = n2;
                                break Label_1107;
                            }
                        }
                        decodeTranslationResponse.message = appendAttemptMessage(decodeTranslationResponse.message, i, decodeTranslationResponse.status);
                        return decodeTranslationResponse;
                    }
                }
                n6 = n4;
                j = n5;
            }
            n2 = n6;
        }
        return buildFailedResponse("No response from translation API");
    }
    
    private VotProtoUtils.TranslationResponse runTranslationWithFallbacks(final String s, final int n, final String s2, final String s3, final MediaItemFormatInfo mediaItemFormatInfo, final String s4, final ProgressListener progressListener, boolean b) throws Exception {
        VotProtoUtils.TranslationResponse translationResponse = this.runTranslationFlow(s, n, s2, s3, mediaItemFormatInfo, s4, progressListener, b);
        if (shouldRetryWithoutLivelyVoice(translationResponse, b)) {
            final StringBuilder sb = new StringBuilder("retry_without_lively from=");
            sb.append(s2);
            reportProgress(progressListener, sb.toString());
            b = false;
            translationResponse = this.runTranslationFlow(s, n, s2, s3, mediaItemFormatInfo, s4, progressListener, false);
        }
        VotProtoUtils.TranslationResponse translationResponse2 = translationResponse;
        if (shouldRetryWithAuto(translationResponse, s2, s3)) {
            final StringBuilder sb2 = new StringBuilder("retry_with_auto from=");
            sb2.append(s2);
            reportProgress(progressListener, sb2.toString());
            if (shouldRetryWithoutLivelyVoice(translationResponse2 = this.runTranslationFlow(s, n, "auto", s3, mediaItemFormatInfo, s4, progressListener, b), b)) {
                reportProgress(progressListener, "retry_with_auto_without_lively");
                translationResponse2 = this.runTranslationFlow(s, n, "auto", s3, mediaItemFormatInfo, s4, progressListener, false);
            }
        }
        return translationResponse2;
    }
    
    private static String safeText(String trim) {
        if (trim == null) {
            return "";
        }
        trim = trim.replace('\n', ' ').replace('\r', ' ').trim();
        if (trim.length() <= 160) {
            return trim;
        }
        return trim.substring(0, 160);
    }
    
    private static String sanitizeLanguage(String trim, final String s) {
        if (trim == null) {
            return s;
        }
        final String replace = trim.trim().toLowerCase(Locale.US).replace('\u2010', '-').replace('\u2011', '-').replace('\u2012', '-').replace('\u2013', '-').replace('\u2014', '-');
        final int index = replace.indexOf(40);
        trim = replace;
        if (index >= 0) {
            trim = replace.substring(0, index).trim();
        }
        int endIndex;
        if ((endIndex = trim.indexOf(45)) < 0) {
            endIndex = trim.indexOf(95);
        }
        String substring = trim;
        if (endIndex > 0) {
            substring = trim.substring(0, endIndex);
        }
        if (!substring.matches("[a-z]{2,3}")) {
            return s;
        }
        return substring;
    }
    
    private static boolean shouldRetryWithAuto(final VotProtoUtils.TranslationResponse translationResponse, final String anObject, final String anObject2) {
        final boolean equals = "auto".equals(anObject);
        boolean b = false;
        if (equals || anObject.equals(anObject2)) {
            return false;
        }
        if (translationResponse == null) {
            return true;
        }
        if (translationResponse.status != 0 && translationResponse.status != 7) {
            String lowerCase;
            if (translationResponse.message != null) {
                lowerCase = translationResponse.message.toLowerCase(Locale.US);
            }
            else {
                lowerCase = "";
            }
            if (lowerCase.contains("language") || lowerCase.contains("translate") || lowerCase.contains("unsupported")) {
                b = true;
            }
            return b;
        }
        return true;
    }
    
    private static boolean shouldRetryWithoutLivelyVoice(final VotProtoUtils.TranslationResponse translationResponse, final boolean b) {
        boolean b3;
        final boolean b2 = b3 = false;
        if (b) {
            if (translationResponse == null) {
                b3 = b2;
            }
            else {
                String lowerCase;
                if (translationResponse.message != null) {
                    lowerCase = translationResponse.message.toLowerCase(Locale.US);
                }
                else {
                    lowerCase = "";
                }
                if (translationResponse.status != 7 && !lowerCase.contains("session_required") && !lowerCase.contains("session required") && !lowerCase.contains("http=401") && !lowerCase.contains("http=402") && !lowerCase.contains("http=403") && !lowerCase.contains("payment required") && !lowerCase.contains("\u0436\u0438\u0432\u044b\u0435 \u0433\u043e\u043b\u043e\u0441\u0430") && !lowerCase.contains("live voices") && !lowerCase.contains("lively voices unavailable")) {
                    b3 = b2;
                    if (!lowerCase.contains("lively voice unavailable")) {
                        return b3;
                    }
                }
                b3 = true;
            }
        }
        return b3;
    }
    
    private static void sleepBeforeRetry(int fast_POLL_DELAY_MS, final int n) {
        int n2;
        if (fast_POLL_DELAY_MS > 0) {
            n2 = Math.max(VotApiServiceImpl.MIN_RETRY_DELAY_MS, Math.min(VotApiServiceImpl.MAX_BACKOFF_MS, fast_POLL_DELAY_MS * 1000));
        }
        else {
            n2 = VotApiServiceImpl.NO_ETA_POLL_MS;
        }
        int n3 = n2;
        if (n < 12) {
            n3 = n2;
            if (fast_POLL_DELAY_MS > 0) {
                n3 = n2;
                if (fast_POLL_DELAY_MS <= 3) {
                    fast_POLL_DELAY_MS = VotApiServiceImpl.FAST_POLL_DELAY_MS;
                    if ((n3 = n2) > fast_POLL_DELAY_MS) {
                        n3 = fast_POLL_DELAY_MS;
                    }
                }
            }
        }
        final long millis = n3;
        try {
            Thread.sleep(millis);
        }
        catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
    
    private static String statusToString(final int n) {
        if (n == 0) {
            return "FAILED";
        }
        if (n == 1) {
            return "FINISHED";
        }
        if (n == 2) {
            return "WAITING";
        }
        if (n == 3) {
            return "LONG_WAITING";
        }
        if (n == 5) {
            return "PART_CONTENT";
        }
        if (n == 6) {
            return "AUDIO_REQUESTED";
        }
        if (n != 7) {
            return null;
        }
        return "SESSION_REQUIRED";
    }
    
    private void uploadChunkAudio(final String s, final String s2, final String s3, final String s4, final int n, final int n2, final byte[] array) throws Exception {
        final byte[] encodeChunkAudioRequest = VotProtoUtils.encodeChunkAudioRequest(s2, s3, s4, n, n2, 1, array);
        final RawResponse requestBinary = this.requestBinary(s, "/video-translation/audio", "PUT", encodeChunkAudioRequest, this.buildVtransHeaders(s, "/video-translation/audio", encodeChunkAudioRequest));
        if (requestBinary.code == 200) {
            return;
        }
        final StringBuilder sb = new StringBuilder("Failed to upload audio chunk: HTTP ");
        sb.append(requestBinary.code);
        throw new IllegalStateException(sb.toString());
    }
    
    private void uploadSingleAudio(final String s, final String s2, final String s3, final String s4, final byte[] array) throws Exception {
        final byte[] encodeSingleAudioRequest = VotProtoUtils.encodeSingleAudioRequest(s2, s3, s4, array);
        final RawResponse requestBinary = this.requestBinary(s, "/video-translation/audio", "PUT", encodeSingleAudioRequest, this.buildVtransHeaders(s, "/video-translation/audio", encodeSingleAudioRequest));
        if (requestBinary.code == 200) {
            return;
        }
        final StringBuilder sb = new StringBuilder("Failed to upload audio: HTTP ");
        sb.append(requestBinary.code);
        throw new IllegalStateException(sb.toString());
    }
    
    private void uploadStreamedAudio(final String s, final String s2, final String s3, final String s4, final long n, final InputStream inputStream, final ProgressListener progressListener) throws Exception {
        final int i = (int)Math.max(1L, (n + 5295308L - 1L) / 5295308L);
        if (i <= 1) {
            this.uploadSingleAudio(s, s2, s3, s4, readFullyBytes(inputStream));
            return;
        }
        final byte[] b = new byte[5295308];
        int n2 = 0;
        while (true) {
            int j = 0;
            while (j < 5295308) {
                final int read = inputStream.read(b, j, 5295308 - j);
                if (read == -1) {
                    break;
                }
                final int n3 = j + read;
                if ((j = n3) == 5295308) {
                    j = n3;
                    break;
                }
            }
            if (j <= 0) {
                break;
            }
            final byte[] array = new byte[j];
            System.arraycopy(b, 0, array, 0, j);
            final StringBuilder sb = new StringBuilder("audio_chunk ");
            final int k = n2 + 1;
            sb.append(k);
            sb.append("/");
            sb.append(i);
            sb.append(" bytes=");
            sb.append(j);
            reportProgress(progressListener, sb.toString());
            this.uploadChunkAudio(s, s2, s3, s4, n2, i, array);
            n2 = k;
        }
        if (n2 != 0) {
            return;
        }
        throw new IllegalStateException("Downloaded YouTube audio is empty");
    }
    
    private void uploadYoutubeAudio(final String p0, final String p1, final String p2, final MediaItemFormatInfo p3, final String p4, final ProgressListener p5) throws Exception {
        // 
        // This method could not be decompiled.
        // 
        // Original Bytecode:
        // 
        //     1: ifnull          792
        //     4: aload_3        
        //     5: invokevirtual   java/lang/String.isEmpty:()Z
        //     8: ifne            792
        //    11: aload_0        
        //    12: aload           4
        //    14: invokeinterface com/liskovsoft/mediaserviceinterfaces/data/MediaItemFormatInfo.getAdaptiveFormats:()Ljava/util/List;
        //    19: aload           5
        //    21: invokespecial   com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.findAudioFormatsForUpload:(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;
        //    24: astore          7
        //    26: aload           7
        //    28: invokeinterface java/util/List.isEmpty:()Z
        //    33: ifne            781
        //    36: aconst_null    
        //    37: astore          8
        //    39: aconst_null    
        //    40: astore          5
        //    42: iconst_0       
        //    43: istore          9
        //    45: iload           9
        //    47: aload           7
        //    49: invokeinterface java/util/List.size:()I
        //    54: if_icmpge       772
        //    57: aload           7
        //    59: iload           9
        //    61: invokeinterface java/util/List.get:(I)Ljava/lang/Object;
        //    66: checkcast       Lcom/liskovsoft/mediaserviceinterfaces/data/MediaFormat;
        //    69: astore          10
        //    71: aload           10
        //    73: invokeinterface com/liskovsoft/mediaserviceinterfaces/data/MediaFormat.getClen:()Ljava/lang/String;
        //    78: ldc2_w          -1
        //    81: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.parseLong:(Ljava/lang/String;J)J
        //    84: lstore          11
        //    86: aload           10
        //    88: invokeinterface com/liskovsoft/mediaserviceinterfaces/data/MediaFormat.getITag:()Ljava/lang/String;
        //    93: iconst_0       
        //    94: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.parseInt:(Ljava/lang/String;I)I
        //    97: istore          13
        //    99: lload           11
        //   101: lconst_0       
        //   102: lcmp           
        //   103: iflt            116
        //   106: lload           11
        //   108: invokestatic    java/lang/String.valueOf:(J)Ljava/lang/String;
        //   111: astore          4
        //   113: goto            121
        //   116: ldc_w           "0"
        //   119: astore          4
        //   121: iload           13
        //   123: aload           4
        //   125: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.buildFileId:(ILjava/lang/String;)Ljava/lang/String;
        //   128: astore          5
        //   130: new             Ljava/lang/StringBuilder;
        //   133: dup            
        //   134: ldc_w           "audio_try "
        //   137: invokespecial   java/lang/StringBuilder.<init>:(Ljava/lang/String;)V
        //   140: astore          4
        //   142: iinc            9, 1
        //   145: aload           4
        //   147: iload           9
        //   149: invokevirtual   java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;
        //   152: pop            
        //   153: aload           4
        //   155: ldc_w           "/"
        //   158: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   161: pop            
        //   162: aload           4
        //   164: aload           7
        //   166: invokeinterface java/util/List.size:()I
        //   171: invokevirtual   java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;
        //   174: pop            
        //   175: aload           4
        //   177: ldc_w           " itag="
        //   180: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   183: pop            
        //   184: aload           4
        //   186: iload           13
        //   188: invokevirtual   java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;
        //   191: pop            
        //   192: aload           4
        //   194: ldc_w           " mime="
        //   197: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   200: pop            
        //   201: aload           4
        //   203: aload           10
        //   205: invokeinterface com/liskovsoft/mediaserviceinterfaces/data/MediaFormat.getMimeType:()Ljava/lang/String;
        //   210: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   213: pop            
        //   214: aload           6
        //   216: aload           4
        //   218: invokevirtual   java/lang/StringBuilder.toString:()Ljava/lang/String;
        //   221: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.reportProgress:(Lcom/liskovsoft/smartyoutubetv2/common/vot/VotApiService$ProgressListener;Ljava/lang/String;)V
        //   224: new             Ljava/net/URL;
        //   227: astore          4
        //   229: aload           4
        //   231: aload           10
        //   233: invokeinterface com/liskovsoft/mediaserviceinterfaces/data/MediaFormat.getUrl:()Ljava/lang/String;
        //   238: invokespecial   java/net/URL.<init>:(Ljava/lang/String;)V
        //   241: aload           4
        //   243: invokevirtual   java/net/URL.openConnection:()Ljava/net/URLConnection;
        //   246: checkcast       Ljava/net/HttpURLConnection;
        //   249: astore          4
        //   251: aload           4
        //   253: ldc_w           "GET"
        //   256: invokevirtual   java/net/HttpURLConnection.setRequestMethod:(Ljava/lang/String;)V
        //   259: aload           4
        //   261: getstatic       com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.CONNECT_TIMEOUT_MS:I
        //   264: invokevirtual   java/net/HttpURLConnection.setConnectTimeout:(I)V
        //   267: aload           4
        //   269: getstatic       com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.READ_TIMEOUT_MS:I
        //   272: invokevirtual   java/net/HttpURLConnection.setReadTimeout:(I)V
        //   275: aload           4
        //   277: iconst_1       
        //   278: invokevirtual   java/net/HttpURLConnection.setInstanceFollowRedirects:(Z)V
        //   281: aload           4
        //   283: ldc_w           "User-Agent"
        //   286: ldc             "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36"
        //   288: invokevirtual   java/net/HttpURLConnection.setRequestProperty:(Ljava/lang/String;Ljava/lang/String;)V
        //   291: aload           4
        //   293: ldc_w           "Accept"
        //   296: ldc_w           "*/*"
        //   299: invokevirtual   java/net/HttpURLConnection.setRequestProperty:(Ljava/lang/String;Ljava/lang/String;)V
        //   302: aload           4
        //   304: ldc_w           "Cache-Control"
        //   307: ldc_w           "no-cache"
        //   310: invokevirtual   java/net/HttpURLConnection.setRequestProperty:(Ljava/lang/String;Ljava/lang/String;)V
        //   313: aload           4
        //   315: invokevirtual   java/net/HttpURLConnection.getResponseCode:()I
        //   318: istore          14
        //   320: iload           14
        //   322: sipush          200
        //   325: if_icmpne       577
        //   328: lload           11
        //   330: lstore          15
        //   332: lload           11
        //   334: lconst_0       
        //   335: lcmp           
        //   336: ifge            358
        //   339: aload           4
        //   341: astore          10
        //   343: aload           4
        //   345: invokevirtual   java/net/HttpURLConnection.getContentLengthLong:()J
        //   348: lstore          15
        //   350: goto            358
        //   353: astore          5
        //   355: goto            655
        //   358: aload           4
        //   360: invokevirtual   java/net/HttpURLConnection.getInputStream:()Ljava/io/InputStream;
        //   363: astore          10
        //   365: lload           15
        //   367: lconst_0       
        //   368: lcmp           
        //   369: ifle            432
        //   372: new             Ljava/lang/StringBuilder;
        //   375: astore          17
        //   377: aload           17
        //   379: invokespecial   java/lang/StringBuilder.<init>:()V
        //   382: aload           17
        //   384: ldc_w           "audio_stream size="
        //   387: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   390: pop            
        //   391: aload           17
        //   393: lload           15
        //   395: invokevirtual   java/lang/StringBuilder.append:(J)Ljava/lang/StringBuilder;
        //   398: pop            
        //   399: aload           6
        //   401: aload           17
        //   403: invokevirtual   java/lang/StringBuilder.toString:()Ljava/lang/String;
        //   406: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.reportProgress:(Lcom/liskovsoft/smartyoutubetv2/common/vot/VotApiService$ProgressListener;Ljava/lang/String;)V
        //   409: aload_0        
        //   410: aload_1        
        //   411: aload_2        
        //   412: aload_3        
        //   413: aload           5
        //   415: lload           15
        //   417: aload           10
        //   419: aload           6
        //   421: invokespecial   com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.uploadStreamedAudio:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/io/InputStream;Lcom/liskovsoft/smartyoutubetv2/common/vot/VotApiService$ProgressListener;)V
        //   424: goto            488
        //   427: astore          5
        //   429: goto            552
        //   432: aload           10
        //   434: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.readFullyBytes:(Ljava/io/InputStream;)[B
        //   437: astore          18
        //   439: new             Ljava/lang/StringBuilder;
        //   442: astore          17
        //   444: aload           17
        //   446: invokespecial   java/lang/StringBuilder.<init>:()V
        //   449: aload           17
        //   451: ldc_w           "audio_single bytes="
        //   454: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   457: pop            
        //   458: aload           17
        //   460: aload           18
        //   462: arraylength    
        //   463: invokevirtual   java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;
        //   466: pop            
        //   467: aload           6
        //   469: aload           17
        //   471: invokevirtual   java/lang/StringBuilder.toString:()Ljava/lang/String;
        //   474: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.reportProgress:(Lcom/liskovsoft/smartyoutubetv2/common/vot/VotApiService$ProgressListener;Ljava/lang/String;)V
        //   477: aload_0        
        //   478: aload_1        
        //   479: aload_2        
        //   480: aload_3        
        //   481: aload           5
        //   483: aload           18
        //   485: invokespecial   com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.uploadSingleAudio:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V
        //   488: aload           4
        //   490: astore          5
        //   492: aload           10
        //   494: ifnull          502
        //   497: aload           10
        //   499: invokevirtual   java/io/InputStream.close:()V
        //   502: new             Ljava/lang/StringBuilder;
        //   505: astore          10
        //   507: aload           10
        //   509: invokespecial   java/lang/StringBuilder.<init>:()V
        //   512: aload           10
        //   514: ldc_w           "audio_try_ok itag="
        //   517: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   520: pop            
        //   521: aload           10
        //   523: iload           13
        //   525: invokevirtual   java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;
        //   528: pop            
        //   529: aload           6
        //   531: aload           10
        //   533: invokevirtual   java/lang/StringBuilder.toString:()Ljava/lang/String;
        //   536: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.reportProgress:(Lcom/liskovsoft/smartyoutubetv2/common/vot/VotApiService$ProgressListener;Ljava/lang/String;)V
        //   539: aload           5
        //   541: ifnull          549
        //   544: aload           5
        //   546: invokevirtual   java/net/HttpURLConnection.disconnect:()V
        //   549: return         
        //   550: astore          5
        //   552: aload           10
        //   554: ifnull          574
        //   557: aload           10
        //   559: invokevirtual   java/io/InputStream.close:()V
        //   562: goto            574
        //   565: astore          10
        //   567: aload           5
        //   569: aload           10
        //   571: invokestatic    kotlin/UInt$$ExternalSyntheticBackport0.m:(Ljava/lang/Throwable;Ljava/lang/Throwable;)V
        //   574: aload           5
        //   576: athrow         
        //   577: new             Ljava/lang/IllegalStateException;
        //   580: astore          10
        //   582: new             Ljava/lang/StringBuilder;
        //   585: astore          5
        //   587: aload           5
        //   589: invokespecial   java/lang/StringBuilder.<init>:()V
        //   592: aload           5
        //   594: ldc_w           "Failed to download YouTube audio: HTTP "
        //   597: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   600: pop            
        //   601: aload           5
        //   603: iload           14
        //   605: invokevirtual   java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;
        //   608: pop            
        //   609: aload           10
        //   611: aload           5
        //   613: invokevirtual   java/lang/StringBuilder.toString:()Ljava/lang/String;
        //   616: invokespecial   java/lang/IllegalStateException.<init>:(Ljava/lang/String;)V
        //   619: aload           10
        //   621: athrow         
        //   622: astore_1       
        //   623: goto            632
        //   626: astore          5
        //   628: goto            655
        //   631: astore_1       
        //   632: aload           4
        //   634: astore_2       
        //   635: goto            762
        //   638: astore          5
        //   640: goto            655
        //   643: astore_1       
        //   644: aload           8
        //   646: astore_2       
        //   647: goto            762
        //   650: astore          5
        //   652: aconst_null    
        //   653: astore          4
        //   655: aload           4
        //   657: astore          10
        //   659: new             Ljava/lang/StringBuilder;
        //   662: astore          17
        //   664: aload           4
        //   666: astore          10
        //   668: aload           17
        //   670: invokespecial   java/lang/StringBuilder.<init>:()V
        //   673: aload           4
        //   675: astore          10
        //   677: aload           17
        //   679: ldc_w           "audio_try_failed itag="
        //   682: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   685: pop            
        //   686: aload           4
        //   688: astore          10
        //   690: aload           17
        //   692: iload           13
        //   694: invokevirtual   java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;
        //   697: pop            
        //   698: aload           4
        //   700: astore          10
        //   702: aload           17
        //   704: ldc_w           " "
        //   707: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   710: pop            
        //   711: aload           4
        //   713: astore          10
        //   715: aload           17
        //   717: aload           5
        //   719: invokevirtual   java/lang/Exception.getMessage:()Ljava/lang/String;
        //   722: invokevirtual   java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
        //   725: pop            
        //   726: aload           4
        //   728: astore          10
        //   730: aload           6
        //   732: aload           17
        //   734: invokevirtual   java/lang/StringBuilder.toString:()Ljava/lang/String;
        //   737: invokestatic    com/liskovsoft/smartyoutubetv2/common/vot/VotApiServiceImpl.reportProgress:(Lcom/liskovsoft/smartyoutubetv2/common/vot/VotApiService$ProgressListener;Ljava/lang/String;)V
        //   740: aload           4
        //   742: ifnull          755
        //   745: aload           4
        //   747: invokevirtual   java/net/HttpURLConnection.disconnect:()V
        //   750: goto            755
        //   753: astore          4
        //   755: goto            45
        //   758: astore_1       
        //   759: aload           10
        //   761: astore_2       
        //   762: aload_2        
        //   763: ifnull          770
        //   766: aload_2        
        //   767: invokevirtual   java/net/HttpURLConnection.disconnect:()V
        //   770: aload_1        
        //   771: athrow         
        //   772: aload           5
        //   774: ifnonnull       778
        //   777: return         
        //   778: aload           5
        //   780: athrow         
        //   781: new             Ljava/lang/IllegalStateException;
        //   784: dup            
        //   785: ldc_w           "No downloadable YouTube audio format found"
        //   788: invokespecial   java/lang/IllegalStateException.<init>:(Ljava/lang/String;)V
        //   791: athrow         
        //   792: new             Ljava/lang/IllegalStateException;
        //   795: dup            
        //   796: ldc_w           "Yandex requested audio, but translationId is empty"
        //   799: invokespecial   java/lang/IllegalStateException.<init>:(Ljava/lang/String;)V
        //   802: astore_1       
        //   803: goto            808
        //   806: aload_1        
        //   807: athrow         
        //   808: goto            806
        //   811: astore_1       
        //   812: goto            549
        //   815: astore_2       
        //   816: goto            770
        //    Exceptions:
        //  throws java.lang.Exception
        //    Exceptions:
        //  Try           Handler
        //  Start  End    Start  End    Type                 
        //  -----  -----  -----  -----  ---------------------
        //  224    251    650    655    Ljava/lang/Exception;
        //  224    251    643    650    Any
        //  251    320    638    643    Ljava/lang/Exception;
        //  251    320    631    632    Any
        //  343    350    353    358    Ljava/lang/Exception;
        //  343    350    758    762    Any
        //  358    365    638    643    Ljava/lang/Exception;
        //  358    365    631    632    Any
        //  372    409    427    432    Any
        //  409    424    550    552    Any
        //  432    488    550    552    Any
        //  497    502    626    631    Ljava/lang/Exception;
        //  497    502    622    626    Any
        //  502    539    626    631    Ljava/lang/Exception;
        //  502    539    622    626    Any
        //  544    549    811    815    Ljava/lang/Exception;
        //  557    562    565    574    Any
        //  567    574    626    631    Ljava/lang/Exception;
        //  567    574    622    626    Any
        //  574    577    626    631    Ljava/lang/Exception;
        //  574    577    622    626    Any
        //  577    622    626    631    Ljava/lang/Exception;
        //  577    622    622    626    Any
        //  659    664    758    762    Any
        //  668    673    758    762    Any
        //  677    686    758    762    Any
        //  690    698    758    762    Any
        //  702    711    758    762    Any
        //  715    726    758    762    Any
        //  730    740    758    762    Any
        //  745    750    753    755    Ljava/lang/Exception;
        //  766    770    815    819    Ljava/lang/Exception;
        // 
        // The error that occurred was:
        // 
        // java.lang.IndexOutOfBoundsException: Index 363 out of bounds for length 363
        //     at java.base/jdk.internal.util.Preconditions.outOfBounds(Preconditions.java:100)
        //     at java.base/jdk.internal.util.Preconditions.outOfBoundsCheckIndex(Preconditions.java:106)
        //     at java.base/jdk.internal.util.Preconditions.checkIndex(Preconditions.java:302)
        //     at java.base/java.util.Objects.checkIndex(Objects.java:365)
        //     at java.base/java.util.ArrayList.get(ArrayList.java:428)
        //     at com.strobel.decompiler.ast.AstBuilder.convertToAst(AstBuilder.java:3362)
        //     at com.strobel.decompiler.ast.AstBuilder.build(AstBuilder.java:112)
        //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.createMethodBody(AstMethodBodyBuilder.java:203)
        //     at com.strobel.decompiler.languages.java.ast.AstMethodBodyBuilder.createMethodBody(AstMethodBodyBuilder.java:93)
        //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createMethodBody(AstBuilder.java:868)
        //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createMethod(AstBuilder.java:761)
        //     at com.strobel.decompiler.languages.java.ast.AstBuilder.addTypeMembers(AstBuilder.java:638)
        //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeCore(AstBuilder.java:605)
        //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createTypeNoCache(AstBuilder.java:195)
        //     at com.strobel.decompiler.languages.java.ast.AstBuilder.createType(AstBuilder.java:162)
        //     at com.strobel.decompiler.languages.java.ast.AstBuilder.addType(AstBuilder.java:137)
        //     at com.strobel.decompiler.languages.java.JavaLanguage.buildAst(JavaLanguage.java:71)
        //     at com.strobel.decompiler.languages.java.JavaLanguage.decompileType(JavaLanguage.java:59)
        //     at com.strobel.decompiler.DecompilerDriver.decompileType(DecompilerDriver.java:333)
        //     at com.strobel.decompiler.DecompilerDriver.decompileJar(DecompilerDriver.java:254)
        //     at com.strobel.decompiler.DecompilerDriver.main(DecompilerDriver.java:144)
        // 
        throw new IllegalStateException("An error occurred while decompiling this method.");
    }
    
    @Override
    public VotTranslateResult translate(String str, String s, int status, String sanitizeLanguage, final String s2, final MediaItemFormatInfo mediaItemFormatInfo, final String s3, final ProgressListener progressListener) {
        if (str != null && !str.isEmpty() && s != null) {
            if (!s.isEmpty()) {
                str = sanitizeLanguage(sanitizeLanguage, "auto");
                sanitizeLanguage = sanitizeLanguage(s2, "ru");
                if (status <= 0) {
                    status = 343;
                }
                try {
                    final boolean resolveUseLivelyVoice = this.resolveUseLivelyVoice(str, sanitizeLanguage, this.mSettings.isUseLivelyVoice());
                    final StringBuilder sb = new StringBuilder("start from=");
                    sb.append(str);
                    sb.append(" to=");
                    sb.append(sanitizeLanguage);
                    sb.append(" lively=");
                    sb.append(resolveUseLivelyVoice);
                    reportProgress(progressListener, sb.toString());
                    final VotProtoUtils.TranslationResponse runTranslationWithFallbacks = this.runTranslationWithFallbacks(s, status, str, sanitizeLanguage, mediaItemFormatInfo, s3, progressListener, resolveUseLivelyVoice);
                    if (runTranslationWithFallbacks == null) {
                        return null;
                    }
                    status = runTranslationWithFallbacks.status;
                    if (status == 1) {
                        return new VotTranslateResult(true, Math.max(runTranslationWithFallbacks.remainingTime, 0), runTranslationWithFallbacks.url, runTranslationWithFallbacks.message, statusToString(runTranslationWithFallbacks.status), buildDebugLine(runTranslationWithFallbacks, "ready"), runTranslationWithFallbacks.translationId);
                    }
                    if (status == 2 || status == 3) {
                        return new VotTranslateResult(false, Math.max(runTranslationWithFallbacks.remainingTime, 0), null, runTranslationWithFallbacks.message, statusToString(runTranslationWithFallbacks.status), buildDebugLine(runTranslationWithFallbacks, "waiting"), runTranslationWithFallbacks.translationId);
                    }
                    if (status == 5) {
                        return new VotTranslateResult(true, 0, runTranslationWithFallbacks.url, runTranslationWithFallbacks.message, statusToString(runTranslationWithFallbacks.status), buildDebugLine(runTranslationWithFallbacks, "partial_ready"), runTranslationWithFallbacks.translationId);
                    }
                    if (status != 6) {
                        return new VotTranslateResult(false, Math.max(runTranslationWithFallbacks.remainingTime, 0), null, runTranslationWithFallbacks.message, statusToString(runTranslationWithFallbacks.status), buildDebugLine(runTranslationWithFallbacks, "server_status"), runTranslationWithFallbacks.translationId);
                    }
                    return new VotTranslateResult(false, Math.max(runTranslationWithFallbacks.remainingTime, 0), null, runTranslationWithFallbacks.message, statusToString(runTranslationWithFallbacks.status), buildDebugLine(runTranslationWithFallbacks, "audio_requested"), runTranslationWithFallbacks.translationId);
                }
                catch (final Exception ex) {
                    Log.e(VotApiServiceImpl.TAG, (Object)"Yandex VOT translate failed", (Throwable)ex);
                    final StringBuilder sb2 = new StringBuilder("exception ");
                    sb2.append(ex.getClass().getSimpleName());
                    str = ex.getMessage();
                    s = "";
                    if (str != null) {
                        final StringBuilder sb3 = new StringBuilder(" ");
                        sb3.append(ex.getMessage());
                        str = sb3.toString();
                    }
                    else {
                        str = "";
                    }
                    sb2.append(str);
                    reportProgress(progressListener, sb2.toString());
                    final String message = ex.getMessage();
                    final StringBuilder sb4 = new StringBuilder("exception: ");
                    sb4.append(ex.getClass().getSimpleName());
                    str = s;
                    if (ex.getMessage() != null) {
                        final StringBuilder sb5 = new StringBuilder(" ");
                        sb5.append(ex.getMessage());
                        str = sb5.toString();
                    }
                    sb4.append(str);
                    return new VotTranslateResult(false, 0, null, message, "ERROR", sb4.toString(), null);
                }
            }
        }
        return null;
    }
    
    private static final class RawResponse
    {
        final byte[] body;
        final int code;
        final String textBody;
        
        RawResponse(final int code, final byte[] body, final String textBody) {
            this.code = code;
            this.body = body;
            this.textBody = textBody;
        }
    }
    
    private static final class Session
    {
        final long createdAtSec;
        final int expiresSec;
        final String secretKey;
        final String uuid;
        
        Session(final String uuid, final String secretKey, final int expiresSec, final long createdAtSec) {
            this.uuid = uuid;
            this.secretKey = secretKey;
            this.expiresSec = expiresSec;
            this.createdAtSec = createdAtSec;
        }
    }
}
