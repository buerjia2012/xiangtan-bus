package com.xtbus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 设置页。只服务标准字号版本：大字版入口仍是顶栏的 Aa。
 * 设置项刻意保持很少——纯查询 App 的可配置项本就不多。
 */
public class SettingsActivity extends Activity {

    private TextView tFont, tPerm, tCache, tVersion;
    private TextView vPerm, vCache, vVersion;
    private Switch switchLarge;
    private TextView chevPerm, chevCache;
    private LinearLayout groupDisplay, groupLocate, groupData, groupAbout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Tokens.load(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        tFont = findViewById(R.id.tFont);
        tPerm = findViewById(R.id.tPerm);
        tCache = findViewById(R.id.tCache);
        tVersion = findViewById(R.id.tVersion);
        vPerm = findViewById(R.id.vPerm);
        vCache = findViewById(R.id.vCache);
        vVersion = findViewById(R.id.vVersion);
        switchLarge = findViewById(R.id.switchLarge);
        switchLarge.setChecked(Tokens.isLarge());
        switchLarge.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (Tokens.isLarge() == isChecked) {
                    return;
                }
                Tokens.setLarge(SettingsActivity.this, isChecked);
                recreate();
            }
        });
        chevPerm = findViewById(R.id.chevPerm);
        chevCache = findViewById(R.id.chevCache);
        groupDisplay = findViewById(R.id.groupDisplay);
        groupLocate = findViewById(R.id.groupLocate);
        groupData = findViewById(R.id.groupData);
        groupAbout = findViewById(R.id.groupAbout);

        applyTokens();

        findViewById(R.id.backBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.rowPerm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppSettings();
            }
        });
        findViewById(R.id.rowCache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearCache();
            }
        });
        vVersion.setText(versionText());
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置页返回时权限可能已变化
        refreshState();
    }

    // ---------------- 令牌落地 ----------------

    private void applyTokens() {
        int gutter = Tokens.dp(this, "xt_gutter");
        int cardPad = Tokens.dp(this, "xt_card_pad");
        int rowH = Tokens.dp(this, "xt_row_h");
        int secGap = Tokens.dp(this, "xt_sec_gap");

        View appbar = findViewById(R.id.appbar);
        appbar.getLayoutParams().height = Tokens.dp(this, "xt_appbar_h");
        appbar.setPadding(Tokens.dp(this, "xt_gap") / 2, 0, gutter, 0);
        TextView title = findViewById(R.id.settingsTitle);
        Tokens.textSize(title, "xt_appbar");
        title.setTextColor(Tokens.color(this, "ink"));
        TextView back = findViewById(R.id.backBtn);
        back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                Tokens.sp(this, "xt_appbar") * 1.1f);
        back.setTextColor(Tokens.color(this, "ink"));

        View body = findViewById(R.id.settingsBody);
        body.setPadding(gutter, 0, gutter, Tokens.dp(this, "xt_group_gap"));

        int[] groupTitles = {R.id.gDisplay, R.id.gLocate, R.id.gData, R.id.gAbout};
        for (int i = 0; i < groupTitles.length; i++) {
            TextView g = findViewById(groupTitles[i]);
            Tokens.textSize(g, "xt_caption");
            g.setTextColor(Tokens.color(this, "ink3"));
            g.setLetterSpacing(0.06f);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) g.getLayoutParams();
            lp.topMargin = i == 0 ? Tokens.dp(this, "xt_group_gap") : secGap;
            lp.bottomMargin = Tokens.dp(this, "xt_gap");
            g.setLayoutParams(lp);
        }

        int[] groups = {R.id.groupDisplay, R.id.groupLocate, R.id.groupData, R.id.groupAbout};
        for (int i = 0; i < groups.length; i++) {
            LinearLayout g = findViewById(groups[i]);
            g.setBackground(cardBg());
            g.setClipToOutline(true);
            g.setPadding(0, 0, 0, 0);
        }

        int[] rows = {R.id.rowFont, R.id.rowPerm, R.id.rowCache, R.id.rowVersion};
        for (int i = 0; i < rows.length; i++) {
            View row = findViewById(rows[i]);
            row.setMinimumHeight(rowH);
            row.setPadding(cardPad, 0, cardPad, 0);
            row.setClickable(true);
        }
        // 版本行没有对应动作，去掉点击态，避免点了只有涟漪没有响应
        findViewById(R.id.rowVersion).setClickable(false);

        TextView[] titles = {tFont, tPerm, tCache, tVersion};
        for (int i = 0; i < titles.length; i++) {
            Tokens.textSize(titles[i], "xt_body");
            titles[i].setTextColor(Tokens.color(this, "ink"));
        }
        TextView[] values = {vPerm, vCache, vVersion};
        for (int i = 0; i < values.length; i++) {
            Tokens.textSize(values[i], "xt_body_s");
            values[i].setTextColor(Tokens.color(this, "ink2"));
        }
        TextView[] chevs = {chevPerm, chevCache};
        for (int i = 0; i < chevs.length; i++) {
            Tokens.textSize(chevs[i], "xt_body");
            chevs[i].setTextColor(Tokens.color(this, "ink3"));
            chevs[i].setPadding(Tokens.dp(this, "xt_gap") / 2, 0, 0, 0);
        }

        // 大字模式开关：关=标准版，开=大字版
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        switchLarge.setTrackTintList(new ColorStateList(states,
                new int[]{Tokens.color(this, "pine"), Tokens.color(this, "line_strong")}));
        switchLarge.setThumbTintList(new ColorStateList(states,
                new int[]{0xFFFFFFFF, Tokens.color(this, "card")}));

        // 关于组两行之间加一条分割线
        if (groupAbout.getChildCount() == 2) {
            View divider = new View(this);
            divider.setBackgroundColor(Tokens.color(this, "line"));
            groupAbout.addView(divider, 1,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        }

        // 定位组只有一行，不需要分割线
        groupLocate.setVisibility(View.VISIBLE);
        groupData.setVisibility(View.VISIBLE);
        groupDisplay.setVisibility(View.VISIBLE);
    }

    private Drawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Tokens.color(this, "card"));
        d.setCornerRadius(Tokens.dp(this, "xt_r_card"));
        d.setStroke(Math.max(1, dp(1)), Tokens.color(this, "line"));
        return d;
    }

    // ---------------- 行为 ----------------

    private void refreshState() {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean granted = fine || coarse;
        vPerm.setText(granted ? (fine ? "已允许" : "大致位置") : "未授权");
        vPerm.setTextColor(Tokens.color(this, granted ? "ink2" : "signal"));
        vCache.setText(cacheSizeText());
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    private File cacheDir() {
        return new File(getCacheDir(), "api");
    }

    private String cacheSizeText() {
        long total = 0;
        File dir = cacheDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                total += files[i].length();
            }
        }
        if (total < 1024) {
            return total / 1024 + ".0 KB";
        }
        return String.format(java.util.Locale.US, "%.1f KB", total / 1024.0);
    }

    private void clearCache() {
        File dir = cacheDir();
        File[] files = dir.listFiles();
        int removed = 0;
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].delete()) {
                    removed++;
                }
            }
        }
        vCache.setText(cacheSizeText());
        Toast.makeText(this, removed > 0 ? "已清除 " + removed + " 个缓存文件" : "暂无缓存",
                Toast.LENGTH_SHORT).show();
    }

    private String versionText() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
