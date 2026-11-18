package com.xtbus;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 线路走向页：拉取轨迹与站点，交给 RouteView 用 Canvas 绘制。 */
public class RouteActivity extends Activity {

    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private RouteView routeView;
    private TextView hint;
    private TextView title;

    private String lineId;
    private String lineName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Api.init(getApplicationContext());
        Tokens.load(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route);

        lineId = getIntent().getStringExtra("lineId");
        lineName = getIntent().getStringExtra("lineName");

        routeView = findViewById(R.id.routeView);
        hint = findViewById(R.id.hint);
        title = findViewById(R.id.title);
        title.setText(prettyName(lineName) + " · 线路走向");
        hint.setText("正在加载线路走向…");

        TextView back = findViewById(R.id.back);
        Tokens.textSize(back, "xt_label");
        Tokens.textSize(title, "xt_appbar");
        Tokens.textSize(hint, "xt_caption");
        back.setGravity(Gravity.CENTER);
        back.setMinHeight(Tokens.dp(this, "xt_touch"));
        back.setMinWidth(Tokens.dp(this, "xt_touch"));

        // 顶栏高度与令牌一致，走向图从顶栏下方开始
        View appbar = findViewById(R.id.appbar);
        int barH = Tokens.dp(this, "xt_appbar_h");
        appbar.getLayoutParams().height = barH;
        android.widget.FrameLayout.LayoutParams routeLp =
                (android.widget.FrameLayout.LayoutParams) routeView.getLayoutParams();
        routeLp.topMargin = barH;
        routeView.setLayoutParams(routeLp);

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        load();
    }

    private void load() {
        if (lineId == null) {
            hint.setText("缺少线路参数");
            return;
        }
        Io.query(new Runnable() {
            @Override
            public void run() {
                try {
                    final Api.Result result = Api.lineRoute(lineId, false);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            render(result.data, result);
                        }
                    });
                } catch (final Api.ApiException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (e.kind == Api.ApiException.Kind.RISK
                                    || e.kind == Api.ApiException.Kind.COOLDOWN) {
                                hint.setText(e.getMessage());
                            } else if (e.kind == Api.ApiException.Kind.BUSINESS) {
                                hint.setText("加载失败：" + e.getMessage());
                            } else {
                                hint.setText("网络异常，请稍后重试");
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hint.setText("加载失败");
                        }
                    });
                }
            }
        });
    }

    private void render(JSONObject data, Api.Result result) {
        JSONArray route = data.optJSONArray("route");
        JSONArray stations = data.optJSONArray("stations");

        double[] lngs = new double[route == null ? 0 : route.length()];
        double[] lats = new double[lngs.length];
        for (int i = 0; i < lngs.length; i++) {
            JSONObject p = route.optJSONObject(i);
            lngs[i] = p == null ? 0 : p.optDouble("lng", 0);
            lats[i] = p == null ? 0 : p.optDouble("lat", 0);
        }

        double[] stLngs = new double[stations == null ? 0 : stations.length()];
        double[] stLats = new double[stLngs.length];
        String first = "";
        String last = "";
        for (int i = 0; i < stLngs.length; i++) {
            JSONObject s = stations.optJSONObject(i);
            stLngs[i] = s == null ? 0 : s.optDouble("lng", 0);
            stLats[i] = s == null ? 0 : s.optDouble("lat", 0);
            if (i == 0 && s != null) {
                first = s.optString("sn");
            }
            if (i == stLngs.length - 1 && s != null) {
                last = s.optString("sn");
            }
        }

        JSONObject line = data.optJSONObject("line");
        if (line != null) {
            String name = line.optString("name");
            if (!name.isEmpty()) {
                title.setText(prettyName(name) + " · 线路走向");
            }
        }

        routeView.setRoute(lngs, lats, stLngs, stLats, first, last);
        String cacheNote = result.fromCache ? " · 缓存" : "";
        if (!first.isEmpty() || !last.isEmpty()) {
            hint.setText(first + " → " + last + " · 共 " + stLngs.length + " 站" + cacheNote);
        } else {
            hint.setText("共 " + stLngs.length + " 站" + cacheNote);
        }
    }

    /** 线路名补“路”，与详情页一致。 */
    private String prettyName(String name) {
        return com.xtbus.Fmt.displayName(name);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }
}
