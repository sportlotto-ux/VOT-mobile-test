// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.vot;

import java.util.Locale;
import android.net.Uri;

public final class VotOauthService
{
    private static final String CALLBACK_AUTH_SERVER_PREFIX = "https://rust-server-531j.onrender.com/auth/callback";
    private static final String CALLBACK_VERIFICATION_COM_PREFIX = "https://oauth.yandex.com/verification_code";
    private static final String CALLBACK_VERIFICATION_PREFIX = "https://oauth.yandex.ru/verification_code";
    private static final String CLIENT_ID = "1666fbe22f3749c581002a4f97b2592d";
    private static final String YANDEX_AUTHORIZE_URL = "https://oauth.yandex.ru/authorize";
    
    private VotOauthService() {
    }
    
    public static String buildAuthorizeUrl() {
        return "https://oauth.yandex.ru/authorize?client_id=1666fbe22f3749c581002a4f97b2592d&response_type=token";
    }
    
    public static String extractAccessToken(final String s) {
        return extractOauthParam(s, "access_token");
    }
    
    public static String extractErrorDescription(String oauthParam) {
        final String oauthParam2 = extractOauthParam(oauthParam, "error_description");
        if (oauthParam2 != null && !oauthParam2.isEmpty()) {
            return oauthParam2;
        }
        oauthParam = extractOauthParam(oauthParam, "error");
        if (oauthParam != null && !oauthParam.isEmpty()) {
            return oauthParam.replace('_', ' ');
        }
        return null;
    }
    
    private static String extractOauthParam(String anObject, final String s) {
        String s3;
        final String s2 = s3 = null;
        if (anObject != null) {
            if (s == null) {
                s3 = s2;
            }
            else {
                final Uri parse = Uri.parse(anObject);
                final String queryParameter = parse.getQueryParameter(s);
                if (queryParameter != null && !queryParameter.isEmpty()) {
                    return queryParameter;
                }
                anObject = parse.getFragment();
                s3 = s2;
                if (anObject != null) {
                    if (anObject.isEmpty()) {
                        s3 = s2;
                    }
                    else {
                        final String[] split = anObject.split("&");
                        final int length = split.length;
                        int n = 0;
                        while (true) {
                            s3 = s2;
                            if (n >= length) {
                                break;
                            }
                            final String s4 = split[n];
                            final int index = s4.indexOf(61);
                            if (index >= 0) {
                                anObject = s4.substring(0, index);
                            }
                            else {
                                anObject = s4;
                            }
                            if (!s.equals(anObject)) {
                                ++n;
                            }
                            else {
                                if (index >= 0) {
                                    anObject = s4.substring(index + 1);
                                }
                                else {
                                    anObject = "";
                                }
                                anObject = Uri.decode(anObject);
                                s3 = s2;
                                if (anObject == null) {
                                    break;
                                }
                                s3 = s2;
                                if (!anObject.isEmpty()) {
                                    s3 = anObject;
                                    break;
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return s3;
    }
    
    private static boolean hasOauthParam(final String s, final String s2) {
        return extractOauthParam(s, s2) != null;
    }
    
    public static boolean isOauthCallbackUrl(final String s) {
        boolean b = false;
        if (s == null) {
            return false;
        }
        final String lowerCase = s.toLowerCase(Locale.US);
        if (lowerCase.startsWith("https://rust-server-531j.onrender.com/auth/callback") || lowerCase.startsWith("https://oauth.yandex.ru/verification_code") || lowerCase.startsWith("https://oauth.yandex.com/verification_code") || hasOauthParam(s, "access_token") || hasOauthParam(s, "error")) {
            b = true;
        }
        return b;
    }
}
