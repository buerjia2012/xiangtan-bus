package com.xtbus;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 路线规划详情页：一个方案一条时间轴。
 * 列表页只做比较，这里负责「照着走」：逐段的上下车站、耗时，第一段公交的实时到站。
 */
public class PlanDetailActivity extends Activity {

    private static final long RT_INTERVAL = 30 * 1000L;
    /** 超过这么久没有成功拿到新数据，就认为这份结果过期了。 */
    private static final long STALE_MS = 5 * 60 * 1000L;

    private int c(String token) {
        return Tokens.color(this, token);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private LinearLayout content;
    private ScrollView scroll;
    private SwipeRefreshLayout pull;
    private TextView counter;
    private TextView prevBtn;
    private TextView nextBtn;
    private TextView footer;

    private String originName, originSid, destName, destSid;
    private double originLat, originLng, destLat, destLng;
    private int strategy;
    private int index;

    private final List<Leg> legs = new ArrayList<Leg>();
    private int durationSec;
    private int distanceM;
    private int transferCount;
    private boolean passed;
    private String firstStart = "";
    private String firstEnd = "";
    private String firstLineId = "";
    private String firstDepSid = "";
    private int etaSec = -1;
    private String licence = "";
    private long updatedAt;

    private boolean foreground;
    private boolean loading;
    private boolean riskStopped;
    /** 第一段实时查询不等待 30 秒，进页面就发。 */
    private boolean rtFetched;
    /** 实时刷新触发的重绘要保留滚动位置。 */
    private boolean keepScrollOnRender;
    /** 接口答复了但这条线暂时没有车，用来区分「还没查」和「确实没有」。 */
    private boolean rtEmpty;
    /** 这份结果已经超过 STALE_MS 没更新，当成「数据过期」。 */
    private boolean stale;
    /** 展开看车位置的乘车段下标，-1 表示全部收起。 */
    private int expandedLeg = -1;
    /** 站表按 lineId 缓存，展开时才拉。 */
    private final java.util.Map<String, JSONArray> stationCache =
            new java.util.HashMap<String, JSONArray>();
    private boolean loadingStations;
    /** 展开后是否已经点开「中间还经过 N 站」。 */
    private boolean stationsAllShown;
    /** 实时返回的车辆所在站序，-1 表示没有。 */
    private int rtOrder = -1;

    /** 方案里的一段：步行或乘车。只保留详情页要展示的字段。 */
    private static final class Leg {
        boolean bus;
        int sec;
        int meters;
        String lineName = "";
        String lineId = "";
        String depName = "";
        String depSid = "";
        double depLat;
        double depLng;
        String arrName = "";
        String arrSid = "";
        double arrLat;
        double arrLng;
        String endStation = "";
        String start = "";
        String end = "";
        int via;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Api.init(getApplicationContext());
        Tokens.load(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plan_detail);

        originName = getIntent().getStringExtra("originName");
        originSid = getIntent().getStringExtra("originSid");
        destName = getIntent().getStringExtra("destName");
        destSid = getIntent().getStringExtra("destSid");
        originLat = getIntent().getDoubleExtra("originLat", Api.lat());
        originLng = getIntent().getDoubleExtra("originLng", Api.lng());
        destLat = getIntent().getDoubleExtra("destLat", Api.lat());
        destLng = getIntent().getDoubleExtra("destLng", Api.lng());
        strategy = getIntent().getIntExtra("strategy", 0);
        index = getIntent().getIntExtra("index", 0);

        content = findViewById(R.id.content);
        scroll = findViewById(R.id.scroll);
        pull = findViewById(R.id.pull);
        counter = findViewById(R.id.counter);
        prevBtn = findViewById(R.id.prev);
        nextBtn = findViewById(R.id.next);
        footer = findViewById(R.id.footer);

        final TextView back = findViewById(R.id.back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchPlan(index - 1);
            }
        });
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchPlan(index + 1);
            }
        });

        pull.setColorSchemeColors(c("pine"), c("signal"));
        pull.setProgressBackgroundColorSchemeColor(c("card"));
        pull.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                reload(true);
            }
        });

        String planJson = getIntent().getStringExtra("planJson");
        String allJson = getIntent().getStringExtra("plansJson");
        boolean ok = false;
        if (allJson != null && !allJson.isEmpty()) {
            ok = parseAll(allJson);
        } else if (planJson != null && !planJson.isEmpty()) {
            ok = parseOne(planJson);
        }
        if (!ok) {
            showError("路线信息已失效", "返回列表重新规划一次。");
            return;
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        scheduleRealtime();
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        ui.removeCallbacks(rtTask);
    }

    // ---------------- 数据 ----------------

    private boolean parseAll(String json) {
        try {
            JSONObject route = new JSONObject();
            route.put("transits", new JSONArray(json));
            return parseRoute(route) && clampIndex() && loadCurrent();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean parseOne(String json) {
        try {
            JSONArray one = new JSONArray();
            one.put(new JSONObject(json));
            JSONObject route = new JSONObject();
            route.put("transits", one);
            index = 0;
            return parseRoute(route) && loadCurrent();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clampIndex() {
        if (plans.isEmpty()) {
            return false;
        }
        if (index < 0) {
            index = 0;
        }
        if (index >= plans.size()) {
            index = plans.size() - 1;
        }
        return true;
    }

    private final List<List<Leg>> plans = new ArrayList<List<Leg>>();
    private final List<Integer> planDuration = new ArrayList<Integer>();
    private final List<Integer> planDistance = new ArrayList<Integer>();
    private final List<String> planStart = new ArrayList<String>();
    private final List<String> planEnd = new ArrayList<String>();
    private final List<Boolean> planPassed = new ArrayList<Boolean>();

    /** 把当前 index 的方案铺到渲染用的字段上。 */
    private boolean loadCurrent() {
        if (plans.isEmpty()) {
            return false;
        }
        legs.clear();
        legs.addAll(plans.get(index));
        durationSec = planDuration.get(index);
        distanceM = planDistance.get(index);
        passed = planPassed.get(index);
        firstStart = planStart.get(index);
        firstEnd = planEnd.get(index);
        firstLineId = "";
        firstDepSid = "";
        etaSec = -1;
        licence = "";
        rtEmpty = false;
        int buses = 0;
        for (Leg l : legs) {
            if (l.bus) {
                if (buses == 0) {
                    firstLineId = l.lineId;
                    firstDepSid = l.depSid;
                }
                buses++;
            }
        }
        transferCount = Math.max(0, buses - 1);
        updatedAt = System.currentTimeMillis();
        return true;
    }

    private Leg parseTransitBus(JSONObject l) {
        Leg b = new Leg();
        b.bus = true;
        b.lineName = l.optString("name");
        b.lineId = l.optString("lineId");
        b.endStation = l.optString("final_station");
        b.sec = l.optInt("duration", 0);
        b.via = parseInt(l.optString("via_num"));
        b.start = hhmm(l.optString("start_time"));
        b.end = hhmm(l.optString("end_time"));
        JSONObject dep = l.optJSONObject("departure_stop");
        if (dep != null) {
            b.depName = dep.optString("name");
            b.depSid = dep.optString("sId");
            // transfer 接口的站点 location 是 GCJ-02（gpstype=gcj）
            String loc = dep.optString("location");
            int comma = loc == null ? -1 : loc.indexOf(',');
            if (comma > 0) {
                try {
                    b.depLng = Double.parseDouble(loc.substring(0, comma).trim());
                    b.depLat = Double.parseDouble(loc.substring(comma + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        JSONObject arr = l.optJSONObject("arrival_stop");
        if (arr != null) {
            b.arrName = arr.optString("name");
            b.arrSid = arr.optString("sId");
            String loc = arr.optString("location");
            int comma = loc == null ? -1 : loc.indexOf(',');
            if (comma > 0) {
                try {
                    b.arrLng = Double.parseDouble(loc.substring(0, comma).trim());
                    b.arrLat = Double.parseDouble(loc.substring(comma + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return b;
    }

    private List<Leg> parseTransit(JSONObject t) {
        List<Leg> list = new ArrayList<Leg>();
        JSONArray segments = t.optJSONArray("segments");
        if (segments != null) {
            for (int k = 0; k < segments.length(); k++) {
                JSONObject seg = segments.optJSONObject(k);
                if (seg == null) {
                    continue;
                }
                JSONObject walking = seg.optJSONObject("walking");
                if (walking != null && walking.optInt("duration", 0) > 0) {
                    Leg w = new Leg();
                    w.bus = false;
                    w.sec = walking.optInt("duration", 0);
                    w.meters = walking.optInt("distance", 0);
                    list.add(w);
                }
                JSONObject bus = seg.optJSONObject("bus");
                JSONArray buslines = bus == null ? null : bus.optJSONArray("buslines");
                if (buslines == null || buslines.length() == 0) {
                    continue;
                }
                JSONObject l = buslines.optJSONObject(0);
                if (l != null) {
                    list.add(parseTransitBus(l));
                }
            }
        }
        return list;
    }

    /** 解析整条 route 的 transits 并整体替换；解析不出方案时不动旧数据。 */
    private boolean parseRoute(JSONObject route) {
        JSONArray transits = route == null ? null : route.optJSONArray("transits");
        if (transits == null || transits.length() == 0) {
            return false;
        }
        List<List<Leg>> newPlans = new ArrayList<List<Leg>>();
        List<Integer> newDuration = new ArrayList<Integer>();
        List<Integer> newDistance = new ArrayList<Integer>();
        List<String> newStart = new ArrayList<String>();
        List<String> newEnd = new ArrayList<String>();
        List<Boolean> newPassed = new ArrayList<Boolean>();
        for (int i = 0; i < transits.length(); i++) {
            JSONObject t = transits.optJSONObject(i);
            if (t == null) {
                continue;
            }
            List<Leg> list = parseTransit(t);
            if (list.isEmpty()) {
                continue;
            }
            String start = "";
            String end = "";
            boolean passedPlan = false;
            boolean firstBus = true;
            for (Leg l : list) {
                if (!l.bus) {
                    continue;
                }
                if (firstBus) {
                    // 首末班取第一段公交，用于文案展示
                    start = l.start;
                    end = l.end;
                    firstBus = false;
                }
                if (!l.end.isEmpty()
                        && com.xtbus.Fmt.lastBusGone(nowMinutes(), parseHm(l.end))) {
                    passedPlan = true;
                }
            }
            newPlans.add(list);
            newDuration.add(t.optInt("duration", 0));
            newDistance.add(t.optInt("distance", 0));
            newStart.add(start);
            newEnd.add(end);
            newPassed.add(passedPlan);
        }
        if (newPlans.isEmpty()) {
            return false;
        }
        plans.clear();
        plans.addAll(newPlans);
        planDuration.clear();
        planDuration.addAll(newDuration);
        planDistance.clear();
        planDistance.addAll(newDistance);
        planStart.clear();
        planStart.addAll(newStart);
        planEnd.clear();
        planEnd.addAll(newEnd);
        planPassed.clear();
        planPassed.addAll(newPassed);
        return true;
    }

    private void reload(final boolean manual) {
        if (loading) {
            pull.setRefreshing(false);
            return;
        }
        loading = true;
        Io.query(new Runnable() {
            @Override
            public void run() {
                try {
                    final Api.Result result = Api.transfer(originName, originSid, originLat, originLng,
                            destName, destSid, destLat, destLng, strategy, true);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            loading = false;
                            pull.setRefreshing(false);
                            JSONObject route = result.data.optJSONObject("route");
                            if (parseRoute(route)) {
                                clampIndex();
                                loadCurrent();
                                render();
                            } else {
                                Toast.makeText(PlanDetailActivity.this, "刷新失败，仍在当前结果",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (final Api.ApiException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            loading = false;
                            pull.setRefreshing(false);
                            Toast.makeText(PlanDetailActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void switchPlan(int target) {
        if (target < 0 || target >= plans.size()) {
            return;
        }
        index = target;
        loadCurrent();
        // 换方案回到收起态，第一屏永远只有一件事
        expandedLeg = -1;
        // 换了方案，第一段可能换线路，实时要重新查一次而不是等满 30 秒
        rtFetched = false;
        render();
    }

    // ---------------- 实时 ----------------

    private void scheduleRealtime() {
        ui.removeCallbacks(rtTask);
        if (!foreground || riskStopped || firstLineId.isEmpty() || firstDepSid.isEmpty()) {
            return;
        }
        // 进页面先查一次，拿到车牌和分钟数，之后固定 30 秒一轮
        ui.postDelayed(rtTask, rtFetched ? RT_INTERVAL : 0);
    }

    private final Runnable rtTask = new Runnable() {
        @Override
        public void run() {
            if (!foreground || riskStopped) {
                return;
            }
            if (computeStale() != stale) {
                stale = computeStale();
                render();
                return;
            }
            rtFetched = true;
            final String lineStn = firstLineId + "," + firstDepSid + ",1;";
            Io.realtime(new Runnable() {
                @Override
                public void run() {
                    try {
                        final JSONObject data = Api.tsfRealInfos(lineStn);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) {
                                    return;
                                }
                                // 先更新时间再重绘：刷新成功后「已失效」要立刻撤销
                                updatedAt = System.currentTimeMillis();
                                keepScrollOnRender = true;
                                applyRealtime(data);
                                updateFooter();
                            }
                        });
                    } catch (Api.ApiException e) {
                        if (e.kind == Api.ApiException.Kind.RISK
                                || e.kind == Api.ApiException.Kind.COOLDOWN) {
                            riskStopped = true;
                        }
                    }
                }
            });
            if (foreground && !riskStopped) {
                ui.postDelayed(this, RT_INTERVAL);
            }
        }
    };

    private void applyRealtime(JSONObject data) {
        if (data == null) {
            return;
        }
        // 接口答复了但这条线这个站没有车，就老实说没有，别一直转「获取中」
        etaSec = -1;
        licence = "";
        rtEmpty = true;
        rtOrder = -1;
        JSONArray lines = data.optJSONArray("lines");
        JSONObject line = lines == null || lines.length() == 0 ? null : lines.optJSONObject(0);
        JSONArray buses = line == null ? null : line.optJSONArray("buses");
        JSONObject bus = buses == null || buses.length() == 0 ? null : buses.optJSONObject(0);
        int travel = bus == null ? -1 : bus.optInt("travelTime", -1);
        if (travel >= 0) {
            etaSec = Math.max(0, Math.round(travel));
            licence = bus.optString("licence");
            if (licence.isEmpty()) {
                licence = bus.optString("busId");
            }
            rtOrder = bus.optInt("order", -1);
            rtEmpty = false;
        }
        render();
    }

    // ---------------- 渲染 ----------------

    private void render() {
        if (content == null) {
            return;
        }
        final int keepY = scroll == null ? 0 : scroll.getScrollY();
        final boolean restore = keepScrollOnRender;
        keepScrollOnRender = false;
        content.removeAllViews();
        // 每次重绘都重新算一次：刷新成功要立刻撤销「过期」
        stale = computeStale();
        content.addView(summaryHeader());
        if (passed || stale) {
            // 失效 / 过期：给一个动作，行程灰显，但站表仍然可以展开看
            content.addView(stateActionButton());
            for (int i = 0; i < legs.size(); i++) {
                content.addView(legView(i, true));
                if (i == expandedLeg) {
                    content.addView(expandPanel(i));
                }
            }
        } else {
            for (int i = 0; i < legs.size(); i++) {
                content.addView(legView(i, false));
                if (i == expandedLeg) {
                    content.addView(expandPanel(i));
                }
            }
        }
        updateFooter();
        int total = plans.size();
        counter.setText((index + 1) + " / " + total);
        setEnabled(prevBtn, index > 0);
        setEnabled(nextBtn, index < total - 1);
        scheduleRealtime();
        if (restore && keepY > 0 && scroll != null) {
            // 实时刷新每 30 秒重建一次页面，这里把用户原来的滚动位置放回去
            scroll.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scroll.scrollTo(0, keepY);
                }
            }, 40);
        }
    }

    /**
    /** 首段步行分钟数；首段就是车时为 0。 */
    private int walkMinutes() {
        return legs.isEmpty() || legs.get(0).bus ? 0 : Math.max(1, Math.round(legs.get(0).sec / 60f));
    }

    private int etaMinutes() {
        return etaSec < 0 ? -1 : Math.max(0, Math.round(etaSec / 60f));
    }

    /** 走到站的时间已经追不上这班车。 */
    private boolean missedNow() {
        int walk = walkMinutes();
        int eta = etaMinutes();
        return walk > 0 && eta >= 0 && eta - walk < 0;
    }

    private boolean computeStale() {
        // 没有实时数据的方案（纯步行 / 首段无公交）不存在“实时过期”
        if (firstLineId == null || firstLineId.isEmpty()) {
            return false;
        }
        return !passed && System.currentTimeMillis() - updatedAt > STALE_MS;
    }

    private int firstBusIndex() {
        for (int i = 0; i < legs.size(); i++) {
            if (legs.get(i).bus) {
                return i;
            }
        }
        return -1;
    }

    /** 失效 / 过期时唯一的一个动作：换方案或立即刷新。 */
    private View stateActionButton() {
        final boolean refresh = stale && !passed;
        TextView t = new TextView(this);
        t.setText(refresh ? "立即刷新" : "换下一个方案");
        Tokens.textSize(t, "xt_label");
        t.setTextColor(c("pine"));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Tokens.dp(this, "xt_touch"));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c("card"));
        bg.setStroke(dp(1), c("pine"));
        bg.setCornerRadius(dp(12));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (refresh) {
                    reload(true);
                    return;
                }
                for (int i = index + 1; i < plans.size(); i++) {
                    if (!planPassed.get(i)) {
                        switchPlan(i);
                        return;
                    }
                }
                Toast.makeText(PlanDetailActivity.this, "今天没有更晚的可行方案了", Toast.LENGTH_SHORT).show();
            }
        });
        return t;
    }

    // ---------------- 版式：顶部数字行 + 分段 + 可展开的车辆位置 ----------------

    /** 这一段能不能看车：只有第一段公交、确实有车、且数据没过期。 */
    private boolean hasLive(int legIndex) {
        return !passed && !stale && !rtEmpty && etaSec >= 0 && legIndex == firstBusIndex();
    }

    private int walkMeters() {
        int sum = 0;
        for (Leg l : legs) {
            if (!l.bus) {
                sum += l.meters;
            }
        }
        return sum;
    }

    private int legStartSec(int index) {
        int t = 0;
        for (int i = 0; i < index && i < legs.size(); i++) {
            t += legs.get(i).sec;
        }
        return t;
    }

    private String clockIn(int sec) {
        return clock.format(new Date(System.currentTimeMillis() + sec * 1000L));
    }

    private View summaryHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Tokens.dp(this, "xt_card_pad");
        box.setPadding(pad, dp(4), pad, dp(6));

        TextView pair = new TextView(this);
        pair.setText(originName + " → " + destName);
        Tokens.textSize(pair, "xt_caption");
        pair.setTextColor(c("ink2"));
        box.addView(pair);

        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        nums.setGravity(Gravity.BOTTOM);
        nums.setPadding(0, dp(4), 0, 0);

        TextView big = new TextView(this);
        big.setTypeface(Typeface.DEFAULT_BOLD);

        if (passed) {
            big.setText("今天的末班 " + firstEnd + " 已过");
            Tokens.textSize(big, "xt_verdict");
            big.setTextColor(c("signal"));
            box.addView(big);
            TextView sub = new TextView(this);
            sub.setText("现在出门只能走到站，明天从首班算");
            Tokens.textSize(sub, "xt_caption");
            sub.setTextColor(c("ink3"));
            sub.setPadding(0, dp(4), 0, 0);
            box.addView(sub);
            return box;
        }
        if (stale) {
            big.setText("数据 " + clock.format(new Date(updatedAt)));
            Tokens.textSize(big, "xt_verdict");
            big.setTextColor(c("ink2"));
            nums.addView(big);
            addNum(nums, "可能已改点");
            box.addView(nums);
            return box;
        }

        big.setText(minNum(durationSec) + " 分钟");
        Tokens.textSize(big, "xt_num");
        big.setTextColor(c("ink"));
        nums.addView(big);
        addNum(nums, "换乘 " + transferCount + " 次");
        if (!Tokens.isLarge() && walkMeters() > 0) {
            addNum(nums, "步行 " + km(walkMeters()) + " 公里");
        }
        addNum(nums, "到达 " + clockIn(durationSec));
        box.addView(nums);
        return box;
    }

    private void addNum(LinearLayout row, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(c("ink2"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(11);
        t.setLayoutParams(lp);
        row.addView(t);
    }

    private String km(int meters) {
        return String.format(Locale.CHINA, "%.1f", meters / 1000f);
    }

    private TextView badge(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c("pine"));
        bg.setCornerRadius(dp(3));
        t.setBackground(bg);
        t.setPadding(dp(5), dp(1), dp(5), dp(1));
        return t;
    }

    private TextView plateView(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c("ink"));
        bg.setCornerRadius(dp(3));
        t.setBackground(bg);
        t.setPadding(dp(5), dp(1), dp(5), dp(1));
        return t;
    }

    /** 实时签：有车但赶不上用朱砂描边；无实时用灰描边；有车能上用墨绿描边。 */
    private TextView rtChip(String text, int color, boolean outline) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_label");
        t.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c("card"));
        bg.setCornerRadius(dp(7));
        if (outline) {
            bg.setStroke(dp(1), color);
        }
        t.setBackground(bg);
        t.setPadding(dp(6), 0, dp(6), 0);
        return t;
    }

    private View legView(final int index, boolean dim) {
        final Leg leg = legs.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Tokens.dp(this, "xt_card_pad");
        row.setPadding(pad, dp(9), pad, dp(9));

        View rail = new View(this);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(leg.bus ? c("pine") : c("line"));
        rbg.setCornerRadius(dp(2));
        rail.setBackground(rbg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(leg.bus ? dp(4) : dp(2),
                LinearLayout.LayoutParams.MATCH_PARENT);
        rlp.rightMargin = dp(10);
        row.addView(rail, rlp);

        LinearLayout bd = new LinearLayout(this);
        bd.setOrientation(LinearLayout.VERTICAL);
        row.addView(bd, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (leg.bus) {
            LinearLayout l1 = new LinearLayout(this);
            l1.setOrientation(LinearLayout.HORIZONTAL);
            l1.setGravity(Gravity.CENTER_VERTICAL);
            l1.addView(badge(shortLine(leg.lineName)));
            if (leg.via > 0) {
                TextView via = new TextView(this);
                via.setText("坐 " + leg.via + " 站");
                Tokens.textSize(via, "xt_body");
                via.setTextColor(c("ink"));
                via.setPadding(dp(6), 0, 0, 0);
                l1.addView(via);
            }
            bd.addView(l1);

            TextView line2 = new TextView(this);
            line2.setText(leg.depName + " → " + leg.arrName);
            Tokens.textSize(line2, "xt_caption");
            line2.setTextColor(c("ink2"));
            line2.setPadding(0, dp(3), 0, 0);
            bd.addView(line2);

            if (!passed && !stale) {
                int boardSec = legStartSec(index);
                if (index == firstBusIndex() && etaSec >= 0) {
                    // 车还没到站时，上车时间不能早于车辆实际到站时间
                    boardSec = Math.max(boardSec, etaSec);
                }
                TextView line3 = new TextView(this);
                line3.setText("预计 " + clockIn(boardSec) + " 上车 · "
                        + clockIn(boardSec + leg.sec) + " 下车");
                Tokens.textSize(line3, "xt_caption");
                line3.setTextColor(c("ink3"));
                line3.setPadding(0, dp(2), 0, 0);
                bd.addView(line3);
            }

            LinearLayout foot = new LinearLayout(this);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            foot.setPadding(0, dp(6), 0, 0);
            if (!licence.isEmpty() && index == firstBusIndex()) {
                foot.addView(plateView(licence));
            }
            TextView chip;
            if (hasLive(index)) {
                boolean missed = missedNow();
                chip = rtChip(missed ? minNum(etaSec) + " 分后到 · 赶不上"
                                : minNum(etaSec) + " 分后到 · 还能上",
                        missed ? c("signal") : c("pine"), true);
            } else if (index == firstBusIndex() && !stale && !passed) {
                chip = rtChip("暂无实时 · 按时刻表", c("ink3"), false);
            } else {
                chip = rtChip("按时刻表", c("ink3"), false);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(6);
            foot.addView(chip, clp);
            bd.addView(foot);
        } else {
            LinearLayout foot = new LinearLayout(this);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            String to = index + 1 < legs.size() ? legs.get(index + 1).depName : destName;
            String s = "步行 " + Math.max(1, Math.round(leg.sec / 60f)) + " 分钟";
            if (leg.meters > 0) {
                s += " · " + leg.meters + " 米";
            }
            if (to != null && !to.isEmpty()) {
                s += " · 至 " + to;
            }
            t.setText(s);
            Tokens.textSize(t, "xt_caption");
            t.setTextColor(c("ink2"));
            foot.addView(t);

            // 离线步行导航入口：目标取下一段的上车站，最后一段取方案终点
            final double toLat;
            final double toLng;
            final String toName;
            if (index + 1 < legs.size()) {
                toLat = legs.get(index + 1).depLat;
                toLng = legs.get(index + 1).depLng;
                toName = legs.get(index + 1).depName;
            } else {
                // 搜索结果的坐标可能缺失（站点对象只有 location），
                // 最后一段退回本条线路的到达站坐标，绝不能把 NaN 传给步行页
                Leg lastBus = null;
                for (int k = legs.size() - 1; k >= 0; k--) {
                    if (legs.get(k).bus) {
                        lastBus = legs.get(k);
                        break;
                    }
                }
                boolean okDest = !Double.isNaN(destLat) && !Double.isNaN(destLng)
                        && destLat != 0 && destLng != 0;
                if (okDest) {
                    toLat = destLat;
                    toLng = destLng;
                    toName = destName;
                } else if (lastBus != null && lastBus.arrLat != 0 && lastBus.arrLng != 0) {
                    toLat = lastBus.arrLat;
                    toLng = lastBus.arrLng;
                    toName = lastBus.arrName;
                } else {
                    toLat = 0;
                    toLng = 0;
                    toName = destName;
                }
            }
            if (toLat != 0 && toLng != 0 && !Double.isNaN(toLat) && !Double.isNaN(toLng)) {
                TextView go = new TextView(this);
                go.setText("步行导航 ›");
                Tokens.textSize(go, "xt_caption");
                go.setTextColor(c("pine"));
                go.setPadding(dp(8), dp(2), 0, dp(2));
                go.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        android.content.Intent it = new android.content.Intent(
                                PlanDetailActivity.this, WalkNavActivity.class);
                        it.putExtra("destName", toName);
                        it.putExtra("destLat", toLat);
                        it.putExtra("destLng", toLng);
                        it.putExtra("destDatum", WalkCoord.DATUM_GCJ);
                        // 起点不传：步行导航一律从手机当前位置出发，
                        // 由步行页等首帧定位后再算，不再用「上一段下车站」冒充「我」。
                        startActivity(it);
                    }
                });
                foot.addView(go);
            }
            bd.addView(foot);
        }

        if (dim) {
            row.setAlpha(0.45f);
        }
        if (leg.bus) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean opening = expandedLeg != index;
                    expandedLeg = opening ? index : -1;
                    stationsAllShown = false;
                    if (opening && !stationCache.containsKey(leg.lineId)) {
                        loadStations(leg);
                    }
                    render();
                }
            });
        }
        return row;
    }

    private View expandPanel(int index) {
        final Leg leg = legs.get(index);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c("card"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), c("line"));
        box.setBackground(bg);
        int pad = Tokens.dp(this, "xt_card_pad");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = pad + dp(12);
        lp.rightMargin = pad;
        lp.bottomMargin = dp(10);
        box.setLayoutParams(lp);

        // 没车/过期时只影响「车画不画」，站表照常显示，提示压成一行小字
        // 实时只给第一段公交，其余段即便整体有车也不画车
        boolean showCar = !rtEmpty && etaSec >= 0 && !stale && !passed
                && index == firstBusIndex();
        String why = "";
        if (stale) {
            why = "数据已过期，先不显示车的位置";
        } else if (passed) {
            why = "今天已经收班，只显示站序";
        } else if (rtEmpty) {
            why = "这条线暂时没有车，只显示站序";
        } else if (!showCar) {
            why = index == firstBusIndex()
                    ? "暂时拿不到这路车的位置，只显示站序"
                    : "这一段没有实时位置，只显示站序";
        }
        if (!why.isEmpty()) {
            addHint(box, why);
        }

        JSONArray arr = stationCache.get(leg.lineId);
        if (arr == null) {
            addHint(box, loadingStations ? "正在获取站序…" : "站名暂时取不到，只显示站序");
        } else {
            addStops(box, leg, arr, showCar);
        }

        TextView tip = new TextView(this);
        String s = (showCar && !licence.isEmpty() ? "车牌 " + licence + " · " : "")
                + "数据 " + clock.format(new Date(updatedAt)) + (showCar ? " · 每 30 秒更新" : "");
        tip.setText(s);
        Tokens.textSize(tip, "xt_caption");
        tip.setTextColor(c("ink3"));
        tip.setPadding(dp(10), dp(2), dp(10), dp(9));
        box.addView(tip);

        // 收起统一走「再点一次乘车段那一行」，面板本身不接点击，避免滑动时误收
        return box;
    }

    private void addHint(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(c("ink3"));
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addView(t);
    }

    private void addStops(LinearLayout box, Leg leg, JSONArray arr, boolean showCar) {
        int depIdx = -1;
        int arrIdx = -1;
        // 同名站可能在表里出现多次，取「上下车之间站数」最接近方案 via 的那一组
        int best = Integer.MAX_VALUE;
        for (int d = 0; d < arr.length(); d++) {
            JSONObject sd = arr.optJSONObject(d);
            if (sd == null || !leg.depName.equals(sd.optString("sn"))) {
                continue;
            }
            for (int a = 0; a < arr.length(); a++) {
                JSONObject sa = arr.optJSONObject(a);
                if (sa == null || a == d || !leg.arrName.equals(sa.optString("sn"))) {
                    continue;
                }
                int cost;
                if (leg.via > 0) {
                    cost = Math.abs(Math.abs(a - d) - leg.via);
                } else {
                    // via 缺失时优先取顺向组合（下车站排在上车站之后）
                    cost = a > d ? 0 : 1;
                }
                if (cost < best) {
                    best = cost;
                    depIdx = d;
                    arrIdx = a;
                }
            }
        }
        if (depIdx < 0) {
            depIdx = indexOfStation(arr, leg.depName);
            arrIdx = indexOfStation(arr, leg.arrName);
        }
        if (depIdx < 0 || arrIdx < 0 || depIdx == arrIdx) {
            addHint(box, "站名在这条线里对不上，暂时列不出站序");
            return;
        }
        int curIdx = -1;
        if (showCar && rtOrder > 0) {
            for (int k = 0; k < arr.length(); k++) {
                JSONObject s = arr.optJSONObject(k);
                if (s != null && s.optInt("order", -1) == rtOrder) {
                    curIdx = k;
                    break;
                }
            }
        }
        // 展开只排「上车站 → 下车站」这一段；站表方向与方案相反时按倒序排
        int dir = arrIdx >= depIdx ? 1 : -1;
        int total = Math.abs(arrIdx - depIdx) + 1;
        // 中间站最多露 5 个（从上车站后面第一个开始），多出来的收成一行
        final int MIDDLE_MAX = 5;
        int middle = total - 2;
        boolean[] show = new boolean[total];
        if (stationsAllShown || middle <= MIDDLE_MAX) {
            java.util.Arrays.fill(show, true);
        } else {
            show[0] = true;
            for (int k = 1; k <= MIDDLE_MAX; k++) {
                show[k] = true;
            }
            show[total - 1] = true;
        }
        // 车在这段站序里的位置（在段外就不画）
        int carPos = -1;
        if (curIdx >= 0) {
            int rel = (curIdx - depIdx) * dir;
            if (rel >= 0 && rel < total) {
                carPos = rel;
            }
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(8), dp(10), dp(2));

        int i = 0;
        while (i < total) {
            if (!show[i]) {
                int j = i;
                while (j < total && !show[j]) {
                    j++;
                }
                list.addView(foldRow("中间还经过 " + (j - i) + " 站"));
                i = j;
                continue;
            }
            int idx = depIdx + dir * i;
            boolean car = i == carPos;
            boolean passedStop = carPos >= 0 && i < carPos;
            String label = null;
            int labelColor = 0;
            if (idx == depIdx) {
                label = "你在这上车";
                labelColor = c("pine");
            } else if (idx == arrIdx) {
                label = "在这下车";
                labelColor = c("pine");
            }
            if (car) {
                if (idx == depIdx) {
                    label = "车已到站 · 你在这上车";
                } else if (idx == arrIdx) {
                    label = "车已到站 · 在这下车";
                } else {
                    label = "车在这里";
                }
                labelColor = c("signal");
            }
            list.addView(stationRow(stationName(arr, idx), car, passedStop, label, labelColor));
            i++;
        }
        box.addView(list);
    }

    /** 站序一行：车当前站用朱砂实心，本站用墨绿空心，已过站灰。 */
    private View stationRow(String name, boolean car, boolean passed, String label, int labelColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        View dot = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (car) {
            bg.setColor(c("signal"));
        } else if (passed) {
            bg.setColor(c("line"));
        } else {
            bg.setColor(c("card"));
            bg.setStroke(dp(2), label != null ? c("pine") : c("ink3"));
        }
        dot.setBackground(bg);
        int size = car ? dp(9) : dp(8);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
        dlp.rightMargin = dp(8);
        row.addView(dot, dlp);

        TextView t = new TextView(this);
        t.setText(name);
        Tokens.textSize(t, car || label != null ? "xt_body" : "xt_caption");
        t.setTextColor(car ? c("signal") : passed ? c("ink3") : c("ink"));
        if (car || label != null) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        row.addView(t);

        if (label != null) {
            row.addView(tag(label, car ? c("signal") : labelColor));
        }
        return row;
    }

    /** 中间折叠行：点一下把这段站全部铺开。 */
    private View foldRow(final String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        View dot = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(c("line"));
        dot.setBackground(bg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
        dlp.rightMargin = dp(11);
        dlp.leftMargin = dp(1);
        row.addView(dot, dlp);

        TextView t = new TextView(this);
        t.setText(text + " ⌄");
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(c("pine"));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stationsAllShown = true;
                render();
            }
        });
        return row;
    }

    private TextView tag(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(color);
        t.setPadding(dp(6), 0, 0, 0);
        return t;
    }

    private String stationName(JSONArray arr, int index) {
        JSONObject s = arr == null ? null : arr.optJSONObject(index);
        return s == null ? "" : s.optString("sn");
    }

    private int indexOfStation(JSONArray arr, String name) {
        if (arr == null || name == null || name.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && name.equals(s.optString("sn"))) {
                return i;
            }
        }
        return -1;
    }

    /** 展开时才拉站表，按 lineId 缓存；拉不到就只显示位置。 */
    private void loadStations(final Leg leg) {
        if (leg.lineId == null || leg.lineId.isEmpty()) {
            return;
        }
        loadingStations = true;
        Io.query(new Runnable() {
            @Override
            public void run() {
                JSONArray got = null;
                try {
                    Api.Result r = Api.lineDetail(leg.lineId, leg.lineName, "",
                            leg.depName, "", "", "", false);
                    JSONObject d = r == null ? null : r.data;
                    got = d == null ? null : d.optJSONArray("stations");
                } catch (Exception ignore) {
                    got = null;
                }
                final JSONArray stations = got;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        loadingStations = false;
                        if (stations != null && stations.length() > 0) {
                            stationCache.put(leg.lineId, stations);
                        }
                        render();
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        foreground = false;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void updateFooter() {
        if (footer == null) {
            return;
        }
        if (passed) {
            footer.setText("数据 " + clock.format(new Date(updatedAt)) + " · 今天已收班");
            footer.setTextColor(c("signal"));
            return;
        }
        if (stale) {
            footer.setText("数据 " + clock.format(new Date(updatedAt)) + " · 下拉重新规划");
            footer.setTextColor(c("ink3"));
            return;
        }
        footer.setText("更新于 " + clock.format(new Date(updatedAt)) + " · 下拉刷新");
        footer.setTextColor(c("ink3"));
    }

    private void showError(String title, String desc) {
        content.removeAllViews();
        LinearLayout card = card();
        TextView t = new TextView(this);
        t.setText(title);
        Tokens.textSize(t, "xt_body");
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(c("ink"));
        card.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        Tokens.textSize(d, "xt_caption");
        d.setTextColor(c("ink2"));
        d.setPadding(0, dp(4), 0, 0);
        card.addView(d);
        content.addView(card);
        counter.setText("");
        setEnabled(prevBtn, false);
        setEnabled(nextBtn, false);
    }

    // ---------------- 小工具 ----------------

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Tokens.dp(this, "xt_card_pad");
        card.setPadding(pad, dp(13), pad, dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private void setEnabled(TextView v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private String minutes(int seconds) {
        int min = Math.max(1, Math.round(seconds / 60f));
        return min + " 分钟";
    }

    private String minNum(int seconds) {
        return String.valueOf(Math.max(1, Math.round(seconds / 60f)));
    }

    private String shortLine(String name) {
        return com.xtbus.Fmt.nodeName(name);
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String hhmm(String raw) {
        return com.xtbus.Fmt.hhmm(raw);
    }

    private int parseHm(String hm) {
        return com.xtbus.Fmt.parseHm(hm);
    }

    private int nowMinutes() {
        return com.xtbus.Fmt.nowMinutes();
    }
}

