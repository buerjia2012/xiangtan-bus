package com.xtbus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** 用 Canvas 绘制线路走向折线，不依赖地图 SDK。 */
public class RouteView extends View {

    private double[] lngs = new double[0];
    private double[] lats = new double[0];
    private double[] stLngs = new double[0];
    private double[] stLats = new double[0];
    private String startName = "";
    private String endName = "";

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public RouteView(Context context) {
        super(context);
        init();
    }

    public RouteView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(4));
        linePaint.setColor(Tokens.color(getContext(), "pine"));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        startPaint.setStyle(Paint.Style.FILL);
        startPaint.setColor(Tokens.color(getContext(), "pine"));
        endPaint.setStyle(Paint.Style.FILL);
        endPaint.setColor(Tokens.color(getContext(), "signal"));

        stopPaint.setStyle(Paint.Style.FILL);
        stopPaint.setColor(Tokens.color(getContext(), "pine70"));

        // 走向页也跟随 App 内字号版本
        labelPaint.setColor(Tokens.color(getContext(), "ink"));
        labelPaint.setTextSize(Tokens.sp(getContext(), "xt_caption"));
        labelPaint.setAntiAlias(true);

        emptyPaint.setColor(Tokens.color(getContext(), "ink3"));
        emptyPaint.setTextSize(Tokens.sp(getContext(), "xt_body"));
        emptyPaint.setAntiAlias(true);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRoute(double[] lngs, double[] lats,
                         double[] stLngs, double[] stLats,
                         String startName, String endName) {
        this.lngs = lngs == null ? new double[0] : lngs;
        this.lats = lats == null ? new double[0] : lats;
        this.stLngs = stLngs == null ? new double[0] : stLngs;
        this.stLats = stLats == null ? new double[0] : stLats;
        this.startName = startName == null ? "" : startName;
        this.endName = endName == null ? "" : endName;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (lngs.length < 2) {
            canvas.drawText("暂无线路轨迹数据", w / 2f, h / 2f, emptyPaint);
            return;
        }

        double centerLat = lats[0];
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double cosLat = Math.cos(Math.toRadians(centerLat));
        for (int i = 0; i < lngs.length; i++) {
            double x = lngs[i] * cosLat;
            double y = lats[i];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        // 最小跨度保护：所有点重合时不能把 scale 放大成 Infinity
        double spanX = Math.max(maxX - minX, 1e-4);
        double spanY = Math.max(maxY - minY, 1e-4);
        int pad = (int) dp(28);
        float scale = (float) Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        float offsetX = (float) (w / 2f - (minX + maxX) / 2 * scale);
        float offsetY = (float) (h / 2f + (minY + maxY) / 2 * scale);

        path.reset();
        for (int i = 0; i < lngs.length; i++) {
            float px = (float) (lngs[i] * cosLat * scale + offsetX);
            float py = (float) (-lats[i] * scale + offsetY);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        canvas.drawPath(path, linePaint);

        // 站点散点：站点坐标与轨迹基准可能略有偏差，画布外的点直接跳过，避免误导
        if (stLngs.length > 0) {
            // 长线路按步长抽样，避免密集重叠，也不至于整条线一个站都不画
            int step = Math.max(1, stLngs.length / 80);
            for (int i = 0; i < stLngs.length && i < stLats.length; i += step) {
                if (stLngs[i] == 0 && stLats[i] == 0) {
                    continue;
                }
                float px = (float) (stLngs[i] * cosLat * scale + offsetX);
                float py = (float) (-stLats[i] * scale + offsetY);
                if (px < 0 || px > w || py < 0 || py > h) {
                    continue;
                }
                canvas.drawCircle(px, py, dp(2.5f), stopPaint);
            }
        }

        // 站点坐标与轨迹来自不同坐标基准（偏差数百米），这里只按轨迹首尾标注，避免误导
        float sx = (float) (lngs[0] * cosLat * scale + offsetX);
        float sy = (float) (-lats[0] * scale + offsetY);
        canvas.drawCircle(sx, sy, dp(5), startPaint);
        drawLabel(canvas, startName, sx, sy, w, true);

        int last = lngs.length - 1;
        float ex = (float) (lngs[last] * cosLat * scale + offsetX);
        float ey = (float) (-lats[last] * scale + offsetY);
        canvas.drawCircle(ex, ey, dp(5), endPaint);
        drawLabel(canvas, endName, ex, ey, w, false);
    }

    private void drawLabel(Canvas canvas, String text, float x, float y, int width, boolean above) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float tw = labelPaint.measureText(text);
        float tx = Math.max(dp(6), Math.min(x - tw / 2, width - tw - dp(6)));
        float ty = above ? y - dp(10) : y + dp(18);
        canvas.drawText(text, tx, ty, labelPaint);
    }

    private float dp(float value) {
        return getResources().getDisplayMetrics().density * value;
    }
}
