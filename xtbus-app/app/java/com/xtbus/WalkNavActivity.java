package com.xtbus;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;

/**
 * 完全离线的步行导航页。
 *
 * 输入（Intent）：
 * - destName / destLat / destLng / destDatum
 * - originLat / originLng / originDatum（可选；不传就用手机当前位置，
 *   即等首帧定位后再规划，等不到才退回 Api 里的上一次定位）
 * - 坐标基准见 WalkCoord；接口 bus/line!lineRoute 是 BD-09，transfer 是 GCJ-02，
 *   系统 GPS 是 WGS-84，路网是 WGS-84。
 */
public class WalkNavActivity extends Activity implements LocationListener {

    private static final long MIN_INTERVAL = 1000L;
    private static final long SLOW_INTERVAL = 4000L;
    private static final float MIN_DISTANCE = 3f;
    private static final double ARRIVE_M = 25.0;
    /** 偏航阈值：离路线垂距超过它且连续命中才重算，避免 GPS 抖动反复重算。 */
    private static final double OFF_ROUTE_M = 45.0;
    private static final int OFF_ROUTE_HITS = 3;
    private static final long REROUTE_COOLDOWN_MS = 15000L;
    private static final int REROUTE_MAX = 6;
    /** 定位漂移过滤：精度差于它、或速度不可能的跳点直接丢弃。 */
    private static final float MAX_ACCURACY = 200f;
    private static final double MAX_SPEED = 12.0;
    private static final float STATIONARY_M = 12f;
    private static final int STATIONARY_HITS = 3;

    private double lastFixLat;
    private double lastFixLon;
    private long lastFixMs;
    private int offRouteHits;
    private long lastRerouteMs;
    private int rerouteCount;
    private int lastActive = -2;
    private float anchorLat;
    private float anchorLon;
    private int stationaryHits;
    private boolean slowRate;
    private boolean rerouting;
    private long lastGpsMs;
    /** 最近一个被采用的定位点的精度，用于择优/抖动判定。 */
    private float lastFixAcc = 999f;
    /** 上次路线计算失败的时刻，用于冷却，避免每帧定位都重算刷屏。 */
    private long lastComputeFailMs;

    private TextView instr;
    private TextView instrSub;
    private TextView meta;
    private TextView state;
    private WalkCompassView compass;

    /** 手机顶部朝向（北=0，顺时针），NaN 表示方向感应不可用。 */
    private float deviceHeading = Float.NaN;
    /** 当前该走的方位角，NaN 表示还没有路线。 */
    private float targetBearing = Float.NaN;
    private SensorManager sensors;
    private final float[] rotMatrix = new float[9];
    private final float[] rotRemap = new float[9];
    private final float[] rotAngles = new float[3];
    /** 手机是否处于竖握姿态；用迟滞切换，避免临界角度来回翻。 */
    private boolean upright;
    private final SensorEventListener headingListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, e.values);
            // 竖握和横放要取不同的轴当航向：
            // - 竖握：屏幕朝外（Z 轴）才是人面向的方向，必须重映射，否则方位角基准落在竖直轴上；
            // - 平握：屏幕朝上，设备 Y 轴（屏幕顶端）就是行进方向，直接用原矩阵。
            // rotMatrix[8] 是设备 Z 轴与世界竖直方向的夹角余弦，接近 0 即竖握。
            float zUp = Math.abs(rotMatrix[8]);
            if (!upright && zUp < 0.40f) {
                upright = true;
                deviceHeading = Float.NaN;
            } else if (upright && zUp > 0.60f) {
                upright = false;
                deviceHeading = Float.NaN;
            }
            if (upright) {
                SensorManager.remapCoordinateSystem(rotMatrix,
                        SensorManager.AXIS_X, SensorManager.AXIS_Z, rotRemap);
                SensorManager.getOrientation(rotRemap, rotAngles);
            } else {
                SensorManager.getOrientation(rotMatrix, rotAngles);
            }
            float deg = (float) ((Math.toDegrees(rotAngles[0]) + 360) % 360);
            if (Float.isNaN(deviceHeading)) {
                deviceHeading = deg;
            } else {
                // 走最短角差做低通，站着不动时指针不抖
                float d = ((deg - deviceHeading + 540f) % 360f) - 180f;
                if (Math.abs(d) > 0.3f) {
                    deviceHeading = (deviceHeading + d * 0.25f + 360f) % 360f;
                }
            }
            compass.setHeading(deviceHeading);
            // 地图跟着手机朝向转：手机面对哪边，地图就把那边摆到屏幕上方
            if (map != null) {
                map.setBearing(deviceHeading);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private LocationManager lm;
    private boolean listening;
    /** GMS 融合定位通道，装了 Play 服务才启用（反射调用，不引入依赖）。 */
    private GmsFused fused;

    private double destLat;
    private double destLng;
    private double destWgsLat;
    private double destWgsLon;
    private String destName;
    private int destDatum;

    private double originLat;
    private double originLng;
    private int originDatum;

    private WalkRouter router;
    private WalkRouter.Route route;
    private boolean arrived;
    private WalkMapPanel map;
    private TextView recenter;
    private Vibrator vib;
    /** 已经震动过的步骤索引：每个转向只震一次，重算路线后重置。 */
    private int lastVibratedStep = -1;
    /** 距离下一转向点多少米触发震动，约等于步行 25 秒路程。 */
    private static final double TURN_VIBRATE_M = 30.0;
    /** GPS 不给 bearing 时，用两次定位的位移方向兜底，否则地图几乎不会转。 */
    private double bearingRefLat;
    private double bearingRefLon;
    private static final double BEARING_MIN_MOVE_M = 5.0;
    /** 是否已按真实定位校准过起点，只做一次。 */
    private boolean alignedToGps;
    /** 是否已把地图强制居中到自己，只做一次。 */
    private boolean centeredOnMe;
    /** 起点是否由调用方传入；否则等首帧定位、从当前位置开始算。 */
    private boolean originFromIntent;
    /** 首帧定位正在算路线，避免连续定位重复触发。 */
    private boolean initialComputing;
    /** 等不到定位的兜底时长：超过它就用上一次已知位置。 */
    private static final long LOCATE_TIMEOUT_MS = 12000L;
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable locateTimeout = new Runnable() {
        @Override
        public void run() {
            if (route != null || initialComputing || isFinishing()) {
                return;
            }
            double lat = Api.getLat();
            double lng = Api.getLng();
            if (lat != 0 && lng != 0 && !Double.isNaN(lat) && !Double.isNaN(lng)) {
                // 先占住标志：onCreate/onResume 会各排一次超时，早到的首帧定位也会触发算路，
                // 不置位就会同时跑两三次 compute，后完成的结果把前面的覆盖掉。
                initialComputing = true;
                compute(lng, lat, WalkCoord.DATUM_GCJ);
            } else {
                showError("定位超时：请确认已开启 GPS，或到窗边/室外再试");
            }
        }
    };

    private int large;

    @Override
    protected void onCreate(Bundle b) {
        Tokens.load(this);
        super.onCreate(b);
        setContentView(R.layout.activity_walknav);
        large = Tokens.isLarge() ? 1 : 0;

        instr = (TextView) findViewById(R.id.instr);
        instrSub = (TextView) findViewById(R.id.instrSub);
        meta = (TextView) findViewById(R.id.meta);
        state = (TextView) findViewById(R.id.state);
        compass = (WalkCompassView) findViewById(R.id.compass);
        // 指针卡与其它卡片一致：左右各 16dp 页边；高度补成正方形
        View card = findViewById(R.id.compassCard);
        int side = getResources().getDisplayMetrics().widthPixels - dp(32);
        ViewGroup.LayoutParams clp = card.getLayoutParams();
        clp.width = side;
        clp.height = side;
        card.setLayoutParams(clp);

        map = (WalkMapPanel) findViewById(R.id.map);
        recenter = (TextView) findViewById(R.id.recenter);
        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        Tokens.textSize(recenter, "xt_caption");
        recenter.setTextColor(Tokens.color(this, "pine"));
        map.setFollowListener(new WalkMapPanel.FollowListener() {
            @Override
            public void onFollowChanged(boolean following) {
                recenter.setVisibility(following ? View.GONE : View.VISIBLE);
            }
        });
        // 初始就是跟随状态：按钮先藏起来，别一进页面就摆一个「没意义」的回到当前
        recenter.setVisibility(map.isFollowing() ? View.GONE : View.VISIBLE);
        recenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                map.setFollowing(true);
            }
        });
        // 大字版把地图压矮一点，给指令卡留出行数
        View mapBox = (View) map.getParent();
        mapBox.getLayoutParams().height = dp(large == 1 ? 165 : 190);

        Tokens.textSize((TextView) findViewById(R.id.title), "xt_appbar");
        Tokens.textSize(instr, "xt_verdict");
        Tokens.textSize(instrSub, "xt_label");
        Tokens.textSize(meta, "xt_body");
        Tokens.textSize(state, "xt_caption");

        destName = getIntent().getStringExtra("destName");
        destLat = getIntent().getDoubleExtra("destLat", 0);
        destLng = getIntent().getDoubleExtra("destLng", 0);
        destDatum = getIntent().getIntExtra("destDatum", WalkCoord.DATUM_GCJ);

        originLat = getIntent().getDoubleExtra("originLat", Api.getLat());
        originLng = getIntent().getDoubleExtra("originLng", Api.getLng());
        originDatum = getIntent().getIntExtra("originDatum", WalkCoord.DATUM_GCJ);
        originFromIntent = getIntent().hasExtra("originLat") && getIntent().hasExtra("originLng");

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        if (destLat == 0 || destLng == 0 || Double.isNaN(destLat) || Double.isNaN(destLng)) {
            showError("目的地坐标缺失");
            return;
        }
        if (originFromIntent) {
            compute(originLng, originLat, originDatum);
        } else {
            // 起点一律取手机当前位置：宁可先等一两秒首帧定位，
            // 也不用调用方给的近似坐标（否则会出现「明明在家，却显示 30 米」）。
            state.setText("定位中");
            meta.setText("正在获取当前位置…");
            startLocation();
            handler.postDelayed(locateTimeout, LOCATE_TIMEOUT_MS);
        }
    }

    private void compute(final double oLng, final double oLat, final int oDatum) {
        state.setText("计算中");
        Io.query(new Runnable() {
            @Override
            public void run() {
                try {
                final WalkGraph g = WalkGraph.get(WalkNavActivity.this);
                if (g == null) {
                    Io.ui(new Runnable() {
                        @Override
                        public void run() {
                            initialComputing = false;
                            showError("步行路网未就绪");
                        }
                    });
                    return;
                }
                double[] o = resolve(g, oLng, oLat, oDatum);
                final double[] d = resolve(g, destLng, destLat, destDatum);
                WalkRouter r = new WalkRouter(g);
                WalkRouter.Route r0 = r.route(o[1], o[0], d[1], d[0], destName);
                if (r0 == null && oDatum != WalkCoord.DATUM_WGS) {
                    // 基准纠正可能把起点吸到没有步道的地方（网络定位常见）：
                    // 退回原始坐标再算一次，宁可位置差几百米也别直接算不出路线
                    r0 = r.route(oLat, oLng, d[1], d[0], destName);
                }
                final WalkRouter.Route rt = r0;
                android.util.Log.i("WalkD", "route o=" + o[1] + "," + o[0]
                        + " d=" + d[1] + "," + d[0]
                        + " rawO=" + oLat + "," + oLng + "@" + oDatum
                        + " rawD=" + destLat + "," + destLng + "@" + destDatum
                        + (rt == null ? " route=null" : " meters=" + rt.meters
                        + " start=" + rt.startLat + "," + rt.startLon
                        + " pts=" + (rt.lats == null ? 0 : rt.lats.length)));
                Io.ui(new Runnable() {
                    @Override
                    public void run() {
                        initialComputing = false;
                        if (isFinishing()) {
                            return;
                        }
                        if (rt == null) {
                            lastComputeFailMs = System.currentTimeMillis();
                            showError("起点或终点附近没有可用步道");
                            return;
                        }
                        router = r;
                        destWgsLat = d[1];
                        destWgsLon = d[0];
                        route = rt;
                        // 朝向统一由方向传感器驱动，这里只更新箭头目标
                        float b0 = routeBearing(Double.NaN, Double.NaN);
                        map.setRoute(rt);
                        targetBearing = b0;
                        compass.setTargetBearing(b0);
                        render();
                        state.setText("定位中");
                        startLocation();
                        // 起点就是刚拿到的定位，直接落图居中，之后正常跟随。
                        // 注意不要把 alignedToGps 置 true：这帧可能是 lastKnownLocation，
                        // 已经过期几百米，下一帧真实定位必须还能触发「按当前位置重算」。
                        if (!originFromIntent) {
                            centeredOnMe = true;
                            map.centerOn(o[1], o[0]);
                        }
                    }
                });
                } catch (final Throwable t) {
                    android.util.Log.e("WalkD", "compute failed", t);
                    Io.ui(new Runnable() {
                        @Override
                        public void run() {
                            initialComputing = false;
                            if (!isFinishing()) {
                                showError("路线计算失败：" + t.getClass().getSimpleName());
                            }
                        }
                    });
                }
            }
        });
    }

    /**
     * 坐标基准兜底：调用方给的基准只在「吸附不比别的基准差」时才采信。
     * 车来了不同接口混用 BD-09 / GCJ-02，硬信一个基准会错 400~1200 m，
     * 这里用「哪个基准离路网更近」做判定，代价是 3 次吸附（各 ~1 ms）。
     *
     * 例外：带 WGS 标记的坐标一律原样使用。GPS 给的就是 WGS-84，
     * 再「换基准试试」会把人整体挪 600 m（实测起点 27.7798,112.9509 被挪到 27.7833,112.9455），
     * 路线、居中、朝向全部跟着错。
     */
    private double[] resolve(WalkGraph g, double lng, double lat, int hint) {
        if (hint == WalkCoord.DATUM_WGS) {
            return new double[]{lng, lat};
        }
        int[] order;
        if (hint == WalkCoord.DATUM_GCJ) {
            order = new int[]{1, 2, 0};
        } else if (hint == WalkCoord.DATUM_BD) {
            order = new int[]{2, 1, 0};
        } else {
            order = new int[]{0, 1, 2};
        }
        double bestD = Double.MAX_VALUE;
        double[] best = WalkCoord.toWgs(lng, lat, hint);
        for (int d : order) {
            double[] w = WalkCoord.toWgs(lng, lat, d);
            double sd = g.snap(w[1], w[0]).dist;
            if (sd < bestD - 1.0) {
                bestD = sd;
                best = w;
            }
        }
        return best;
    }

    private void render() {
        if (route == null) {
            return;
        }
        List<WalkRouter.Step> list = route.steps;
        WalkRouter.Step cur = list.isEmpty() ? null : list.get(0);
        if (cur != null) {
            instr.setText(cur.text);
            int n = list.size();
            instrSub.setText(n > 1 && list.get(1) != null ? "然后 " + list.get(1).text : "");
        }
        meta.setText("全程 " + WalkRouter.fmt(route.meters) + " · 约 "
                + Math.max(1, Math.round(route.seconds / 60f)) + " 分钟");
        lastActive = 0;
    }

    // ---------- 方向感应（罗盘） ----------

    /** 注册方向传感器：罗盘盘面要跟着手机转，指针才能指成「相对我」的方向。 */
    private void startHeading() {
        if (sensors != null) {
            return;
        }
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s == null || !sensors.registerListener(headingListener, s, SensorManager.SENSOR_DELAY_UI)) {
            sensors = null;
        }
    }

    private void stopHeading() {
        if (sensors != null) {
            try {
                sensors.unregisterListener(headingListener);
            } catch (Throwable ignored) {
            }
            sensors = null;
        }
    }

    // ---------- 定位 ----------


    private void startLocation() {
        if (listening) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // 之前这里只提示不申请，等于永远等不到定位
            if (Build.VERSION.SDK_INT >= 23) {
                state.setText("请求定位权限…");
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                return;
            }
            state.setText("未授权定位 · 仅显示路线");
            return;
        }
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            state.setText("无定位服务");
            return;
        }
        try {
            boolean any = false;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // minDistance 设 0：室内走动时 3 m 的阈值经常一直不触发，1 秒一报更稳
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_INTERVAL, 0f, this);
                any = true;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_INTERVAL, 0f, this);
                any = true;
            }
            // 被动定位不额外耗电：别的应用在定位时能顺带拿到一帧，室内很有用
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, MIN_INTERVAL, 0f, this);
            }
            // 有 GMS 就叠加融合定位：GPS+WiFi+基站+传感器，室内几秒出点，
            // 与高德/系统地图同一条通道；没有则静默失败，不影响系统定位。
            if (fused == null) {
                fused = new GmsFused();
            }
            if (GmsFused.available(this)) {
                fused.start(this, new GmsFused.Sink() {
                    @Override
                    public void onFix(Location l) {
                        onLocationChanged(l);
                    }
                });
            }
            if (!any) {
                state.setText("请打开 GPS 或移动网络定位");
                return;
            }
            listening = true;
            state.setText("定位中");
            // 上次定位取「最新的一帧」，不要死等 GPS：网络/被动往往更新、更快
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) {
                    continue;
                }
                Location x = lm.getLastKnownLocation(p);
                if (x != null && (best == null || x.getTime() > best.getTime())) {
                    best = x;
                }
            }
            if (best != null) {
                // 先落状态再处理上一次定位，否则「已到达」会被「定位中」覆盖
                onLocationChanged(best);
            }
        } catch (SecurityException e) {
            state.setText("未授权定位 · 仅显示路线");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != 1) {
            return;
        }
        boolean ok = false;
        for (int r : results) {
            if (r == PackageManager.PERMISSION_GRANTED) {
                ok = true;
            }
        }
        if (ok) {
            listening = false;
            startLocation();
        } else {
            state.setText("未授权定位 · 仅显示路线");
        }
    }

    /** GPS 与 GMS 融合定位（fused）给的都是 WGS-84；网络/被动在国产 ROM 上常是 GCJ-02。 */
    private static boolean isWgsFix(Location l) {
        String p = l.getProvider();
        return LocationManager.GPS_PROVIDER.equals(p) || "fused".equals(p);
    }

    private void stopLocation() {
        if (fused != null) {
            fused.stop();
        }
        if (lm != null && listening) {
            try {
                lm.removeUpdates(this);
            } catch (Throwable ignored) {
            }
        }
        listening = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocation();
        stopHeading();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startHeading();
        if (!arrived && (route != null || !originFromIntent)) {
            startLocation();
            if (route == null) {
                // onCreate 已经排过一次超时，这里先撤掉再排，避免两个超时几乎同时触发两次算路
                handler.removeCallbacks(locateTimeout);
                handler.postDelayed(locateTimeout, LOCATE_TIMEOUT_MS);
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(locateTimeout);
        stopLocation();
        stopHeading();
        if (map != null) {
            map.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location l) {
        if (arrived || l == null) {
            return;
        }
        android.util.Log.i("WalkD", "loc p=" + l.getProvider()
                + " acc=" + (l.hasAccuracy() ? l.getAccuracy() : -1)
                + " age=" + (l.getTime() > 0 ? (System.currentTimeMillis() - l.getTime()) / 1000 : -1)
                + " at=" + l.getLatitude() + "," + l.getLongitude()
                + " hasRoute=" + (route != null));
        // 还没有路线：起点就是当前位置，拿到第一帧可用定位再算，
        // 不先用调用方/上次的旧坐标凑一条假路线。
        if (route == null) {
            // 先画蓝点：不管路线有没有算出来，位置本身就该立刻显示，
            // 否则打开页面几秒内地图上什么都看不到，像定位坏了。
            map.setFix(l.getLatitude(), l.getLongitude());
            if (!centeredOnMe) {
                centeredOnMe = true;
                map.centerOn(l.getLatitude(), l.getLongitude());
            }
            if (initialComputing) {
                return;
            }
            // 刚失败过就等几秒再试，否则每一帧定位都会重新「计算中」刷屏
            if (System.currentTimeMillis() - lastComputeFailMs < 8000) {
                return;
            }
            float acc0 = l.hasAccuracy() ? l.getAccuracy() : 40f;
            if (acc0 > MAX_ACCURACY) {
                state.setText("定位较弱");
                return;
            }
            // lastKnownLocation 可能是几分钟前的旧位置：拿它当起点，
            // 路线就会从「你之前在哪」画起，跟你现在的蓝点之间断开一截。
            long age = System.currentTimeMillis() - l.getTime();
            if (l.getTime() > 0 && age > 60000) {
                state.setText("等待定位…");
                return;
            }
            initialComputing = true;
            handler.removeCallbacks(locateTimeout);
            // 系统定位（GPS / 融合 / 网络）一律按 WGS-84 处理：这台机器上网络定位
            // 也是融合下发的 WGS 坐标，再按 GCJ 反算会整体挪 600 m，
            // 路线就从"你附近"变成"你西北 600 米"出发——这就是线连不到蓝点的原因。
            compute(l.getLongitude(), l.getLatitude(), WalkCoord.DATUM_WGS);
            return;
        }
        float acc = l.hasAccuracy() ? l.getAccuracy() : 40f;
        long now = System.currentTimeMillis();
        boolean wgs = isWgsFix(l);
        long lastAge = lastFixMs > 0 ? now - lastFixMs : Long.MAX_VALUE;
        // 0) 不是「谁先来用谁」：按精度/新鲜度择优。
        //    GPS 刚给过准确的点（8 秒内），网络点又没比它准 30 m 以上，
        //    就是同一时刻的劣质副本，直接丢，免得蓝点在准确位置和漂移点之间来回跳。
        if (!wgs && lastAge < 8000 && acc > lastFixAcc - 30) {
            android.util.Log.i("WalkD", "drop net: worse than fresh wgs acc=" + acc + ">=" + lastFixAcc);
            return;
        }
        if (wgs) {
            lastGpsMs = now;
        }
        // 1) 精度太差的点直接丢，避免漂移把剩余距离和偏航判定带飞
        if (acc > MAX_ACCURACY) {
            state.setText("定位较弱");
            android.util.Log.i("WalkD", "drop acc=" + acc);
            return;
        }
        // 2) 不可能的跳点（>12 m/s）丢掉，这类点多半是多径反射
        if (lastFixMs > 0) {
            double dt = (now - lastFixMs) / 1000.0;
            double d = WalkCoord.meters(lastFixLat, lastFixLon, l.getLatitude(), l.getLongitude());
            if (dt > 0 && d / dt > MAX_SPEED) {
                android.util.Log.i("WalkD", "drop jump d=" + d + " dt=" + dt);
                return;
            }
            // 3) 抖动门限：位置突然跳 >50 m 且精度明显更差、又是刚拿到的点，按漂移处理
            if (d > 50 && lastAge < 10000 && acc > lastFixAcc + 20) {
                android.util.Log.i("WalkD", "drop jitter d=" + d + " acc=" + acc);
                return;
            }
        }
        lastFixLat = l.getLatitude();
        lastFixLon = l.getLongitude();
        lastFixMs = now;
        lastFixAcc = acc;

        double fixLat = l.getLatitude();
        double fixLon = l.getLongitude();
        // 网络定位在国产 ROM 上常直接给 GCJ-02：每次网络定位都试一遍基准，
        // 只有「换基准后明显更贴路线（>60 m）」才切换。GPS 一定是 WGS-84，不动它。
        double off = route.distanceTo(fixLat, fixLon);
        if (!wgs) {
            double[] gj = WalkCoord.gcj2wgs(fixLon, fixLat);
            double offG = route.distanceTo(gj[1], gj[0]);
            double[] bd = WalkCoord.bd2wgs(fixLon, fixLat);
            double offB = route.distanceTo(bd[1], bd[0]);
            if (offG < off - 60 && offG <= offB) {
                fixLat = gj[1];
                fixLon = gj[0];
                off = offG;
            } else if (offB < off - 60) {
                fixLat = bd[1];
                fixLon = bd[0];
                off = offB;
            }
        }

        // 系统 GPS 为 WGS-84，与路网同基准
        double remain = route.remaining(fixLat, fixLon);
        // 明显偏离路线时，把「走回路线的距离」也算进去，避免显示「剩余 1 米」却离路线几百米
        if (off > OFF_ROUTE_M) {
            remain += off;
        }
        android.util.Log.i("WalkD", "fix p=" + l.getProvider() + " acc=" + acc
                + " at=" + fixLat + "," + fixLon + " off=" + off + " remain=" + remain);
        int total = route.steps.size();
        state.setText(acc < 25 ? "已定位" : "定位较弱");
        map.setFix(fixLat, fixLon);
        // 朝向只认「路线在这附近该往哪走」这一个来源。
        // 之前同时用路线方向、GPS bearing、位移方向，三者互相覆盖，位置跳一下角度就乱摆。
        float bear = routeBearing(fixLat, fixLon);
        targetBearing = bear;
        compass.setTargetBearing(bear);
        // 首次拿到真实定位时，把起点从「调用方传入的坐标」纠正为当前位置。
        // 规划页传来的起点可能是公交下车站或搜索点，和手机实际位置差几百米，
        // 此时路线会悬在别处、地图也不会居中到人身上。
        // 首次拿到定位就把地图落到自己身上：fitRoute 只保证看全路线，
        // 起点是调用方坐标时会偏出几百米，必须强制居中一次。
        boolean rerouting = alignToGpsOnce(fixLat, fixLon);
        // 若正在重算路线，setRoute 里的 fitRoute 会再次改中心，等重算回来再居中
        if (!centeredOnMe && !rerouting) {
            centeredOnMe = true;
            map.centerOn(fixLat, fixLon);
        }

        // 3) 静止漂移：连续几次几乎没动就降频，动了立刻恢复
        updateRate(l);

        if (remain <= ARRIVE_M && off <= OFF_ROUTE_M) {
            arrived = true;
            instr.setText(destName != null && destName.length() > 0 ? "已到达 " + destName : "已到达");
            instrSub.setText("导航结束");
            meta.setText("全程 " + WalkRouter.fmt(route.meters));
            compass.setArrived(true);
            stopLocation();
            state.setText("已到达");
            return;
        }

        meta.setText("剩余 " + WalkRouter.fmt(remain) + " · 约 "
                + Math.max(1, Math.round(remain / 1.25 / 60)) + " 分钟");
        int active = activeStep(remain);
        WalkRouter.Step s = route.steps.get(active);
        if (active != lastActive) {
            instr.setText(s.text);
            instrSub.setText(active + 1 < total ? "然后 " + route.steps.get(active + 1).text : "");
            lastActive = active;
        }

        // 转向震动：距下一转向点 <30 m 时双震一次。用单调递增判断，
        // 这样 remain 因偏航修正而回跳、active 后退时不会重复震。
        if (active + 1 < total && active > lastVibratedStep) {
            WalkRouter.Step next = route.steps.get(active + 1);
            if (next.icon != 3 && (next.lat != 0 || next.lon != 0)) {
                double dNext = WalkCoord.meters(fixLat, fixLon, next.lat, next.lon);
                if (dNext <= TURN_VIBRATE_M) {
                    vibrateForTurn();
                    lastVibratedStep = active;
                }
            }
        }

        // 4) 偏航：连续命中且过了冷却才重算，一次偏航只重算一次
        if (off > OFF_ROUTE_M) {
            offRouteHits++;
            if (offRouteHits >= OFF_ROUTE_HITS && !rerouting
                    && now - lastRerouteMs > REROUTE_COOLDOWN_MS
                    && rerouteCount < REROUTE_MAX) {
                reroute(fixLat, fixLon);
            }
        } else {
            offRouteHits = 0;
        }
    }

    /** 静止降频 / 恢复 1 Hz，纯为省电，不改判定逻辑。 */
    private void updateRate(Location l) {
        if (anchorLat == 0 && anchorLon == 0) {
            anchorLat = (float) l.getLatitude();
            anchorLon = (float) l.getLongitude();
            stationaryHits = 0;
            return;
        }
        float moved = dist(anchorLat, anchorLon, (float) l.getLatitude(), (float) l.getLongitude());
        if (moved >= STATIONARY_M * 2) {
            stationaryHits = 0;
            anchorLat = (float) l.getLatitude();
            anchorLon = (float) l.getLongitude();
            if (slowRate) {
                slowRate = false;
                reRegister(MIN_INTERVAL);
            }
            return;
        }
        if (moved < STATIONARY_M) {
            stationaryHits++;
            if (stationaryHits >= STATIONARY_HITS && !slowRate) {
                slowRate = true;
                reRegister(SLOW_INTERVAL);
            }
        }
    }

    private static float dist(float lat1, float lon1, float lat2, float lon2) {
        float[] r = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, r);
        return r[0];
    }

    private void reRegister(long interval) {
        if (!listening || lm == null) {
            return;
        }
        try {
            lm.removeUpdates(this);
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, MIN_DISTANCE, this);
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, interval, MIN_DISTANCE, this);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 按当前位置重算，失败则保留原路线并提示。 */
    private void reroute(final double lat, final double lon) {
        android.util.Log.i("WalkD", "reroute from " + lat + "," + lon);
        rerouting = true;
        rerouteCount++;
        lastRerouteMs = System.currentTimeMillis();
        offRouteHits = 0;
        state.setText("正在重新规划");
        Io.query(new Runnable() {
            @Override
            public void run() {
                final WalkRouter.Route rt = router.route(lat, lon, destWgsLat, destWgsLon, destName);
                Io.ui(new Runnable() {
                    @Override
                    public void run() {
                        rerouting = false;
                        if (isFinishing()) {
                            return;
                        }
                        if (rt == null) {
                            state.setText("已偏离路线 · 保持原路线");
                            return;
                        }
                        route = rt;
                        lastActive = -2;
                        lastVibratedStep = -1;
                        anchorLat = 0;
                        anchorLon = 0;
                        stationaryHits = 0;
                        state.setText("已重新规划");
                        float rb = routeBearing(lat, lon);
                        map.setRoute(route);
                        targetBearing = rb;
                        compass.setTargetBearing(rb);
                        compass.setArrived(false);
                        // 重算后回到「跟着我」的状态，并立刻把视野落到人身上：
                        // setRoute 里的 fitRoute 会把中心改到路线中心，这里必须覆盖回来
                        centeredOnMe = true;
                        map.centerOn(lat, lon);
                        instr.setText(route.steps.get(0).text);
                        instrSub.setText("已按当前位置重新规划");
                        meta.setText("全程 " + WalkRouter.fmt(route.meters) + " · 约 "
                                + Math.max(1, Math.round(route.seconds / 60f)) + " 分钟");
                    }
                });
            }
        });
    }

    /**
     * 首次定位后校准一次：若调用方给的起点离我超过 40 m，就按当前位置重算路线并居中。
     * 只做一次，之后交给偏航重算逻辑，避免每次定位都重算。
     */
    private boolean alignToGpsOnce(double lat, double lon) {
        if (alignedToGps) {
            return false;
        }
        alignedToGps = true;
        if (route == null || route.lats == null || route.lats.length < 2) {
            return false;
        }
        double d0 = WalkCoord.meters(lat, lon, route.lats[0], route.lons[0]);
        if (d0 > 40) {
            reroute(lat, lon);
            return true;
        }
        return false;
    }

    /**
     * 按路线走向取当前该朝哪个方向：从离我最近的路点往前走约 25 米，
     * 用这一段的方向作为车头朝向。这样打开页面、还没走动时地图就已经朝行进方向摆好，
     * 不用等 GPS 上报方位角。返回 -1 表示算不出来（调用方会忽略）。
     */
    private float routeBearing(double lat, double lon) {
        if (route == null || route.lats == null || route.lats.length < 2) {
            return -1;
        }
        int n = route.lats.length;
        int i = 0;
        if (!Double.isNaN(lat)) {
            double best = Double.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                double d = WalkCoord.meters(lat, lon, route.lats[k], route.lons[k]);
                if (d < best) {
                    best = d;
                    i = k;
                }
            }
        }
        double acc = 0;
        for (int k = i + 1; k < n; k++) {
            acc += WalkCoord.meters(route.lats[k - 1], route.lons[k - 1],
                    route.lats[k], route.lons[k]);
            if (acc >= 25 || k == n - 1) {
                // 必须用有符号的经纬度差：WalkCoord.meters() 返回无符号距离，
                // 直接拿它算方位角会永远落在 0~90°（第一象限）。
                double dLon = route.lons[k] - route.lons[i];
                double dLat = route.lats[k] - route.lats[i];
                double dx = dLon * Math.cos(Math.toRadians(route.lats[i])) * 111320d;
                double dy = dLat * 110540d;
                if (dx == 0 && dy == 0) {
                    return -1;
                }
                // 方位角 = atan2(东, 北)，北=0 顺时针增大。
                // 写成 atan2(dy, dx) 得到的是数学角（东=0 逆时针），
                // 会整体偏左约 40°，表现就是「地图固定往左上角斜」。
                double deg = Math.toDegrees(Math.atan2(dx, dy));
                return (float) ((deg + 360) % 360);
            }
        }
        return -1;
    }

    private void updateBearing(Location l, double lat, double lon) {
        if (l.hasBearing() && (!l.hasSpeed() || l.getSpeed() > 0.5f)) {
            bearingRefLat = lat;
            bearingRefLon = lon;
            return;
        }
        if (bearingRefLat == 0 && bearingRefLon == 0) {
            bearingRefLat = lat;
            bearingRefLon = lon;
            return;
        }
        double d = WalkCoord.meters(bearingRefLat, bearingRefLon, lat, lon);
        if (d < BEARING_MIN_MOVE_M) {
            return;
        }
        double dy = lat - bearingRefLat;
        double dx = (lon - bearingRefLon) * Math.cos(Math.toRadians(lat));
        float b = (float) ((Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360);
        bearingRefLat = lat;
        bearingRefLon = lon;
    }

    /** 转向提醒：双震 180-120-180 ms，不重复。 */
    private void vibrateForTurn() {
        if (vib == null || !vib.hasVibrator()) {
            return;
        }
        long[] pattern = {0, 180, 120, 180};
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vib.vibrate(pattern, -1);
        }
    }

    private int activeStep(double remain) {
        double acc = 0;
        for (int i = 0; i < route.steps.size() - 1; i++) {
            acc += route.steps.get(i).meters;
            if (acc >= route.meters - remain) {
                return i;
            }
        }
        return Math.max(0, route.steps.size() - 2);
    }

    @Override
    public void onProviderEnabled(String s) {
    }

    @Override
    public void onProviderDisabled(String s) {
        state.setText("定位已关闭");
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle b) {
    }

    private void showError(final String msg) {
        Io.ui(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                instr.setText(msg);
                instrSub.setText("可以返回重新规划，或检查定位是否开启");
                meta.setText("");
                state.setText("");
            }
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
