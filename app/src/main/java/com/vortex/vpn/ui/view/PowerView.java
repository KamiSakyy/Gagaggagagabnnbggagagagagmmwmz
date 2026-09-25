package com.vortex.vpn.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.vortex.vpn.core.VpnState;

/**
 * The main power control, drawn from scratch: a thin progress ring, a soft glow and a sphere with
 * the power glyph. Minimal by design - one accent colour, one hairline, no decoration. The ring
 * fills while the tunnel starts/stops and the sphere lights up when it is connected.
 */
public class PowerView extends View {

    private static final int COLOR_ACCENT = 0xFF00E0A0;
    private static final int COLOR_ACCENT_DEEP = 0xFF00B583;
    private static final int COLOR_IDLE = 0xFF2C2C36;
    private static final int COLOR_STARTING = 0xFFFFB020;
    private static final int COLOR_ERROR = 0xFFFF4D5E;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private int status = VpnState.STOPPED;
    private boolean failed;
    private float progress;
    private float pulse;
    private ValueAnimator progressAnimator;
    private ValueAnimator pulseAnimator;

    public PowerView(Context context) {
        this(context, null);
    }

    public PowerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PowerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(1.5f));
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(dp(3));
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(4));
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        spherePaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
    }

    public void setStatus(int status) {
        if (this.status == status) {
            return;
        }
        this.status = status;
        if (status == VpnState.STARTING || status == VpnState.STOPPING) {
            startRotation();
        } else {
            stopRotation();
            setProgress(status == VpnState.STARTED ? 1f : 0f);
        }
        invalidate();
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
        invalidate();
    }

    private int activeColor() {
        if (failed) {
            return COLOR_ERROR;
        }
        if (status == VpnState.STARTED) {
            return COLOR_ACCENT;
        }
        if (status == VpnState.STARTING || status == VpnState.STOPPING) {
            return COLOR_STARTING;
        }
        return COLOR_IDLE;
    }

    /** Animated ring fill: 0 = empty, 1 = full circle. */
    public void setProgress(float target) {
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        progressAnimator = ValueAnimator.ofFloat(progress, target);
        progressAnimator.setDuration(420);
        progressAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                progress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        progressAnimator.start();
    }

    /** The ring spins while the tunnel is being (dis)connected. */
    private void startRotation() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1500);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                pulse = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pulseAnimator.start();
    }

    private void stopRotation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        pulse = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRotation();
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        float size = Math.min(width, height);
        float centerX = getPaddingLeft() + width / 2f;
        float centerY = getPaddingTop() + height / 2f;
        int color = activeColor();

        float radius = size / 2f - dp(6);
        float sphereRadius = radius - dp(20);

        // Soft glow behind the sphere - the only "decorative" element, kept very subtle.
        int glowAlpha = failed ? 90 : status == VpnState.STARTED ? 70 : 34;
        glowPaint.setShader(new RadialGradient(centerX, centerY, radius * 1.05f,
                new int[]{withAlpha(color, glowAlpha), withAlpha(color, glowAlpha / 4), Color.TRANSPARENT},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, radius * 1.05f, glowPaint);
        glowPaint.setShader(null);

        // Hairline track plus the progress arc.
        trackPaint.setColor(withAlpha(color, status == VpnState.STOPPED && !failed ? 70 : 45));
        canvas.drawCircle(centerX, centerY, radius, trackPaint);

        if (status == VpnState.STARTING || status == VpnState.STOPPING) {
            float start = pulse * 360f;
            oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
            progressPaint.setColor(color);
            canvas.drawArc(oval, start, 110f, false, progressPaint);
            canvas.drawArc(oval, start + 180f, 40f, false, progressPaint);
        } else if (progress > 0.001f) {
            oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
            progressPaint.setColor(color);
            canvas.drawArc(oval, -90f, 360f * Math.min(1f, progress), false, progressPaint);
        }

        // Sphere: a flat, slightly darker disc with a hairline edge - "minimal", not glossy.
        spherePaint.setShader(new RadialGradient(centerX, centerY - sphereRadius * 0.3f,
                sphereRadius * 1.6f,
                new int[]{withAlpha(color, status == VpnState.STARTED ? 62 : 40),
                        withAlpha(color, status == VpnState.STARTED ? 26 : 16),
                        withAlpha(COLOR_IDLE, 0)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, sphereRadius, spherePaint);
        spherePaint.setShader(null);

        trackPaint.setColor(withAlpha(color, status == VpnState.STARTED || failed ? 150 : 90));
        canvas.drawCircle(centerX, centerY, sphereRadius, trackPaint);

        // Power glyph.
        glyphPaint.setColor(status == VpnState.STARTED ? COLOR_ACCENT
                : failed ? COLOR_ERROR : withAlpha(color, 210));
        float glyphRadius = sphereRadius * 0.46f;
        oval.set(centerX - glyphRadius, centerY - glyphRadius, centerX + glyphRadius, centerY + glyphRadius);
        canvas.drawArc(oval, -62f, 304f, false, glyphPaint);
        canvas.drawLine(centerX, centerY - glyphRadius - dp(4), centerX, centerY - dp(1), glyphPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
