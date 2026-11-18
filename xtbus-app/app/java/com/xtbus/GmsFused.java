package com.xtbus;

import android.content.Context;
import android.location.Location;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 走 Google Play 服务的融合定位（FusedLocationProviderClient）。
 *
 * 为什么用反射而不是直接依赖：工程是纯 javac + d8、不带任何第三方运行库，
 * 加 compileOnly 依赖会让构建脚本变复杂；反射只在装了 GMS 的机器上启用，
 * 没装的机器（国内多数 ROM）自动退回系统 LocationManager，不影响安装包。
 *
 * 融合定位的效果跟高德/系统地图一致：GPS + WiFi + 基站 + 传感器融合，
 * 室内也能几秒出点，这是纯 LocationManager 拿不到网络定位时最大的差距。
 */
final class GmsFused {

    interface Sink {
        void onFix(Location l);
    }

    private Object client;
    private Object listener;
    private boolean started;

    /** 探测 GMS 是否可用（未安装时 Class.forName 直接抛异常，代价极小）。 */
    static boolean available(Context c) {
        try {
            Class.forName("com.google.android.gms.location.LocationServices");
            Class.forName("com.google.android.gms.common.GoogleApiAvailability");
            Object availability = Class.forName("com.google.android.gms.common.GoogleApiAvailability")
                    .getMethod("getInstance").invoke(null);
            int code = (Integer) availability.getClass()
                    .getMethod("isGooglePlayServicesAvailable", Context.class)
                    .invoke(availability, c);
            return code == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 开始接收融合定位。返回 false 表示这条通道不可用，调用方继续用系统定位。 */
    boolean start(Context c, Sink sink) {
        if (started) {
            return true;
        }
        try {
            Class<?> services = Class.forName("com.google.android.gms.location.LocationServices");
            client = services.getMethod("getFusedLocationProviderClient", Context.class)
                    .invoke(null, c);
            if (client == null) {
                return false;
            }
            final Class<?> reqCls = Class.forName("com.google.android.gms.location.LocationRequest");
            Object req = reqCls.getMethod("create").invoke(null);
            // PRIORITY_HIGH_ACCURACY = 100，与高精定位同等优先级
            req = reqCls.getMethod("setPriority", int.class).invoke(req, 100);
            req = reqCls.getMethod("setInterval", long.class).invoke(req, 1000L);
            req = reqCls.getMethod("setFastestInterval", long.class).invoke(req, 1000L);
            req = reqCls.getMethod("setSmallestDisplacement", float.class).invoke(req, 0f);

            final Class<?> lisCls = Class.forName("com.google.android.gms.location.LocationListener");
            listener = Proxy.newProxyInstance(lisCls.getClassLoader(), new Class<?>[]{lisCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method m, Object[] args) {
                            if ("onLocationChanged".equals(m.getName()) && args != null
                                    && args.length == 1 && args[0] instanceof Location) {
                                sink.onFix((Location) args[0]);
                            }
                            return null;
                        }
                    });
            client.getClass()
                    .getMethod("requestLocationUpdates", reqCls, lisCls, Looper.class)
                    .invoke(client, req, listener, Looper.getMainLooper());
            started = true;
            android.util.Log.i("WalkD", "gms fused started");
            return true;
        } catch (Throwable t) {
            android.util.Log.i("WalkD", "gms fused unavailable: " + t);
            return false;
        }
    }

    void stop() {
        if (!started || client == null || listener == null) {
            return;
        }
        try {
            Class<?> lisCls = Class.forName("com.google.android.gms.location.LocationListener");
            client.getClass().getMethod("removeLocationUpdates", lisCls).invoke(client, listener);
        } catch (Throwable ignored) {
        }
        started = false;
    }
}
