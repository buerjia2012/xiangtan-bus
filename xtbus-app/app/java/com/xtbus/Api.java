package com.xtbus;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebSettings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 车来了 H5 接口的最小实现（只读 GET）。
 *
 * 请求契约按 customer_ch5 的 Rest 服务对齐：
 * - 公共参数：s/v/vc/src/userId/h5Id/sign/cityId/geo_lat/geo_lng/lat/lng/gpstype
 * - 仅 3 类加密接口带 cryptoSign，且只签接口自己的 payload
 * - GET 也带 Content-Type: text/plain;charset=UTF-8
 *
 * 另外做了三件事：每安装唯一身份、短 TTL 缓存、失败分类与指数退避。
 */
public final class Api {

    private static final String TAG = "xtbus-http";
    private static final boolean DEBUG_LOG = false;

    private static final String BASE = "https://web.chelaile.net.cn/api/";
    private static final String FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14; wv) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/120.0 Mobile Safari/537.36";
    // 以下两个常量与 H5 客户端的接口契约一致；服务端调整加密/签名时必须同步更新
    private static final String AES_KEY = "422556651C7F7B2B5C266EED06068230";
    private static final String SIGN_SALT = "qwihrnbtmj";

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;

    // TTL 比各自的自动刷新周期短一截，避免定时器到点时仍命中缓存
    private static final long NEARBY_TTL = 30L * SECOND;
    private static final long DETAIL_TTL = 20L * SECOND;
    private static final long BUS_TTL = 15L * SECOND;
    private static final long ROUTE_TTL = 24L * HOUR;
    private static final long SEARCH_TTL = 10L * MINUTE;
    private static final long PLAN_TTL = 45L * SECOND;

    /** 缓存总量与文件数上限，超出后按修改时间淘汰。 */
    private static final long CACHE_MAX_BYTES = 5L * 1024 * 1024;
    private static final int CACHE_MAX_FILES = 200;
    private static final long MAX_STALE = 24L * HOUR;

    private static final long[] RISK_DELAYS = {2L * MINUTE, 5L * MINUTE, 15L * MINUTE, 30L * MINUTE};
    private static final long[] NET_DELAYS = {60L * SECOND, 120L * SECOND, 300L * SECOND};

    /** 湘潭在市来了的城市 ID，实测固定为 173。 */
    public static final String CITY_ID = "173";

    /** 当前坐标（GCJ-02），默认湘潭市中心。 */
    private static volatile double sLat = 27.823894;
    private static volatile double sLng = 112.938037;

    private static Context sContext;
    private static String sUserId;
    private static String sUserAgent = FALLBACK_UA;

    private static final Object CACHE_LOCK = new Object();

    // 退避状态（持久化，避免杀进程重开继续打）
    private static volatile int sRiskLevel;
    private static volatile long sRiskUntil;
    private static volatile int sNetFails;
    private static volatile long sNetUntil;
    /** 连成多少次后风控等级降一级，避免一次成功就把计数清零。 */
    private static int sOkStreak;

    private enum SignMode {
        NONE,
        CITY_STATE,
        FULL
    }

    public static final class Result {
        public final JSONObject data;
        public final boolean fromCache;
        public final long fetchedAt;
        /** 本次是失败/冷却后用缓存兜底。 */
        public final boolean degraded;
        /** 本次是风控/冷却导致的兜底，调用方应停止自动刷新。 */
        public final boolean risk;

        private Result(JSONObject data, boolean fromCache, long fetchedAt,
                       boolean degraded, boolean risk) {
            this.data = data;
            this.fromCache = fromCache;
            this.fetchedAt = fetchedAt;
            this.degraded = degraded;
            this.risk = risk;
        }
    }

    public static class ApiException extends Exception {
        public enum Kind {
            BUSINESS,
            RISK,
            NETWORK,
            COOLDOWN
        }

        public final Kind kind;

        public ApiException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    private Api() {
    }

    /** 每个进程调用一次即可；重复调用无副作用。 */
    public static synchronized void init(Context context) {
        if (sContext != null || context == null) {
            return;
        }
        sContext = context.getApplicationContext();

        SharedPreferences identity = sContext.getSharedPreferences("xtbus_identity", Context.MODE_PRIVATE);
        String uid = identity.getString("userId", "");
        if (uid == null || !uid.startsWith("browser_")) {
            uid = "browser_" + Long.toString(System.currentTimeMillis(), 36) + "_" + random4();
            identity.edit().putString("userId", uid).apply();
        }
        sUserId = uid;

        try {
            String ua = WebSettings.getDefaultUserAgent(sContext);
            if (ua != null && !ua.isEmpty()) {
                sUserAgent = ua;
            }
        } catch (Throwable ignored) {
        }

        SharedPreferences risk = riskPrefs();
        sRiskLevel = risk.getInt("level", 0);
        sRiskUntil = risk.getLong("risk_until", 0);
        sNetFails = risk.getInt("net_fails", 0);
        sNetUntil = risk.getLong("net_until", 0);

        // 网络恢复时立刻清零网络退避，避免连通后仍被旧冷却挡住
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    sContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        clearNetCooldown();
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        // 只有拿到「已验证可达」的网络才解除退避，弱网超时不解除
                        if (caps != null
                                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            clearNetCooldown();
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    public static void setLocation(double lat, double lng) {
        sLat = lat;
        sLng = lng;
    }

    public static double getLat() {
        return sLat;
    }

    public static double getLng() {
        return sLng;
    }

    /** 距离冷却结束还有多久（毫秒）。风控与网络取较长者，仅用于展示。 */
    public static long cooldownRemaining() {
        long until = Math.max(sRiskUntil, sNetUntil);
        return Math.max(0, until - System.currentTimeMillis());
    }

    /** 服务端风控冷却剩余时间。 */
    public static long riskCooldownRemaining() {
        return Math.max(0, sRiskUntil - System.currentTimeMillis());
    }

    /** 网络失败退避剩余时间。 */
    public static long netCooldownRemaining() {
        return Math.max(0, sNetUntil - System.currentTimeMillis());
    }

    private static void clearNetCooldown() {
        if (sNetFails == 0 && sNetUntil == 0) {
            return;
        }
        sNetFails = 0;
        sNetUntil = 0;
        persistRisk();
    }

    private static boolean networkValidated() {
        if (sContext == null) {
            return false;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    sContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String userId() {
        return sUserId;
    }

    // ---------------- 接口 ----------------

    /** 附近站点及其线路。force=true 时跳过新鲜缓存（手动刷新/定时刷新）。 */
    public static Result nearby(boolean force) throws ApiException {
        LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
        p.put("cityState", "2");
        // 缓存键量化到 4 位小数（约 10 米网格），避免定位抖动导致缓存永远不命中
        String key = "nearby:" + fmt4(sLat) + "," + fmt4(sLng);
        return call("bus/stop!encryptedNearlines.action", p, SignMode.CITY_STATE,
                false, key, NEARBY_TTL, "*/*", force);
    }

    /** 线路/站点搜索。 */
    public static Result search(String keyword, boolean force) throws ApiException {
        LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
        p.put("key", keyword);
        p.put("count", "20");
        String key = "search:20:" + keyword;
        return call("basesearch/client/clientSearch.action", p, SignMode.NONE,
                false, key, SEARCH_TTL, "text/plain", force);
    }

    /** 实时到站：targetOrder 取附近列表的 targetStation.order。 */
    public static Result busList(String lineId, String targetOrder,
                                 String stationName, String nextStationName,
                                 boolean force) throws ApiException {
        LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
        p.put("lineId", lineId);
        p.put("targetOrder", targetOrder == null || targetOrder.isEmpty() ? "-1" : targetOrder);
        if (stationName != null && !stationName.isEmpty()) {
            p.put("stationName", stationName);
        }
        if (nextStationName != null && !nextStationName.isEmpty()) {
            p.put("nextStationName", nextStationName);
        }
        String key = "bus:" + lineId + ":" + targetOrder + ":" + stationName + ":" + nextStationName;
        return call("bus/line!busList.action", p, SignMode.NONE, false, key,
                BUS_TTL, "*/*", force);
    }

    /**
     * 线路详情：与 H5 #!/linedetail 的 p() 保持同样的字段与顺序。
     */
    public static Result lineDetail(String lineId, String lineName, String direction,
                                    String stationName, String nextStationName,
                                    String lineNo, String targetOrder,
                                    boolean force) throws ApiException {
        LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
        putIfNotEmpty(p, "lineId", lineId);
        putIfNotEmpty(p, "lineName", lineName);
        putIfNotEmpty(p, "direction", direction);
        putIfNotEmpty(p, "stationName", stationName);
        putIfNotEmpty(p, "nextStationName", nextStationName);
        putIfNotEmpty(p, "lineNo", lineNo);
        putIfNotEmpty(p, "targetOrder", targetOrder);
        String key = "detail:" + lineId + ":" + lineName + ":" + direction + ":"
                + stationName + ":" + nextStationName + ":" + lineNo + ":" + targetOrder;
        return call("bus/line!encryptedLineDetail.action", p, SignMode.FULL,
                false, key, DETAIL_TTL, "*/*", force);
    }

    /** 线路走向（轨迹点 + 站点）。官方 H5 这个接口用 s=android，且不签名。 */
    public static Result lineRoute(String lineId, boolean force) throws ApiException {
        LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
        putIfNotEmpty(p, "lineId", lineId);
        String key = "route:" + lineId;
        return call("bus/line!lineRoute.action", p, SignMode.NONE,
                true, key, ROUTE_TTL, "*/*", force);
    }

    /**
     * 换乘规划，对齐 H5 transferresult 页的请求。
     * strategy：0 最快 / 2 少换乘 / 3 少步行（高德 TransferPolicy）。
     */
    public static Result transfer(String originName, String originSid,
                                  double originLat, double originLng,
                                  String destName, String destSid,
                                  double destLat, double destLng, int strategy,
                                  boolean force)
            throws ApiException {
        LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
        p.put("origin_lng", fmt(originLng));
        p.put("origin_lat", fmt(originLat));
        p.put("dest_lng", fmt(destLng));
        p.put("dest_lat", fmt(destLat));
        p.put("origin_stn", originName == null ? "" : originName);
        p.put("origin_sid", originSid == null ? "" : originSid);
        p.put("dest_stn", destName == null ? "" : destName);
        p.put("dest_sid", destSid == null ? "" : destSid);
        p.put("gpstype", "gcj");
        p.put("strategy", String.valueOf(strategy));
        p.put("s", "h5");
        String key = "transfer:" + originSid + "," + fmt4(originLng) + "," + fmt4(originLat)
                + "->" + destSid + "," + fmt4(destLng) + "," + fmt4(destLat) + ":" + strategy;
        return call("transfer/transit!integrate.action", p, SignMode.NONE,
                false, key, PLAN_TTL, "*/*", force);
    }

    /**
     * 换乘结果页的实时到站：只查方案第一段公交。
     * 该接口自带固定 sign，不能走公共参数里的 sign=1，所以单独拼 URL。
     * lineStn 形如「lineId,sId,nextSId;」。
     */
    public static JSONObject tsfRealInfos(String lineStn) throws ApiException {
        LinkedHashMap<String, String> all = new LinkedHashMap<String, String>();
        all.put("sign", "3njR1hLB/onHXw2F+qhzxw==");
        all.put("lineStn", lineStn);
        all.put("limit", "1");
        all.put("reqSrc", "1");
        all.put("s", "h5");
        all.put("v", "9.1.2");
        all.put("vc", "1");
        all.put("src", "app_xiangtanchuxing");
        all.put("userId", sUserId);
        all.put("h5Id", sUserId);
        all.put("cityId", CITY_ID);
        all.put("geo_lat", fmt(sLat));
        all.put("geo_lng", fmt(sLng));
        all.put("lat", fmt(sLat));
        all.put("lng", fmt(sLng));
        all.put("gpstype", "gcj");
        return parse(httpGet(BASE + "bus/line!encryptedTsfRealInfos.action?" + buildQuery(all), "*/*"));
    }

    /** 当前定位坐标（GCJ-02），规划页作为默认起点。 */
    public static double lat() {
        return sLat;
    }

    public static double lng() {
        return sLng;
    }

    private static void putIfNotEmpty(LinkedHashMap<String, String> p, String key, String value) {
        if (value != null && !value.isEmpty()) {
            p.put(key, value);
        }
    }

    // ---------------- 请求核心 ----------------

    private static Result call(String handler, LinkedHashMap<String, String> payload,
                               SignMode mode, boolean lineRoute, String cacheKey,
                               long cacheTtl, String accept, boolean force) throws ApiException {
        Entry fresh = force ? null : readCache(cacheKey, cacheTtl);
        if (fresh != null) {
            if (DEBUG_LOG) {
                Log.d(TAG, "CACHE " + handler + " age=" + (System.currentTimeMillis() - fresh.t) + "ms");
            }
            return new Result(fresh.data, true, fresh.t, false, false);
        }

        long riskWait = riskCooldownRemaining();
        long netWait = netCooldownRemaining();
        // 退避只在「网络被系统验证可达」或「拿到真实响应」时解除，避免弱网超时下反复打接口
        if (netWait > 0 && networkValidated()) {
            clearNetCooldown();
            netWait = 0;
        }
        if (riskWait > 0 || netWait > 0) {
            Entry stale = readCache(cacheKey, MAX_STALE);
            if (stale != null) {
                boolean risk = riskWait > 0;
                if (DEBUG_LOG) {
                    Log.d(TAG, "COOLDOWN " + handler + " risk=" + riskWait
                            + "ms net=" + netWait + "ms use-stale");
                }
                return new Result(stale.data, true, stale.t, true, risk);
            }
            if (riskWait > 0) {
                throw new ApiException(ApiException.Kind.COOLDOWN, cooldownText(riskWait));
            }
            throw new ApiException(ApiException.Kind.NETWORK, "当前无网络，请检查网络后重试");
        }

        LinkedHashMap<String, String> signed = new LinkedHashMap<String, String>(payload);
        if (mode == SignMode.CITY_STATE) {
            LinkedHashMap<String, String> only = new LinkedHashMap<String, String>();
            only.put("cityState", payload.get("cityState"));
            signed.put("cryptoSign", sign(only));
        } else if (mode == SignMode.FULL) {
            signed.put("cryptoSign", sign(payload));
        }

        LinkedHashMap<String, String> all = commonParams(signed, lineRoute);
        String url = BASE + handler + "?" + buildQuery(all);

        try {
            String body = httpGet(url, accept);
            JSONObject data = parse(body);
            writeCache(cacheKey, data);
            onSuccess();
            if (DEBUG_LOG) {
                Log.d(TAG, "OK " + handler + " bytes=" + body.length());
            }
            return new Result(data, false, System.currentTimeMillis(), false, false);
        } catch (ApiException e) {
            if (DEBUG_LOG) {
                Log.d(TAG, "FAIL " + handler + " kind=" + e.kind + " msg=" + e.getMessage());
            }
            if (e.kind == ApiException.Kind.RISK) {
                onRisk();
                Entry stale = readCache(cacheKey, MAX_STALE);
                if (stale != null) {
                    if (DEBUG_LOG) {
                        Log.d(TAG, "RISK " + handler + " use-stale");
                    }
                    return new Result(stale.data, true, stale.t, true, true);
                }
            } else if (e.kind == ApiException.Kind.NETWORK) {
                onNetwork();
                Entry stale = readCache(cacheKey, MAX_STALE);
                if (stale != null) {
                    if (DEBUG_LOG) {
                        Log.d(TAG, "NETWORK " + handler + " use-stale");
                    }
                    return new Result(stale.data, true, stale.t, true, false);
                }
            } else if (e.kind == ApiException.Kind.BUSINESS) {
                onReachable();
            }
            throw e;
        }
    }

    /** H5 的 o()：payload 在前，公共参数在后；lineRoute 的 s 固定为 android。 */
    private static LinkedHashMap<String, String> commonParams(LinkedHashMap<String, String> payload,
                                                              boolean lineRoute) {
        LinkedHashMap<String, String> all = new LinkedHashMap<String, String>();
        all.putAll(payload);
        String lat = fmt(sLat);
        String lng = fmt(sLng);
        all.put("s", lineRoute ? "android" : "h5");
        all.put("v", "9.1.2");
        all.put("vc", "1");
        all.put("src", "app_xiangtanchuxing");
        all.put("userId", sUserId);
        all.put("h5Id", sUserId);
        all.put("sign", "1");
        all.put("cityId", CITY_ID);
        all.put("geo_lat", lat);
        all.put("geo_lng", lng);
        if (!all.containsKey("lat")) {
            all.put("lat", lat);
        }
        if (!all.containsKey("lng")) {
            all.put("lng", lng);
        }
        if (!all.containsKey("gpstype")) {
            all.put("gpstype", "gcj");
        }
        return all;
    }

    private static String httpGet(String url, String accept) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", sUserAgent);
            conn.setRequestProperty("Referer", referer());
            conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
            conn.setRequestProperty("Accept", accept == null ? "*/*" : accept);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");

            if (DEBUG_LOG) {
                Log.d(TAG, "GET " + url);
                Log.d(TAG, "UA=" + sUserAgent
                        + " | Referer=" + referer()
                        + " | Accept=" + (accept == null ? "*/*" : accept)
                        + " | Content-Type=text/plain;charset=UTF-8");
            }

            int code = conn.getResponseCode();
            if (code == 403 || code == 429 || code == 503) {
                throw new ApiException(ApiException.Kind.RISK, "HTTP " + code);
            }
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new ApiException(code >= 500 ? ApiException.Kind.NETWORK : ApiException.Kind.RISK,
                        "HTTP " + code);
            }
            String text = readAll(in);
            if (code >= 400) {
                if (looksLikeRisk(text)) {
                    throw new ApiException(ApiException.Kind.RISK, "HTTP " + code);
                }
                throw new ApiException(ApiException.Kind.NETWORK, "HTTP " + code);
            }
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ApiException.Kind.NETWORK, "网络异常");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static JSONObject parse(String body) throws ApiException {
        if (body == null) {
            throw new ApiException(ApiException.Kind.NETWORK, "网络异常");
        }
        if (!body.contains("**YGKJ")) {
            // 风控页是 HTML，按 RISK 处理并进入冷却；其余异常响应按网络问题处理，避免误锁。
            if (looksLikeRisk(body)) {
                throw new ApiException(ApiException.Kind.RISK, "访问受限");
            }
            throw new ApiException(ApiException.Kind.NETWORK, "响应形态异常");
        }
        String json = stripMarkers(body);
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            throw new ApiException(ApiException.Kind.RISK, "响应解析失败");
        }

        JSONObject jsonr = root.optJSONObject("jsonr");
        if (jsonr == null) {
            jsonr = root;
        }

        String status = jsonr.optString("status", "");
        if (!status.isEmpty() && !"00".equals(status) && !"OK".equals(status)) {
            String msg = jsonr.optString("errmsg", "");
            if (looksLikeRisk(msg)) {
                throw new ApiException(ApiException.Kind.RISK, msg);
            }
            throw new ApiException(ApiException.Kind.BUSINESS,
                    msg.isEmpty() ? "接口返回异常（status=" + status + "）" : msg);
        }

        JSONObject data = jsonr.optJSONObject("data");
        if (data == null) {
            // 正常但没有数据体（附近无线路、搜索无结果）属于空结果，不能当成风控去锁 2 分钟
            return new JSONObject();
        }

        String encrypted = data.optString("encryptResult", "");
        if (!encrypted.isEmpty()) {
            try {
                return new JSONObject(decrypt(encrypted));
            } catch (Exception e) {
                throw new ApiException(ApiException.Kind.RISK, "数据解密失败");
            }
        }
        return data;
    }

    private static boolean looksLikeRisk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String s = text.toLowerCase(Locale.US);
        return s.contains("<html") || s.contains("captcha") || s.contains("forbidden")
                || s.contains("too many") || text.contains("验证码") || text.contains("人机")
                || text.contains("频繁") || text.contains("风控") || text.contains("访问受限");
    }

    private static String referer() {
        return "https://web.chelaile.net.cn/customer_ch5/?src=app_xiangtanchuxing"
                + "&lat=" + jsEncode(fmt(sLat)) + "&lng=" + jsEncode(fmt(sLng));
    }

    // ---------------- 签名 / 编码 ----------------

    /** 与原 H5 相同：JSON 去花括号后把 ":" 换成 "="、"," 换成 "&"，末尾拼接固定盐再取 MD5。 */
    private static String sign(LinkedHashMap<String, String> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"")
                    .append(escape(e.getValue())).append('"');
        }
        sb.append('}');
        String raw = sb.substring(1, sb.length() - 1).replace(':', '=').replace(',', '&');
        return md5(raw + SIGN_SALT);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("md5 unavailable", e);
        }
    }

    /** 与浏览器 encodeURIComponent 对齐：空格为 %20，且不编码 ! ' ( ) * ~。 */
    private static String jsEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            String encoded = URLEncoder.encode(value, "UTF-8");
            return encoded.replace("+", "%20")
                    .replace("%7E", "~").replace("%7e", "~")
                    .replace("%21", "!").replace("%27", "'")
                    .replace("%28", "(").replace("%29", ")")
                    .replace("%2A", "*").replace("%2a", "*");
        } catch (Exception e) {
            return value;
        }
    }

    private static String buildQuery(LinkedHashMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(jsEncode(e.getKey())).append('=').append(jsEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String decrypt(String base64) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        SecretKeySpec key = new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] out = cipher.doFinal(Base64.decode(base64, Base64.DEFAULT));
        return new String(out, "UTF-8");
    }

    private static String stripMarkers(String body) {
        int start = body.indexOf("**YGKJ");
        int end = body.lastIndexOf("YGKJ##");
        if (start >= 0 && end > start) {
            return body.substring(start + 6, end);
        }
        return body.trim();
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    /** 缓存键用：量化到 4 位小数（约 10 米），抑制 GPS 抖动。 */
    private static String fmt4(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private static String random4() {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyz";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ---------------- 缓存 ----------------

    private static final class Entry {
        JSONObject data;
        long t;
    }

    private static Entry readCache(String key, long ttl) {
        if (sContext == null) {
            return null;
        }
        synchronized (CACHE_LOCK) {
            File f = cacheFile(key);
            if (f == null || !f.exists()) {
                return null;
            }
            try {
                String raw = readFile(f);
                JSONObject wrapper = new JSONObject(raw);
                long t = wrapper.optLong("t", 0);
                if (t <= 0 || System.currentTimeMillis() - t > ttl) {
                    return null;
                }
                JSONObject data = wrapper.optJSONObject("d");
                if (data == null) {
                    return null;
                }
                Entry entry = new Entry();
                entry.data = data;
                entry.t = t;
                return entry;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static void writeCache(String key, JSONObject data) {
        if (sContext == null || data == null) {
            return;
        }
        synchronized (CACHE_LOCK) {
            FileOutputStream out = null;
            try {
                File dir = cacheDir();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                JSONObject wrapper = new JSONObject();
                wrapper.put("t", System.currentTimeMillis());
                wrapper.put("d", data);
                File f = cacheFile(key);
                if (f == null) {
                    return;
                }
                out = new FileOutputStream(f);
                out.write(wrapper.toString().getBytes("UTF-8"));
            } catch (Exception ignored) {
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            pruneCache();
        }
    }

    /** 缓存按修改时间淘汰：超过 200 个文件或 5MB 时删最旧的，避免只增不减。 */
    private static void pruneCache() {
        File dir = cacheDir();
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (files.length <= CACHE_MAX_FILES && total <= CACHE_MAX_BYTES) {
            return;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int count = files.length;
        for (File f : files) {
            if (count <= CACHE_MAX_FILES / 2 && total <= CACHE_MAX_BYTES / 2) {
                break;
            }
            long len = f.length();
            if (f.delete()) {
                total -= len;
                count--;
            }
        }
    }

    private static File cacheDir() {
        return sContext == null ? null : new File(sContext.getCacheDir(), "api");
    }

    private static File cacheFile(String key) {
        File dir = cacheDir();
        return dir == null ? null : new File(dir, md5(key) + ".json");
    }

    private static String readFile(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ---------------- 退避 ----------------

    private static SharedPreferences riskPrefs() {
        return sContext.getSharedPreferences("xtbus_risk", Context.MODE_PRIVATE);
    }

    private static synchronized void persistRisk() {
        if (sContext == null) {
            return;
        }
        riskPrefs().edit()
                .putInt("level", sRiskLevel)
                .putLong("risk_until", sRiskUntil)
                .putInt("net_fails", sNetFails)
                .putLong("net_until", sNetUntil)
                .putInt("ok_streak", sOkStreak)
                .apply();
    }

    /** 连续 3 次成功才降一级风控，避免一次成功就把计数清零。 */
    private static synchronized void onSuccess() {
        sNetFails = 0;
        sNetUntil = 0;
        if (sRiskLevel > 0) {
            sOkStreak++;
            if (sOkStreak >= 3) {
                sRiskLevel--;
                sOkStreak = 0;
            }
        }
        persistRisk();
    }

    private static synchronized void onReachable() {
        sNetFails = 0;
        sNetUntil = 0;
        persistRisk();
    }

    private static synchronized void onRisk() {
        sOkStreak = 0;
        if (sRiskLevel < RISK_DELAYS.length) {
            sRiskLevel++;
        }
        long delay = RISK_DELAYS[Math.min(sRiskLevel - 1, RISK_DELAYS.length - 1)];
        sRiskUntil = System.currentTimeMillis() + delay;
        persistRisk();
    }

    private static synchronized void onNetwork() {
        sNetFails++;
        if (sNetFails >= 3) {
            long delay = NET_DELAYS[Math.min(sNetFails - 3, NET_DELAYS.length - 1)];
            sNetUntil = System.currentTimeMillis() + delay;
        }
        persistRisk();
    }

    private static String cooldownText(long waitMs) {
        long minutes = Math.max(1, (waitMs + MINUTE - 1) / MINUTE);
        return "数据更新稍慢，请约 " + minutes + " 分钟后再试";
    }

    /** WGS-84 转 GCJ-02。 */
    public static double[] wgs2gcj(double lng, double lat) {
        if (outOfChina(lat, lng)) {
            return new double[]{lng, lat};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - 0.00669342162296594323 * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((6335552.717000426 / (magic * sqrtMagic)) * Math.PI);
        dLng = (dLng * 180.0) / ((6378245.0 / sqrtMagic * Math.cos(radLat)) * Math.PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
