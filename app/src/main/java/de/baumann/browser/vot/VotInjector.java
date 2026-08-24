package de.baumann.browser.vot;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.InputStream;

/**
 * Phase 2.2: VOT injection via virtual asset host.
 *
 * Scripts are served as real <script src="https://vot.assets.local/..."> through
 * shouldInterceptRequest instead of being pushed through evaluateJavascript.
 * Rationale: vot.user.js is ~1.1MB; chunked eval is blocked by YouTube's enforced
 * Trusted Types allowlist (createPolicy with arbitrary names throws), and arbitrary
 * chunk splits break string literals. <script> elements are not TT sinks and have
 * no size limits; async=false preserves execution order.
 */
public class VotInjector {
    private static final String TAG = "VotInjector";
    // Use www.youtube.com/__vot/* so CSP script-src https://*.youtube.com allows it; intercepted via shouldInterceptRequest
    public static final String ASSET_HOST = "www.youtube.com";
    public static final String ASSET_PREFIX = "/__vot__/"; 
    private static final String VOT_ASSET = "vot/vot.user.js";
    private static final String SKIPPER_ASSET = "vot/skipping-rules.js";

    public static boolean shouldInject(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("youtube-nocookie.com");
    }

    /** Serves vot assets for www.youtube.com/__vot/*; null when not ours. */
    public static WebResourceResponse interceptAsset(WebView view, String url) {
        if (url == null || view == null) return null;
        Uri uri = Uri.parse(url);
        if (!ASSET_HOST.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith(ASSET_PREFIX)) return null;
        String asset = "vot" + path.substring(ASSET_PREFIX.length() - 1); // "/__vot/vot.user.js" -> "/vot.user.js"
        if (asset.startsWith("/")) asset = asset.substring(1);
        try {
            AssetManager am = view.getContext().getAssets();
            InputStream is = am.open(asset);
            WebResourceResponse resp = new WebResourceResponse("application/javascript", "utf-8", is);
            resp.setResponseHeaders(java.util.Collections.singletonMap("Cache-Control", "no-cache"));
            return resp;
        } catch (Exception e) {
            Log.w(TAG, "interceptAsset failed for " + asset + ": " + e);
            return null;
        }
    }

    public static String loadAssetText(Context ctx, String assetPath) {
        try {
            AssetManager am = ctx.getAssets();
            InputStream is = am.open(assetPath);
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
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

    /** Tiny bootstrap: shim + ad skipper inline, VOT + skipping-rules as <script src>. */
    public static String buildBootstrapJs(Context ctx) {
        String votUrl = "https://" + ASSET_HOST + ASSET_PREFIX + "vot.user.js";
        String rulesUrl = "https://" + ASSET_HOST + ASSET_PREFIX + "skipping-rules.js";
        // Atomic dedup: check+set in one synchronous evaluate, no cross-call race
        // YouTube enforces require-trusted-types-for 'script' -> s.src needs TrustedScriptURL
        // DOM may be null at onPageStarted -> poll for head/documentElement
        return "(function(){"
                + "if(window.__votInjected||window.__votInjecting)return;"
                + "window.__votInjecting=true;"
                + GmShim.getShimJs()
                + skipperJs()
                + "var getTrustedUrl=function(url){"
                + "  if(window.trustedTypes&&window.trustedTypes.createPolicy){"
                + "    var names=['youtube','yt','default','vot','tt','google'];"
                + "    for(var i=0;i<names.length;i++){try{var p=window.trustedTypes.createPolicy(names[i],{createScriptURL:function(s){return s;}});return p.createScriptURL(url);}catch(e){}}"
                + "    try{var p=window.trustedTypes.createPolicy('vot-'+Math.random(),{createScriptURL:function(s){return s;}});return p.createScriptURL(url);}catch(e){}"
                + "  }return url;"
                + "};"
                + "var load=function(src){"
                + "  var s=document.createElement('script');"
                + "  try{s.src=getTrustedUrl(src);}catch(e){try{s.src=src;}catch(e2){}}"
                + "  s.async=false;"
                + "  var appendScript=function(){var t=document.head||document.documentElement||document.body;if(t){try{t.appendChild(s);return true;}catch(e){console.error('VOT append failed',e);return false;}}return false;};"
                + "  if(!appendScript()){"
                + "    if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',appendScript,{once:true});}"
                + "    else{var obs=new MutationObserver(function(){if(appendScript())obs.disconnect();});obs.observe(document,{childList:true,subtree:true});}"
                + "    var iv=setInterval(function(){if(appendScript())clearInterval(iv);},20);"
                + "    setTimeout(function(){clearInterval(iv);},5000);"
                + "  }"
                + "};"
                + "try{load('" + rulesUrl + "');load('" + votUrl + "');}catch(e){console.error('VOT bootstrap script tag failed',e);}"
                + "setTimeout(function(){window.__votInjected=true;window.__votInjecting=false;},100);"
                + "})();";
    }

    private static String skipperJs() {
        return "var baseRate=function(){try{return parseFloat(localStorage.getItem('vot_baseRate')||'1')||1;}catch(e){return 1;}};"
                + "var adTimerStart=null;"
                + "setInterval(function(){"
                + " try {"
                + "  var video=document.querySelector('video');"
                + "  var adShowing=!!document.querySelector((window.__votSkippingRules&&window.__votSkippingRules.adOverlaySelector)||'.ytp-ad-player-overlay') || (document.documentElement.className.indexOf('ad-showing')>-1);"
                + "  if(!adShowing){ adTimerStart=null; if(video && video.playbackRate!==baseRate()) video.playbackRate=baseRate(); return; }"
                + "  var sels=(window.__votSkippingRules&&window.__votSkippingRules.skipSelectors)||['.ytp-ad-skip-button-modern','.ytp-ad-skip-button'];"
                + "  var btn=null; for(var i=0;i<sels.length;i++){ btn=document.querySelector(sels[i]); if(btn) break; }"
                + "  if(btn){ btn.click(); return; }"
                + "  if(!adTimerStart) adTimerStart=Date.now();"
                + "  if(Date.now()-adTimerStart<4000) return;"
                + "  if(video && video.playbackRate!==10) video.playbackRate=10;"
                + " } catch(e){}"
                + "},500);";
    }

    public static void inject(WebView webView) {
        try {
            Context ctx = webView.getContext();
            String js = buildBootstrapJs(ctx);
            webView.evaluateJavascript(js, null);
            Log.d(TAG, "VOT bootstrap injected, size=" + js.length());
        } catch (Exception e) {
            Log.w(TAG, "inject failed", e);
        }
    }
}
