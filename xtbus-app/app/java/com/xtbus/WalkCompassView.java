package com.xtbus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 步行导航指针。
 *
 * 只在正方形卡片里画一支箭头，指向「接下来该走的方向」。
 * heading 为手机顶部朝向的方位角（北=0、顺时针），NaN 表示拿不到方向感应；
 * target  为行进方向的方位角，NaN 表示还没有路线。
 */
public class WalkCompassView extends View {

    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needle = new Path();
    /** 箭头来自 arrow-up-narrow.svg（viewBox 512，窄头 40°，圆头描边）。 */
    private static final float VB_CENTER = 256f;
    /** 箭头含描边后的实际高度（viewBox 坐标），用来把它缩放到控件内。 */
    private static final float VB_HEIGHT = 432f;
    private static final float VB_STROKE = 62f;

    private float heading = Float.NaN;
    private float target = Float.NaN;
    private boolean arrived;

    public WalkCompassView(Context c) {
        this(c, null);
    }

    public WalkCompassView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(VB_STROKE);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setStrokeJoin(Paint.Join.ROUND);
        needlePaint.setColor(Tokens.color(getContext(), "pine"));

        // 箭头形状：折角在 (256,71)，两条腿落在 y=230.3，竖线一直拉到 441
        needle.moveTo(122.3f, 230.3f);
        needle.lineTo(VB_CENTER, 71f);
        needle.lineTo(389.7f, 230.3f);
        needle.moveTo(VB_CENTER, 71f);
        needle.lineTo(VB_CENTER, 441f);
    }

    public void setHeading(float deg) {
        if (Float.isNaN(deg)) {
            return;
        }
        heading = deg;
        invalidate();
    }

    public void setTargetBearing(float deg) {
        if (deg < 0 || Float.isNaN(deg)) {
            return;
        }
        target = deg;
        invalidate();
    }

    public void setArrived(boolean value) {
        if (arrived == value) {
            return;
        }
        arrived = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || arrived || Float.isNaN(target)) {
            return;
        }

        // 指针：屏幕角度 = 行进方向 - 手机朝向。
        // 拿不到方向感应时盘面北向不再可信，指针退回「正上方」，
        // 此时地图已按路线朝向摆好，正上方就是该走的方向。
        float arrow = Float.isNaN(heading) ? 0f : target - heading;
        float cx = w / 2f;
        float cy = h / 2f;
        float scale = (Math.min(w, h) * 0.64f) / VB_HEIGHT;
        canvas.save();
        canvas.rotate(arrow, cx, cy);
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        canvas.translate(-VB_CENTER, -VB_CENTER);
        canvas.drawPath(needle, needlePaint);
        canvas.restore();
    }
}
