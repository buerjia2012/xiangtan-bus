package com.xtbus;

/**
 * 坐标系换算。全离线，只用数学公式，不依赖任何库。
 *
 * 基准事实（实测 2026-11-15，湘潭 733 个站点）：
 * - 系统 GPS 原始坐标 = WGS-84
 * - 接口 bus/line!lineRoute 的站点 = BD-09（字段 gpsType=bd），与 WGS-84 差约 1205 m
 * - 接口 transfer/transit 里的站点 location = GCJ-02（字段 gpstype=gcj）
 * - OSM 路网 = WGS-84
 *
 * 所以进入步行路网前必须统一到 WGS-84。
 */
final class WalkCoord {

    static final int DATUM_WGS = 0;
    static final int DATUM_GCJ = 1;
    static final int DATUM_BD = 2;

    private static final double AXIS = 6378245.0;
    private static final double EE = 0.00669342162296594323;
    private static final double X_PI = Math.PI * 3000.0 / 180.0;

    private WalkCoord() {
    }

    /** 任意基准 -> WGS-84。 */
    static double[] toWgs(double lng, double lat, int datum) {
        if (datum == DATUM_GCJ) {
            return gcj2wgs(lng, lat);
        }
        if (datum == DATUM_BD) {
            return bd2wgs(lng, lat);
        }
        return new double[]{lng, lat};
    }

    static double[] wgs2gcj(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dLat = transLat(lng - 105.0, lat - 35.0);
        double dLng = transLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    /** 迭代反解，误差 < 1e-9 度。 */
    static double[] gcj2wgs(double lng, double lat) {
        double wlng = lng;
        double wlat = lat;
        for (int i = 0; i < 6; i++) {
            double[] g = wgs2gcj(wlng, wlat);
            wlng += lng - g[0];
            wlat += lat - g[1];
        }
        return new double[]{wlng, wlat};
    }

    static double[] gcj2bd(double lng, double lat) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double t = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[]{z * Math.cos(t) + 0.0065, z * Math.sin(t) + 0.006};
    }

    static double[] bd2gcj(double lng, double lat) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double t = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.cos(t), z * Math.sin(t)};
    }

    static double[] wgs2bd(double lng, double lat) {
        double[] g = wgs2gcj(lng, lat);
        return gcj2bd(g[0], g[1]);
    }

    static double[] bd2wgs(double lng, double lat) {
        double[] g = bd2gcj(lng, lat);
        return gcj2wgs(g[0], g[1]);
    }

    private static boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transLat(double x, double y) {
        double r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return r;
    }

    private static double transLng(double x, double y) {
        double r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return r;
    }

    /** 米制距离（haversine）。 */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
