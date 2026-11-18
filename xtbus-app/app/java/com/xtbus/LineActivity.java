package com.xtbus;

/* loaded from: classes.dex */
public class LineActivity extends android.app.Activity {
    /** 车辆行光晕的透明度（约 10%）。 */
    private static final int HALO_ALPHA = 0x1A000000;
    private static final long REFRESH_INTERVAL = 30000;
    private static final long REFRESH_JITTER = 3000;
    private android.widget.TextView backToBus;
    private android.widget.LinearLayout busList;
    private android.view.View busRowView;
    private org.json.JSONObject currentLine;
    private java.lang.String direction;
    private long lastLoadAt;
    private com.xtbus.LineActivity.RailRow lastRailRow;
    private java.lang.String lineId;
    private java.lang.String lineName;
    private java.lang.String lineNo;
    private android.widget.TextView lineNoView;
    private android.widget.LinearLayout list;
    private boolean loading;
    private android.widget.TextView metaLine;
    private android.widget.TextView msg;
    private java.lang.String nextStationName;
    private org.json.JSONArray otherLines;
    private com.xtbus.LineActivity.LoadCallback pendingCallback;
    private boolean pendingRecent;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout pull;
    private boolean riskStopped;
    private android.widget.ScrollView scrollView;
    private org.json.JSONArray stationCache;
    private java.lang.String stationName;
    private boolean switching;
    private java.lang.String targetOrder;
    private android.view.View targetRow;
    /** 列表末尾的动态占位：让末段车辆也能被顶到上沿。 */
    private android.view.View bottomSpacer;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
    private int featuredOrder = -1;
    private int passOrder = -1;
    private int currentTargetOrder = -1;
    private java.lang.String currentStationName = "";
    private boolean following = true;
    /** 当前方向的终点站；顶栏放不下「开往 xxx」时改到车辆卡右侧。 */
    private java.lang.String curEndSn = "";
    /** 顶栏「线路名 + 开往 终点」已被省略号截断，后续渲染不再塞回顶栏。 */
    private boolean titleOverflow;
    private org.json.JSONObject lastLineJson;
    private org.json.JSONArray lastBusesJson;
    private org.json.JSONArray lastStationsJson;
    private final java.util.Comparator<org.json.JSONObject> BUS_CMP = new java.util.Comparator<org.json.JSONObject>() { // from class: com.xtbus.LineActivity.9
        @Override // java.util.Comparator
        public int compare(org.json.JSONObject jSONObject, org.json.JSONObject jSONObject2) {
            boolean z = jSONObject.optInt("state", 0) == 1;
            if (z != (jSONObject2.optInt("state", 0) == 1)) {
                return z ? -1 : 1;
            }
            long travelTime = com.xtbus.LineActivity.this.travelTime(jSONObject);
            long travelTime2 = com.xtbus.LineActivity.this.travelTime(jSONObject2);
            if (travelTime != travelTime2) {
                return java.lang.Long.compare(travelTime, travelTime2);
            }
            int optInt = jSONObject.optInt("distanceToSc", 0);
            int optInt2 = jSONObject2.optInt("distanceToSc", 0);
            if (optInt == optInt2) {
                int optInt3 = jSONObject.optInt("order", -1);
                int optInt4 = jSONObject2.optInt("order", -1);
                if (optInt3 != optInt4) {
                    return java.lang.Integer.compare(optInt3, optInt4);
                }
                return jSONObject.optString("licence").compareTo(jSONObject2.optString("licence"));
            }
            return java.lang.Integer.compare(optInt, optInt2);
        }
    };
    private final java.lang.Runnable refreshTask = new java.lang.Runnable() { // from class: com.xtbus.LineActivity.14
        @Override // java.lang.Runnable
        public void run() {
            if (!com.xtbus.LineActivity.this.riskStopped) {
                com.xtbus.LineActivity.this.load(false);
            }
        }
    };

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public interface LoadCallback {
        void onResult(boolean z);
    }

    private int c(java.lang.String str) {
        return com.xtbus.Tokens.color(this, str);
    }

    @Override // android.app.Activity
    protected void onCreate(android.os.Bundle bundle) {
        com.xtbus.Api.init(getApplicationContext());
        com.xtbus.Tokens.load(this);
        super.onCreate(bundle);
        setContentView(com.xtbus.R.layout.activity_line);
        this.lineId = getIntent().getStringExtra("lineId");
        this.lineName = getIntent().getStringExtra("lineName");
        this.lineNo = getIntent().getStringExtra("lineNo");
        this.direction = getIntent().getStringExtra("direction");
        this.stationName = getIntent().getStringExtra("stationName");
        this.nextStationName = getIntent().getStringExtra("nextStationName");
        this.targetOrder = getIntent().getStringExtra("targetOrder");
        this.scrollView = (android.widget.ScrollView) findViewById(com.xtbus.R.id.scroll);
        this.busList = (android.widget.LinearLayout) findViewById(com.xtbus.R.id.busList);
        this.list = (android.widget.LinearLayout) findViewById(com.xtbus.R.id.list);
        // 占位挂在 content 末尾（不是 list），避免每次重建站序时被 removeAllViews 清掉
        android.widget.LinearLayout content =
                (android.widget.LinearLayout) findViewById(com.xtbus.R.id.content);
        this.bottomSpacer = new android.view.View(this);
        if (content != null) {
            content.addView(this.bottomSpacer, new android.widget.LinearLayout.LayoutParams(-1, 0));
        }
        this.lineNoView = (android.widget.TextView) findViewById(com.xtbus.R.id.lineNo);
        this.metaLine = (android.widget.TextView) findViewById(com.xtbus.R.id.metaLine);
        this.msg = (android.widget.TextView) findViewById(com.xtbus.R.id.msg);
        this.backToBus = (android.widget.TextView) findViewById(com.xtbus.R.id.backToBus);
        this.lineNoView.setText(prettyName(this.lineName));
        applyTokens();
        this.scrollView.setOnScrollChangeListener(new android.view.View.OnScrollChangeListener() { // from class: com.xtbus.LineActivity.1
            @Override // android.view.View.OnScrollChangeListener
            public void onScrollChange(android.view.View view, int i, int i2, int i3, int i4) {
                com.xtbus.LineActivity.this.updateBackToBus();
            }
        });
        final int scaledTouchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        this.scrollView.setOnTouchListener(new android.view.View.OnTouchListener() { // from class: com.xtbus.LineActivity.2
            private float downY;
            private boolean dragging;

            @Override // android.view.View.OnTouchListener
            public boolean onTouch(android.view.View view, android.view.MotionEvent motionEvent) {
                int actionMasked = motionEvent.getActionMasked();
                if (actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    this.downY = motionEvent.getY();
                    this.dragging = false;
                } else if (actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                    if (!this.dragging && java.lang.Math.abs(motionEvent.getY() - this.downY) > scaledTouchSlop) {
                        this.dragging = true;
                        com.xtbus.LineActivity.this.following = false;
                    }
                } else if (actionMasked == android.view.MotionEvent.ACTION_UP || actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                    this.dragging = false;
                }
                return false;
            }
        });
        this.backToBus.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.xtbus.LineActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                com.xtbus.LineActivity.this.following = true;
                com.xtbus.LineActivity.this.scrollToVehicle(true);
            }
        });
        findViewById(com.xtbus.R.id.back).setOnClickListener(new android.view.View.OnClickListener() { // from class: com.xtbus.LineActivity.4
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                com.xtbus.LineActivity.this.finish();
            }
        });
        findViewById(com.xtbus.R.id.swapBtn).setOnClickListener(new android.view.View.OnClickListener() { // from class: com.xtbus.LineActivity.5
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                com.xtbus.LineActivity.this.swapDirection();
            }
        });
        this.pull = (androidx.swiperefreshlayout.widget.SwipeRefreshLayout) findViewById(com.xtbus.R.id.pull);
        this.pull.setOnRefreshListener(new androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener() { // from class: com.xtbus.LineActivity.6
            @Override // androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
            public void onRefresh() {
                if (com.xtbus.LineActivity.this.loading) {
                    com.xtbus.LineActivity.this.pull.setRefreshing(false);
                } else {
                    com.xtbus.LineActivity.this.load(true);
                }
            }
        });
        this.pull.setColorSchemeColors(c("pine"), c("signal"));
        this.pull.setProgressBackgroundColorSchemeColor(c("card"));
        if (this.lineId == null) {
            this.msg.setText("缺少线路参数");
        } else {
            load(true);
        }
    }

    private void applyTokens() {
        android.widget.TextView textView = (android.widget.TextView) findViewById(com.xtbus.R.id.back);
        com.xtbus.Tokens.textSize(textView, "xt_appbar");
        textView.setTextColor(c("ink"));
        com.xtbus.Tokens.textSize(this.lineNoView, "xt_line_no");
        this.lineNoView.setTextColor(c("ink"));
        com.xtbus.Tokens.textSize(this.metaLine, "xt_caption");
        this.metaLine.setTextColor(c("ink2"));
        android.widget.TextView textView2 = (android.widget.TextView) findViewById(com.xtbus.R.id.swapBtn);
        int dp = com.xtbus.Tokens.dp(this, "xt_touch");
        textView2.setMinHeight(dp);
        textView2.setMinWidth(dp);
        if (com.xtbus.Tokens.isLarge()) {
            textView2.setText("换向");
            com.xtbus.Tokens.textSize(textView2, "xt_body");
            textView2.setPadding(dp(10), 0, dp(10), 0);
        } else {
            textView2.setText("⇄");
            com.xtbus.Tokens.textSize(textView2, "xt_line_no");
            textView2.setPadding(dp(8), 0, dp(8), 0);
        }
        textView2.setTextColor(c("pine"));
        com.xtbus.Tokens.textSize(this.msg, "xt_caption");
        this.msg.setTextColor(c("ink2"));
        com.xtbus.Tokens.textSize(this.backToBus, "xt_label");
    }

    private java.lang.CharSequence etaSpan(java.lang.String str) {
        java.lang.String str2;
        if (str == null || str.isEmpty()) {
            return "";
        }
        if (str.endsWith("分钟")) {
            str2 = str.substring(0, str.length() - 2);
        } else if (str.endsWith("分")) {
            str2 = str.substring(0, str.length() - 1);
        } else if (!str.endsWith("钟")) {
            str2 = str;
        } else {
            str2 = str.substring(0, str.length() - 1);
        }
        try {
            java.lang.Integer.parseInt(str2.trim());
            android.text.SpannableStringBuilder spannableStringBuilder = new android.text.SpannableStringBuilder(str2.trim());
            spannableStringBuilder.append((java.lang.CharSequence) "分");
            int length = spannableStringBuilder.length() - 1;
            spannableStringBuilder.setSpan(new android.text.style.RelativeSizeSpan(com.xtbus.Tokens.isLarge() ? 0.4f : 0.5f), length, spannableStringBuilder.length(), 33);
            spannableStringBuilder.setSpan(new android.text.style.StyleSpan(0), length, spannableStringBuilder.length(), 33);
            return spannableStringBuilder;
        } catch (java.lang.Exception e) {
            return str;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyLoad(boolean z) {
        if (this.pendingCallback != null) {
            com.xtbus.LineActivity.LoadCallback loadCallback = this.pendingCallback;
            this.pendingCallback = null;
            loadCallback.onResult(z);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void load(boolean z) {
        com.xtbus.LineActivity lineActivity;
        if (this.loading) {
            lineActivity = this;
        } else {
            if (this.lineId != null) {
                this.loading = true;
                if (z) {
                    this.msg.setText("正在获取线路详情…");
                }
                final java.lang.String str = this.lineId;
                final java.lang.String str2 = this.lineName;
                final java.lang.String str3 = this.direction;
                final java.lang.String str4 = this.stationName;
                final java.lang.String str5 = this.nextStationName;
                final java.lang.String str6 = this.lineNo;
                final java.lang.String str7 = this.targetOrder;
                com.xtbus.Io.query(new java.lang.Runnable() { // from class: com.xtbus.LineActivity.7
                    @Override // java.lang.Runnable
                    public void run() {
                        try {
                            final com.xtbus.Api.Result lineDetail = com.xtbus.Api.lineDetail(str, str2, str3, str4, str5, str6, str7, true);
                            com.xtbus.LineActivity.this.ui.post(new java.lang.Runnable() { // from class: com.xtbus.LineActivity.7.1
                                @Override // java.lang.Runnable
                                public void run() {
                                    if (com.xtbus.LineActivity.this.isFinishing()) {
                                        return;
                                    }
                                    com.xtbus.LineActivity.this.loading = false;
                                    com.xtbus.LineActivity.this.lastLoadAt = java.lang.System.currentTimeMillis();
                                    com.xtbus.LineActivity.this.riskStopped = lineDetail.risk;
                                    com.xtbus.LineActivity.this.render(lineDetail.data, lineDetail);
                                    if (com.xtbus.LineActivity.this.pull != null) {
                                        com.xtbus.LineActivity.this.pull.setRefreshing(false);
                                    }
                                    if (com.xtbus.LineActivity.this.riskStopped) {
                                        com.xtbus.LineActivity.this.ui.removeCallbacks(com.xtbus.LineActivity.this.refreshTask);
                                    } else {
                                        com.xtbus.LineActivity.this.scheduleNext();
                                    }
                                    com.xtbus.LineActivity.this.notifyLoad(true);
                                }
                            });
                        } catch (com.xtbus.Api.ApiException e) {
                            com.xtbus.LineActivity.this.ui.post(new java.lang.Runnable() { // from class: com.xtbus.LineActivity.7.2
                                @Override // java.lang.Runnable
                                public void run() {
                                    if (com.xtbus.LineActivity.this.isFinishing()) {
                                        return;
                                    }
                                    com.xtbus.LineActivity.this.loading = false;
                                    com.xtbus.LineActivity.this.lastLoadAt = java.lang.System.currentTimeMillis();
                                    if (com.xtbus.LineActivity.this.pull != null) {
                                        com.xtbus.LineActivity.this.pull.setRefreshing(false);
                                    }
                                    if (e.kind == com.xtbus.Api.ApiException.Kind.RISK || e.kind == com.xtbus.Api.ApiException.Kind.COOLDOWN) {
                                        com.xtbus.LineActivity.this.riskStopped = true;
                                        com.xtbus.LineActivity.this.ui.removeCallbacks(com.xtbus.LineActivity.this.refreshTask);
                                        com.xtbus.LineActivity.this.msg.setText(e.getMessage());
                                    } else if (e.kind == com.xtbus.Api.ApiException.Kind.BUSINESS) {
                                        com.xtbus.LineActivity.this.msg.setText("获取失败：" + e.getMessage());
                                        com.xtbus.LineActivity.this.scheduleNext();
                                    } else {
                                        com.xtbus.LineActivity.this.msg.setText("网络异常，稍后自动重试");
                                        com.xtbus.LineActivity.this.scheduleNext();
                                    }
                                    com.xtbus.LineActivity.this.notifyLoad(false);
                                }
                            });
                        } catch (java.lang.Exception e2) {
                            com.xtbus.LineActivity.this.ui.post(new java.lang.Runnable() { // from class: com.xtbus.LineActivity.7.3
                                @Override // java.lang.Runnable
                                public void run() {
                                    if (com.xtbus.LineActivity.this.isFinishing()) {
                                        return;
                                    }
                                    com.xtbus.LineActivity.this.loading = false;
                                    com.xtbus.LineActivity.this.lastLoadAt = java.lang.System.currentTimeMillis();
                                    if (com.xtbus.LineActivity.this.pull != null) {
                                        com.xtbus.LineActivity.this.pull.setRefreshing(false);
                                    }
                                    com.xtbus.LineActivity.this.msg.setText("获取失败");
                                    com.xtbus.LineActivity.this.scheduleNext();
                                    com.xtbus.LineActivity.this.notifyLoad(false);
                                }
                            });
                        }
                    }
                });
                return;
            }
            lineActivity = this;
        }
        if (lineActivity.pull != null) {
            lineActivity.pull.setRefreshing(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void render(org.json.JSONObject jSONObject, com.xtbus.Api.Result result) {
        if (this.pull != null) {
            this.pull.setRefreshing(false);
        }
        org.json.JSONObject optJSONObject = jSONObject.optJSONObject("line");
        if (optJSONObject != null) {
            this.currentLine = optJSONObject;
            java.lang.String optString = optJSONObject.optString("lineId");
            if (!optString.isEmpty()) {
                this.lineId = optString;
            }
            java.lang.String optString2 = optJSONObject.optString("lineNo");
            if (!optString2.isEmpty()) {
                this.lineNo = optString2;
            }
            this.direction = java.lang.String.valueOf(optJSONObject.optInt("direction", 0));
            java.lang.String optString3 = optJSONObject.optString("name", this.lineName);
            if (!optString3.isEmpty()) {
                this.lineName = optString3;
            }
            this.curEndSn = optJSONObject.optString("endSn");
            applyTitle();
            java.lang.String metaText = metaText(optJSONObject);
            this.metaLine.setText(metaText);
            this.metaLine.setVisibility(metaText.isEmpty() ? 8 : 0);
        }
        this.currentTargetOrder = jSONObject.optInt("targetOrder", -1);
        this.otherLines = jSONObject.optJSONArray("otherlines");
        org.json.JSONArray optJSONArray = jSONObject.optJSONArray("stations");
        org.json.JSONArray optJSONArray2 = jSONObject.optJSONArray("buses");
        this.stationCache = optJSONArray;
        this.currentStationName = "";
        if (optJSONArray != null) {
            int i = 0;
            while (true) {
                if (i < optJSONArray.length()) {
                    org.json.JSONObject optJSONObject2 = optJSONArray.optJSONObject(i);
                    if (optJSONObject2 == null || optJSONObject2.optInt("order", -1) != this.currentTargetOrder) {
                        i++;
                    } else {
                        this.currentStationName = optJSONObject2.optString("sn");
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        boolean z = this.following;
        final int scrollY = this.scrollView.getScrollY();
        renderBusCards(optJSONObject, optJSONArray2, optJSONArray);
        renderStations(optJSONArray, optJSONArray2);
        if (result.risk) {
            this.msg.setText("数据更新稍慢 · 更新于 " + agoText(result.fetchedAt));
        } else if (result.degraded) {
            this.msg.setText("网络异常 · 更新于 " + agoText(result.fetchedAt));
        } else {
            this.msg.setText("更新于 " + agoText(result.fetchedAt) + (result.fromCache ? " · 缓存" : ""));
        }
        if (this.pendingRecent) {
            this.pendingRecent = false;
            recordRecent();
        }
        if (z) {
            scrollToVehicle(false);
        } else {
            setBottomSpacer(0);
            this.scrollView.post(new java.lang.Runnable() { // from class: com.xtbus.LineActivity.8
                @Override // java.lang.Runnable
                public void run() {
                    com.xtbus.LineActivity.this.scrollView.scrollTo(0, scrollY);
                    com.xtbus.LineActivity.this.updateBackToBus();
                }
            });
        }
        updateBackToBus();
    }

    private java.lang.String agoText(long j) {
        long currentTimeMillis = java.lang.System.currentTimeMillis() - j;
        if (currentTimeMillis < 5000) {
            return "刚刚";
        }
        if (currentTimeMillis < 60000) {
            return (currentTimeMillis / 1000) + "秒前";
        }
        if (currentTimeMillis < 3600000) {
            return (currentTimeMillis / 60000) + "分钟前";
        }
        if (currentTimeMillis < 86400000) {
            return (currentTimeMillis / 3600000) + "小时前";
        }
        return (currentTimeMillis / 86400000) + "天前";
    }

    private void renderBusCards(org.json.JSONObject jSONObject, org.json.JSONArray jSONArray, org.json.JSONArray jSONArray2) {
        this.lastLineJson = jSONObject;
        this.lastBusesJson = jSONArray;
        this.lastStationsJson = jSONArray2;
        this.busList.removeAllViews();
        this.featuredOrder = -1;
        this.passOrder = -1;
        if (jSONObject != null && jSONObject.optInt("state", 0) != 0) {
            java.lang.String optString = jSONObject.optString("desc", "等待发车");
            this.featuredOrder = nearestApproachingBusOrder(jSONArray);
            boolean isEmpty = validBuses(jSONArray).isEmpty();
            if (this.featuredOrder > 0) {
                this.passOrder = this.featuredOrder;
            } else if (!isEmpty && this.currentTargetOrder > 0) {
                this.passOrder = this.currentTargetOrder;
            } else {
                this.passOrder = -1;
            }
            addStateCard(optString.isEmpty() ? "等待发车" : optString, firstBusText(jSONObject));
            return;
        }
        java.util.List<org.json.JSONObject> validBuses = validBuses(jSONArray);
        java.util.ArrayList arrayList = new java.util.ArrayList();
        for (int i = 0; i < validBuses.size(); i++) {
            org.json.JSONObject jSONObject2 = validBuses.get(i);
            if (this.currentTargetOrder <= 0 || jSONObject2.optInt("order", -1) < this.currentTargetOrder) {
                arrayList.add(jSONObject2);
            }
        }
        java.util.Collections.sort(arrayList, this.BUS_CMP);
        if (!arrayList.isEmpty()) {
            this.featuredOrder = ((org.json.JSONObject) arrayList.get(0)).optInt("order", -1);
            this.passOrder = this.featuredOrder;
            addEtaBar((org.json.JSONObject) arrayList.get(0));
            return;
        }
        if (!validBuses.isEmpty()) {
            org.json.JSONObject jSONObject3 = null;
            for (int i2 = 0; i2 < validBuses.size(); i2++) {
                org.json.JSONObject jSONObject4 = validBuses.get(i2);
                int optInt = jSONObject4.optInt("order", -1);
                if (jSONObject3 == null || optInt < jSONObject3.optInt("order", -1)) {
                    jSONObject3 = jSONObject4;
                }
            }
            this.passOrder = this.currentTargetOrder > 0 ? this.currentTargetOrder : -1;
            java.lang.String nowAt = stationNameAt(jSONArray2, jSONObject3.optInt("order", -1));
            boolean lastGone = com.xtbus.Fmt.lastBusGone(
                    com.xtbus.Fmt.nowMinutes(), lastTimeMinutes());
            // 白天车辆刚驶过候车站时，不能说成“末班车已过”
            addStateCard(lastGone ? "末班车已过本站" : "车辆已过本站",
                    (lastGone ? "下一班已收班" : "下一班未发车")
                            + (nowAt.isEmpty() ? "" : " · 现在 " + nowAt));
            return;
        }
        addStateCard("当前暂无车辆在线", "");
    }

    /** 当前线路的末班时间（分钟），取不到返回 0。 */
    private int lastTimeMinutes() {
        if (this.currentLine == null) {
            return 0;
        }
        return com.xtbus.Fmt.parseHm(this.currentLine.optString("lastTime"));
    }

    private int nearestApproachingBusOrder(org.json.JSONArray jSONArray) {
        java.util.List<org.json.JSONObject> validBuses = validBuses(jSONArray);
        int i = -1;
        for (int i2 = 0; i2 < validBuses.size(); i2++) {
            int optInt = validBuses.get(i2).optInt("order", -1);
            if ((this.currentTargetOrder <= 0 || optInt < this.currentTargetOrder) && (i < 0 || optInt < i)) {
                i = optInt;
            }
        }
        return i;
    }

    private java.util.List<org.json.JSONObject> validBuses(org.json.JSONArray jSONArray) {
        java.util.ArrayList arrayList = new java.util.ArrayList();
        if (jSONArray == null) {
            return arrayList;
        }
        for (int i = 0; i < jSONArray.length(); i++) {
            org.json.JSONObject optJSONObject = jSONArray.optJSONObject(i);
            if (optJSONObject != null && optJSONObject.optInt("order", -1) > 0) {
                arrayList.add(optJSONObject);
            }
        }
        return arrayList;
    }

    private java.lang.String stationNameAt(org.json.JSONArray jSONArray, int i) {
        if (jSONArray == null || i <= 0) {
            return "";
        }
        for (int i2 = 0; i2 < jSONArray.length(); i2++) {
            org.json.JSONObject optJSONObject = jSONArray.optJSONObject(i2);
            if (optJSONObject != null && optJSONObject.optInt("order", -1) == i) {
                return optJSONObject.optString("sn");
            }
        }
        return "";
    }

    private int lastStationOrder() {
        int optInt;
        if (this.stationCache == null) {
            return 0;
        }
        int i = 0;
        for (int i2 = 0; i2 < this.stationCache.length(); i2++) {
            org.json.JSONObject optJSONObject = this.stationCache.optJSONObject(i2);
            if (optJSONObject != null && (optInt = optJSONObject.optInt("order", 0)) > i) {
                i = optInt;
            }
        }
        return i;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long travelTime(org.json.JSONObject jSONObject) {
        int optInt;
        org.json.JSONObject firstTravel = firstTravel(jSONObject);
        if (firstTravel != null && (optInt = firstTravel.optInt("travelTime", -1)) >= 0) {
            return optInt;
        }
        return Long.MAX_VALUE;
    }

    private org.json.JSONObject firstTravel(org.json.JSONObject jSONObject) {
        org.json.JSONArray optJSONArray = jSONObject.optJSONArray("travels");
        if (optJSONArray == null) {
            return null;
        }
        return optJSONArray.optJSONObject(0);
    }

    private void addEtaBar(org.json.JSONObject jSONObject) {
        int optInt = jSONObject.optInt("order", -1);
        boolean z = jSONObject.optInt("state", 0) == 1 && optInt == this.currentTargetOrder;
        android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(0, dp(8), 0, dp(8));
        linearLayout.setMinimumHeight(com.xtbus.Tokens.dp(this, "xt_row_h"));
        android.widget.LinearLayout linearLayout2 = new android.widget.LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(80);
        linearLayout.addView(linearLayout2, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(etaSpan(etaText(jSONObject, z)));
        com.xtbus.Tokens.textSizeFixed(textView, "xt_eta");
        textView.setTextColor(z ? c("signal") : c("ink"));
        textView.setTypeface(textView.getTypeface(), 1);
        textView.setIncludeFontPadding(false);
        textView.setSingleLine(true);
        linearLayout2.addView(textView, new android.widget.LinearLayout.LayoutParams(-2, -2));
        linearLayout2.addView(new android.view.View(this), new android.widget.LinearLayout.LayoutParams(0, 1, 1.0f));
        java.lang.String optString = jSONObject.optString("licence");
        if (optString.isEmpty()) {
            optString = jSONObject.optString("busId", "车辆");
        }
        android.widget.TextView textView2 = new android.widget.TextView(this);
        textView2.setText(optString);
        com.xtbus.Tokens.textSize(textView2, "xt_body_s");
        textView2.setTextColor(c("ink2"));
        textView2.setSingleLine(true);
        textView2.setPadding(0, 0, 0, dp(5));
        linearLayout2.addView(textView2, new android.widget.LinearLayout.LayoutParams(-2, -2));
        android.widget.LinearLayout linearLayout3 = new android.widget.LinearLayout(this);
        linearLayout3.setOrientation(0);
        linearLayout3.setGravity(16);
        linearLayout3.setPadding(0, dp(4), 0, 0);
        android.view.View view = new android.view.View(this);
        android.graphics.drawable.GradientDrawable gradientDrawable = new android.graphics.drawable.GradientDrawable();
        gradientDrawable.setColor(c("signal"));
        gradientDrawable.setCornerRadius(dp(2));
        view.setBackground(gradientDrawable);
        android.widget.LinearLayout.LayoutParams layoutParams = new android.widget.LinearLayout.LayoutParams(dp(9), dp(9));
        layoutParams.rightMargin = dp(7);
        linearLayout3.addView(view, layoutParams);
        android.widget.TextView textView3 = new android.widget.TextView(this);
        textView3.setText(positionText(jSONObject, optInt));
        com.xtbus.Tokens.textSize(textView3, "xt_body_s");
        textView3.setTextColor(c("ink2"));
        linearLayout3.addView(textView3, new android.widget.LinearLayout.LayoutParams(-2, -2));
        addEndTag(linearLayout3);
        linearLayout.addView(linearLayout3);
        this.busList.addView(linearLayout, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }

    private java.lang.CharSequence positionText(org.json.JSONObject jSONObject, int i) {
        if (i == this.currentTargetOrder && jSONObject.optInt("state", 0) == 1) {
            return "已到本站";
        }
        int lastStationOrder = (this.currentTargetOrder > 0 ? this.currentTargetOrder : lastStationOrder()) - i;
        java.lang.StringBuilder sb = new java.lang.StringBuilder();
        if (lastStationOrder > 0) {
            sb.append("还剩 ").append(lastStationOrder).append(" 站");
        }
        java.lang.String stationNameAt = stationNameAt(this.stationCache, i + 1);
        if (stationNameAt.isEmpty()) {
            return sb.length() == 0 ? "即将到站" : sb.toString();
        }
        android.text.SpannableStringBuilder spannableStringBuilder = new android.text.SpannableStringBuilder(sb);
        if (spannableStringBuilder.length() > 0) {
            spannableStringBuilder.append((java.lang.CharSequence) " · ");
        }
        spannableStringBuilder.append((java.lang.CharSequence) "下一站 ");
        int length = spannableStringBuilder.length();
        spannableStringBuilder.append((java.lang.CharSequence) stationNameAt);
        spannableStringBuilder.setSpan(new android.text.style.StyleSpan(1), length, spannableStringBuilder.length(), 33);
        spannableStringBuilder.setSpan(new android.text.style.ForegroundColorSpan(c("ink")), length, spannableStringBuilder.length(), 33);
        return spannableStringBuilder;
    }

    private void addStateCard(java.lang.String str, java.lang.String str2) {
        android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(0, dp(10), 0, dp(14));
        android.widget.LinearLayout head = new android.widget.LinearLayout(this);
        head.setOrientation(0);
        head.setGravity(16);
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(str);
        textView.setTextColor(c("ink"));
        com.xtbus.Tokens.textSize(textView, "xt_appbar");
        textView.setTypeface(textView.getTypeface(), 1);
        head.addView(textView, new android.widget.LinearLayout.LayoutParams(-2, -2));
        addEndTag(head);
        linearLayout.addView(head, new android.widget.LinearLayout.LayoutParams(-1, -2));
        if (str2 != null && !str2.isEmpty()) {
            android.widget.TextView textView2 = new android.widget.TextView(this);
            textView2.setText(str2);
            textView2.setTextColor(c("ink2"));
            com.xtbus.Tokens.textSize(textView2, "xt_body_s");
            textView2.setPadding(0, dp(6), 0, 0);
            linearLayout.addView(textView2);
        }
        this.busList.addView(linearLayout, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }

    /** 顶栏放大字后放不下「开往 终点」时，把方向信息挪到车辆卡右侧。 */
    private void addEndTag(android.widget.LinearLayout row) {
        if (!this.titleOverflow || this.curEndSn.isEmpty()) {
            return;
        }
        android.view.View spacer = new android.view.View(this);
        row.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, 1, 1f));
        android.widget.TextView tag = new android.widget.TextView(this);
        tag.setText("开往 " + this.curEndSn);
        com.xtbus.Tokens.textSize(tag, "xt_body_s");
        tag.setTextColor(c("ink2"));
        tag.setSingleLine(true);
        tag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tag.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.55f));
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(10);
        row.addView(tag, lp);
    }

    private void applyTitle() {
        if (this.titleOverflow) {
            this.lineNoView.setText(prettyName(this.lineName));
            return;
        }
        this.lineNoView.setText(identityText(this.lineName, this.curEndSn));
        this.lineNoView.post(new java.lang.Runnable() { // from class: com.xtbus.LineActivity.15
            @Override // java.lang.Runnable
            public void run() {
                if (com.xtbus.LineActivity.this.titleOverflow) {
                    return;
                }
                android.text.Layout layout = com.xtbus.LineActivity.this.lineNoView.getLayout();
                if (layout == null || layout.getLineCount() == 0
                        || layout.getEllipsisCount(0) == 0) {
                    return;
                }
                com.xtbus.LineActivity.this.titleOverflow = true;
                com.xtbus.LineActivity.this.lineNoView.setText(
                        com.xtbus.LineActivity.this.prettyName(com.xtbus.LineActivity.this.lineName));
                com.xtbus.LineActivity.this.renderBusCards(
                        com.xtbus.LineActivity.this.lastLineJson,
                        com.xtbus.LineActivity.this.lastBusesJson,
                        com.xtbus.LineActivity.this.lastStationsJson);
            }
        });
    }

    private java.lang.String metaText(org.json.JSONObject jSONObject) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder(scheduleSpan(jSONObject));
        java.lang.String optString = jSONObject.optString("price");
        if (!optString.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(optString);
        }
        return sb.toString();
    }

    private java.lang.CharSequence identityText(java.lang.String str, java.lang.String str2) {
        android.text.SpannableStringBuilder spannableStringBuilder = new android.text.SpannableStringBuilder(prettyName(str));
        int length = spannableStringBuilder.length();
        spannableStringBuilder.append((java.lang.CharSequence) "  开往 ").append((java.lang.CharSequence) emptyAsDash(str2));
        spannableStringBuilder.setSpan(new android.text.style.ForegroundColorSpan(c("ink2")), length, spannableStringBuilder.length(), 33);
        spannableStringBuilder.setSpan(new android.text.style.StyleSpan(0), length, spannableStringBuilder.length(), 33);
        return spannableStringBuilder;
    }

    private java.lang.String scheduleSpan(org.json.JSONObject jSONObject) {
        java.lang.String firstTime = firstTime(jSONObject);
        java.lang.String lastTime = lastTime(jSONObject);
        if (firstTime.isEmpty() || lastTime.isEmpty()) {
            return "";
        }
        return firstTime + "–" + lastTime;
    }

    private java.lang.String firstTime(org.json.JSONObject jSONObject) {
        java.lang.String str;
        org.json.JSONArray optJSONArray = jSONObject.optJSONArray("segTimes");
        if (optJSONArray != null && optJSONArray.length() > 0) {
            str = timeAt(optJSONArray.optJSONArray(0), 0);
        } else {
            str = "";
        }
        return str.isEmpty() ? jSONObject.optString("firstTime") : str;
    }

    private java.lang.String lastTime(org.json.JSONObject jSONObject) {
        java.lang.String str;
        org.json.JSONArray optJSONArray = jSONObject.optJSONArray("segTimes");
        if (optJSONArray != null && optJSONArray.length() > 0) {
            str = timeAt(optJSONArray.optJSONArray(optJSONArray.length() - 1), 1);
        } else {
            str = "";
        }
        return str.isEmpty() ? jSONObject.optString("lastTime") : str;
    }

    private java.lang.String firstBusText(org.json.JSONObject jSONObject) {
        java.lang.String firstTime = firstTime(jSONObject);
        return firstTime.isEmpty() ? "" : "首班 " + firstTime;
    }

    private void renderStations(org.json.JSONArray jSONArray, org.json.JSONArray jSONArray2) {
        boolean z;
        boolean z2;
        boolean z3;
        int optInt;
        int optInt2;
        this.list.removeAllViews();
        this.busRowView = null;
        this.targetRow = null;
        this.lastRailRow = null;
        if (jSONArray == null || jSONArray.length() == 0) {
            android.widget.TextView textView = new android.widget.TextView(this);
            textView.setText("暂无站点数据");
            textView.setTextColor(c("ink2"));
            com.xtbus.Tokens.textSize(textView, "xt_body");
            textView.setGravity(17);
            textView.setPadding(0, dp(24), 0, dp(24));
            this.list.addView(textView);
            return;
        }
        java.util.HashMap hashMap = new java.util.HashMap();
        if (jSONArray2 != null) {
            for (int i = 0; i < jSONArray2.length(); i++) {
                org.json.JSONObject optJSONObject = jSONArray2.optJSONObject(i);
                if (optJSONObject != null && (optInt2 = optJSONObject.optInt("order", -1)) > 0) {
                    java.util.List list = (java.util.List) hashMap.get(java.lang.Integer.valueOf(optInt2));
                    if (list == null) {
                        list = new java.util.ArrayList();
                        hashMap.put(java.lang.Integer.valueOf(optInt2), list);
                    }
                    list.add(optJSONObject);
                }
            }
        }
        java.util.ArrayList arrayList = new java.util.ArrayList();
        for (int i2 = 0; i2 < jSONArray.length(); i2++) {
            org.json.JSONObject optJSONObject2 = jSONArray.optJSONObject(i2);
            if (optJSONObject2 != null && (optInt = optJSONObject2.optInt("order", 0)) > 0) {
                arrayList.add(optJSONObject2);
            }
        }
        if (arrayList.isEmpty()) {
            for (int i3 = 0; i3 < jSONArray.length(); i3++) {
                org.json.JSONObject optJSONObject3 = jSONArray.optJSONObject(i3);
                if (optJSONObject3 != null) {
                    arrayList.add(optJSONObject3);
                }
            }
        }
        java.util.Collections.sort(arrayList, new java.util.Comparator<org.json.JSONObject>() { // from class: com.xtbus.LineActivity.10
            @Override // java.util.Comparator
            public int compare(org.json.JSONObject jSONObject, org.json.JSONObject jSONObject2) {
                return java.lang.Integer.compare(jSONObject.optInt("order", Integer.MAX_VALUE), jSONObject2.optInt("order", Integer.MAX_VALUE));
            }
        });
        int i5 = 0;
        boolean z4 = true;
        while (i5 < arrayList.size()) {
            org.json.JSONObject jSONObject = (org.json.JSONObject) arrayList.get(i5);
            int i6 = i5 + 1;
            int optInt3 = jSONObject.optInt("order", i6);
            // 站序允许重复（环线/数据异常），不再因为和上一行相同就整行丢掉
            z = optInt3 == this.currentTargetOrder;
            z2 = this.passOrder > 0 && optInt3 < this.passOrder && !z;
            int i7 = optInt3 > 0 ? optInt3 : i6;
            java.util.List<org.json.JSONObject> list2 = (java.util.List) hashMap.get(java.lang.Integer.valueOf(optInt3));
            z3 = optInt3 == this.featuredOrder;
            addStationRow(i7, jSONObject, z, list2, z2, z4, z3);
            z4 = false;
            i5 = i6;
        }
        if (this.lastRailRow != null) {
            this.lastRailRow.setLast(true);
        }
    }

    private void addStationRow(final int i, org.json.JSONObject jSONObject, boolean z, java.util.List<org.json.JSONObject> list, boolean z2, boolean z3, boolean z4) {
        int dp;
        int c;
        int c2;
        final java.lang.String optString = jSONObject.optString("sn");
        boolean z5 = (list == null || list.isEmpty()) ? false : true;
        boolean isLarge = com.xtbus.Tokens.isLarge();
        int dp2 = com.xtbus.Tokens.dp(this, "xt_card_pad");
        com.xtbus.LineActivity.RailRow railRow = new com.xtbus.LineActivity.RailRow(this);
        railRow.setOrientation(0);
        railRow.setGravity(16);
        railRow.setPadding(dp2, dp(7), dp2, dp(7));
        if (z5) {
            dp = dp(com.xtbus.Tokens.isLarge() ? 72 : 52);
        } else {
            dp = com.xtbus.Tokens.dp(this, "xt_tl_row_h");
        }
        railRow.setMinimumHeight(dp);
        railRow.setFirst(z3);
        this.lastRailRow = railRow;
        int dp3 = com.xtbus.Tokens.dp(this, "xt_num");
        int dp4 = dp(8) / 2;
        int paddingLeft = railRow.getPaddingLeft() + dp4 + (dp3 / 2);
        // 分色点在节点中心：上段看上一站，下段看本站，避免整行同色造成边界偏移
        int grey = c("line_strong");
        int green = c("pine");
        boolean abovePassed = this.passOrder > 0 && (i - 1) < this.passOrder;
        boolean belowPassed = this.passOrder > 0 && i < this.passOrder;
        railRow.setRail(paddingLeft, com.xtbus.Tokens.dp(this, "xt_tl_track"),
                abovePassed ? grey : green, belowPassed ? grey : green);
        if (z5) {
            railRow.setBackgroundColor(c("signal_tint"));
            railRow.setBar(c("signal"), dp(3));
        }
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(java.lang.String.valueOf(i));
        com.xtbus.Tokens.textSizeFixed(textView, "xt_num_font");
        textView.setGravity(17);
        boolean z6 = z5;
        if (z && isLarge) {
            c = -1;
        } else {
            if (z) {
                c = c("pine");
            } else if (z2) {
                c = c("ink3");
            } else {
                c = c("ink2");
            }
        }
        textView.setTextColor(c);
        android.graphics.drawable.GradientDrawable gradientDrawable = new android.graphics.drawable.GradientDrawable();
        gradientDrawable.setShape(1);
        gradientDrawable.setColor(-1);
        if (!z) {
            gradientDrawable.setStroke(dp(1), c("line_strong"));
        } else {
            if (!isLarge) {
                gradientDrawable.setStroke(dp(2), c("pine"));
            } else {
                gradientDrawable.setColor(c("pine"));
                gradientDrawable.setStroke(dp(1), c("pine"));
            }
            textView.setTypeface(textView.getTypeface(), 1);
        }
        textView.setBackground(gradientDrawable);
        android.widget.LinearLayout.LayoutParams layoutParams = new android.widget.LinearLayout.LayoutParams(dp3, dp3);
        layoutParams.leftMargin = dp4;
        layoutParams.rightMargin = layoutParams.leftMargin + dp(7);
        railRow.addView(textView, layoutParams);
        android.widget.LinearLayout linearLayout = new android.widget.LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1.0f));
        railRow.addView(linearLayout);
        android.widget.TextView textView2 = new android.widget.TextView(this);
        textView2.setText(optString);
        com.xtbus.Tokens.textSize(textView2, "xt_station");
        if (z6) {
            textView2.setTextColor(c("signal"));
            textView2.setTypeface(textView2.getTypeface(), 1);
        } else if (z) {
            textView2.setTextColor(c("ink"));
            textView2.setTypeface(textView2.getTypeface(), 1);
        } else {
            textView2.setTextColor(z2 ? c("ink3") : c("ink"));
        }
        android.widget.LinearLayout linearLayout2 = new android.widget.LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout2.addView(textView2, new android.widget.LinearLayout.LayoutParams(-2, -2));
        addStationTag(linearLayout2, jSONObject);
        linearLayout.addView(linearLayout2);
        if (z6) {
            android.widget.TextView textView3 = new android.widget.TextView(this);
            textView3.setText(plateText(list));
            com.xtbus.Tokens.textSize(textView3, "xt_caption");
            textView3.setTextColor(c("signal"));
            textView3.setSingleLine(true);
            textView3.setPadding(dp(8), 0, 0, 0);
            railRow.addView(textView3);
        }
        if (z) {
            android.widget.TextView textView4 = new android.widget.TextView(this);
            textView4.setText("候车站");
            android.graphics.drawable.GradientDrawable gradientDrawable2 = new android.graphics.drawable.GradientDrawable();
            gradientDrawable2.setCornerRadius(dp(isLarge ? 6 : 5));
            if (isLarge) {
                com.xtbus.Tokens.textSize(textView4, "xt_label");
                textView4.setTextColor(-1);
                textView4.setTypeface(textView4.getTypeface(), 1);
                textView4.setPadding(dp(8), dp(2), dp(8), dp(2));
                gradientDrawable2.setColor(c("pine"));
            } else {
                com.xtbus.Tokens.textSizeFixed(textView4, "xt_num_font");
                textView4.setTextColor(c("pine"));
                textView4.setPadding(dp(4), dp(1), dp(4), dp(1));
                gradientDrawable2.setColor(0);
                gradientDrawable2.setStroke(dp(1), c("pine"));
            }
            textView4.setBackground(gradientDrawable2);
            railRow.addView(textView4);
            if (isLarge && !z6) {
                railRow.setHalo((c("pine") & androidx.core.view.ViewCompat.MEASURED_SIZE_MASK) | HALO_ALPHA, dp(26));
            }
        }
        railRow.setOnClickListener(new android.view.View.OnClickListener() { // from class: com.xtbus.LineActivity.11
            @Override // android.view.View.OnClickListener
            public void onClick(android.view.View view) {
                com.xtbus.LineActivity.this.switchTarget(i, optString);
            }
        });
        railRow.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(c("scrim")), null, null));
        this.list.addView(railRow, new android.widget.LinearLayout.LayoutParams(-1, -2));
        if (z4) {
            this.busRowView = railRow;
        }
        if (z) {
            this.targetRow = railRow;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void switchTarget(int i, java.lang.String str) {
        if (this.lineId == null || this.loading || this.switching) {
            return;
        }
        if (i == this.currentTargetOrder) {
            return;
        }
        final java.lang.String str2 = this.targetOrder;
        final java.lang.String str3 = this.stationName;
        final java.lang.String str4 = this.nextStationName;
        final boolean z = this.following;
        this.targetOrder = java.lang.String.valueOf(i);
        this.stationName = str;
        this.nextStationName = nextStationNameOf(i);
        this.switching = true;
        this.following = false;
        this.msg.setText("切换到 " + str + "…");
        this.pendingCallback = new com.xtbus.LineActivity.LoadCallback() { // from class: com.xtbus.LineActivity.12
            @Override // com.xtbus.LineActivity.LoadCallback
            public void onResult(boolean z2) {
                com.xtbus.LineActivity.this.switching = false;
                if (!z2) {
                    com.xtbus.LineActivity.this.targetOrder = str2;
                    com.xtbus.LineActivity.this.stationName = str3;
                    com.xtbus.LineActivity.this.nextStationName = str4;
                    com.xtbus.LineActivity.this.following = z;
                    java.lang.String str5 = com.xtbus.LineActivity.this.currentStationName.isEmpty() ? "原候车站" : com.xtbus.LineActivity.this.currentStationName;
                    com.xtbus.LineActivity.this.msg.setText("切换失败，仍在 " + str5);
                    android.widget.Toast.makeText(com.xtbus.LineActivity.this, "切换失败，仍在 " + str5, 0).show();
                } else {
                    // 切换成功也恢复原来的跟随状态，不要因为换站就永久关掉车辆跟随
                    com.xtbus.LineActivity.this.following = z;
                }
            }
        };
        load(false);
    }

    private java.lang.String nextStationNameOf(int i) {
        if (this.stationCache == null) {
            return "";
        }
        for (int i2 = 0; i2 < this.stationCache.length(); i2++) {
            org.json.JSONObject optJSONObject = this.stationCache.optJSONObject(i2);
            if (optJSONObject != null && optJSONObject.optInt("order", -1) == i + 1) {
                return optJSONObject.optString("sn");
            }
        }
        return "";
    }

    private void addStationTag(android.widget.LinearLayout linearLayout, org.json.JSONObject jSONObject) {
        java.lang.String str;
        java.lang.String str2;
        org.json.JSONObject optJSONObject;
        org.json.JSONArray optJSONArray = jSONObject.optJSONArray("stationTags");
        if (optJSONArray != null && optJSONArray.length() > 0 && (optJSONObject = optJSONArray.optJSONObject(0)) != null) {
            str = optJSONObject.optString("name");
            str2 = optJSONObject.optString("color");
        } else {
            str = "";
            str2 = "";
        }
        if (str.isEmpty() && jSONObject.optInt("special", 0) == 1) {
            str = jSONObject.optString("specialLabel");
        }
        if (str.isEmpty()) {
            return;
        }
        int parseColor = parseColor(str2, c("signal"));
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(str);
        com.xtbus.Tokens.textSizeFixed(textView, "xt_num_font");
        textView.setTypeface(textView.getTypeface(), 1);
        textView.setTextColor(parseColor);
        textView.setSingleLine(true);
        textView.setPadding(dp(3), dp(1), dp(3), dp(1));
        android.graphics.drawable.GradientDrawable gradientDrawable = new android.graphics.drawable.GradientDrawable();
        gradientDrawable.setColor(android.graphics.Color.argb(46, android.graphics.Color.red(parseColor), android.graphics.Color.green(parseColor), android.graphics.Color.blue(parseColor)));
        gradientDrawable.setCornerRadius(dp(3));
        textView.setBackground(gradientDrawable);
        android.widget.LinearLayout.LayoutParams layoutParams = new android.widget.LinearLayout.LayoutParams(-2, -2);
        layoutParams.leftMargin = dp(6);
        linearLayout.addView(textView, layoutParams);
    }

    private int parseColor(java.lang.String str, int i) {
        if (str == null || str.isEmpty()) {
            return i;
        }
        try {
            return android.graphics.Color.parseColor(str);
        } catch (java.lang.Exception e) {
            return i;
        }
    }

    private java.lang.String plateText(java.util.List<org.json.JSONObject> list) {
        java.util.ArrayList arrayList = new java.util.ArrayList(list);
        java.util.Collections.sort(arrayList, this.BUS_CMP);
        org.json.JSONObject jSONObject = (org.json.JSONObject) arrayList.get(0);
        java.lang.String optString = jSONObject.optString("licence");
        if (optString.isEmpty()) {
            optString = jSONObject.optString("busId", "车辆");
        }
        if (arrayList.size() > 1) {
            return optString + " +" + (arrayList.size() - 1);
        }
        return optString;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void swapDirection() {
        if (this.currentLine == null || this.loading || this.switching) {
            return;
        }
        int optInt = this.currentLine.optInt("direction", 0);
        int targetDir = optInt == 0 ? 1 : 0;
        java.lang.String newLineId = null;
        if (this.otherLines != null) {
            for (int i = 0; i < this.otherLines.length(); i++) {
                org.json.JSONObject optJSONObject = this.otherLines.optJSONObject(i);
                if (optJSONObject == null) {
                    continue;
                }
                java.lang.String optString = optJSONObject.optString("lineId");
                if (optString.isEmpty()) {
                    continue;
                }
                if (optJSONObject.has("direction")
                        && optJSONObject.optInt("direction", -1) == targetDir) {
                    newLineId = optString;
                    break;
                }
                if (newLineId == null) {
                    newLineId = optString;
                }
            }
        }
        if (newLineId != null) {
            this.lineId = newLineId;
        }
        this.direction = java.lang.String.valueOf(targetDir);
        // 换向后终点名变了，重新判断顶栏是否放得下
        this.titleOverflow = false;
        this.stationName = this.currentStationName;
        this.nextStationName = "-1";
        // 换向后落到新方向的默认站点；没有反向线路时保留当前候车站
        this.targetOrder = newLineId != null ? "-1" : java.lang.String.valueOf(this.currentTargetOrder);
        this.following = true;
        this.pendingRecent = true;
        load(true);
    }

    private void recordRecent() {
        int i;
        try {
            android.content.SharedPreferences sharedPreferences = getSharedPreferences("xtbus", 0);
            org.json.JSONArray jSONArray = new org.json.JSONArray(sharedPreferences.getString("recent_lines_v2", "[]"));
            org.json.JSONObject jSONObject = new org.json.JSONObject();
            jSONObject.put("lineId", this.lineId == null ? "" : this.lineId);
            jSONObject.put("lineName", this.lineName == null ? "" : this.lineName);
            jSONObject.put("lineNo", this.lineNo == null ? "" : this.lineNo);
            try {
                i = java.lang.Integer.parseInt(this.direction == null ? "0" : this.direction);
            } catch (java.lang.Exception e) {
                i = 0;
            }
            jSONObject.put("direction", i);
            jSONObject.put("endSn", this.currentLine == null ? "" : this.currentLine.optString("endSn"));
            jSONObject.put("stationName", this.currentStationName == null ? "" : this.currentStationName);
            jSONObject.put("nextStationName", this.nextStationName == null ? "" : this.nextStationName);
            jSONObject.put("targetOrder", this.targetOrder == null ? "" : this.targetOrder);
            org.json.JSONArray jSONArray2 = new org.json.JSONArray();
            jSONArray2.put(jSONObject);
            java.lang.String optString = jSONObject.optString("lineId");
            for (int i2 = 0; i2 < jSONArray.length() && jSONArray2.length() < 6; i2++) {
                org.json.JSONObject optJSONObject = jSONArray.optJSONObject(i2);
                if (optJSONObject != null && (!optString.equals(optJSONObject.optString("lineId")) || i != optJSONObject.optInt("direction", 0))) {
                    jSONArray2.put(optJSONObject);
                }
            }
            sharedPreferences.edit().putString("recent_lines_v2", jSONArray2.toString()).apply();
        } catch (java.lang.Exception e2) {
        }
    }

    private java.lang.String timeAt(org.json.JSONArray jSONArray, int i) {
        return jSONArray == null ? "" : jSONArray.optString(i, "");
    }

    private java.lang.String emptyAsDash(java.lang.String str) {
        return (str == null || str.isEmpty()) ? "--" : str;
    }

    private java.lang.String etaText(org.json.JSONObject jSONObject, boolean z) {
        int optInt;
        if (z) {
            return "已到站";
        }
        org.json.JSONObject firstTravel = firstTravel(jSONObject);
        if (firstTravel == null || (optInt = firstTravel.optInt("travelTime", -1)) < 0) {
            return "--";
        }
        if (optInt <= 0) {
            return "即将到站";
        }
        if (optInt <= 30) {
            return "30秒";
        }
        if (optInt < 60) {
            return "1分钟";
        }
        if (optInt < 3600) {
            return java.lang.Math.round(optInt / 60.0f) + "分钟";
        }
        long optLong = firstTravel.optLong("arrivalTime", 0L);
        return optLong > 0 ? this.clock.format(new java.util.Date(optLong)) : "--";
    }

    private java.lang.String prettyName(java.lang.String str) {
        return com.xtbus.Fmt.displayName(str, "线路详情");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scrollToVehicle(final boolean z) {
        if (!this.following) {
            return;
        }
        final boolean z2 = this.busRowView == null;
        final android.view.View view = z2 ? this.targetRow : this.busRowView;
        if (view == null) {
            return;
        }
        this.scrollView.post(new java.lang.Runnable() { // from class: com.xtbus.LineActivity.13
            private boolean corrected;
            private int tries;

            @Override // java.lang.Runnable
            public void run() {
                if (!com.xtbus.LineActivity.this.following) {
                    return;
                }
                if (view.getHeight() != 0 && com.xtbus.LineActivity.this.scrollView.getHeight() != 0) {
                    if (!z2) {
                        // 底部补足到一屏，末段车辆也能顶到列表上沿
                        int need = java.lang.Math.max(0,
                                com.xtbus.LineActivity.this.scrollView.getHeight() - view.getHeight());
                        android.view.ViewGroup.LayoutParams lp =
                                com.xtbus.LineActivity.this.bottomSpacer == null ? null
                                        : com.xtbus.LineActivity.this.bottomSpacer.getLayoutParams();
                        if (lp != null && lp.height != need) {
                            com.xtbus.LineActivity.this.setBottomSpacer(need);
                            com.xtbus.LineActivity.this.scrollView.postDelayed(this, 40L);
                            return;
                        }
                    }
                    int offsetInScroll = com.xtbus.LineActivity.this.offsetInScroll(view);
                    android.view.View childAt = com.xtbus.LineActivity.this.scrollView.getChildAt(0);
                    int max = childAt == null ? 0 : java.lang.Math.max(0, childAt.getHeight() - com.xtbus.LineActivity.this.scrollView.getHeight());
                    if (!z2) {
                        com.xtbus.LineActivity.this.scrollView.smoothScrollTo(0, java.lang.Math.min(java.lang.Math.max(0, offsetInScroll), max));
                        com.xtbus.LineActivity.this.updateBackToBus();
                        return;
                    }
                    int min = java.lang.Math.min(java.lang.Math.max(0, offsetInScroll - java.lang.Math.max(0, (com.xtbus.LineActivity.this.scrollView.getHeight() - view.getHeight()) / 2)), max);
                    if (java.lang.Math.abs(com.xtbus.LineActivity.this.scrollView.getScrollY() - min) <= 1) {
                        com.xtbus.LineActivity.this.updateBackToBus();
                        return;
                    }
                    if (z) {
                        com.xtbus.LineActivity.this.scrollView.smoothScrollTo(0, min);
                    } else {
                        com.xtbus.LineActivity.this.scrollView.scrollTo(0, min);
                        if (!this.corrected) {
                            this.corrected = true;
                            com.xtbus.LineActivity.this.scrollView.postDelayed(this, 180L);
                        }
                    }
                    com.xtbus.LineActivity.this.updateBackToBus();
                    return;
                }
                int i = this.tries;
                this.tries = i + 1;
                if (i < 20) {
                    com.xtbus.LineActivity.this.scrollView.postDelayed(this, 40L);
                }
            }
        });
    }

    private void setBottomSpacer(int height) {
        if (this.bottomSpacer == null) {
            return;
        }
        android.view.ViewGroup.LayoutParams lp = this.bottomSpacer.getLayoutParams();
        if (lp == null || lp.height == height) {
            return;
        }
        lp.height = height;
        this.bottomSpacer.setLayoutParams(lp);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBackToBus() {
        if (this.backToBus == null) {
            return;
        }
        if (this.busRowView == null || this.busRowView.getHeight() == 0) {
            this.backToBus.setVisibility(8);
            return;
        }
        int offsetInScroll = offsetInScroll(this.busRowView) - this.scrollView.getScrollY();
        this.backToBus.setVisibility(this.busRowView.getHeight() + offsetInScroll <= 0 || offsetInScroll >= this.scrollView.getHeight() ? 0 : 8);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int offsetInScroll(android.view.View view) {
        int i = 0;
        while (view != null && view != this.scrollView) {
            i += view.getTop();
            java.lang.Object parent = view.getParent();
            view = parent instanceof android.view.View ? (android.view.View) parent : null;
        }
        return i;
    }

    private int dp(int i) {
        return java.lang.Math.round(getResources().getDisplayMetrics().density * i);
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        if (this.riskStopped && com.xtbus.Api.riskCooldownRemaining() == 0) {
            this.riskStopped = false;
        }
        if (this.loading || this.riskStopped) {
            return;
        }
        long currentTimeMillis = java.lang.System.currentTimeMillis() - this.lastLoadAt;
        if (this.lastLoadAt > 0 && currentTimeMillis < REFRESH_INTERVAL) {
            scheduleNext(REFRESH_INTERVAL - currentTimeMillis);
        } else {
            load(false);
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
        this.ui.removeCallbacks(this.refreshTask);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleNext() {
        scheduleNext(java.lang.Math.max(15000L, ((long) (((java.lang.Math.random() * 2.0d) - 1.0d) * 3000.0d)) + REFRESH_INTERVAL));
    }

    private void scheduleNext(long j) {
        this.ui.removeCallbacks(this.refreshTask);
        if (this.riskStopped || isFinishing()) {
            return;
        }
        this.ui.postDelayed(this.refreshTask, java.lang.Math.max(5000L, j));
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        super.onDestroy();
        this.ui.removeCallbacks(this.refreshTask);
        if (this.pull != null) {
            this.pull.setRefreshing(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class RailRow extends android.widget.LinearLayout {
        private int aboveColor;
        private int barColor;
        private int barWidth;
        private int belowColor;
        private boolean first;
        private int haloColor;
        private int haloRadius;
        private boolean last;
        private final android.graphics.Paint railPaint;
        private int railWidth;
        private int railX;

        RailRow(android.content.Context context) {
            super(context);
            this.railPaint = new android.graphics.Paint(1);
            setWillNotDraw(false);
        }

        void setRail(int i, int i2, int i3, int i4) {
            this.railX = i;
            this.railWidth = i2;
            this.aboveColor = i3;
            this.belowColor = i4;
        }

        void setBar(int i, int i2) {
            this.barColor = i;
            this.barWidth = i2;
            invalidate();
        }

        void setHalo(int i, int i2) {
            this.haloColor = i;
            this.haloRadius = i2;
            invalidate();
        }

        void setFirst(boolean z) {
            this.first = z;
        }

        void setLast(boolean z) {
            this.last = z;
            invalidate();
        }

        @Override // android.widget.LinearLayout, android.view.View
        protected void onDraw(android.graphics.Canvas canvas) {
            android.graphics.Canvas canvas2;
            super.onDraw(canvas);
            int height = getHeight();
            if (height > 0) {
                float f = this.railX - (this.railWidth / 2.0f);
                float f2 = f + this.railWidth;
                int i = height / 2;
                if (!this.first) {
                    this.railPaint.setColor(this.aboveColor);
                    canvas.drawRect(f, 0.0f, f2, i, this.railPaint);
                }
                if (!this.last) {
                    this.railPaint.setColor(this.belowColor);
                    canvas.drawRect(f, i, f2, height, this.railPaint);
                }
                if (this.haloColor != 0 && this.haloRadius > 0) {
                    this.railPaint.setColor(this.haloColor);
                    canvas2 = canvas;
                    canvas2.drawCircle(this.railX, i, this.haloRadius, this.railPaint);
                } else {
                    canvas2 = canvas;
                }
                if (this.barColor != 0 && this.barWidth > 0) {
                    this.railPaint.setColor(this.barColor);
                    canvas2.drawRect(0.0f, 0.0f, this.barWidth, height, this.railPaint);
                }
            }
        }
    }
}
