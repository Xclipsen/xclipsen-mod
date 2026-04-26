/*
 * Decompiled with CFR 0.152.
 */
package com.autocroesus.util;

import java.util.regex.Pattern;

public class ColorUtil {
    private static final Pattern COLOR_CODE = Pattern.compile("\u00a7[0-9a-fk-orA-FK-OR]");

    public static String stripColors(String s) {
        if (s == null) {
            return "";
        }
        return COLOR_CODE.matcher(s).replaceAll("");
    }

    public static String formatNumber(long num) {
        return String.format("%,d", num);
    }

    public static String formatNumber(double num) {
        return String.format("%,.0f", num);
    }

    public static String formattedBool(boolean b) {
        return b ? "\u00a7atrue" : "\u00a7cfalse";
    }
}

