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
                        // Direct evaluate without eval — bypasses Trusted Types entirely (single chunk, no split issues)
                        webView.evaluateJavascript(js, v -> {
                            synchronized (sLock) { sInjecting = false; }
                            webView.evaluateJavascript("window.__votInjecting=false;", null);
                        });
                        Log.d(TAG, "VOT injected single chunk size=" + js.length());
                        return;
                    }
                    injectChunked(webView, js, 0, chunkSize);
                } catch (Exception e) {
                    Log.w(TAG, "inject inner failed", e);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "inject failed", e);
        }
    }

    private static void injectChunked(WebView webView, String js, int offset, int chunkSize) {
        if (offset >= js.length()) {
            Log.d(TAG, "VOT chunked injection complete");
            return;
        }
        int tmpEnd = Math.min(offset + chunkSize, js.length());
        // Avoid splitting surrogate pair
        if (tmpEnd < js.length() && tmpEnd > 0 && Character.isHighSurrogate(js.charAt(tmpEnd - 1)) && Character.isLowSurrogate(js.charAt(tmpEnd))) {
            tmpEnd += 1;
        }
        final int end = tmpEnd;
        final String chunk = js.substring(offset, end);
        // Use quoted storage — safe to split at arbitrary offset, chunk is treated as string literal
        if (offset == 0) {
            webView.evaluateJavascript("window.__votChunks=[];", v -> {
                webView.evaluateJavascript("window.__votChunks.push(" + org.json.JSONObject.quote(chunk) + ");", v2 -> injectChunked(webView, js, end, chunkSize));
            });
            Log.d(TAG, "VOT chunked start total=" + js.length() + " chunkSize=" + chunkSize);
        } else if (end < js.length()) {
            webView.evaluateJavascript("window.__votChunks.push(" + org.json.JSONObject.quote(chunk) + ");", v -> injectChunked(webView, js, end, chunkSize));
        } else {
            // last chunk — join and eval with Trusted Types support (YouTube requires TrustedScript)
            String finalJs = "window.__votChunks.push(" + org.json.JSONObject.quote(chunk) + ");"
                    + "window.__votCombined=window.__votChunks.join('');"
                    + "window.__votChunks=null;"
                    + "console.log('VOT combined len='+window.__votCombined.length+', head='+window.__votCombined.slice(0,120)+', tail='+window.__votCombined.slice(-400));"
                    + "console.log('VOT tail2 '+window.__votCombined.slice(-800,-400));"
                    + "try{"
                    + "if(window.trustedTypes&&window.trustedTypes.createPolicy){"
                    + "var p=null;try{p=window.trustedTypes.createPolicy('vot-'+Math.random(),{createScript:function(s){return s;}});}catch(e){console.log('TT vot random failed '+e);}"
                    + "if(!p){try{p=window.trustedTypes.createPolicy('default',{createScript:function(s){return s;}});}catch(e){console.log('TT default failed '+e);}}"
                    + "if(p){console.log('TT policy ok '+p.name);var s=p.createScript(window.__votCombined);eval(s);}else{console.error('TT no policy');}"
                    + "}else{console.error('TT unsupported');}"
                    + "}catch(e){console.error('VOT eval failed',e);console.error(e.stack||e);}"
                    + "window.__votCombined=null;window.__votInjecting=false;";
            webView.evaluateJavascript(finalJs, v -> { synchronized (sLock) { sInjecting = false; } });
            Log.d(TAG, "VOT injected chunked final total=" + js.length());
        }
    }
}
