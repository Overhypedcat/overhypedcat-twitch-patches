package app.morphed.extension.twitch.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** An on/off switch: a squircle track and a round thumb that slides, anti-aliased, no shadows or glow. */
public final class Toggle extends View {
    public interface Listener {
        void toggled(boolean on);
    }

    private final Theme theme;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trackPath = new Path();
    private final RectF bounds = new RectF();
    private boolean on;
    private float position; // 0 off .. 1 on
    private Listener listener;

    public Toggle(Context context, Theme theme, boolean on) {
        super(context);
        this.theme = theme;
        this.on = on;
        position = on ? 1 : 0;
        thumb.setColor(Color.WHITE);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> setOn(!this.on, true));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean value, boolean fromUser) {
        if (value == on) return;
        on = value;
        ValueAnimator animator = ValueAnimator.ofFloat(position, on ? 1 : 0);
        animator.setDuration(160);
        animator.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
        if (fromUser && listener != null) listener.toggled(on);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(theme.dp(48), theme.dp(28));
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff, aa = a >>> 24;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff, ba = b >>> 24;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float trackHeight = theme.dpf(24);
        float top = (h - trackHeight) / 2;
        bounds.set(theme.dpf(1), top, w - theme.dpf(1), top + trackHeight);
        track.setColor(mix(theme.trackOff, Theme.PURPLE, position));
        Squircle.path(bounds, trackHeight / 2, trackPath);
        canvas.drawPath(trackPath, track);
        float radius = trackHeight / 2 - theme.dpf(3);
        float left = bounds.left + theme.dpf(3) + radius;
        float right = bounds.right - theme.dpf(3) - radius;
        canvas.drawCircle(left + (right - left) * position, h / 2, radius, thumb);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(on);
    }
}
