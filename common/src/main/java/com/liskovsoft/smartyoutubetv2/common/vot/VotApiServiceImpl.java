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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class VotApiServiceImpl implements VotApiService {
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
            if (listener != null) listener.onProgress("start");
            ensureSession();
            if (listener != null) listener.onProgress("session_ok");
            String path = "/video-translation/translate";
            boolean useLively = false;
            try { useLively = VotSettings.instance(mContext).isUseLivelyVoice(); } catch (Exception ignored) {}
            byte[] body = VotProtoUtils.encodeTranslationRequest(url, duration, requestLang, responseLang, title, false, useLively);
            byte[] resp = postProtobuf(path, body);
            if (resp == null) {
                return new VotTranslateResult(false, 0, null, "worker 400", "FAILED", "worker 400", null);
            }
            VotProtoUtils.TranslationResponse tr = VotProtoUtils.decodeTranslationResponse(resp);
            // Map status int to string
            String statusStr = statusToString(tr.status);
            boolean translated = tr.status == VotProtoUtils.STATUS_FINISHED && tr.url != null && !tr.url.isEmpty();
            String dbg = "status=" + tr.status + " url=" + (tr.url != null ? tr.url.substring(0, Math.min(40, tr.url.length())) : "null");
            if (tr.message != null && !tr.message.isEmpty()) dbg += " msg=" + tr.message;
            return new VotTranslateResult(translated, tr.remainingTime, tr.url, tr.message, statusStr, dbg, tr.translationId);
        } catch (Exception e) {
            return new VotTranslateResult(false, 0, null, e.getMessage(), "FAILED", e.getMessage(), null);
        }
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
        String uuid = generateUuid();
        byte[] body = VotProtoUtils.encodeSessionRequest(uuid, "video-translation");
        byte[] resp = postProtobufForSession("/session/create", body);
        if (resp == null) throw new IllegalStateException("session create failed");
        VotProtoUtils.SessionResponse sr = VotProtoUtils.decodeSessionResponse(resp);
        secretKey = sr.secretKey;
        lastUuid = uuid;
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
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private byte[] postProtobuf(String path, byte[] body) throws Exception {
        String sig = hmacHex(HMAC_KEY, body);
        String token = lastUuid + ":" + path + ":" + COMPONENT_VERSION;
        String tokenSig = hmacHex(HMAC_KEY, token.getBytes(StandardCharsets.UTF_8));
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
        Request req = new Request.Builder()
                .url("https://" + WORKER_HOST + path)
                .post(RequestBody.create(JSON, json.toString()))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            byte[] bytes = resp.body() != null ? resp.body().bytes() : null;
            if (!resp.isSuccessful()) return null;
            return bytes;
        }
    }
}
