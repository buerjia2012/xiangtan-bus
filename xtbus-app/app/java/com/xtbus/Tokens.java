package com.xtbus;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.widget.TextView;

import java.util.HashMap;

/**
 * 设计令牌入口。
 *
 * 两套数值分别落在 res/values/dimens.xml（后缀 _c，标准/小字版）与
 * res/values/dimens_large.xml（后缀 _l，大字版）。Android 的资源限定符
 * 无法由用户切换，所以这里在运行时按偏好拼接资源名取对应那一套，
 * 调用方只写语义名，例如 Tokens.dp(ctx, "xt_row_h")。
 *
 * 颜色只有一套（res/values/colors.xml），两版共用，保证语义一致。
 */
public final class Tokens {

    private static final String PREFS = "xtbus";
    private static final String KEY_LARGE = "large_edition";

    private static final HashMap<String, Integer> ID_CACHE = new HashMap<String, Integer>();
    private static boolean large;

    private Tokens() {
    }

    /** 每个 Activity 在 setContentView 之前调用。 */
    public static void load(Context context) {
        large = prefs(context).getBoolean(KEY_LARGE, false);
    }

    public static boolean isLarge() {
        return large;
    }

    public static void setLarge(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_LARGE, value).apply();
        large = value;
        ID_CACHE.clear();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 尺寸 ----------------

    /** 取像素尺寸（dp 类令牌）。 */
    public static int dp(Context context, String token) {
        return context.getResources().getDimensionPixelSize(id(context, "dimen", edition(token)));
    }

    /** 取像素字号（sp 类令牌），已按系统字体缩放换算。 */
    public static float sp(Context context, String token) {
        return context.getResources().getDimension(id(context, "dimen", edition(token)));
    }

    public static int color(Context context, String token) {
        return context.getColor(id(context, "color", "xt_" + token));
    }

    // ---------------- 便捷写法 ----------------

    public static void textSize(TextView view, String token) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(view.getContext(), token));
    }

    /**
     * 固定容器（到站牌、线路号牌）专用：不跟随系统字体缩放，
     * 只跟随 App 内的大字/小字版，避免系统字体放大后文字被裁。
     */
    public static void textSizeFixed(TextView view, String token) {
        Context c = view.getContext();
        float density = c.getResources().getDisplayMetrics().density;
        float scaled = c.getResources().getDisplayMetrics().scaledDensity;
        float px = sp(c, token);
        if (scaled > 0) {
            px = px * density / scaled;
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    public static void padding(TextView view, String l, String t, String r, String b) {
        Context c = view.getContext();
        view.setPadding(pad(c, l), pad(c, t), pad(c, r), pad(c, b));
    }

    private static int pad(Context c, String token) {
        return token == null || token.isEmpty() ? 0 : dp(c, token);
    }

    // ---------------- 内部 ----------------

    private static String edition(String token) {
        return token + (large ? "_l" : "_c");
    }

    private static int id(Context context, String type, String name) {
        String key = type + "/" + name;
        Integer cached = ID_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int res = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (res == 0) {
            throw new IllegalStateException("missing " + key);
        }
        ID_CACHE.put(key, res);
        return res;
    }
}
