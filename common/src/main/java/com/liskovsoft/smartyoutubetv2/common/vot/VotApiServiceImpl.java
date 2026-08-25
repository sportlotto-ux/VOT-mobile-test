package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import android.annotation.SuppressLint;
import android.util.Log;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@SuppressLint("NewApi")
public class VotApiServiceImpl implements VotApiService {
    private static final String TAG = "VOT";
    private static final String WORKER_HOST = "vot-worker.vtrans.eu.cc";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String COMPONENT_VERSION = "26.6.4.760";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 YaBrowser/25.12.4.1198 Safari/537.36";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build();
    private final Context mContext;
    private volatile String secretKey;
    private volatile String lastUuid;

    public VotApiServiceImpl(Context context) {
        this.mContext = context.getApplicationContext();
    }

    @Override
    public VotTranslateResult translate(String url, String title, int duration, String requestLang, String responseLang, MediaItemFormatInfo formatInfo, String videoId, ProgressListener listener) {
        try {
            Log.e(TAG, "translate start url=" + url + " dur=" + duration + " title=" + title);
            if (listener != null) listener.onProgress("start");
            ensureSession();
            Log.e(TAG, "session ok secret len=" + (secretKey != null ? secretKey.length() : 0));
            if (listener != null) listener.onProgress("session_ok");
            String path = "/video-translation/translate";
            boolean useLively = false;
            try { useLively = VotSettings.instance(mContext).isUseLivelyVoice(); } catch (Exception ignored) {}
            // sanitize langs like original
            requestLang = sanitizeLanguage(requestLang, "auto");
            responseLang = sanitizeLanguage(responseLang, "ru");
            for (int attempt = 0; attempt < 3; attempt++) {
                byte[] body = VotProtoUtils.encodeTranslationRequest(url, duration, requestLang, responseLang, title, true, useLively);
                Log.e(TAG, "translate body len=" + body.length + " hex=" + hmacHex(HMAC_KEY, body).substring(0,8) + " poll=" + attempt);
                byte[] resp = postProtobuf(path, body);
                if (resp == null) {
                    Log.e(TAG, "worker translate null (400) url=" + url + " attempt=" + attempt);
                    if (attempt >= 2) return new VotTranslateResult(false, 0, null, "worker 400", "FAILED", "worker 400", null);
                    // retry with new session on 401/403
                    synchronized (this) { secretKey = null; }
                    ensureSession();
                    continue;
                }
                Log.e(TAG, "translate resp len=" + resp.length);
                VotProtoUtils.TranslationResponse tr = VotProtoUtils.decodeTranslationResponse(resp);
                String statusStr = statusToString(tr.status);
                String dbg = buildDebug(tr, "poll=" + attempt);
                Log.e(TAG, "translate result " + dbg + " statusStr=" + statusStr + " url=" + (tr.url != null ? tr.url.substring(0, Math.min(40, tr.url.length())) : "null"));
                if (tr.status == VotProtoUtils.STATUS_FINISHED && tr.url != null && !tr.url.isEmpty()) {
                    if (listener != null) listener.onProgress("ready");
                    return new VotTranslateResult(true, Math.max(tr.remainingTime, 0), tr.url, tr.message, statusStr, dbg, tr.translationId);
                }
                if (tr.status == VotProtoUtils.STATUS_PART_CONTENT && tr.url != null && !tr.url.isEmpty()) {
                    if (listener != null) listener.onProgress("partial_ready");
                    return new VotTranslateResult(true, 0, tr.url, tr.message, statusStr, dbg, tr.translationId);
                }
                if (tr.status == VotProtoUtils.STATUS_WAITING || tr.status == VotProtoUtils.STATUS_LONG_WAITING) {
                    int waitSec = tr.remainingTime > 0 ? tr.remainingTime : 5;
                    // limit to 60s max per poll, add 2s buffer, and fast poll for first 12 attempts if small eta
                    int sleepMs = Math.max(5000, Math.min(60000, waitSec * 1000 + 2000));
                    if (waitSec <= 3 && attempt < 12) sleepMs = 1000;
                    Log.e(TAG, "waiting status=" + statusStr + " eta=" + waitSec + " sleepMs=" + sleepMs);
                    if (listener != null) listener.onProgress("waiting eta=" + waitSec + "s");
                    // loop inside for waiting: poll until ready or timeout (max 180s)
                    long deadline = System.currentTimeMillis() + Math.min(180000, waitSec * 1000 + 30000);
                    // we stay inside outer loop but do inner polling
                    while (System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        // re-request same translation (Yandex will return updated status)
                        body = VotProtoUtils.encodeTranslationRequest(url, duration, requestLang, responseLang, title, true, useLively);
                        resp = postProtobuf(path, body);
                        if (resp == null) {
                            Log.e(TAG, "poll worker null");
                            break;
                        }
                        tr = VotProtoUtils.decodeTranslationResponse(resp);
                        statusStr = statusToString(tr.status);
                        dbg = buildDebug(tr, "poll_wait");
                        Log.e(TAG, "poll result " + dbg);
                        if (tr.status == VotProtoUtils.STATUS_FINISHED && tr.url != null && !tr.url.isEmpty()) {
                            return new VotTranslateResult(true, Math.max(tr.remainingTime, 0), tr.url, tr.message, statusStr, dbg, tr.translationId);
                        }
                        if (tr.status == VotProtoUtils.STATUS_PART_CONTENT && tr.url != null && !tr.url.isEmpty()) {
                            return new VotTranslateResult(true, 0, tr.url, tr.message, statusStr, dbg, tr.translationId);
                        }
                        if (tr.status != VotProtoUtils.STATUS_WAITING && tr.status != VotProtoUtils.STATUS_LONG_WAITING) {
                            // break to outer handling (failed, etc)
                            break;
                        }
                        if (tr.remainingTime > 0) {
                            sleepMs = Math.max(5000, Math.min(60000, tr.remainingTime * 1000 + 2000));
                            if (tr.remainingTime <= 3) sleepMs = 1000;
                        } else {
                            sleepMs = 5000;
                        }
                        if (listener != null) listener.onProgress("waiting poll eta=" + tr.remainingTime);
                        // adjust deadline if new eta larger?
                        // continue polling
                    }
                    // if still waiting after deadline, return waiting result to let controller retry
                    boolean translated = false;
                    return new VotTranslateResult(translated, Math.max(tr.remainingTime, 0), tr.url, tr.message, statusStr, dbg + " waiting_timeout", tr.translationId);
                }
                if (tr.status == VotProtoUtils.STATUS_AUDIO_REQUESTED) {
                    Log.e(TAG, "audio requested - need upload, fallback");
                    // try fallback without lively voice
                    if (useLively) {
                        useLively = false;
                        Log.e(TAG, "retry without livelyVoice");
                        continue;
                    }
                    return new VotTranslateResult(false, Math.max(tr.remainingTime, 0), null, tr.message, statusStr, dbg, tr.translationId);
                }
                if (tr.status == VotProtoUtils.STATUS_SESSION_REQUIRED) {
                    synchronized (this) { secretKey = null; }
                    if (attempt < 2) {
                        Log.e(TAG, "session required, retry with new session");
                        ensureSession();
                        continue;
                    }
                }
                if (tr.status == VotProtoUtils.STATUS_FAILED && tr.shouldRetry > 0 && attempt < 2) {
                    int sleepMs = Math.max(5000, Math.min(60000, tr.shouldRetry * 1000));
                    Log.e(TAG, "server retry shouldRetry=" + tr.shouldRetry + " sleepMs=" + sleepMs);
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                boolean translated = tr.status == VotProtoUtils.STATUS_FINISHED && tr.url != null && !tr.url.isEmpty();
                return new VotTranslateResult(translated, tr.remainingTime, tr.url, tr.message, statusStr, dbg, tr.translationId);
            }
            return new VotTranslateResult(false, 0, null, "No response", "FAILED", "No response", null);
        } catch (Exception e) {
            Log.e(TAG, "translate exception", e);
            return new VotTranslateResult(false, 0, null, e.getMessage(), "FAILED", e.getMessage(), null);
        }
    }

    private String buildDebug(VotProtoUtils.TranslationResponse tr, String prefix) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) { sb.append(prefix); sb.append(' '); }
        sb.append(statusToString(tr.status));
        if (tr.remainingTime >= 0) { sb.append(" eta="); sb.append(tr.remainingTime); sb.append('s'); }
        if (tr.shouldRetry >= 0) { sb.append(" retry="); sb.append(tr.shouldRetry); sb.append('s'); }
        if (tr.translationId != null && !tr.translationId.isEmpty()) { sb.append(" id="); sb.append(tr.translationId); }
        if (tr.url != null && !tr.url.isEmpty()) sb.append(" url=ok");
        if (tr.isLivelyVoice) sb.append(" lively=1");
        if (tr.message != null && !tr.message.isEmpty()) { sb.append(" msg="); sb.append(tr.message); }
        return sb.toString();
    }

    private static String sanitizeLanguage(String lang, String fallback) {
        if (lang == null) return fallback;
        String s = lang.trim().toLowerCase(java.util.Locale.US).replace('\u2010','-').replace('\u2011','-').replace('\u2012','-').replace('\u2013','-').replace('\u2014','-');
        int idx = s.indexOf('(');
        if (idx >= 0) s = s.substring(0, idx).trim();
        int end = s.indexOf('-');
        if (end < 0) end = s.indexOf('_');
        if (end > 0) s = s.substring(0, end);
        if (!s.matches("[a-z]{2,3}")) return fallback;
        return s;
    }

    private String statusToString(int s) {
        switch (s) {
            case VotProtoUtils.STATUS_FINISHED: return "FINISHED";
            case VotProtoUtils.STATUS_WAITING: return "WAITING";
            case VotProtoUtils.STATUS_LONG_WAITING: return "LONG_WAITING";
            case VotProtoUtils.STATUS_PART_CONTENT: return "PART_CONTENT";
            case VotProtoUtils.STATUS_AUDIO_REQUESTED: return "AUDIO_REQUESTED";
            case VotProtoUtils.STATUS_SESSION_REQUIRED: return "SESSION_REQUIRED";
            default: return "FAILED";
        }
    }

    private synchronized void ensureSession() throws Exception {
        if (secretKey != null) return;
        Log.e(TAG, "ensureSession start");
        String uuid = generateUuid();
        byte[] body = VotProtoUtils.encodeSessionRequest(uuid, "video-translation");
        byte[] resp = postProtobufForSession("/session/create", body);
        if (resp == null) throw new IllegalStateException("session create failed");
        VotProtoUtils.SessionResponse sr = VotProtoUtils.decodeSessionResponse(resp);
        secretKey = sr.secretKey;
        lastUuid = uuid;
        Log.e(TAG, "ensureSession ok secret len=" + (secretKey != null ? secretKey.length() : 0));
    }

    private String generateUuid() {
        String hex = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) sb.append(hex.charAt((int)(Math.random()*16)));
        lastUuid = sb.toString();
        return lastUuid;
    }

    private String hmacHex(String key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(Charset.forName("UTF-8")), "HmacSHA256"));
        byte[] out = mac.doFinal(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private byte[] postProtobuf(String path, byte[] body) throws Exception {
        String sig = hmacHex(HMAC_KEY, body);
        String token = lastUuid + ":" + path + ":" + COMPONENT_VERSION;
        String tokenSig = hmacHex(HMAC_KEY, token.getBytes(Charset.forName("UTF-8")));
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/x-protobuf");
        headers.put("Content-Type", "application/x-protobuf");
        headers.put("Vtrans-Signature", sig);
        headers.put("Sec-Vtrans-Sk", secretKey != null ? secretKey : "");
        headers.put("Sec-Vtrans-Token", tokenSig + ":" + token);
        return postViaWorker(path, body, headers);
    }

    private byte[] postProtobufForSession(String path, byte[] body) throws Exception {
        String sig = hmacHex(HMAC_KEY, body);
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/x-protobuf");
        headers.put("Content-Type", "application/x-protobuf");
        headers.put("Vtrans-Signature", sig);
        return postViaWorker(path, body, headers);
    }

    private byte[] postViaWorker(String path, byte[] body, JSONObject headers) throws Exception {
        JSONObject json = new JSONObject();
        json.put("headers", headers);
        JSONArray arr = new JSONArray();
        for (byte b : body) arr.put(b & 0xFF);
        json.put("body", arr);
        Log.e(TAG, "postViaWorker " + path + " bodyLen=" + body.length + " headers=" + headers.toString().substring(0, Math.min(200, headers.toString().length())));
        Request req = new Request.Builder()
                .url("https://" + WORKER_HOST + path)
                .post(RequestBody.create(JSON, json.toString()))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            byte[] bytes = resp.body() != null ? resp.body().bytes() : null;
            Log.e(TAG, "postViaWorker resp " + path + " code=" + resp.code() + " len=" + (bytes != null ? bytes.length : -1) + " head=" + (bytes != null ? new String(bytes, 0, Math.min(bytes.length, 300), Charset.forName("UTF-8")) : "null"));
            if (!resp.isSuccessful()) return null;
            return bytes;
        }
    }
}
