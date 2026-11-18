package com.xtbus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.InputFilter;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 首页：常驻搜索 + 附近线路（按站点分组）+ 最近使用。
 * 视觉：站牌制式——到站分钟是屏幕上唯一的主角，其余退为纸与墨。
 * 字号版本（大字 / 小字）由 {@link Tokens} 在运行时切换。
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1001;
    private static final String PREFS = "xtbus";
    private static final String KEY_RECENT = "recent_lines_v2";
    private static final String KEY_FAV = "fav_lines_v1";
    /** 首页自动刷新周期与“返回即刷新”的判定阈值。 */
    private static final long HOME_REFRESH_INTERVAL = 45 * 1000L;
    private static final long HOME_STALE_MS = 30 * 1000L;
    private static final int MAX_KEYWORD = 30;
    private static final long NAV_DEBOUNCE_MS = 800L;

    /** 到站牌三态：即将 / 正常 / 无数据。 */
    private static final int ETA_URGENT = 0;
    private static final int ETA_NORMAL = 1;
    private static final int ETA_NONE = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat dayClock = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    private SwipeRefreshLayout pull;
    /** 本页视图构建时使用的版本，用于从设置页返回后判断是否需要重建。 */
    private boolean builtLarge;
    private LinearLayout list;
    private LinearLayout favBox;
    private LinearLayout recentBox;
    private TextView status;
    private TextView subtitle;
    private TextView appTitle;
    private EditText searchInput;
    private View appbar;
    private View searchWrap;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean locationApplied;
    /** 首次定位后改为低频更新（60 秒 / 300 米），避免常驻高频定位。 */
    private boolean locationHot;
    private boolean searching;
    private String currentKeyword = "";
    /** 搜索请求序号：旧请求后返回时直接丢弃，避免结果与关键词错配。 */
    private int searchSeq;
    private boolean foreground;
    /** 返回前台静默刷新时保留的滚动位置。 */
    private int keepScrollY;
    private boolean nearbyLoading;
    private boolean initialLoadDone;
    private long lastManualRefresh;
    private long lastNearbyAt;
    private long lastNavAt;
    private double lastFixLat = Double.NaN;
    private double lastFixLng = Double.NaN;
    /** 最近一次附近线路数据：长按「添加换向」时用来找同名反方向线路的真实终点。 */
    private JSONObject lastNearbyData;

    /** 8 秒仍无定位时用市中心坐标请求一次；必须能取消，否则页面销毁后仍会执行。 */
    private final Runnable fallbackNearby = new Runnable() {
        @Override
        public void run() {
            if (initialLoadDone || searching || isFinishing()) {
                return;
            }
            showNearby();
            setStatus("未获取到定位，使用湘潭市中心参考位置");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Api.init(getApplicationContext());
        Tokens.load(this);
        builtLarge = Tokens.isLarge();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pull = findViewById(R.id.pull);
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        subtitle = findViewById(R.id.subtitle);
        appTitle = findViewById(R.id.appTitle);
        searchInput = findViewById(R.id.searchInput);
        appbar = findViewById(R.id.appbar);
        searchWrap = findViewById(R.id.searchWrap);

        applyTokens();

        // 标准版：齿轮 → 设置页；大字版：保留 Aa → 字号对话框
        final boolean largeEdition = Tokens.isLarge();
        findViewById(R.id.sizeBtn).setVisibility(largeEdition ? View.VISIBLE : View.GONE);
        findViewById(R.id.settingsBtn).setVisibility(largeEdition ? View.GONE : View.VISIBLE);
        findViewById(R.id.entry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (largeEdition) {
                    showEditionDialog();
                } else {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
            }
        });
        if (largeEdition) {
            // 大字版不额外放齿轮，长按 Aa 进入设置，顶栏布局保持不变
            findViewById(R.id.entry).setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;
                }
            });
        }

        pull.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                long now = System.currentTimeMillis();
                if (now - lastManualRefresh < 5000) {
                    pull.setRefreshing(false);
                    Toast.makeText(MainActivity.this, "刚刚已刷新，请稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                lastManualRefresh = now;
                if (searching) {
                    doSearch(true);
                } else {
                    showNearby();
                }
            }
        });
        pull.setColorSchemeColors(Tokens.color(this, "pine"), Tokens.color(this, "signal"));
        pull.setProgressBackgroundColorSchemeColor(Tokens.color(this, "card"));

        searchInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_KEYWORD)});
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                doSearch();
                return true;
            }
        });

        // 先渲染本地区块，不等网络；最近使用等正文渲染完再收在页面底部
        renderLocalScaffold();
        addEmpty("正在获取附近线路…");
        appendRecent();

        if (savedInstanceState != null && savedInstanceState.getBoolean("searching", false)) {
            String keyword = savedInstanceState.getString("keyword", "");
            searchInput.setText(keyword);
            searchInput.setSelection(searchInput.getText().length());
            doSearch();
        }

        initLocation();
        // 定位就绪前不请求；8 秒仍无定位则用市中心参考坐标请求一次。
        ui.postDelayed(fallbackNearby, 8000);
    }

    // ---------------- 令牌落地 ----------------

    private void applyTokens() {
        int gutter = Tokens.dp(this, "xt_gutter");

        appbar.getLayoutParams().height = Tokens.dp(this, "xt_appbar_h");
        appbar.setPadding(gutter, 0, dp(8), 0);

        Tokens.textSize(appTitle, "xt_appbar");
        appTitle.setTextColor(Tokens.color(this, "ink"));
        Tokens.textSize(subtitle, "xt_caption");
        subtitle.setTextColor(Tokens.color(this, "ink2"));

        View entry = findViewById(R.id.entry);
        int touch = Tokens.dp(this, "xt_touch");
        entry.setLayoutParams(new LinearLayout.LayoutParams(touch, touch));
        Tokens.textSize((TextView) findViewById(R.id.sizeBtn), "xt_label");
        View settingsBtn = findViewById(R.id.settingsBtn);
        int icon = Tokens.dp(this, "xt_icon");
        settingsBtn.setLayoutParams(new FrameLayout.LayoutParams(
                icon, icon, android.view.Gravity.CENTER));

        searchWrap.setPadding(gutter, 0, gutter, dp(4));
        View pill = findViewById(R.id.searchPill);
        pill.getLayoutParams().height = Tokens.dp(this, "xt_search_h");
        Tokens.textSize(searchInput, "xt_body");
        searchInput.setTextColor(Tokens.color(this, "ink"));
        searchInput.setHintTextColor(Tokens.color(this, "ink3"));

        status.setTextColor(Tokens.color(this, "ink2"));
        Tokens.textSize(status, "xt_caption");
        status.setPadding(gutter, dp(6), gutter, 0);

        list.setPadding(gutter, dp(2), gutter, dp(24));
        pull.setBackgroundColor(Tokens.color(this, "paper"));
    }

    private void showEditionDialog() {
        final String[] items = {"标准字号", "大字版"};
        int checked = Tokens.isLarge() ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("字号")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        boolean wantLarge = which == 1;
                        if (wantLarge != Tokens.isLarge()) {
                            Tokens.setLarge(MainActivity.this, wantLarge);
                            recreate();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 定位 ----------------

    private void initLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        try {
            String[] providers = {LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
            for (String provider : providers) {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
        } catch (SecurityException ignored) {
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 6L * 3600 * 1000) {
            applyLocation(best, "上次定位");
            return;
        }
        requestLocationOnce();
    }

    private void requestLocationOnce() {
        try {
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    applyLocation(location, "实时定位");
                }

                @Override
                public void onStatusChanged(String provider, int statusCode, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    15000, 50, locationListener, Looper.getMainLooper());
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    15000, 50, locationListener, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
    }

    private void applyLocation(Location location, String source) {
        if (location == null) {
            return;
        }
        double[] gcj = Api.wgs2gcj(location.getLongitude(), location.getLatitude());
        boolean real = "实时定位".equals(source);
        subtitle.setText(real ? "湘潭 · 实时定位" : "湘潭 · 上次定位");
        if (locationApplied) {
            // 首次定位后继续接受更新，但 100 米内视为抖动，不重载列表
            if (!Double.isNaN(lastFixLat)) {
                float[] out = new float[1];
                Location.distanceBetween(lastFixLat, lastFixLng, gcj[1], gcj[0], out);
                if (out[0] < 100) {
                    return;
                }
            }
        }
        locationApplied = true;
        lastFixLat = gcj[1];
        lastFixLng = gcj[0];
        Api.setLocation(gcj[1], gcj[0]);
        // 已拿到可用定位，改成低频更新，避免整页生命周期内 15 秒一次 GPS
        if (real) {
            switchToLowFrequencyLocation();
        }
        // 晚到的定位（8 秒兜底已用市中心请求过）也需要刷新列表
        if (!searching && System.currentTimeMillis() - lastNearbyAt > HOME_STALE_MS) {
            showNearby();
        }
    }

    private void switchToLowFrequencyLocation() {
        if (locationHot || locationManager == null || locationListener == null) {
            return;
        }
        locationHot = true;
        try {
            locationManager.removeUpdates(locationListener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    60000, 300, locationListener, Looper.getMainLooper());
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    60000, 300, locationListener, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) {
            return;
        }
        // Android 12+ 允许只给“大致位置”：FINE 被拒、COARSE 允许时仍应继续定位
        boolean granted = false;
        for (int i = 0; i < permissions.length && i < results.length; i++) {
            boolean location = Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                    || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i]);
            if (location && results[i] == PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }
        }
        if (granted) {
            initLocation();
        } else {
            if (!initialLoadDone) {
                showNearby();
            }
            setStatus("未授权定位，使用湘潭市中心参考位置");
            // 永久拒绝时给出可操作的提示，而不是只退回市中心
            if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(this, "定位权限已被拒绝，可在系统设置中手动开启",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------- 附近线路 ----------------

    /** 只渲染本地内容（收藏 + 分区标题），不依赖网络。最近使用在页面末尾追加。 */
    private void renderLocalScaffold() {
        clearList();
        favBox = new LinearLayout(this);
        favBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(favBox);
        renderFavorites();
        addSection("附近站点", null);
    }

    /** 最近使用固定收在页面最底部，等正文渲染完再追加。 */
    private void appendRecent() {
        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(recentBox);
        renderRecent();
    }

    private void showNearby() {
        if (nearbyLoading) {
            pull.setRefreshing(false);
            return;
        }
        nearbyLoading = true;
        initialLoadDone = true;
        searching = false;
        appTitle.setText("附近公交");
        setStatus("正在获取附近线路…");
        // 请求期间保留已有内容（尤其是本地“最近使用”），避免失败时整页空白
        if (list.getChildCount() == 0) {
            renderLocalScaffold();
        }
        Io.query(new Runnable() {
            @Override
            public void run() {
                try {
                    final Api.Result result = Api.nearby(true);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            nearbyLoading = false;
                            renderNearby(result.data, result);
                            restoreScroll();
                        }
                    });
                } catch (final Api.ApiException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            nearbyLoading = false;
                            String text;
                            if (e.kind == Api.ApiException.Kind.RISK
                                    || e.kind == Api.ApiException.Kind.COOLDOWN) {
                                text = e.getMessage();
                            } else if (e.kind == Api.ApiException.Kind.BUSINESS) {
                                text = "加载失败：" + e.getMessage();
                            } else {
                                text = "网络异常 · 请检查网络后重试";
                            }
                            showNearbyFailure(text);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            nearbyLoading = false;
                            showNearbyFailure("加载失败 · 请稍后重试");
                        }
                    });
                }
            }
        });
    }

    /** 从详情页返回时的静默刷新会把列表重建，这里把用户原来的滚动位置放回去。 */
    private void restoreScroll() {
        if (keepScrollY <= 0) {
            return;
        }
        final ScrollView scroll = findViewById(R.id.scroll);
        final int y = keepScrollY;
        keepScrollY = 0;
        if (scroll == null) {
            return;
        }
        scroll.postDelayed(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, y);
            }
        }, 60);
    }

    /** 失败态：保留最近使用，给出明确的重试入口，不再整页空白。 */
    private void showNearbyFailure(String message) {
        renderLocalScaffold();
        addEmpty(message);
        addActionRow("重新加载", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNearby();
            }
        });
        appendRecent();
        setStatus("");
        pull.setRefreshing(false);
    }

    private void renderNearby(JSONObject data, Api.Result result) {
        lastNearbyAt = System.currentTimeMillis();
        lastNearbyData = data;
        clearList();
        favBox = new LinearLayout(this);
        favBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(favBox);
        renderFavorites();
        addSection("附近站点", null);

        JSONArray stations = data.optJSONArray("nearLines");
        int lineCount = 0;
        int stationCount = 0;
        if (stations != null) {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject st = stations.optJSONObject(i);
                if (st == null) {
                    continue;
                }
                String stationName = st.optString("sn");
                int distance = st.optInt("distance", 0);
                JSONArray lines = st.optJSONArray("lines");
                if (lines == null || lines.length() == 0) {
                    continue;
                }

                List<JSONObject> wraps = new ArrayList<JSONObject>();
                for (int j = 0; j < lines.length(); j++) {
                    JSONObject wrap = lines.optJSONObject(j);
                    if (wrap != null && wrap.optJSONObject("line") != null) {
                        wraps.add(wrap);
                    }
                }
                if (wraps.isEmpty()) {
                    continue;
                }
                Collections.sort(wraps, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject a, JSONObject b) {
                        return Integer.compare(etaSeconds(a), etaSeconds(b));
                    }
                });

                LinearLayout card = newCard();
                card.addView(stationHead(stationName, distance, wraps.size()));
                for (int j = 0; j < wraps.size(); j++) {
                    if (j > 0) {
                        card.addView(divider());
                    }
                    JSONObject wrap = wraps.get(j);
                    card.addView(lineRow(wrap.optJSONObject("line"), wrap));
                    lineCount++;
                }
                list.addView(card);
                stationCount++;
            }
        }

        if (lineCount == 0) {
            addEmpty("附近暂未查到线路，下拉重试或检查定位");
            setStatus("附近暂未查到线路");
        } else {
            String cacheNote = result.fromCache ? " · 缓存" : "";
            addFooter(updatedText(result.fetchedAt) + cacheNote
                    + " · " + stationCount + " 个站点 · " + lineCount + " 条线路");
            setStatus(result.degraded ? "网络异常，当前显示缓存数据" : "");
        }
        appendRecent();
        pull.setRefreshing(false);
    }

    /** 更新时间文案：近期用相对时间，跨天补日期，避免 24 小时缓存被误读为刚刚更新。 */
    private String updatedText(long fetchedAt) {
        long age = Math.max(0, System.currentTimeMillis() - fetchedAt);
        if (age < 60 * 1000L) {
            return "刚刚更新";
        }
        if (age < 60 * 60 * 1000L) {
            return (age / (60 * 1000L)) + " 分钟前更新";
        }
        Calendar at = Calendar.getInstance();
        at.setTimeInMillis(fetchedAt);
        Calendar now = Calendar.getInstance();
        if (at.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && at.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            return "今天 " + clock.format(new Date(fetchedAt)) + " 更新";
        }
        return dayClock.format(new Date(fetchedAt)) + " 更新";
    }

    // ---------------- 搜索 ----------------

    private void doSearch() {
        doSearch(false);
    }

    private void doSearch(boolean force) {
        String keyword = searchInput.getText().toString().trim();
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入线路名或站点名", Toast.LENGTH_SHORT).show();
            return;
        }
        if (keyword.length() > MAX_KEYWORD) {
            keyword = keyword.substring(0, MAX_KEYWORD);
            searchInput.setText(keyword);
            searchInput.setSelection(keyword.length());
        }
        if (keyword.indexOf('%') >= 0) {
            Toast.makeText(this, "关键词不能包含 %，请修改后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        currentKeyword = keyword;
        searching = true;
        initialLoadDone = true;
        appTitle.setText("搜索结果");
        hideKeyboard();
        setStatus("正在搜索“" + keyword + "”…");
        final String query = keyword;
        final int seq = ++searchSeq;
        final boolean bypass = force;
        Io.query(new Runnable() {
            @Override
            public void run() {
                try {
                    final Api.Result result = Api.search(query, bypass);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            // 已有更新的搜索时丢弃本次结果，避免旧结果覆盖新关键词
                            if (seq != searchSeq || isFinishing()) {
                                return;
                            }
                            renderSearch(result.data, result.fromCache);
                        }
                    });
                } catch (final Api.ApiException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != searchSeq || isFinishing()) {
                                return;
                            }
                            pull.setRefreshing(false);
                            renderSearchError(searchErrorMessage(e));
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != searchSeq || isFinishing()) {
                                return;
                            }
                            pull.setRefreshing(false);
                            renderSearchError("搜索失败，请稍后重试");
                        }
                    });
                }
            }
        });
    }

    private String searchErrorMessage(Api.ApiException e) {
        if (e.kind == Api.ApiException.Kind.RISK
                || e.kind == Api.ApiException.Kind.COOLDOWN) {
            return e.getMessage();
        }
        if (e.kind == Api.ApiException.Kind.BUSINESS) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("%") || msg.contains("参数")) {
                return "关键词包含不支持的字符，请修改后重试";
            }
            return "搜索失败：" + msg;
        }
        return "网络异常，请检查网络后重试";
    }

    /** 搜索失败：保留返回入口与重试按钮，不再只剩一行状态文字。 */
    private void renderSearchError(String message) {
        clearList();
        addBackRow();
        addEmpty(message);
        addActionRow("重试", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch(true);
            }
        });
        setStatus("");
    }

    private void renderSearch(JSONObject data, boolean fromCache) {
        clearList();
        addBackRow();

        JSONArray lines = data.optJSONArray("lines");
        JSONArray stations = data.optJSONArray("stations");
        int count = 0;

        if (lines != null && lines.length() > 0) {
            addSection("线路", String.valueOf(lines.length()));
            LinearLayout card = newCard();
            int added = 0;
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line == null) {
                    continue;
                }
                if (added > 0) {
                    card.addView(divider());
                }
                card.addView(lineRow(line, null));
                added++;
                count++;
            }
            list.addView(card);
        }
        if (stations != null && stations.length() > 0) {
            addSection("站点", String.valueOf(stations.length()));
            LinearLayout card = newCard();
            int added = 0;
            for (int i = 0; i < stations.length(); i++) {
                JSONObject station = stations.optJSONObject(i);
                if (station == null) {
                    continue;
                }
                if (added > 0) {
                    card.addView(divider());
                }
                card.addView(stationResultRow(station));
                added++;
                count++;
            }
            list.addView(card);
        }
        if (count == 0) {
            addEmpty("没有找到匹配的线路或站点");
            setStatus("");
        } else {
            setStatus("共 " + count + " 条结果" + (fromCache ? " · 缓存" : ""));
        }
        pull.setRefreshing(false);
    }

    // ---------------- 收藏 / 最近使用（本地记录） ----------------

    /** 收藏区块：没有收藏时整块不显示，不留标题与空白。 */
    private void renderFavorites() {
        if (favBox == null) {
            return;
        }
        favBox.removeAllViews();
        JSONArray arr = loadFavs();
        if (arr.length() == 0) {
            favBox.setVisibility(View.GONE);
            return;
        }
        favBox.setVisibility(View.VISIBLE);
        favBox.addView(sectionRow("收藏", null, 0));

        // 与搜索结果同一套线路卡：一张卡内多行、行间分隔线，整行可点进详情
        LinearLayout card = newCard();
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            if (added > 0) {
                card.addView(divider());
            }
            card.addView(lineRow(lineFor(entry), null, entry));
            added++;
        }
        favBox.addView(card);
    }

    /** 把本地收藏记录还原成搜索结果里的 line 结构，渲染走同一条 lineRow。 */
    private JSONObject lineFor(JSONObject entry) {
        JSONObject line = new JSONObject();
        try {
            line.put("lineId", entry.optString("lineId"));
            line.put("name", entry.optString("lineName"));
            line.put("lineNo", entry.optString("lineNo"));
            line.put("direction", entry.optInt("direction", 0));
            line.put("endSn", entry.optString("endSn"));
            line.put("stationsNum", entry.optInt("stationsNum", 0));
            line.put("firstTime", entry.optString("firstTime"));
            line.put("lastTime", entry.optString("lastTime"));
            line.put("reverse", entry.optInt("reverse", 0));
        } catch (Exception ignored) {
        }
        return line;
    }

    private void renderRecent() {
        if (recentBox == null) {
            return;
        }
        recentBox.removeAllViews();
        JSONArray arr = loadRecent();
        if (arr.length() == 0) {
            recentBox.setVisibility(View.GONE);
            return;
        }
        recentBox.setVisibility(View.VISIBLE);
        recentBox.addView(sectionRow("最近使用", null, 0));
        recentBox.addView(chipsRow(arr));
    }

    /** 收藏与最近使用共用同一套胶囊样式与点击直达行为。 */
    private View chipsRow(JSONArray arr) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < arr.length(); i++) {
            final JSONObject entry = arr.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String end = entry.optString("endSn");
            String tag = end.isEmpty()
                    ? (entry.optInt("reverse", 0) == 1 ? "反向" : "")
                    : end;
            TextView chip = new TextView(this);
            chip.setText(displayName(entry.optString("lineName")) + (tag.isEmpty() ? "" : " · " + tag));
            Tokens.textSize(chip, "xt_label");
            chip.setTextColor(Tokens.color(this, "pine"));
            chip.setGravity(Gravity.CENTER);
            chip.setMaxLines(1);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            int padH = dp(14);
            chip.setPadding(padH, dp(6), padH, dp(6));
            chip.setMinHeight(Tokens.dp(this, "xt_touch") - dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Tokens.color(this, "card"));
            bg.setStroke(dp(1), Tokens.color(this, "line"));
            bg.setCornerRadius(dp(999));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEntry(entry);
                }
            });
            row.addView(chip);
        }
        hsv.addView(row);
        return hsv;
    }

    private JSONArray loadRecent() {
        try {
            String raw = prefs().getString(KEY_RECENT, "");
            return raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONArray loadFavs() {
        try {
            String raw = prefs().getString(KEY_FAV, "");
            return raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private boolean isFav(JSONObject entry) {
        JSONArray arr = loadFavs();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && sameEntry(o, entry)) {
                return true;
            }
        }
        return false;
    }

    /** 收藏/取消收藏：最新收藏排最前，最多 6 条，与最近使用同一套去重口径，两份记录互不影响。 */
    private void toggleFav(JSONObject entry) {
        JSONArray old = loadFavs();
        JSONArray kept = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (sameEntry(o, entry)) {
                removed = true;
                continue;
            }
            kept.put(o);
        }
        if (!removed) {
            JSONArray out = new JSONArray();
            out.put(entry);
            for (int i = 0; i < kept.length() && out.length() < 6; i++) {
                out.put(kept.optJSONObject(i));
            }
            kept = out;
        }
        prefs().edit().putString(KEY_FAV, kept.toString()).apply();
    }

    private void recordRecent(JSONObject entry) {
        if (entry == null || entry.optString("lineId").isEmpty()) {
            return;
        }
        JSONArray old = loadRecent();
        JSONArray out = new JSONArray();
        out.put(entry);
        for (int i = 0; i < old.length() && out.length() < 6; i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null || sameEntry(o, entry)) {
                continue;
            }
            out.put(o);
        }
        prefs().edit().putString(KEY_RECENT, out.toString()).apply();
    }

    private boolean sameEntry(JSONObject a, JSONObject b) {
        return a.optString("lineId").equals(b.optString("lineId"))
                && a.optInt("direction", 0) == b.optInt("direction", 0);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------------- 跳转 ----------------

    private JSONObject entryFor(JSONObject line, JSONObject wrap) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("lineId", line.optString("lineId"));
            entry.put("lineName", line.optString("name"));
            entry.put("lineNo", line.optString("lineNo"));
            entry.put("direction", line.optInt("direction", 0));
            entry.put("endSn", line.optString("endSn"));
            // 收藏卡要显示「N 站 · 首末班」，这里一并存下来，避免只能显示「查看实时到站」
            entry.put("stationsNum", line.optInt("stationsNum", 0));
            entry.put("firstTime", line.optString("firstTime"));
            entry.put("lastTime", line.optString("lastTime"));
            JSONObject target = wrap == null ? null : wrap.optJSONObject("targetStation");
            JSONObject next = wrap == null ? null : wrap.optJSONObject("nextStation");
            entry.put("stationName", target == null ? "" : target.optString("sn"));
            entry.put("nextStationName", next == null ? "" : next.optString("sn"));
            entry.put("targetOrder", target == null ? "-1" : String.valueOf(target.optInt("order", -1)));
        } catch (Exception ignored) {
        }
        return entry;
    }

    private void openEntry(JSONObject entry) {
        long now = System.currentTimeMillis();
        if (now - lastNavAt < NAV_DEBOUNCE_MS) {
            return;
        }
        lastNavAt = now;
        Intent intent = new Intent(this, LineActivity.class);
        intent.putExtra("lineId", entry.optString("lineId"));
        intent.putExtra("lineName", entry.optString("lineName"));
        intent.putExtra("lineNo", entry.optString("lineNo"));
        intent.putExtra("direction", String.valueOf(entry.optInt("direction", 0)));
        intent.putExtra("stationName", entry.optString("stationName"));
        intent.putExtra("nextStationName", entry.optString("nextStationName"));
        intent.putExtra("targetOrder", entry.optString("targetOrder", "-1"));
        startActivity(intent);
        recordRecent(entry);
        renderRecent();
    }

    // ---------------- 首页组件 ----------------

    private int cardPad() {
        return Tokens.dp(this, "xt_card_pad");
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Tokens.color(this, "card"));
        bg.setCornerRadius(Tokens.dp(this, "xt_r_card"));
        bg.setStroke(dp(1), Tokens.color(this, "line"));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Tokens.dp(this, "xt_group_gap");
        card.setLayoutParams(lp);
        return card;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = cardPad();
        lp.rightMargin = cardPad();
        v.setLayoutParams(lp);
        v.setBackgroundColor(Tokens.color(this, "line"));
        return v;
    }

    private View stationHead(String name, int distance, int lineCount) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int pad = cardPad();
        head.setPadding(pad, pad - dp(2), pad, dp(4));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Tokens.color(this, "ink"));
        Tokens.textSize(title, "xt_station");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title);

        TextView meta = new TextView(this);
        meta.setText(distance > 0 ? distance + " 米" : "");
        meta.setTextColor(Tokens.color(this, "ink2"));
        Tokens.textSize(meta, "xt_caption");
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = dp(8);
        meta.setLayoutParams(metaLp);
        head.addView(meta);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(spacer);

        TextView count = new TextView(this);
        count.setText(lineCount + " 条");
        count.setTextColor(Tokens.color(this, "ink3"));
        Tokens.textSize(count, "xt_caption");
        head.addView(count);
        return head;
    }

    private View lineRow(JSONObject line, JSONObject wrap) {
        return lineRow(line, wrap, null);
    }

    /**
     * @param entryOverride 收藏卡传入本地记录，点开时仍带上原候车站上下文
     */
    private View lineRow(JSONObject line, JSONObject wrap, JSONObject entryOverride) {
        String name = line.optString("name");
        String end = line.optString("endSn");
        int stationCount = line.optInt("stationsNum", 0);
        String first = line.optString("firstTime");
        String last = line.optString("lastTime");
        String eta = etaText(wrap);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Tokens.dp(this, "xt_row_h"));
        int pad = cardPad();
        int vpad = Tokens.isLarge() ? dp(10) : dp(5);
        row.setPadding(pad, vpad, pad, vpad);

        // 线路号牌：墨绿实心，表达线路身份，不随状态变化
        TextView badge = new TextView(this);
        badge.setText(badgeText(name));
        Tokens.textSizeFixed(badge, "xt_badge_font");
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Tokens.color(this, "pine_on"));
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(Tokens.dp(this, "xt_badge_min"));
        badge.setMinHeight(Tokens.dp(this, "xt_badge_h"));
        badge.setPadding(dp(9), dp(2), dp(9), dp(2));
        // 超长线路号（如「605路A线」）在牌内折成两行，不降字号、不挤压终点名
        badge.setMaxWidth(Tokens.dp(this, "xt_badge_max"));
        badge.setSingleLine(false);
        badge.setMaxLines(2);
        badge.setEllipsize(null);
        badge.setLineSpacing(0, 0.92f);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Tokens.color(this, "pine"));
        badgeBg.setCornerRadius(Tokens.dp(this, "xt_r_badge"));
        badge.setBackground(badgeBg);
        row.addView(badge);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = Tokens.dp(this, "xt_gap");
        col.setLayoutParams(colLp);
        row.addView(col);

        TextView title = new TextView(this);
        if (end.isEmpty()) {
            title.setText(displayName(name) + (line.optInt("reverse", 0) == 1 ? "（反向）" : ""));
            title.setTextColor(Tokens.color(this, "ink"));
            title.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            // 「开往」小一号、次要色；终点名加粗，和稿子一致
            String prefix = "开往 ";
            SpannableStringBuilder sb = new SpannableStringBuilder(prefix).append(end);
            sb.setSpan(new RelativeSizeSpan(Tokens.isLarge() ? 0.82f : 0.73f), 0, prefix.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Tokens.color(this, "ink2")), 0, prefix.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.NORMAL), 0, prefix.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            title.setText(sb);
            title.setTextColor(Tokens.color(this, "ink"));
            title.setTypeface(Typeface.DEFAULT_BOLD);
        }
        Tokens.textSize(title, "xt_dest");
        // 大字版允许两行，不靠截断隐藏站名；小字版单行省略
        title.setMaxLines(Tokens.isLarge() ? 2 : 1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        boolean showMeta = wrap == null || !Tokens.isLarge();
        if (showMeta) {
            StringBuilder sub = new StringBuilder();
            if (stationCount > 0) {
                sub.append(stationCount).append(" 站");
            }
            String span = timeRange(first, last);
            if (!span.isEmpty()) {
                if (sub.length() > 0) {
                    sub.append(" · ");
                }
                sub.append(span);
            }
            if (wrap == null) {
                if (sub.length() > 0) {
                    sub.append(" · ");
                }
                sub.append("查看实时到站");
            }
            if (sub.length() > 0) {
                TextView subView = new TextView(this);
                subView.setText(sub.toString());
                subView.setTextColor(Tokens.color(this, "ink2"));
                Tokens.textSize(subView, "xt_body_s");
                subView.setSingleLine(true);
                subView.setEllipsize(TextUtils.TruncateAt.END);
                subView.setPadding(0, dp(2), 0, 0);
                col.addView(subView);
            }
        }

        if (wrap == null) {
            row.addView(chevron());
        } else {
            row.addView(etaPlate(eta));
        }

        final JSONObject entry = entryOverride != null ? entryOverride : entryFor(line, wrap);
        row.setClickable(true);
        row.setBackgroundResource(selectableBackground());
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEntry(entry);
            }
        });
        // 长按（系统默认 500ms）浮出收藏按钮；拦截后不再触发进详情的点击
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showFavPopup(v, entry);
                return true;
            }
        });
        return row;
    }

    /**
     * 收藏按钮贴着被长按的行向下浮出（下方空间不够时系统会翻到行的上方），
     * 点空白处即消失，不遮挡页面其他区域、不改变列表布局。
     */
    private void showFavPopup(final View anchor, final JSONObject entry) {
        final boolean fav = isFav(entry);
        final JSONObject reverse = reverseEntryFor(entry);
        final boolean favReverse = isFav(reverse);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(Tokens.color(this, "card"));
        boxBg.setCornerRadius(dp(14));
        boxBg.setStroke(dp(1), Tokens.color(this, "line"));
        box.setBackground(boxBg);
        box.setElevation(dp(8));

        TextView btn = favButton(fav ? "取消收藏" : "收藏线路", fav ? "ink2" : "pine");
        box.addView(btn);

        TextView swap = favButton(favReverse ? "已收藏换向" : "添加换向",
                favReverse ? "ink3" : "pine");
        LinearLayout.LayoutParams swapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        swapLp.leftMargin = dp(6);
        swap.setLayoutParams(swapLp);
        box.addView(swap);

        final PopupWindow pw = new PopupWindow(box,
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(8));

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFav(entry);
                renderFavorites();
                pw.dismiss();
                Toast.makeText(MainActivity.this,
                        fav ? "已取消收藏" : "已收藏", Toast.LENGTH_SHORT).show();
            }
        });

        swap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pw.dismiss();
                if (favReverse) {
                    Toast.makeText(MainActivity.this, "已收藏反向线路", Toast.LENGTH_SHORT).show();
                    return;
                }
                toggleFav(reverse);
                renderFavorites();
                Toast.makeText(MainActivity.this,
                        "已收藏 " + displayName(entry.optString("lineName")) + "（反向）",
                        Toast.LENGTH_SHORT).show();
            }
        });

        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = box.getMeasuredWidth();
        pw.setWidth(w);
        pw.showAsDropDown(anchor, Math.max(0, (anchor.getWidth() - w) / 2), dp(2));
    }

    /** 弹窗按钮：两种操作共用同一套尺寸与底色。 */
    private TextView favButton(String text, String colorToken) {
        TextView btn = new TextView(this);
        btn.setText(text);
        Tokens.textSize(btn, "xt_label");
        btn.setTextColor(Tokens.color(this, colorToken));
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        btn.setMinHeight(Tokens.dp(this, "xt_touch"));
        btn.setMinWidth(dp(132));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Tokens.color(this, "sunken"));
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        return btn;
    }

    /**
     * 反向线路记录：优先在附近数据里找同名、反方向的那条（终点和 lineId 都是现成的），
     * 找不到再退回本地拼一条：方向取反、清掉候车站上下文。
     */
    private JSONObject reverseEntryFor(JSONObject entry) {
        String name = entry.optString("lineName");
        int dir = entry.optInt("direction", 0);
        JSONArray stations = lastNearbyData == null ? null : lastNearbyData.optJSONArray("nearLines");
        if (stations != null && !name.isEmpty()) {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject st = stations.optJSONObject(i);
                JSONArray lines = st == null ? null : st.optJSONArray("lines");
                if (lines == null) {
                    continue;
                }
                for (int j = 0; j < lines.length(); j++) {
                    JSONObject wrap = lines.optJSONObject(j);
                    JSONObject line = wrap == null ? null : wrap.optJSONObject("line");
                    if (line == null || !name.equals(line.optString("name"))
                            || line.optInt("direction", 0) == dir) {
                        continue;
                    }
                    return entryFor(line, wrap);
                }
            }
        }
        return reverseEntry(entry);
    }

    /** 兜底：本地拼一条反向记录（终点未知，列表里标注「反向」，进详情后由服务端补全）。 */
    private JSONObject reverseEntry(JSONObject entry) {
        JSONObject out = new JSONObject();
        try {
            out.put("lineId", entry.optString("lineId"));
            out.put("lineName", entry.optString("lineName"));
            out.put("lineNo", entry.optString("lineNo"));
            out.put("direction", entry.optInt("direction", 0) == 0 ? 1 : 0);
            out.put("endSn", "");
            out.put("stationsNum", entry.optInt("stationsNum", 0));
            out.put("firstTime", entry.optString("firstTime"));
            out.put("lastTime", entry.optString("lastTime"));
            out.put("stationName", "");
            out.put("nextStationName", "");
            out.put("targetOrder", "-1");
            out.put("reverse", 1);
        } catch (Exception ignored) {
        }
        return out;
    }

    private View stationResultRow(final JSONObject station) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Tokens.dp(this, "xt_row_h"));
        int pad = cardPad();
        row.setPadding(pad, dp(7), pad, dp(7));

        TextView badge = new TextView(this);
        badge.setText("站");
        Tokens.textSize(badge, "xt_line_no");
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Tokens.color(this, "ink2"));
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(Tokens.dp(this, "xt_badge_min"));
        badge.setMinHeight(Tokens.dp(this, "xt_badge_h"));
        badge.setPadding(dp(9), dp(2), dp(9), dp(2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Tokens.color(this, "sunken"));
        badgeBg.setCornerRadius(Tokens.dp(this, "xt_r_badge"));
        badge.setBackground(badgeBg);
        row.addView(badge);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = Tokens.dp(this, "xt_gap");
        col.setLayoutParams(colLp);
        row.addView(col);

        TextView title = new TextView(this);
        title.setText(station.optString("sn"));
        title.setTextColor(Tokens.color(this, "ink"));
        Tokens.textSize(title, "xt_dest");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        // 点站点直接进路线规划：起点用当前定位，终点就是这个站
        TextView sub = new TextView(this);
        sub.setText("点此规划路线");
        sub.setTextColor(Tokens.color(this, "ink2"));
        Tokens.textSize(sub, "xt_body_s");
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);
        row.addView(chevron());

        row.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPlan(station);
            }
        });

        return row;
    }

    /** 从搜索结果进路线规划：起点取最近一次定位（已是 GCJ-02）。 */
    private void openPlan(JSONObject station) {
        boolean located = !Double.isNaN(lastFixLat) && !Double.isNaN(lastFixLng);
        android.content.Intent intent =
                new android.content.Intent(MainActivity.this, PlanActivity.class);
        intent.putExtra("destSid", station.optString("sId"));
        intent.putExtra("destName", station.optString("sn"));
        // 搜索接口的站点对象不一定带 lat/lng，只有 "lng,lat" 形式的 location
        double lat = station.optDouble("lat", Double.NaN);
        double lng = station.optDouble("lng", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            String loc = station.optString("location");
            int comma = loc == null ? -1 : loc.indexOf(',');
            if (comma > 0) {
                try {
                    lng = Double.parseDouble(loc.substring(0, comma).trim());
                    lat = Double.parseDouble(loc.substring(comma + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        intent.putExtra("destLat", Double.isNaN(lat) ? 0 : lat);
        intent.putExtra("destLng", Double.isNaN(lng) ? 0 : lng);
        intent.putExtra("originName", located ? "当前位置" : "湘潭市中心");
        intent.putExtra("originLat", located ? lastFixLat : Api.lat());
        intent.putExtra("originLng", located ? lastFixLng : Api.lng());
        startActivity(intent);
    }

    private View chevron() {
        TextView tv = new TextView(this);
        tv.setText("›");
        tv.setTextColor(Tokens.color(this, "ink3"));
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * 到站牌。只有三种形态：即将（朱砂实心）/ 正常（墨绿浅底）/ 暂无（虚线）。
     * 数字与单位用 RelativeSizeSpan 形成 2:1 的层级，基线自动对齐。
     */
    private View etaPlate(String eta) {
        int state = etaState(eta);
        TextView tv = new TextView(this);
        tv.setGravity(Gravity.CENTER);
        // 固定宽高：同一列表里所有到站牌尺寸一致，右侧边缘构成一条竖线
        int plateW = Tokens.dp(this, "xt_plate_w");
        int plateH = Tokens.dp(this, "xt_plate_h");
        tv.setIncludeFontPadding(false);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Tokens.dp(this, "xt_r_plate"));

        if (state == ETA_NONE) {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), Tokens.color(this, "line_strong"), dp(3), dp(3));
            tv.setText("暂无");
            Tokens.textSizeFixed(tv, "xt_plate_word");
            tv.setTextColor(Tokens.color(this, "ink3"));
        } else if ("即将".equals(eta)) {
            bg.setColor(Tokens.color(this, "signal"));
            tv.setText("即将");
            Tokens.textSizeFixed(tv, "xt_plate_word");
            tv.setTextColor(Color.WHITE);
        } else {
            boolean urgent = state == ETA_URGENT;
            bg.setColor(urgent ? Tokens.color(this, "signal") : Tokens.color(this, "pine90"));
            String text = eta.endsWith("分") ? eta.substring(0, eta.length() - 1) : eta;
            SpannableStringBuilder sb = new SpannableStringBuilder(text);
            sb.append("分");
            int unitStart = sb.length() - 1;
            sb.setSpan(new RelativeSizeSpan(Tokens.isLarge() ? 0.40f : 0.50f), unitStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.NORMAL), unitStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sb);
            Tokens.textSizeFixed(tv, "xt_eta");
            tv.setTextColor(urgent ? Color.WHITE : Tokens.color(this, "pine"));
        }
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(plateW, plateH);
        lp.leftMargin = Tokens.dp(this, "xt_gap");
        tv.setLayoutParams(lp);
        return tv;
    }

    private int etaState(String eta) {
        if ("暂无".equals(eta) || eta.isEmpty()) {
            return ETA_NONE;
        }
        if ("即将".equals(eta)) {
            return ETA_URGENT;
        }
        int minutes = -1;
        try {
            String digits = eta.replace("分", "").trim();
            minutes = Integer.parseInt(digits);
        } catch (Exception ignored) {
        }
        return minutes >= 0 && minutes <= 3 ? ETA_URGENT : ETA_NORMAL;
    }

    // ---------------- 列表骨架 ----------------

    private void addSection(String title, String trailing) {
        list.addView(sectionRow(title, trailing, Tokens.dp(this, "xt_sec_gap")));
    }

    private LinearLayout sectionRow(String title, String trailing, int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        Tokens.textSize(titleView, "xt_label");
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Tokens.color(this, "ink"));
        titleView.setSingleLine(true);
        row.addView(titleView);

        View rule = new View(this);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(0, Math.max(1, dp(1)), 1f);
        ruleLp.leftMargin = dp(10);
        ruleLp.rightMargin = dp(10);
        rule.setLayoutParams(ruleLp);
        rule.setBackgroundColor(Tokens.color(this, "line_strong"));
        row.addView(rule);

        if (trailing != null && !trailing.isEmpty()) {
            TextView trailView = new TextView(this);
            trailView.setText(trailing);
            Tokens.textSize(trailView, "xt_caption");
            trailView.setTextColor(Tokens.color(this, "ink2"));
            trailView.setSingleLine(true);
            row.addView(trailView);
        }
        return row;
    }

    private int selectableBackground() {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private void addActionRow(String text, View.OnClickListener listener) {
        TextView action = new TextView(this);
        action.setText(text);
        Tokens.textSize(action, "xt_label");
        action.setTextColor(Tokens.color(this, "pine"));
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(16), dp(14), dp(16), dp(14));
        action.setMinHeight(Tokens.dp(this, "xt_touch"));
        action.setBackgroundResource(selectableBackground());
        action.setOnClickListener(listener);
        list.addView(action);
    }

    private void addBackRow() {
        TextView back = new TextView(this);
        back.setText("← 返回附近线路");
        Tokens.textSize(back, "xt_label");
        back.setTextColor(Tokens.color(this, "pine"));
        back.setPadding(dp(2), dp(10), dp(2), dp(10));
        back.setMinHeight(Tokens.dp(this, "xt_touch"));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchInput.setText("");
                searching = false;
                showNearby();
            }
        });
        list.addView(back);
    }

    private void addEmpty(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        Tokens.textSize(empty, "xt_label");
        empty.setTextColor(Tokens.color(this, "ink2"));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(40), dp(16), dp(40));
        list.addView(empty);
    }

    /** 空文案时整行隐藏，避免标题栏下方留下一条空行。 */
    private void setStatus(String text) {
        status.setText(text);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void addFooter(String text) {
        TextView footer = new TextView(this);
        footer.setText(text);
        Tokens.textSize(footer, "xt_caption");
        footer.setTextColor(Tokens.color(this, "ink3"));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(14), dp(8), dp(8));
        list.addView(footer);
    }

    // ---------------- 到站文案 ----------------

    private String etaText(JSONObject wrap) {
        if (wrap == null) {
            return "";
        }
        JSONArray states = wrap.optJSONArray("stnStates");
        if (states == null || states.length() == 0) {
            return "暂无";
        }
        JSONObject s0 = states.optJSONObject(0);
        if (s0 == null) {
            return "暂无";
        }
        int travelTime = s0.optInt("travelTime", -1);
        int value = s0.optInt("value", -1);
        if (travelTime == 0) {
            // 车辆已到站：不显示“暂无”，也不要冒充分钟数
            return "即将";
        }
        if (travelTime > 0 && travelTime < 60) {
            return "即将";
        }
        if (value > 0) {
            return value + "分";
        }
        if (travelTime > 0) {
            return Math.max(1, travelTime / 60) + "分";
        }
        // 与 etaSeconds() 一致：没有有效数据时返回“暂无”，避免显示“即将”却排在最后
        return "暂无";
    }

    private int etaSeconds(JSONObject wrap) {
        if (wrap == null) {
            return Integer.MAX_VALUE;
        }
        JSONArray states = wrap.optJSONArray("stnStates");
        if (states == null || states.length() == 0) {
            return Integer.MAX_VALUE;
        }
        JSONObject s0 = states.optJSONObject(0);
        if (s0 == null) {
            return Integer.MAX_VALUE;
        }
        int travelTime = s0.optInt("travelTime", -1);
        if (travelTime >= 0) {
            return travelTime;
        }
        int value = s0.optInt("value", -1);
        return value > 0 ? value * 60 : Integer.MAX_VALUE;
    }

    private String prettyDistance(int meters) {
        if (meters <= 0) {
            return "";
        }
        if (meters < 1000) {
            return meters + "米";
        }
        return String.format(Locale.CHINA, "%.1f公里", meters / 1000f);
    }

    /** 首末班「6:00–22:30」：小时不补零，和稿子一致。 */
    private String timeRange(String first, String last) {
        if (first.isEmpty() && last.isEmpty()) {
            return "";
        }
        if (first.isEmpty()) {
            return trimHour(last);
        }
        if (last.isEmpty()) {
            return trimHour(first);
        }
        return trimHour(first) + "–" + trimHour(last);
    }

    private String trimHour(String t) {
        return t.length() >= 5 && t.charAt(0) == '0' ? t.substring(1) : t;
    }

    /**
     * 号牌文案。两版都允许折行，断点固定在「路」之后，
     * 避免出现「605路A / 线」这种断法。
     */
    private String badgeText(String name) {
        String display = displayName(name);
        int road = display.indexOf('路');
        if (display.length() >= 5 && road > 0 && road < display.length() - 1) {
            return display.substring(0, road + 1) + "\n" + display.substring(road + 1);
        }
        return display;
    }

    private String displayName(String name) {
        return Fmt.displayName(name);
    }

    // ---------------- 通用 ----------------

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void clearList() {
        list.removeAllViews();
        // 容器已脱离列表，置空避免收藏/最近使用刷新到看不见的旧视图上
        favBox = null;
        recentBox = null;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            searchInput.setText("");
            searching = false;
            showNearby();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 字号切换会走 recreate()，这里保留搜索状态，避免结果被清空
        outState.putBoolean("searching", searching);
        outState.putString("keyword", currentKeyword);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        // 从设置页回来时字号可能已改变，需要按新令牌重建界面
        Tokens.load(this);
        if (Tokens.isLarge() != builtLarge) {
            recreate();
            return;
        }
        renderRecent();
        if (!searching && lastNearbyAt > 0
                && System.currentTimeMillis() - lastNearbyAt > HOME_STALE_MS) {
            // 返回前台时列表会重建，先记住当前位置，渲染完再放回去
            ScrollView scroll = findViewById(R.id.scroll);
            keepScrollY = scroll == null ? 0 : scroll.getScrollY();
            showNearby();
        }
        ui.removeCallbacks(autoRefresh);
        ui.postDelayed(autoRefresh, HOME_REFRESH_INTERVAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        ui.removeCallbacks(autoRefresh);
    }

    /** 页面可见时每 45 秒刷新一次到站时间，避免脚注长期停在同一时刻。 */
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!foreground || isFinishing()) {
                return;
            }
            if (!searching && !nearbyLoading) {
                showNearby();
            }
            ui.postDelayed(this, HOME_REFRESH_INTERVAL);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(fallbackNearby);
        if (locationListener != null && locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
    }
}
