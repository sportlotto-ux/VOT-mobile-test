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
        // skippingRules defines window.__votSkippingRules
        String inner = shim + "\n" + skippingRules + "\n" + skipperJs + "\n" + vot;
        // Dedup guard — prevents double injection on onPageStarted+onPageFinished
        return "if(window.__votInjected){console.log('VOT already injected');}else{window.__votInjected=true;" + inner + "\n}";
        // Note: vot.user.js is wrapped, but its top-level return/break still works because it's not in function scope originally
    }

    public static void inject(WebView webView) {
        try {
            Context ctx = webView.getContext();
            String js = buildInjectionJs(ctx);
            if (js.isEmpty()) {
                Log.w(TAG, "injection js empty");
                return;
            }
            // Binder limit ~1MB, vot is 1.1M — chunk to avoid TransactionTooLargeException
            // Split into 400k chunks and chain evaluateJavascript
            int chunkSize = 400 * 1024;
            if (js.length() <= chunkSize) {
                webView.evaluateJavascript(js, null);
                Log.d(TAG, "VOT injected single chunk size=" + js.length());
                return;
            }
            // chunked injection: wrap each chunk in eval, sequence via callbacks
            injectChunked(webView, js, 0, chunkSize);
        } catch (Exception e) {
            Log.w(TAG, "inject failed", e);
        }
    }

    private static void injectChunked(WebView webView, String js, int offset, int chunkSize) {
        if (offset >= js.length()) {
            Log.d(TAG, "VOT chunked injection complete");
            return;
        }
        int end = Math.min(offset + chunkSize, js.length());
        String chunk = js.substring(offset, end);
        // Escape chunk for JS string? We send raw JS, but need to ensure chunk boundaries don't split surrogate pairs — substring handles, but JS eval of partial code may be syntactically invalid if split inside string literal.
        // To avoid syntax errors, we split at line boundaries where possible.
        // Find last newline before end to avoid splitting inside vot's minified line (which has no newlines) — for minified, chunk will split arbitrarily but still may break string literals.
        // For MVP we split raw; vot.user.js is mostly one line with no unescaped newlines, but splitting inside a string literal could break.
        // Better: encode chunk as base64 and eval via atob? Simpler: just send chunk as is and rely on JS engine to handle concatenated evals? That won't work because partial JS is not valid.
        // So we use a different strategy: store chunks in an array and eval combined.
        // Approach: first chunk creates window.__votChunks = [], then push, final chunk joins and evals.
        if (offset == 0) {
            webView.evaluateJavascript("window.__votChunks=[];", v -> {
                webView.evaluateJavascript("window.__votChunks.push(" + org.json.JSONObject.quote(chunk) + ");", v2 -> injectChunked(webView, js, end, chunkSize));
            });
        } else if (end < js.length()) {
            webView.evaluateJavascript("window.__votChunks.push(" + org.json.JSONObject.quote(chunk) + ");", v -> injectChunked(webView, js, end, chunkSize));
        } else {
            // last chunk
            webView.evaluateJavascript("window.__votChunks.push(" + org.json.JSONObject.quote(chunk) + "); window.__votCombined=window.__votChunks.join(''); window.__votChunks=null; try{ eval(window.__votCombined); }catch(e){ console.error('VOT eval failed', e); } window.__votCombined=null;", null);
            Log.d(TAG, "VOT injected chunked total size=" + js.length());
        }
    }
}
