package de.baumann.browser.vot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Phase 2: Native bridge for GM_xmlhttpRequest via OkHttp.
 * Whitelist, https only, GET/POST, 10s timeout, dot-matching.
 * Async delivery via evaluateJavascript -> __votGm.deliver
 */
public class NativeHttpBridge {
    private static final String TAG = "VOTBridge";
    private static final long TIMEOUT_SEC = 20;

    // Whitelist per TZ 2.3 + phase0 @connect
    private static final String[] ALLOWED_HOSTS = new String[]{
            "api.browser.yandex.ru",
            "yandex.ru",
            "yandex.net",
            "raw.githubusercontent.com",
            "toil.cc",
            "vot.toil.cc",
            "workers.dev",
            "onrender.com",
            "eu.cc",
            "timeweb.cloud",
            "cloudflare-dns.com",
            "porntn.com",
            "youtube.com",
            "googlevideo.com",
            "greasyfork.org"
    };

    private final Context context;
    private final WebView webView;
    private final OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public NativeHttpBridge(Context context, WebView webView) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    private static boolean hostAllowed(String host, String domain) {
        if (host == null || domain == null) return false;
        host = host.toLowerCase();
        domain = domain.toLowerCase();
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private boolean isHostAllowed(String host) {
        if (host == null) return false;
        for (String allowed : ALLOWED_HOSTS) {
            // handle wildcard like *.greasyfork.org -> check greasyfork.org
            String domain = allowed.startsWith("*.") ? allowed.substring(2) : allowed;
            if (hostAllowed(host, domain)) return true;
        }
        return false;
    }

    @JavascriptInterface
    public void nativeFetch(int id, String url, String method, String headersJson, String body) {
        // Validate
        if (url == null || url.isEmpty()) {
            deliverError(id, "empty url");
            return;
        }
        String m = method == null ? "GET" : method.toUpperCase();
        if (!m.equals("GET") && !m.equals("POST")) {
            deliverError(id, "method not allowed: " + m);
            return;
        }
        // scheme https only
        if (!url.toLowerCase().startsWith("https://")) {
            deliverError(id, "only https allowed");
            return;
        }
        String host;
        try {
            host = new java.net.URL(url).getHost();
        } catch (Exception e) {
            deliverError(id, "bad url: " + e.getMessage());
            return;
        }
        if (!isHostAllowed(host)) {
            Log.w(TAG, "Host blocked: " + host + " url=" + url);
            deliverError(id, "Host blocked: " + host);
            return;
        }

        Request.Builder rb = new Request.Builder().url(url);
        // headers
        if (headersJson != null && !headersJson.isEmpty() && !headersJson.equals("null")) {
            try {
                JSONObject jo = new JSONObject(headersJson);
                Iterator<String> keys = jo.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = jo.optString(k, null);
                    if (v != null) rb.header(k, v);
                }
            } catch (Exception e) {
                Log.w(TAG, "bad headers json", e);
            }
        }

        // Detect Content-Type from headersJson, default to json if body looks like json
        String contentType = null;
        if (headersJson != null && !headersJson.isEmpty() && !headersJson.equals("null")) {
            try {
                JSONObject joTmp = new JSONObject(headersJson);
                Iterator<String> itTmp = joTmp.keys();
                while (itTmp.hasNext()) {
                    String kTmp = itTmp.next();
                    if (kTmp.equalsIgnoreCase("Content-Type") || kTmp.equalsIgnoreCase("content-type")) {
                        contentType = joTmp.optString(kTmp, null);
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (contentType == null) {
            String trimmed = body != null ? body.trim() : "";
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) contentType = "application/json; charset=utf-8";
            else contentType = "application/x-www-form-urlencoded";
        }
        if (de.baumann.browser.BuildConfig.DEBUG) Log.d(TAG, "nativeFetch id=" + id + " " + m + " " + url + " ct=" + contentType + " bodyLen=" + (body != null ? body.length() : 0));

        if (m.equals("GET")) {
            rb.get();
        } else {
            MediaType mt = MediaType.parse(contentType);
            if (mt == null) mt = MediaType.parse("application/octet-stream");
            RequestBody rbBody = body == null ? RequestBody.create(new byte[0]) : RequestBody.create(body, mt);
            rb.post(rbBody);
        }

        Request req = rb.build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "nativeFetch failure " + url + " " + e);
                String msg = e.getMessage() != null ? e.getMessage() : "network error";
                if (msg.toLowerCase().contains("timeout")) {
                    deliverTimeout(id);
                } else {
                    deliverError(id, msg);
                }
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    byte[] raw = rb != null ? rb.bytes() : new byte[0];
                    // keep utf8 string for text responses, and base64 for binary (protobuf)
                    String respText = new String(raw, StandardCharsets.UTF_8);
                    String b64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
                    String headLog = raw.length > 500 ? b64.substring(0, 500) + "...b64 len=" + b64.length() : (respText.length() > 500 ? respText.substring(0, 500) : respText);
                    if (de.baumann.browser.BuildConfig.DEBUG) Log.d(TAG, "nativeFetch resp id=" + id + " code=" + response.code() + " url=" + url + " bytes=" + raw.length + " bodyHead=" + headLog);
                    // Build headers string like Tampermonkey: "key: value\r\n"
                    StringBuilder headersSb = new StringBuilder();
                    for (String name : response.headers().names()) {
                        headersSb.append(name).append(": ").append(response.header(name)).append("\r\n");
                    }
                    String finalUrl = response.request().url().toString();
                    JSONObject json = new JSONObject();
                    try {
                        json.put("status", response.code());
                        json.put("statusText", response.message() != null ? response.message() : "");
                        json.put("responseText", respText);
                        json.put("response", respText);
                        json.put("responseBase64", b64);
                        json.put("responseHeaders", headersSb.toString());
                        json.put("finalUrl", finalUrl);
                        json.put("readyState", 4);
                    } catch (Exception je) {
                        deliverError(id, "json build failed");
                        return;
                    }
                    deliverSuccess(id, json);
                } catch (Exception e) {
                    deliverError(id, "response read failed: " + e.getMessage());
                }
            }
        });
    }

    private void deliverSuccess(int id, JSONObject json) {
        mainHandler.post(() -> {
            try {
                String js = "__votGm.deliver(" + id + ", " + JSONObject.quote(json.toString()) + ", 'load')";
                // The shim expects JSON object, not string — we pass stringified then parse in JS
                // Better: pass raw json string without extra quote if possible, but to avoid injection use quote+JSON.parse
                // We will call __votGm.deliverRaw
                String js2 = "__votGm.deliver(" + id + ", " + json.toString() + ", 'load')";
                webView.evaluateJavascript(js2, null);
            } catch (Exception e) {
                Log.w(TAG, "deliverSuccess failed", e);
            }
        });
    }

    private void deliverError(int id, String msg) {
        mainHandler.post(() -> {
            try {
                JSONObject err = new JSONObject();
                err.put("error", msg);
                err.put("status", 0);
                err.put("responseText", "");
                err.put("responseHeaders", "");
                String js = "__votGm.deliver(" + id + ", " + err.toString() + ", 'error')";
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "deliverError failed", e);
            }
        });
    }

    private void deliverTimeout(int id) {
        mainHandler.post(() -> {
            try {
                JSONObject err = new JSONObject();
                err.put("error", "timeout");
                String js = "__votGm.deliver(" + id + ", " + err.toString() + ", 'timeout')";
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "deliverTimeout failed", e);
            }
        });
    }

    // --- GM storage sync bridge (TЗ 2.3.2) ---

    @JavascriptInterface
    public String gmGetValue(String key, String def) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            // vot_ prefix to avoid collision
            String v = sp.getString("vot_" + key, null);
            if (v == null) return def != null ? def : "null";
            return v;
        } catch (Exception e) {
            return def != null ? def : "null";
        }
    }

    @JavascriptInterface
    public void gmSetValue(String key, String value) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            sp.edit().putString("vot_" + key, value).apply();
        } catch (Exception e) {
            Log.w(TAG, "gmSetValue failed", e);
        }
    }

    @JavascriptInterface
    public void gmDeleteValue(String key) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            sp.edit().remove("vot_" + key).apply();
        } catch (Exception e) {
            Log.w(TAG, "gmDeleteValue failed", e);
        }
    }

    @JavascriptInterface
    public String gmListValues() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String k : sp.getAll().keySet()) {
                if (k.startsWith("vot_")) {
                    arr.put(k.substring(4));
                }
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public String gmGetValues(String keysJson) {
        try {
            JSONObject in = new JSONObject(keysJson);
            JSONObject out = new JSONObject();
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            Iterator<String> it = in.keys();
            while (it.hasNext()) {
                String k = it.next();
                String def = in.optString(k, null);
                String v = sp.getString("vot_" + k, null);
                if (v == null) v = def != null ? def : "null";
                out.put(k, v);
            }
            return out.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
