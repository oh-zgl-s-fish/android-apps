package com.zglinus.recstt.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 自绘播放进度条。
 *
 * 为什么不用 SeekBar：SeekBar 的轨道由 progressDrawable 的 level 驱动裁剪，
 * 在自定义 LayerDrawable + setLayerInset 的组合下，轨道层经常被裁成不可见，
 * 屏幕上只剩一个 thumb 圆点（本项目真机反复出现过）。
 * wxcallrec 那次也是同一个坑，最后靠自绘解决 —— 这里直接走自绘，不再和框架较劲。
 *
 * 行为：轻点任意位置跳到那里（不在拖动），横向按住拖动连续 seek，松手回调。
 */
public final class SeekStrip extends View {

    public interface Listener {
        /** 用户落到某个位置（拖动中会连续回调）。 */
        void onMove(int posMs, boolean fromUser);

        /** 手指按下，开始拖动。 */
        void onStart(int posMs);

        /** 松手，落到 posMs。 */
        void onStop(int posMs);
    }

    private static final int TRACK_H = 4;     // dp
    private static final int THUMB_R = 8;     // dp
    private static final int TOUCH_H = 40;    // dp，触摸区高度

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int max = 1;
    private int progress = 0;
    private boolean dragging;
    private Listener listener;

    public SeekStrip(Context c) {
        super(c);
        // 轨道：未播放用实色灰（之前用 primary 20% alpha，在浅色面上根本看不见）
        bgPaint.setColor(0xFFE6E0E9);
        fgPaint.setColor(Ui.PRIMARY);
        thumbPaint.setColor(Ui.PRIMARY);
        thumbRing.setColor(0x40FFFFFF);
        thumbRing.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setMax(int ms) {
        max = Math.max(1, ms);
        invalidate();
    }

    public int getMax() {
        return max;
    }

    public void setProgress(int ms) {
        if (dragging) return;   // 拖动时别被播放进度抢走
        progress = Math.max(0, Math.min(max, ms));
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) dp(TOUCH_H));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float pad = dp(THUMB_R) + dp(2);
        float cy = h / 2f;
        float trackH = dp(TRACK_H);

        // 1) 整条底轨（这就是"后面没有条"缺的那根）
        rect.set(pad, cy - trackH / 2f, w - pad, cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, bgPaint);

        // 2) 已播放部分
        float ratio = max <= 0 ? 0f : (float) progress / max;
        float px = pad + (w - pad * 2) * ratio;
        if (px > pad) {
            rect.set(pad, cy - trackH / 2f, px, cy + trackH / 2f);
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, fgPaint);
        }

        // 3) 圆点
        float r = dp(THUMB_R);
        canvas.drawCircle(px, cy, r, thumbPaint);
        canvas.drawCircle(px, cy, r, thumbRing);
    }

    private int posAt(float x) {
        float pad = dp(THUMB_R) + dp(2);
        float w = getWidth() - pad * 2;
        if (w <= 0) return 0;
        float ratio = (x - pad) / w;
        if (ratio < 0) ratio = 0;
        if (ratio > 1) ratio = 1;
        return Math.round(ratio * max);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                progress = posAt(e.getX());
                invalidate();
                if (listener != null) listener.onStart(progress);
                if (listener != null) listener.onMove(progress, true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                progress = posAt(e.getX());
                invalidate();
                if (listener != null) listener.onMove(progress, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                progress = posAt(e.getX());
                invalidate();
                if (listener != null) listener.onStop(progress);
                // 轻点也当成一次完整的 start+stop，交给上层决定是否开播
                performClick();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
