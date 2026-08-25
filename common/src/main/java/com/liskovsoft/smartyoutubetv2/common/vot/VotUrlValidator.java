// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.vot;

import java.util.Locale;

public class VotUrlValidator
{
    public static boolean validate(String lowerCase) {
        boolean b = false;
        if (lowerCase == null) {
            return false;
        }
        lowerCase = lowerCase.trim().toLowerCase(Locale.US);
        if (lowerCase.startsWith("http://") || lowerCase.startsWith("https://")) {
            b = true;
        }
        return b;
    }
}
