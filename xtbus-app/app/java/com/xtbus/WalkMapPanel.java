package com.xtbus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线步行小地图：底图来自打进 assets 的 mapsforge .map（湘潭范围），
 * 不联网、不下载瓦片。路线、转向点、当前位置都是叠加图层。
 *
 * 手势由 MapView 自己处理（双指缩放/拖动），这里只旁听触摸：
 * 用户一动地图就暂停跟随，由页面上的「回到当前」恢复。
 */
public class WalkMapPanel extends FrameLayout {

    public interface FollowListener {
        void onFollowChanged(boolean following);
    }

    private MapView mapView;
    private Polyline routeLine;
    /** 已走过路段（暖灰 xt_ink3），画在 routeLine 之下，未走部分仍保持墨绿。 */
    private Polyline passedLine;
    private WalkRouter.Route route;
    private final List<Marker> turnMarkers = new ArrayList<Marker>();
    private Bitmap dotMe;
    private Bitmap dotTurn;
    private Bitmap dotEnd;

    private Marker me;
    private boolean following = true;
    private boolean hasRoute;
    private FollowListener listener;
    private boolean ready;
    /** 当前地图旋转角（方位角，北=0 顺时针），-1 表示尚未收到过有效 bearing。 */
    private float currentBearing = -1;
    /** 已走过段的终点（路线线段索引），只增不减，避免定位抖动让灰段来回跳。 */
    private int passedSeg = -1;
    /** 路线外接框：定位点离路线太远时不跟随，免得把整条路线甩出画面。 */
    private double rMinLat, rMaxLat, rMinLon, rMaxLon;
    /** 底图在后台准备期间，先把路线/定位/居中请求存下来，就绪后一次性补上。 */
    private WalkRouter.Route pendingRoute;
    private double pendFixLat = Double.NaN, pendFixLon = Double.NaN;
    private double pendCenterLat = Double.NaN, pendCenterLon = Double.NaN;
    /** 圆点位图半径（px），按屏幕密度换算，避免不同机器上一个针尖一个饼。 */
    private int meAnchor = 8, turnAnchor = 5, endAnchor = 6;

    public WalkMapPanel(Context c, android.util.AttributeSet attrs) {
        super(c, attrs);
        init(c);
    }

    public WalkMapPanel(Context c) {
        super(c);
        init(c);
    }

    private void init(Context c) {
        AndroidGraphicFactory.createInstance(c.getApplicationContext());

        mapView = new MapView(c);
        // MapView.onTouchEvent 第一行判断 isClickable，不设 true 拖动/双指缩放全部无效，
        // 表现为：手指划地图没反应，「回到当前」按了也看不出变化。
        mapView.setClickable(true);
        // 先用 MATCH_PARENT 保证任何情况下都有尺寸（0×0 会让所有图层都无处绘制，整片空白）；
        // onSizeChanged 里再换成对角线尺寸，旋转后四角才不露底。
        addView(mapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mapView.setBuiltInZoomControls(false);
        mapView.setZoomLevelMin((byte) 10);
        mapView.setZoomLevelMax((byte) 19);
        mapView.setZoomLevel((byte) 16);
        // 不要比例尺刻度条（左下角那条 200 m / 500 ft）
        mapView.setMapScaleBar(null);
        mapView.getModel().displayModel.setBackgroundColor(0xFFF6F2EA);

        // 底图复制 / mmap 放到工作线程，避免首次进页面时在主线程拷 3.5 MB
        ready = false;
        prepareBase();

        float routeWidth = dp(3f);
        org.mapsforge.core.graphics.Paint passedStroke = AndroidGraphicFactory.INSTANCE.createPaint();
        passedStroke.setColor(0xFF8C8377);
        passedStroke.setStrokeWidth(routeWidth);
        passedStroke.setStyle(Style.STROKE);
        passedLine = new Polyline(passedStroke, AndroidGraphicFactory.INSTANCE);
        mapView.getLayerManager().getLayers().add(passedLine);

        org.mapsforge.core.graphics.Paint stroke = AndroidGraphicFactory.INSTANCE.createPaint();
        stroke.setColor(0xFF16302B);
        stroke.setStrokeWidth(routeWidth);
        stroke.setStyle(Style.STROKE);
        routeLine = new Polyline(stroke, AndroidGraphicFactory.INSTANCE);
        mapView.getLayerManager().getLayers().add(routeLine);

        // 圆点尺寸按 dp 换算：写死 px 在高密度屏上只有几个 dp，低密度屏又巨大
        int meSize = dp(16);
        int turnSize = dp(9);
        int endSize = dp(12);
        meAnchor = meSize / 2;
        turnAnchor = turnSize / 2;
        endAnchor = endSize / 2;
        dotMe = circle(meSize, 0xFF2B6CF0, 0xFFFFFFFF, dp(1.5f));
        dotTurn = circle(turnSize, 0xFFFFFFFF, 0xFF16302B, dp(1f));
        dotEnd = circle(endSize, 0xFFC0452F, 0xFFFFFFFF, dp(1.5f));

        mapView.setOnTouchListener(new OnTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // 双指一定是用户主动缩放，直接退出跟随
                        setFollowing(false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        // 只有真的拖动（超过系统 touch slop）才取消跟随，
                        // 否则轻点和布局期的零星 MOVE 会把跟随误关掉
                        if (e.getPointerCount() > 1) {
                            setFollowing(false);
                        } else {
                            double dx = e.getX() - downX;
                            double dy = e.getY() - downY;
                            if (Math.sqrt(dx * dx + dy * dy) > ViewConfiguration.get(
                                    getContext()).getScaledTouchSlop()) {
                                setFollowing(false);
                            }
                        }
                        break;
                }
                return false;
            }
        });
    }

    /** 底图准备：拷贝 + mmap 都在工作线程，图层对象回主线程创建。 */
    private void prepareBase() {
        Io.query(new Runnable() {
            @Override
            public void run() {
                final MapFile data;
                try {
                    data = openMapFile();
                } catch (Throwable t) {
                    android.util.Log.e("WalkMap", "initBase failed", t);
                    return;
                }
                Io.ui(new Runnable() {
                    @Override
                    public void run() {
                        buildMap(data);
                    }
                });
            }
        });
    }

    private MapFile openMapFile() throws Exception {
        // mapsforge 要随机读文件，AssetInputStream 不支持，首启复制一份到私有目录（约 3.5 MB）
        // 版本号随切图更新：换了 .map 必须改它，否则已安装用户不会重新复制 assets
        String ver = "2026-11-17";
        java.io.File f = new java.io.File(getContext().getFilesDir(), "xiangtan.map");
        android.content.SharedPreferences sp = getContext()
                .getSharedPreferences("xtbus_map", Context.MODE_PRIVATE);
        if (!f.exists() || !ver.equals(sp.getString("map_ver", ""))) {
            java.io.InputStream in = getContext().getAssets().open("xiangtan.map");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            sp.edit().putString("map_ver", ver).apply();
        }
        return new MapFile(f);
    }

    private void buildMap(MapFile data) {
        try {
            // 瓦片缓存要按放大后的 MapView 算：旋转方案把控件放到对角线尺寸，可视面积约翻倍，
            // 64 张会在一次拖动后把还没画的瓦片挤掉。128 张 ≈ 32 MB 上限。
            TileCache cache = new InMemoryTileCache(128);
            AssetsRenderTheme theme = new AssetsRenderTheme(
                    getContext().getAssets(), "", "xt-map-theme.xml");
            // cacheLabels=true：地名/标注不画进瓦片，改由 LabelLayer 在屏幕坐标系里画，
            // 这样地图旋转时文字仍然水平可读（mapsforge 官方的车头向上用法）。
            TileRendererLayer layer = AndroidUtil.createTileRendererLayer(
                    cache, mapView.getModel().mapViewPosition, data, theme, false, true, true);
            // 底图必须垫在最下面：后台建层晚于路线图层，用 add 会盖住路线
            mapView.getLayerManager().getLayers().add(0, layer);
            mapView.getLayerManager().getLayers().add(
                    1, new org.mapsforge.map.layer.labels.LabelLayer(
                            AndroidGraphicFactory.INSTANCE, layer.getLabelStore()));
            ready = true;
            if (currentBearing >= 0) {
                setBearing(currentBearing);
            }
            if (pendingRoute != null) {
                WalkRouter.Route r = pendingRoute;
                pendingRoute = null;
                setRoute(r);
            }
            if (!Double.isNaN(pendFixLat)) {
                setFix(pendFixLat, pendFixLon);
            }
            if (!Double.isNaN(pendCenterLat)) {
                centerOn(pendCenterLat, pendCenterLon);
            }
            mapView.repaint();
        } catch (Throwable t) {
            android.util.Log.e("WalkMap", "buildMap failed", t);
        }
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 车头向上：地图跟着手机朝向转（和常见地图 App 的导航模式一致）。
     * bearing 传设备朝向（北=0、顺时针），地图反向转 -bearing 后，
     * 手机面对的方向就是屏幕上方；正北在后下方。
     * 小于 2° 的变化直接忽略，省掉每秒几十次重绘。
     */
    public void setBearing(float bearing) {
        if (bearing < 0 || Float.isNaN(bearing)) {
            return;
        }
        if (currentBearing >= 0) {
            float diff = Math.abs(((bearing - currentBearing + 540f) % 360f) - 180f);
            if (diff < 2f) {
                return;
            }
        }
        currentBearing = bearing;
        if (!ready) {
            return;
        }
        int w = mapView.getWidth();
        int h = mapView.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        mapView.rotate(new org.mapsforge.core.model.Rotation(
                -currentBearing, w * 0.5f, h * 0.5f));
        mapView.getLayerManager().redrawLayers();
    }

    public void setFollowListener(FollowListener l) {
        listener = l;
    }

    public boolean isFollowing() {
        return following;
    }

    public void setFollowing(boolean f) {
        if (following == f) {
            return;
        }
        following = f;
        if (listener != null) {
            listener.onFollowChanged(f);
        }
        if (f && me != null) {
            mapView.setCenter(me.getLatLong());
            mapView.repaint();
        }
    }

    // 不重写 onLayout：MapView 用 MATCH_PARENT 铺满面板。
    // 原生旋转时 mapsforge 的帧缓冲本身就是 hypot(w,h) 见方，四角天然够用；
    // 手动放大控件会让帧缓冲与视图尺寸不匹配，画面被推出可视区。
    /** 立即把地图中心移到指定坐标并恢复跟随：打开页面时要直接落在人身上。 */
    public void centerOn(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)
                || Math.abs(lat) < 0.1 || Math.abs(lon) < 0.1) {
            return;
        }
        pendCenterLat = lat;
        pendCenterLon = lon;
        if (!ready) {
            return;
        }
        // 必须走 MapView.setCenter（与「回到当前」按钮同一条路径）。
        // 只调 mapViewPosition.setCenter 不会触发帧缓冲重绘，画面会停在原位。
        mapView.setCenter(new LatLong(lat, lon));
        if (!following) {
            following = true;
            if (listener != null) {
                listener.onFollowChanged(true);
            }
        }
        mapView.repaint();
    }

    public void setFix(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)
                || Math.abs(lat) < 0.1 || Math.abs(lon) < 0.1) {
            return;
        }
        pendFixLat = lat;
        pendFixLon = lon;
        if (!ready) {
            return;
        }
        LatLong p = new LatLong(lat, lon);
        if (me == null) {
            me = new Marker(p, dotMe, meAnchor, meAnchor);
            mapView.getLayerManager().getLayers().add(me);
        } else {
            me.setLatLong(p);
        }
        if (following) {
            if (nearRoute(p)) {
                mapView.setCenter(p);
            } else {
                // 离路线太远时不再强行把视野拽过去，但必须退出跟随态：
                // 否则「回到当前」不会出现，地图停着不动又没有入口，像卡死。
                setFollowing(false);
            }
        }
        updatePassed(lat, lon);
        mapView.repaint();
    }

    /** 路线与转向点。 */
    public void setRoute(WalkRouter.Route r) {
        pendingRoute = r;
        android.util.Log.i("WalkD", "setRoute ready=" + ready + " r=" + (r == null)
                + " lats=" + (r != null && r.lats != null ? r.lats.length : -1)
                + " steps=" + (r != null ? r.steps.size() : -1));
        if (!ready || r == null || r.lats == null || r.lats.length < 2) {
            return;
        }
        pendingRoute = null;
        this.route = r;
        passedSeg = -1;
        List<LatLong> pts = new ArrayList<LatLong>(r.lats.length);
        for (int i = 0; i < r.lats.length; i++) {
            pts.add(new LatLong(r.lats[i], r.lons[i]));
        }
        passedLine.clear();
        routeLine.clear();
        routeLine.addPoints(pts);

        for (Marker m : turnMarkers) {
            mapView.getLayerManager().getLayers().remove(m);
        }
        turnMarkers.clear();
        for (int i = 0; i < r.steps.size() - 1; i++) {
            WalkRouter.Step s = r.steps.get(i);
            if (s.lat == 0 && s.lon == 0) {
                continue;
            }
            Marker m = new Marker(new LatLong(s.lat, s.lon), dotTurn, turnAnchor, turnAnchor);
            turnMarkers.add(m);
            mapView.getLayerManager().getLayers().add(m);
        }
        LatLong end = new LatLong(r.lats[r.lats.length - 1], r.lons[r.lats.length - 1]);
        Marker endMark = new Marker(end, dotEnd, endAnchor, endAnchor);
        turnMarkers.add(endMark);
        mapView.getLayerManager().getLayers().add(endMark);

        hasRoute = true;
        fitRoute(r);
        mapView.repaint();
        android.util.Log.i("WalkD", "setRoute ok layers=" + mapView.getLayerManager().getLayers().size()
                + " zoom=" + mapView.getModel().mapViewPosition.getZoomLevel()
                + " center=" + mapView.getModel().mapViewPosition.getCenter()
                + " viewSize=" + mapView.getWidth() + "x" + mapView.getHeight());
    }

    /**
     * 把路线在「当前位置在路线上的投影点」处拆成两段：已走过用暖灰、未走过仍用墨绿。
     * 与 WalkRouter.Route.distanceTo 同一套投影算法，偏航超过 45 m 时保持上一次灰段不动，
     * 避免人偏离路线时灰段跳到离自己最近的路点。
     */
    private void updatePassed(double lat, double lon) {
        if (route == null || route.lats == null || route.lats.length < 2) {
            return;
        }
        double bestD = Double.MAX_VALUE;
        int bestSeg = 0;
        double bestT = 0;
        double cos = Math.cos(Math.toRadians(lat));
        double[] lats = route.lats;
        double[] lons = route.lons;
        for (int i = 0; i + 1 < lats.length; i++) {
            double ax = (lons[i] - lon) * 111320.0 * cos;
            double ay = (lats[i] - lat) * 111320.0;
            double bx = (lons[i + 1] - lon) * 111320.0 * cos;
            double by = (lats[i + 1] - lat) * 111320.0;
            double dx = bx - ax;
            double dy = by - ay;
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : -(ax * dx + ay * dy) / l2;
            if (t < 0) {
                t = 0;
            } else if (t > 1) {
                t = 1;
            }
            double px = ax + t * dx;
            double py = ay + t * dy;
            double d = px * px + py * py;
            if (d < bestD) {
                bestD = d;
                bestSeg = i;
                bestT = t;
            }
        }
        if (Math.sqrt(bestD) > 45) {
            android.util.Log.i("WalkD", "passed skip off=" + (int) Math.sqrt(bestD));
            return;
        }
        // 灰段只前进不后退：定位抖动把投影点拉回前一段时保持原样，别让灰线来回跳
        if (bestSeg < passedSeg) {
            return;
        }
        passedSeg = bestSeg;
        double projLat = lats[bestSeg] + bestT * (lats[bestSeg + 1] - lats[bestSeg]);
        double projLon = lons[bestSeg] + bestT * (lons[bestSeg + 1] - lons[bestSeg]);

        List<LatLong> passed = new ArrayList<LatLong>(bestSeg + 2);
        for (int i = 0; i <= bestSeg; i++) {
            passed.add(new LatLong(lats[i], lons[i]));
        }
        passed.add(new LatLong(projLat, projLon));

        List<LatLong> remain = new ArrayList<LatLong>(lats.length - bestSeg);
        remain.add(new LatLong(projLat, projLon));
        for (int i = bestSeg + 1; i < lats.length; i++) {
            remain.add(new LatLong(lats[i], lons[i]));
        }

        passedLine.clear();
        if (passed.size() >= 2) {
            passedLine.addPoints(passed);
        }
        routeLine.clear();
        if (remain.size() >= 2) {
            routeLine.addPoints(remain);
        }
    }

    /** 定位点是否落在路线附近（外接框外扩约 200 m）。 */
    private boolean nearRoute(LatLong p) {
        if (!hasRoute) {
            return true;
        }
        double pad = 0.0018;
        return p.latitude >= rMinLat - pad && p.latitude <= rMaxLat + pad
                && p.longitude >= rMinLon - pad && p.longitude <= rMaxLon + pad;
    }

    private void fitRoute(WalkRouter.Route r) {
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (int i = 0; i < r.lats.length; i++) {
            if (r.lats[i] < minLat) {
                minLat = r.lats[i];
            }
            if (r.lats[i] > maxLat) {
                maxLat = r.lats[i];
            }
            if (r.lons[i] < minLon) {
                minLon = r.lons[i];
            }
            if (r.lons[i] > maxLon) {
                maxLon = r.lons[i];
            }
        }
        rMinLat = minLat;
        rMaxLat = maxLat;
        rMinLon = minLon;
        rMaxLon = maxLon;
        double spanLat = (maxLat - minLat) * 111320.0;
        double spanLon = (maxLon - minLon) * 111320.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2));
        // 按控件实际像素定级：从最大级别往下找第一个能把整条路线装进视野的级别。
        // mapsforge 只支持整数级别，用「多少米给几级」的经验表会时松时紧。
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            vw = (int) (360 * getResources().getDisplayMetrics().density);
            vh = (int) (190 * getResources().getDisplayMetrics().density);
        }
        double latC = (minLat + maxLat) / 2;
        byte z = 11;
        // 地图旋转后路线的屏幕外接框会变宽变高：按旋转角把两个方向的跨度重新合成，
        // 否则「车头朝上」之后路线两端会被裁出画面。
        double spin = currentBearing < 0 ? 0 : currentBearing;
        double cosB = Math.abs(Math.cos(Math.toRadians(spin)));
        double sinB = Math.abs(Math.sin(Math.toRadians(spin)));
        double needW = spanLon * cosB + spanLat * sinB;
        double needH = spanLon * sinB + spanLat * cosB;
        // 缩放上限 16：视野宽约 2.1 km。这份离线切图在 17 级只剩 4~5 条路，
        // 再放大就是一片空白，不但看不出自己在哪，还会让人以为地图坏了。
        for (int cand = 16; cand >= 11; cand--) {
            double mpp = 156543.03392 * Math.cos(Math.toRadians(latC)) / (1 << cand);
            if (needH <= vh * 0.94 * mpp && needW <= vw * 0.94 * mpp) {
                z = (byte) cand;
                break;
            }
        }
        mapView.setZoomLevel(z);
        mapView.setCenter(new LatLong((minLat + maxLat) / 2, (minLon + maxLon) / 2));
    }

    /** 画一个实心圆点（外圈描边），转成人地图图层能用的位图。 */
    private Bitmap circle(int size, int fill, int ring, float ringWidth) {
        android.graphics.Bitmap ab = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(ab);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawCircle(r, r, r - ringWidth, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(ringWidth);
        p.setColor(ring);
        c.drawCircle(r, r, r - ringWidth / 2f, p);
        return new org.mapsforge.map.android.graphics.AndroidBitmap(ab);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (ready && currentBearing >= 0) {
            // 旋转支点是控件像素中心，尺寸变化后必须按新尺寸重新落一次
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    setBearing(currentBearing);
                }
            });
        }
    }

    public void onDestroy() {
        try {
            mapView.destroyAll();
        } catch (Throwable ignored) {
        }
    }
}
