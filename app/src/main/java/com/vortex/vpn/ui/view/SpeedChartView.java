package com.vortex.vpn.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Rolling traffic graph (download above the axis, upload below). */
public class SpeedChartView extends View {

    private static final int POINTS = 60;

    private final Paint downPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint downFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint upPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint upFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final List<Float> down = new ArrayList<>();
    private final List<Float> up = new ArrayList<>();

    public SpeedChartView(Context context) {
        this(context, null);
    }

    public SpeedChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpeedChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        downPaint.setStyle(Paint.Style.STROKE);
        downPaint.setStrokeWidth(dp(1.6f));
        downPaint.setColor(0xFF00E0A0);
        upPaint.setStyle(Paint.Style.STROKE);
        upPaint.setStrokeWidth(dp(1.6f));
        upPaint.setColor(0xFF7C5CFF);
        downFill.setStyle(Paint.Style.FILL);
        upFill.setStyle(Paint.Style.FILL);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(0x14FFFFFF);
    }

    public void push(long downlink, long uplink) {
        down.add((float) Math.max(0, downlink));
        up.add((float) Math.max(0, uplink));
        while (down.size() > POINTS) {
            down.remove(0);
        }
        while (up.size() > POINTS) {
            up.remove(0);
        }
        invalidate();
    }

    public void reset() {
        down.clear();
        up.clear();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        downFill.setShader(new LinearGradient(0, 0, 0, h / 2f, 0x5500E0A0, 0x0000E0A0, Shader.TileMode.CLAMP));
        upFill.setShader(new LinearGradient(0, h, 0, h / 2f, 0x407C5CFF, 0x007C5CFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float middle = height / 2f;
        float padding = dp(2);

        canvas.drawLine(0, middle, width, middle, gridPaint);
        canvas.drawLine(0, middle / 2f, width, middle / 2f, gridPaint);
        canvas.drawLine(0, middle + middle / 2f, width, middle + middle / 2f, gridPaint);

        float maxDown = 1f;
        for (Float value : down) {
            maxDown = Math.max(maxDown, value);
        }
        float maxUp = 1f;
        for (Float value : up) {
            maxUp = Math.max(maxUp, value);
        }
        float step = width / (POINTS - 1f);

        drawSeries(canvas, down, maxDown, step, padding, middle - padding, true);
        drawSeries(canvas, up, maxUp, step, padding, middle + padding, false);
    }

    private void drawSeries(Canvas canvas, List<Float> values, float max, float step,
                            float top, float bottom, boolean download) {
        if (values.size() < 2) {
            return;
        }
        int offset = values.size() - 1;
        float scale = Math.abs(bottom - top) / max;
        path.reset();
        path.moveTo(0, bottom);
        for (int i = 0; i < values.size(); i++) {
            float x = (offset - (values.size() - 1 - i)) * step;
            float value = values.get(i) * scale;
            float y = download ? bottom - Math.min(value, Math.abs(bottom - top)) : bottom + Math.min(value, Math.abs(bottom - top));
            if (i == 0) {
                path.lineTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        Path fill = new Path(path);
        fill.lineTo((values.size() - 1) * step, bottom);
        fill.close();
        canvas.drawPath(fill, download ? downFill : upFill);
        canvas.drawPath(path, download ? downPaint : upPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int alpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
