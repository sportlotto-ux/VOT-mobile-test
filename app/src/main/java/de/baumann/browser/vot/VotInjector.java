package de.baumann.browser.vot;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Phase 2.2: decides inject and loads bundled scripts.
 * For MVP loads from assets (static), later VotScriptUpdater will supply cached version.
 */
public class VotInjector {
    private static final String TAG = "VotInjector";
    private static final String VOT_ASSET = "vot/vot.user.js";
    private static final String SKIPPER_ASSET = "vot/skipping-rules.js";
    private static final Object sLock = new Object();
    private static boolean sInjecting = false;

    public static boolean shouldInject(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // match youtube hosts per TZ 2.2
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("youtube-nocookie.com")
                || lower.contains("m.youtube.com") || lower.contains("www.youtube.com");
    }

    public static String loadAssetText(Context ctx, String assetPath) {
        try {
            AssetManager am = ctx.getAssets();
            InputStream is = am.open(assetPath);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "load asset failed " + assetPath + " " + e);
            return "";
        }
    }

    public static String buildInjectionJs(Context ctx) {
        String shim = GmShim.getShimJs();
        String skippingRules = loadAssetText(ctx, SKIPPER_ASSET);
        String vot = loadAssetText(ctx, VOT_ASSET);
        if (vot.isEmpty()) {
            Log.w(TAG, "vot.user.js empty");
            return "";
        }
        // CSS + skipper JS per TZ 2.4
        String skipperJs = ""
                + "(function(){"
                + " try {"
                + "  var css=document.createElement('style');"
                + "  var rules=(window.__votSkippingRules && window.__votSkippingRules.css) || 'ytd-ad-slot{display:none !important}';"
                + "  css.textContent=rules;"
                + "  (document.head||document.documentElement).appendChild(css);"
                + " } catch(e){}"
                + " var baseRate = function(){ try{ return parseFloat(localStorage.getItem('vot_baseRate')||'1')||1; }catch(e){return 1;} };"
                + " var adTimerStart=null;"
                + " setInterval(function(){"
                + "  try {"
                + "   var video=document.querySelector('video');"
                + "   var adShowing=!!document.querySelector((window.__votSkippingRules&&window.__votSkippingRules.adOverlaySelector)||'.ytp-ad-player-overlay') || (document.documentElement.className.indexOf('ad-showing')>-1);"
                + "   if(!adShowing){ adTimerStart=null; if(video && video.playbackRate!==baseRate()) video.playbackRate=baseRate(); return; }"
                + "   var sels=(window.__votSkippingRules&&window.__votSkippingRules.skipSelectors)||['.ytp-ad-skip-button-modern','.ytp-ad-skip-button'];"
                + "   var btn=null; for(var i=0;i<sels.length;i++){ btn=document.querySelector(sels[i]); if(btn) break; }"
                + "   if(btn){ btn.click(); return; }"
                + "   if(!adTimerStart) adTimerStart=Date.now();"
                + "   if(Date.now()-adTimerStart<4000) return;"
                + "   if(video && video.playbackRate!==10) video.playbackRate=10;"
                + "  } catch(e){}"
                + " },500);"
                + "})();";

        // Order: shim -> skipping-rules -> skipper logic -> vot.user.js
        String inner = shim + "\n" + skippingRules + "\n" + skipperJs + "\n" + vot;
        // Dedup is handled outside via window.__votInjected/__votInjecting check; just mark injected
        return "window.__votInjected=true;\n" + inner;
        // Note: vot.user.js is wrapped, but its top-level return/break still works because it's not in function scope originally
    }

    public static void inject(WebView webView) {
        // Java-side lock to prevent concurrent chunked injections before JS flag is set (async)
        synchronized (sLock) {
            if (sInjecting) {
                Log.d(TAG, "VOT Java lock: already injecting, skip");
                return;
            }
            sInjecting = true;
        }
        try {
            // Dedup check before heavy asset load
            webView.evaluateJavascript("(window.__votInjected||window.__votInjecting) ? 'true' : 'false'", val -> {
                if (val != null && val.contains("true")) {
                    synchronized (sLock) { sInjecting = false; }
                    Log.d(TAG, "VOT already injected/in progress, skip");
                    return;
                }
                // Lock to prevent concurrent injections (YouTube SPA fires onPageStarted+onPageFinished rapidly)
                webView.evaluateJavascript("window.__votInjecting=true;", null);
                try {
                    Context ctx = webView.getContext();
                    String js = buildInjectionJs(ctx);
                    if (js.isEmpty()) {
                        Log.w(TAG, "injection js empty");
                        synchronized (sLock) { sInjecting = false; }
                        webView.evaluateJavascript("window.__votInjecting=false;", null);
                        return;
                    }
                    int chunkSize = 400 * 1024;
                    if (js.length() <= chunkSize) {
                        // Direct evaluate without eval — bypasses Trusted Types entirely
                        webView.evaluateJavascript(js, v -> {
                            synchronized (sLock) { sInjecting = false; }
                            webView.evaluateJavascript("window.__votInjecting=false;", null);
                        });
                        Log.d(TAG, "VOT injected single chunk size=" + js.length());
                        return;
                    }
                    injectChunkedDirect(webView, js, 0, chunkSize);
                } catch (Exception e) {
                    Log.w(TAG, "inject inner failed", e);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "inject failed", e);
        }
    }

    private static void injectChunkedDirect(WebView webView, String js, int offset, int chunkSize) {
        if (offset >= js.length()) {
            webView.evaluateJavascript("window.__votInjecting=false;", v -> { synchronized (sLock) { sInjecting = false; } });
            Log.d(TAG, "VOT chunked injection complete total=" + js.length());
            return;
        }
        int end = Math.min(offset + chunkSize, js.length());
        // Find safe split point near end (search backwards for ; or } or newline) to avoid breaking inside string literal / expression
        if (end < js.length()) {
            int safe = -1;
            // search last 4k for a safe boundary
            int searchStart = Math.max(offset, end - 4096);
            for (int i = end - 1; i >= searchStart; i--) {
                char c = js.charAt(i);
                if (c == ';' || c == '\n') { safe = i + 1; break; }
            }
            if (safe > offset && safe < end) {
                end = safe;
            }
        }
        String chunk = js.substring(offset, end);
        final int nextOffset = end;
        webView.evaluateJavascript(chunk, v -> {
            // Continue with next chunk; log errors if any (v contains result or error? WebView returns JSON)
            if (v != null && v.contains("Exception")) {
                Log.w(TAG, "chunk eval returned exception at offset " + offset + ": " + v);
            }
            injectChunkedDirect(webView, js, nextOffset, chunkSize);
        });
        if (offset == 0) {
            Log.d(TAG, "VOT chunked direct start total=" + js.length() + " chunkSize=" + chunkSize);
        }
    }
}
