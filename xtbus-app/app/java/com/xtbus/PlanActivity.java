package com.xtbus;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 路线规划：从首页搜索的站点直接进来，起点默认当前定位。
 * 数据来自 transfer/transit!integrate.action；实时到站只并第一段公交，
 * 30 秒一次、仅前台。
 */
public class PlanActivity extends Activity {

    private static final int STRATEGY_FAST = 0;
    private static final int STRATEGY_LESS_CHANGE = 2;
    private static final int STRATEGY_LESS_WALK = 3;
    private static final long REALTIME_INTERVAL = 30 * 1000L;
    /** 换乘两次及以上的方案收进「其他方案」。 */
    private static final int OTHERS_TRANSFER = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private LinearLayout content;
    private LinearLayout tabBox;
    private LinearLayout planBox;
    private TextView originView;
    private TextView destView;
    private TextView statusView;

    private String destSid = "";
    private String destName = "";
    private double destLat;
    private double destLng;
    private String originSid = "";
    private String originName = "当前位置";
    private double originLat;
    private double originLng;

    private int strategy = STRATEGY_FAST;
    private boolean showingOthers;
    private boolean loading;
    private boolean foreground;
    private boolean riskStopped;

    private final List<Plan> plans = new ArrayList<Plan>();
    private final List<EtaSlot> etaSlots = new ArrayList<EtaSlot>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Api.init(getApplicationContext());
        Tokens.load(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plan);

        destSid = safe(getIntent().getStringExtra("destSid"));
        destName = safe(getIntent().getStringExtra("destName"));
        destLat = getIntent().getDoubleExtra("destLat", Api.lat());
        destLng = getIntent().getDoubleExtra("destLng", Api.lng());
        originName = safe(getIntent().getStringExtra("originName"));
        if (originName.isEmpty()) {
            originName = "当前位置";
        }
        originLat = getIntent().getDoubleExtra("originLat", Api.lat());
        originLng = getIntent().getDoubleExtra("originLng", Api.lng());
        originSid = safe(getIntent().getStringExtra("originSid"));

        content = findViewById(R.id.content);
        applyTokens();
        buildOdCard();
        buildTabs();

        statusView = new TextView(this);
        Tokens.textSize(statusView, "xt_caption");
        statusView.setTextColor(c("ink3"));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(2), 0, dp(8));
        statusView.setVisibility(View.GONE);
        content.addView(statusView, matchWrap());

        planBox = new LinearLayout(this);
        planBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(planBox, matchWrap());

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.swapBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swapEnds();
            }
        });

        load(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        // 风控冷却结束后恢复实时刷新，否则一次风控会让本页永远不再更新
        if (riskStopped && Api.riskCooldownRemaining() == 0) {
            riskStopped = false;
        }
        if (hasUsable()) {
            startRealtime();
        }
    }

    @Override
    protected void onPause() {
        foreground = false;
        ui.removeCallbacks(realtimeTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        foreground = false;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------- 令牌 ----------------

    private int c(String token) {
        return Tokens.color(this, token);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void applyTokens() {
        TextView back = findViewById(R.id.back);
        Tokens.textSize(back, "xt_appbar");
        back.setTextColor(c("ink"));
        TextView title = findViewById(R.id.title);
        Tokens.textSize(title, "xt_appbar");
        title.setTextColor(c("ink"));
        TextView swap = findViewById(R.id.swapBtn);
        if (Tokens.isLarge()) {
            swap.setText("换向");
            Tokens.textSize(swap, "xt_body");
        } else {
            swap.setText("⇄");
            Tokens.textSize(swap, "xt_line_no");
        }
        swap.setTextColor(c("pine"));
        swap.setMinHeight(Tokens.dp(this, "xt_touch"));
        swap.setMinWidth(Tokens.dp(this, "xt_touch"));
    }

    // ---------------- 起终点 ----------------

    private void buildOdCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Tokens.dp(this, "xt_card_pad");
        card.setPadding(pad, dp(10), pad, dp(8));
        card.setLayoutParams(matchWrap());

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.addView(dot(c("signal"), false));
        originView = odName();
        row1.addView(originView, weightWrap());
        row1.addView(odSub("起点"));
        card.addView(row1);

        View conn = new View(this);
        conn.setBackgroundColor(c("line"));
        LinearLayout.LayoutParams connLp = new LinearLayout.LayoutParams(dp(1), dp(14));
        connLp.leftMargin = dp(4);
        card.addView(conn, connLp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.addView(dot(c("pine"), true));
        destView = odName();
        row2.addView(destView, weightWrap());
        TextView destSub = odSub("终点");
        row2.addView(destSub);
        card.addView(row2);

        TextView when = new TextView(this);
        when.setText("现在出发");
        Tokens.textSize(when, "xt_caption");
        when.setTextColor(c("ink3"));
        when.setPadding(0, dp(9), 0, 0);
        card.addView(when);

        content.addView(card, marginBottom(dp(10)));
        refreshOdText();
    }

    private View dot(int color, boolean square) {
        View v = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        if (square) {
            bg.setCornerRadius(dp(3));
        } else {
            bg.setShape(GradientDrawable.OVAL);
        }
        bg.setSize(dp(10), dp(10));
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView odName() {
        TextView t = new TextView(this);
        Tokens.textSize(t, "xt_station");
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(c("ink"));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private TextView odSub(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(c("ink3"));
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, 0, 0);
        return t;
    }

    private void refreshOdText() {
        originView.setText(originName);
        destView.setText(destName);
    }

    private void swapEnds() {
        String n = originName;
        originName = destName;
        destName = n;
        String sid = originSid;
        originSid = destSid;
        destSid = sid;
        double lat = originLat;
        originLat = destLat;
        destLat = lat;
        double lng = originLng;
        originLng = destLng;
        destLng = lng;
        refreshOdText();
        showingOthers = false;
        load(false);
    }

    // ---------------- 策略 ----------------

    private void buildTabs() {
        tabBox = new LinearLayout(this);
        tabBox.setOrientation(LinearLayout.HORIZONTAL);
        tabBox.setLayoutParams(marginBottom(dp(10)));
        tabBox.addView(tab("推荐", STRATEGY_FAST));
        tabBox.addView(tab("少换乘", STRATEGY_LESS_CHANGE));
        tabBox.addView(tab("少步行", STRATEGY_LESS_WALK));
        content.addView(tabBox);
        styleTabs();
    }

    private View tab(String text, final int value) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSize(t, "xt_label");
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(7), dp(13), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (strategy == value) {
                    return;
                }
                strategy = value;
                showingOthers = false;
                styleTabs();
                load(false);
            }
        });
        return t;
    }

    private void styleTabs() {
        int[] values = {STRATEGY_FAST, STRATEGY_LESS_CHANGE, STRATEGY_LESS_WALK};
        for (int i = 0; i < tabBox.getChildCount(); i++) {
            TextView t = (TextView) tabBox.getChildAt(i);
            boolean on = values[i] == strategy;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(999));
            bg.setColor(on ? c("pine") : c("card"));
            bg.setStroke(dp(1), on ? c("pine") : c("line"));
            t.setBackground(bg);
            t.setTextColor(on ? 0xFFFFFFFF : c("ink2"));
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    // ---------------- 请求 ----------------

    private void load(final boolean silent) {
        if (loading) {
            return;
        }
        if (destLat == 0 && destLng == 0) {
            showError("缺少终点坐标，请回搜索结果重新进入");
            return;
        }
        if (!originSid.isEmpty() && originSid.equals(destSid)) {
            planBox.removeAllViews();
            planBox.addView(emptyCard("起点和终点是同一个站", "换一个终点，或直接步行到站台。", false));
            return;
        }
        loading = true;
        showStatus(silent ? "" : "正在规划路线…");
        final double oLat = originLat;
        final double oLng = originLng;
        final int useStrategy = strategy;
        Io.query(new Runnable() {
            @Override
            public void run() {
                try {
                    final Api.Result result = Api.transfer(originName, originSid, oLat, oLng,
                            destName, destSid, destLat, destLng, useStrategy, true);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            loading = false;
                            // 请求在途时用户已切换策略：这份结果作废，按新策略重发
                            if (useStrategy != strategy) {
                                load(false);
                                return;
                            }
                            render(result);
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
                            if (e.kind == Api.ApiException.Kind.RISK
                                    || e.kind == Api.ApiException.Kind.COOLDOWN) {
                                riskStopped = true;
                            }
                            showError(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void render(Api.Result result) {
        parsePlans(result.data);
        if (!result.risk) {
            riskStopped = false;
        }
        renderPlans();
        boolean anyUsable = hasUsable();
        String note = anyUsable ? plans.size() + " 个方案" : "";
        if (result.fromCache) {
            note += (note.isEmpty() ? "" : " · ") + "缓存于 " + clock.format(new Date(result.fetchedAt));
        }
        if (result.degraded) {
            note += (note.isEmpty() ? "" : " · ") + "网络不稳定";
        }
        showStatus(note);
        if (anyUsable) {
            startRealtime();
        }
    }

    private void parsePlans(JSONObject data) {
        plans.clear();
        JSONObject route = data == null ? null : data.optJSONObject("route");
        JSONArray transits = route == null ? null : route.optJSONArray("transits");
        if (transits == null) {
            return;
        }
        int now = nowMinutes();
        for (int i = 0; i < transits.length(); i++) {
            JSONObject t = transits.optJSONObject(i);
            if (t == null) {
                continue;
            }
            Plan p = new Plan();
            p.raw = t;
            p.durationSec = t.optInt("duration", 0);
            p.walkM = t.optInt("walking_distance", 0);
            p.distanceM = t.optInt("distance", 0);
            JSONArray segments = t.optJSONArray("segments");
            if (segments != null) {
                for (int k = 0; k < segments.length(); k++) {
                    JSONObject seg = segments.optJSONObject(k);
                    if (seg == null) {
                        continue;
                    }
                    JSONObject walking = seg.optJSONObject("walking");
                    if (walking != null && walking.optInt("duration", 0) > 0) {
                        Seg w = new Seg();
                        w.bus = false;
                        w.walkSec = walking.optInt("duration", 0);
                        w.walkM = walking.optInt("distance", 0);
                        p.segs.add(w);
                    }
                    JSONObject bus = seg.optJSONObject("bus");
                    JSONArray buslines = bus == null ? null : bus.optJSONArray("buslines");
                    if (buslines == null || buslines.length() == 0) {
                        continue;
                    }
                    JSONObject l = buslines.optJSONObject(0);
                    if (l == null) {
                        continue;
                    }
                    Seg b = new Seg();
                    b.bus = true;
                    b.lineName = l.optString("name");
                    b.lineId = l.optString("lineId");
                    b.endStation = l.optString("final_station");
                    b.start = hhmm(l.optString("start_time"));
                    b.end = hhmm(l.optString("end_time"));
                    b.rideSec = l.optInt("duration", 0);
                    b.via = parseInt(l.optString("via_num"));
                    b.state = l.optInt("state", 0);
                    JSONObject dep = l.optJSONObject("departure_stop");
                    if (dep != null) {
                        b.depName = dep.optString("name");
                        b.depSid = dep.optString("sId");
                    }
                    JSONObject arr = l.optJSONObject("arrival_stop");
                    if (arr != null) {
                        b.arrName = arr.optString("name");
                        b.arrSid = arr.optString("sId");
                    }
                    JSONArray buses = l.optJSONArray("buses");
                    if (buses != null && buses.length() > 0) {
                        JSONObject first = buses.optJSONObject(0);
                        b.eta = first == null ? -1 : first.optInt("travelTime", -1);
                    }
                    p.segs.add(b);
                }
            }
            p.transferCount = Math.max(0, p.busCount() - 1);
            p.endMinutes = lastEndMinutes(p);
            // 任意一段公交末班已过即视为赶不上，不能只看第一段
            p.passed = anyBusGone(p, now);
            plans.add(p);
        }
        Collections.sort(plans, new Comparator<Plan>() {
            @Override
            public int compare(Plan a, Plan b) {
                if (a.passed != b.passed) {
                    return a.passed ? 1 : -1;
                }
                return a.durationSec - b.durationSec;
            }
        });
    }

    private boolean hasUsable() {
        for (Plan p : plans) {
            if (!p.passed) {
                return true;
            }
        }
        return false;
    }

    private int lastEndMinutes(Plan p) {
        for (Seg s : p.segs) {
            if (s.bus && !s.end.isEmpty()) {
                return parseHm(s.end);
            }
        }
        return 0;
    }

    /** 已收班的那一段末班时间；没有任何一段收班时退回第一段。 */
    private String goneEnd(Plan p) {
        String fallback = "";
        for (Seg s : p.segs) {
            if (!s.bus || s.end.isEmpty()) {
                continue;
            }
            if (fallback.isEmpty()) {
                fallback = s.end;
            }
            if (com.xtbus.Fmt.lastBusGone(nowMinutes(), parseHm(s.end))) {
                return s.end;
            }
        }
        return fallback;
    }

    /** 任一段公交的末班已过即判为赶不上。 */
    private boolean anyBusGone(Plan p, int now) {
        for (Seg s : p.segs) {
            if (!s.bus || s.end.isEmpty()) {
                continue;
            }
            int end = parseHm(s.end);
            if (com.xtbus.Fmt.lastBusGone(now, end)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 渲染 ----------------

    private void renderPlans() {
        planBox.removeAllViews();
        etaSlots.clear();
        int usable = 0;
        for (Plan p : plans) {
            if (!p.passed) {
                usable++;
            }
        }
        if (usable == 0) {
            planBox.addView(noUsableCard());
            return;
        }
        int index = 0;
        int others = 0;
        boolean recommendedUsed = false;
        for (Plan p : plans) {
            if (p.transferCount >= OTHERS_TRANSFER && !showingOthers) {
                others++;
                index++;
                continue;
            }
            planBox.addView(planCard(p, index, !recommendedUsed && !p.passed), marginBottom(dp(10)));
            if (!p.passed) {
                recommendedUsed = true;
            }
            index++;
        }
        if (others > 0) {
            TextView more = new TextView(this);
            more.setText(showingOthers ? "收起其他方案" : "其他方案（" + others + "）");
            Tokens.textSize(more, "xt_label");
            more.setTextColor(c("ink2"));
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(10), 0, dp(4));
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showingOthers = !showingOthers;
                    renderPlans();
                }
            });
            planBox.addView(more);
        }
    }

    private View planCard(final Plan p, final int index, boolean recommended) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Tokens.dp(this, "xt_card_pad");
        card.setPadding(pad, dp(11), pad, dp(11));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (recommended) {
            top.addView(tag("推荐", c("signal")));
        }
        TextView dur = new TextView(this);
        dur.setText(minutesText(p.durationSec));
        Tokens.textSize(dur, "xt_eta");
        dur.setTypeface(Typeface.DEFAULT_BOLD);
        dur.setTextColor(p.passed ? c("ink3") : c("ink"));
        top.addView(dur);
        TextView kind = new TextView(this);
        kind.setText(p.transferCount == 0 ? "直达" : "换乘 " + p.transferCount + " 次");
        Tokens.textSize(kind, "xt_label");
        kind.setTextColor(c("ink2"));
        kind.setPadding(dp(8), 0, 0, 0);
        top.addView(kind);
        card.addView(top);

        Seg firstBus = firstBus(p);
        if (firstBus != null && !firstBus.start.isEmpty()) {
            TextView meta = new TextView(this);
            meta.setText("首班 " + firstBus.start + " · 末班 " + firstBus.end
                    + (p.distanceM > 0 ? " · 全程 " + km(p.distanceM) : ""));
            Tokens.textSize(meta, "xt_caption");
            meta.setTextColor(c("ink3"));
            meta.setPadding(0, dp(4), 0, 0);
            card.addView(meta);
        }

        LinearLayout segs = new LinearLayout(this);
        segs.setOrientation(LinearLayout.VERTICAL);
        segs.setPadding(0, dp(8), 0, 0);
        fillSegs(segs, p, false);
        card.addView(segs);

        if (p.passed) {
            TextView warn = new TextView(this);
            warn.setText("今日末班 " + goneEnd(p) + " 已过"
                    + (firstBus == null || firstBus.start.isEmpty() ? "" : "，首班 " + firstBus.start));
            Tokens.textSize(warn, "xt_caption");
            warn.setTextColor(c("ink2"));
            warn.setBackground(softBg(c("sunken")));
            warn.setPadding(dp(9), dp(6), dp(9), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            card.addView(warn, lp);
        } else if (firstBus != null) {
            TextView eta = etaView(firstBus);
            if (firstBus.eta < 0) {
                eta.setVisibility(View.GONE);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            card.addView(eta, lp);
            etaSlots.add(new EtaSlot(firstBus.depSid, firstBus.lineId, eta, firstBus.depName));
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail(index);
            }
        });
        return card;
    }

    /** 进入独立的路线详情页；列表页保持不动，返回时滚动位置与策略 tab 原样保留。 */
    private void openDetail(int planIndex) {
        // 只带当前列表上看得见的方案：折叠在「其他方案」里的不参与详情页的上一班/下一班
        JSONArray payload = new JSONArray();
        int target = 0;
        for (int i = 0; i < plans.size(); i++) {
            Plan p = plans.get(i);
            if (p.raw == null) {
                continue;
            }
            if (!showingOthers && p.transferCount >= OTHERS_TRANSFER) {
                continue;
            }
            if (i == planIndex) {
                target = payload.length();
            }
            payload.put(p.raw);
        }
        if (payload.length() == 0) {
            return;
        }
        Intent i = new Intent(this, PlanDetailActivity.class);
        i.putExtra("plansJson", payload.toString());
        i.putExtra("index", Math.max(0, Math.min(target, payload.length() - 1)));
        i.putExtra("originName", originName);
        i.putExtra("originSid", originSid);
        i.putExtra("originLat", originLat);
        i.putExtra("originLng", originLng);
        i.putExtra("destName", destName);
        i.putExtra("destSid", destSid);
        i.putExtra("destLat", destLat);
        i.putExtra("destLng", destLng);
        i.putExtra("strategy", strategy);
        startActivity(i);
    }

    private void fillSegs(LinearLayout box, Plan p, boolean detailed) {
        if (!detailed) {
            TextView sum = new TextView(this);
            sum.setText(summary(p));
            Tokens.textSize(sum, "xt_body_s");
            sum.setTextColor(c("ink2"));
            sum.setLineSpacing(dp(2), 1f);
            box.addView(sum);
            return;
        }
        for (int i = 0; i < p.segs.size(); i++) {
            Seg s = p.segs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(7);
            row.setLayoutParams(rowLp);

            row.addView(icon(s));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = dp(9);
            col.setLayoutParams(colLp);

            TextView title = new TextView(this);
            Tokens.textSize(title, "xt_body");
            title.setTextColor(c("ink"));
            TextView sub = new TextView(this);
            Tokens.textSize(sub, "xt_caption");
            sub.setTextColor(c("ink3"));
            sub.setPadding(0, dp(2), 0, 0);

            if (s.bus) {
                title.setText(s.lineName + (s.via > 0 ? " · " + s.via + " 站" : ""));
                String dir = s.endStation.isEmpty() ? "" : "往 " + s.endStation + " · ";
                sub.setText(dir + "上车 " + s.depName + " · 下车 " + s.arrName);
            } else {
                title.setText("步行 " + s.walkM + " 米");
                sub.setText(nextIsBus(p, i) ? "到 " + nextBusDep(p, i) + " 乘车" : "到 " + destName);
            }
            col.addView(title);
            col.addView(sub);
            row.addView(col);

            TextView time = new TextView(this);
            time.setText(minutesText(s.bus ? s.rideSec : s.walkSec));
            Tokens.textSize(time, "xt_caption");
            time.setTextColor(c("ink3"));
            row.addView(time);

            box.addView(row);
        }
    }

    private View icon(Seg s) {
        TextView t = new TextView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(s.bus ? c("pine") : c("sunken"));
        t.setBackground(bg);
        t.setText(s.bus ? shortLine(s.lineName) : "步");
        t.setTextColor(s.bus ? 0xFFFFFFFF : c("ink2"));
        Tokens.textSizeFixed(t, "xt_num_font");
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(24));
        t.setPadding(dp(4), dp(3), dp(4), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView etaView(Seg bus) {
        TextView t = new TextView(this);
        t.setText(etaText(bus.eta, bus.depName));
        Tokens.textSize(t, "xt_caption");
        t.setTextColor(c("signal"));
        t.setBackground(softBg(c("signal90")));
        t.setPadding(dp(9), dp(6), dp(9), dp(6));
        return t;
    }

    private String etaText(int minutes, String stop) {
        if (minutes <= 0) {
            return "● 车辆即将到站";
        }
        return "● 下一班 " + minutes + " 分后到 " + stop;
    }

    private View emptyCard(String title, String detail, boolean retry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Tokens.dp(this, "xt_card_pad");
        card.setPadding(pad, dp(20), pad, dp(18));

        TextView t = new TextView(this);
        t.setText(title);
        Tokens.textSize(t, "xt_station");
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(c("ink"));
        t.setGravity(Gravity.CENTER);
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(detail);
        Tokens.textSize(d, "xt_body_s");
        d.setTextColor(c("ink2"));
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(dp(3), 1f);
        d.setPadding(0, dp(8), 0, 0);
        card.addView(d);

        if (retry) {
            TextView btn = new TextView(this);
            btn.setText("重试");
            Tokens.textSize(btn, "xt_label");
            btn.setTextColor(0xFFFFFFFF);
            btn.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(c("pine"));
            bg.setCornerRadius(dp(10));
            btn.setBackground(bg);
            btn.setPadding(dp(18), dp(10), dp(18), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    load(false);
                }
            });
            card.addView(btn);
        }
        return card;
    }

    /** 无可用方案：在同一张卡里写清哪条线赶不上、首班几点。 */
    private View noUsableCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Tokens.dp(this, "xt_card_pad");
        card.setPadding(pad, dp(20), pad, dp(16));

        TextView title = new TextView(this);
        title.setText(plans.isEmpty() ? "这个时段没有公交方案" : "现在赶不上这班车");
        Tokens.textSize(title, "xt_station");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(c("ink"));
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView lead = new TextView(this);
        lead.setText(plans.isEmpty()
                ? "起终点之间没查到常规公交线路。"
                : "下面的线路今天末班已过，早一点或明天再来：");
        Tokens.textSize(lead, "xt_body_s");
        lead.setTextColor(c("ink2"));
        lead.setGravity(Gravity.CENTER);
        lead.setPadding(0, dp(8), 0, dp(2));
        card.addView(lead);

        int earliest = 0;
        int shown = 0;
        for (Plan p : plans) {
            if (shown >= 4) {
                break;
            }
            String start = hhmm(firstStart(p));
            String end = p.endMinutes > 0 ? formatHm(p.endMinutes) : hhmm(lastEnd(p));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(softBg(c("sunken")));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(7);
            row.setLayoutParams(rowLp);

            TextView lines = new TextView(this);
            lines.setText(planLines(p));
            Tokens.textSize(lines, "xt_body_s");
            lines.setTextColor(c("ink"));
            lines.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(lines);

            TextView meta = new TextView(this);
            meta.setText("末班 " + (end.isEmpty() ? "—" : end) + " 已过"
                    + (start.isEmpty() ? "" : " · 首班 " + start));
            Tokens.textSize(meta, "xt_caption");
            meta.setTextColor(c("ink3"));
            meta.setPadding(0, dp(2), 0, 0);
            row.addView(meta);

            card.addView(row);
            shown++;

            int m = parseHm(start);
            if (m > 0 && (earliest == 0 || m < earliest)) {
                earliest = m;
            }
        }

        TextView foot = new TextView(this);
        foot.setText(earliest > 0
                ? "最早 " + formatHm(earliest) + " 起有首班车，到点再来。"
                : "线路首班一般在 06:00 前后，可稍后再试。");
        Tokens.textSize(foot, "xt_caption");
        foot.setTextColor(c("ink3"));
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(10), 0, 0);
        card.addView(foot);

        TextView btn = new TextView(this);
        btn.setText("重新规划");
        Tokens.textSize(btn, "xt_label");
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c("pine"));
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        btn.setPadding(dp(18), dp(10), dp(18), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(true);
            }
        });
        card.addView(btn);
        return card;
    }

    private String planLines(Plan p) {
        StringBuilder sb = new StringBuilder();
        for (Seg s : p.segs) {
            if (!s.bus) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append(s.lineName);
        }
        return sb.length() == 0 ? "步行方案" : sb.toString();
    }

    private String firstStart(Plan p) {
        for (Seg s : p.segs) {
            if (s.bus && !s.start.isEmpty()) {
                return s.start;
            }
        }
        return "";
    }

    private String lastEnd(Plan p) {
        for (Seg s : p.segs) {
            if (s.bus && !s.end.isEmpty()) {
                return s.end;
            }
        }
        return "";
    }

    private String formatHm(int minutes) {
        return String.format(Locale.CHINA, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    private void showError(String message) {
        planBox.removeAllViews();
        if ("响应形态异常".equals(message)) {
            planBox.addView(emptyCard("这个时段没有公交方案",
                    "起终点之间没查到常规公交线路。\n请确认定位在城区范围内，或换个终点再试。", true));
            showStatus("");
            return;
        }
        planBox.addView(emptyCard("没有拿到规划结果",
                message == null || message.isEmpty() ? "请稍后重试" : message, true));
        showStatus("");
    }

    private void showStatus(String text) {
        if (statusView == null) {
            return;
        }
        statusView.setText(text);
        statusView.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------------- 实时 ----------------

    private void startRealtime() {
        ui.removeCallbacks(realtimeTask);
        if (!foreground || riskStopped) {
            return;
        }
        ui.postDelayed(realtimeTask, REALTIME_INTERVAL);
    }

    private final Runnable realtimeTask = new Runnable() {
        @Override
        public void run() {
            if (!foreground || riskStopped) {
                return;
            }
            if (loading) {
                // 规划请求在途：本轮跳过，但仍要续排，避免定时链断掉
                ui.postDelayed(this, 5000);
                return;
            }
            final String lineStn = buildLineStn();
            if (!lineStn.isEmpty()) {
                Io.realtime(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final JSONObject data = Api.tsfRealInfos(lineStn);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isFinishing()) {
                                        applyRealtime(data);
                                    }
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
            }
            if (foreground && !riskStopped) {
                ui.postDelayed(this, REALTIME_INTERVAL);
            }
        }
    };

    /** 只取每个方案第一段公交（最多 3 条），末尾分号。 */
    private String buildLineStn() {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (Plan p : plans) {
            Seg b = firstBus(p);
            if (b == null || b.lineId.isEmpty() || b.depSid.isEmpty()) {
                continue;
            }
            sb.append(b.lineId).append(",").append(b.depSid).append(",1;");
            if (++used >= 3) {
                break;
            }
        }
        return sb.toString();
    }

    private void applyRealtime(JSONObject data) {
        if (data == null) {
            return;
        }
        JSONArray lines = data.optJSONArray("lines");
        if (lines == null) {
            return;
        }
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            String sId = line.optString("sId");
            String lineId = line.optString("lineId");
            JSONArray buses = line.optJSONArray("buses");
            if (buses == null || buses.length() == 0) {
                continue;
            }
            JSONObject bus = buses.optJSONObject(0);
            if (bus == null) {
                continue;
            }
            int travel = bus.optInt("travelTime", -1);
            if (travel < 0) {
                continue;
            }
            int minutes = Math.max(0, Math.round(travel / 60f));
            for (EtaSlot slot : etaSlots) {
                // 同一上车站可能有不同线路，必须同时比对 lineId，避免串数据
                boolean match = lineId.isEmpty()
                        ? slot.sId.equals(sId)
                        : slot.lineId.equals(lineId) && slot.sId.equals(sId);
                if (match) {
                    slot.view.setText(etaText(minutes, slot.stop));
                    slot.view.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    // ---------------- 小工具 ----------------

    private Seg firstBus(Plan p) {
        for (Seg s : p.segs) {
            if (s.bus) {
                return s;
            }
        }
        return null;
    }

    private boolean nextIsBus(Plan p, int index) {
        return index + 1 < p.segs.size() && p.segs.get(index + 1).bus;
    }

    private String nextBusDep(Plan p, int index) {
        return index + 1 < p.segs.size() ? p.segs.get(index + 1).depName : destName;
    }

    private String summary(Plan p) {
        StringBuilder sb = new StringBuilder();
        for (Seg s : p.segs) {
            if (sb.length() > 0) {
                sb.append("  ›  ");
            }
            sb.append(s.bus ? s.lineName : "步行 " + s.walkM + " 米");
        }
        return sb.toString();
    }

    private CharSequence minutesText(int seconds) {
        int min = Math.max(1, Math.round(seconds / 60f));
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(String.valueOf(min));
        sb.append(" 分");
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.55f), sb.length() - 2, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private String km(int meters) {
        return String.format(Locale.CHINA, "%.1f 公里", meters / 1000f);
    }

    private TextView tag(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        Tokens.textSizeFixed(t, "xt_num_font");
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(5));
        t.setBackground(bg);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable softBg(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        return bg;
    }

    private String shortLine(String name) {
        return com.xtbus.Fmt.shortLine(name);
    }

    private String hhmm(String raw) {
        return com.xtbus.Fmt.hhmm(raw);
    }

    private int parseHm(String hhmm) {
        return com.xtbus.Fmt.parseHm(hhmm);
    }

    private int nowMinutes() {
        return com.xtbus.Fmt.nowMinutes();
    }

    private int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String safe(String v) {
        return v == null ? "" : v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams marginBottom(int px) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = px;
        return lp;
    }

    private static class Seg {
        boolean bus;
        String lineName = "";
        String lineId = "";
        String depName = "";
        String arrName = "";
        String depSid = "";
        String arrSid = "";
        String endStation = "";
        String start = "";
        String end = "";
        int via;
        int rideSec;
        int walkM;
        int walkSec;
        int state;
        int eta = -1;
    }

    private static class Plan {
        int durationSec;
        int walkM;
        int distanceM;
        int transferCount;
        int endMinutes;
        boolean passed;
        JSONObject raw;
        final List<Seg> segs = new ArrayList<Seg>();

        int busCount() {
            int n = 0;
            for (Seg s : segs) {
                if (s.bus) {
                    n++;
                }
            }
            return n;
        }
    }

    private static class EtaSlot {
        final String sId;
        final String lineId;
        final TextView view;
        final String stop;

        EtaSlot(String sId, String lineId, TextView view, String stop) {
            this.sId = sId == null ? "" : sId;
            this.lineId = lineId == null ? "" : lineId;
            this.view = view;
            this.stop = stop;
        }
    }
}
