package com.vortex.vpn.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.vortex.vpn.R;
import com.vortex.vpn.core.VpnState;

/**
 * The main power control: a breathing ring with a power glyph that changes colour with
 * the tunnel state. Drawn from scratch (no bitmaps) so it stays crisp and tiny.
 */
public class PowerView extends View {

    private static final int COLOR_ACCENT = 0xFF00E0A0;
    private static final int COLOR_ACCENT_DARK = 0xFF00A87A;
    private static final int COLOR_IDLE = 0xFF2A2A33;
    private static final int COLOR_STARTING = 0xFFFFB020;
    private static final int COLOR_ERROR = 0xFFFF4D5E;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringSoftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private int status = VpnState.STOPPED;
    private float sweepAngle;
    private float pulse;
    private ValueAnimator sweepAnimator;
    private ValueAnimator pulseAnimator;
    private boolean failed;

    public PowerView(Context context) {
        this(context, null);
    }

    public PowerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PowerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        ringSoftPaint.setStyle(Paint.Style.STROKE);
        ringSoftPaint.setStrokeWidth(dp(1));
        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeWidth(dp(3));
        sweepPaint.setStrokeCap(Paint.Cap.ROUND);
        sweepPaint.setColor(COLOR_ACCENT);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(3.2f));
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        bodyPaint.setStyle(Paint.Style.FILL);
    }

    public void setStatus(int status) {
        if (this.status == status) {
            return;
        }
        this.status = status;
        if (status == VpnState.STARTING || status == VpnState.STOPPING) {
            startSweep();
        } else {
            stopSweep();
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
        switch (status) {
            case VpnState.STARTED:
                return COLOR_ACCENT;
            case VpnState.STARTING:
            case VpnState.STOPPING:
                return COLOR_STARTING;
            default:
                return COLOR_IDLE;
        }
    }

    private void startSweep() {
        if (sweepAnimator != null && sweepAnimator.isRunning()) {
            return;
        }
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f);
        sweepAnimator.setDuration(1400);
        sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sweepAnimator.setInterpolator(new LinearInterpolator());
        sweepAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                sweepAngle = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        sweepAnimator.start();
        startPulse();
    }

    private void startPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1800);
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

    private void stopSweep() {
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
            sweepAnimator = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        sweepAngle = 0f;
        pulse = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSweep();
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

        float outerRadius = size / 2f - dp(2);
        float bodyRadius = outerRadius - dp(14);

        ringSoftPaint.setColor(withAlpha(color, 40));
        oval.set(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius);
        canvas.drawCircle(centerX, centerY, outerRadius, ringSoftPaint);
        ringSoftPaint.setColor(withAlpha(color, 20));
        canvas.drawCircle(centerX, centerY, outerRadius - dp(6), ringSoftPaint);

        if (pulse > 0f) {
            float pulseRadius = bodyRadius + dp(6) + pulse * dp(12);
            ringSoftPaint.setColor(withAlpha(color, (int) (70 * (1f - pulse))));
            canvas.drawCircle(centerX, centerY, pulseRadius, ringSoftPaint);
        }

        bodyPaint.setShader(new LinearGradient(
                centerX - bodyRadius, centerY - bodyRadius, centerX + bodyRadius, centerY + bodyRadius,
                status == VpnState.STARTED ? COLOR_ACCENT : withAlpha(color, 255),
                status == VpnState.STARTED ? COLOR_ACCENT_DARK : withAlpha(color, 190),
                Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, bodyRadius, bodyPaint);
        bodyPaint.setShader(null);

        ringPaint.setColor(withAlpha(color, 150));
        canvas.drawCircle(centerX, centerY, outerRadius, ringPaint);

        if (sweepAngle > 0f) {
            oval.set(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius);
            sweepPaint.setColor(color);
            canvas.drawArc(oval, sweepAngle, 90f, false, sweepPaint);
            canvas.drawArc(oval, sweepAngle + 180f, 45f, false, sweepPaint);
        }

        glyphPaint.setColor(status == VpnState.STARTED ? 0xFF04150F : withAlpha(color, 255));
        float glyphRadius = bodyRadius * 0.52f;
        oval.set(centerX - glyphRadius, centerY - glyphRadius, centerX + glyphRadius, centerY + glyphRadius);
        canvas.drawArc(oval, -60f, 300f, false, glyphPaint);
        canvas.drawLine(centerX, centerY - glyphRadius - dp(3), centerX, centerY - dp(2), glyphPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
