package com.xtbus;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 线路名与时间文本的统一口径，避免各页面各写一套。
 * 只做纯函数，不持有 Context。
 */
final class Fmt {

    private static final Pattern HM = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::\\d{2})?$");

    private Fmt() {
    }

    /** 线路名补「路」：以数字结尾时补，其余原样。 */
    static String displayName(String name) {
        return displayName(name, "线路");
    }

    /** 同上，空值用调用方给的兜底文案。 */
    static String displayName(String name, String fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        char last = name.charAt(name.length() - 1);
        return last >= '0' && last <= '9' ? name + "路" : name;
    }

    /** 号牌用短名：去掉「路」并截到 4 字。 */
    static String shortLine(String name) {
        if (name == null) {
            return "公交";
        }
        String v = name.replace("路", "");
        return v.length() > 4 ? v.substring(0, 4) : v;
    }

    /** 站序节点用：去掉「路」「线」。 */
    static String nodeName(String name) {
        return name == null ? "" : name.replace("路", "").replace("线", "");
    }

    /**
     * 时间字段统一成 HH:mm。兼容 H:mm、HHmm、HH:mm:ss 与 10/13 位时间戳，
     * 识别不了返回空串（页面按“无首末班”处理），不输出非法时间。
     */
    static String hhmm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.matches("\\d{10,13}")) {
            try {
                long value = Long.parseLong(s);
                long ms = s.length() >= 13 ? value : value * 1000L;
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(ms);
                return String.format(Locale.CHINA, "%02d:%02d",
                        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
            } catch (Exception e) {
                return "";
            }
        }
        Matcher m = HM.matcher(s);
        if (m.matches()) {
            int h = parseInt(m.group(1));
            int min = parseInt(m.group(2));
            return valid(h, min) ? String.format(Locale.CHINA, "%02d:%02d", h, min) : "";
        }
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() == 3) {
            digits = "0" + digits;
        }
        if (digits.length() < 4) {
            return "";
        }
        int h = parseInt(digits.substring(0, 2));
        int min = parseInt(digits.substring(2, 4));
        return valid(h, min) ? String.format(Locale.CHINA, "%02d:%02d", h, min) : "";
    }

    /** HH:mm → 当日分钟数，失败返回 0。 */
    static int parseHm(String hm) {
        String text = hhmm(hm);
        if (text.length() < 5) {
            return 0;
        }
        return parseInt(text.substring(0, 2)) * 60 + parseInt(text.substring(3, 5));
    }

    /** 当前本地时间的当日分钟数。 */
    static int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /**
     * 末班是否已过：跨零点（末班 20 点后、现在凌晨 5 点前）视为已过；
     * 早班线路（末班早于 6 点）也能正确判断。
     */
    static boolean lastBusGone(int nowMinutes, int endMinutes) {
        if (endMinutes <= 0) {
            return false;
        }
        if (endMinutes >= 20 * 60 && nowMinutes < 5 * 60) {
            return true;
        }
        return nowMinutes > endMinutes;
    }

    private static boolean valid(int h, int min) {
        return h >= 0 && h <= 23 && min >= 0 && min <= 59;
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
