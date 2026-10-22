package com.xiagu.bp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Diverging bar around the neutral score of 50: green to the right when the factor helps,
 * red to the left when it hurts. ±30 points fill a full half, so typical 40–70 scores stay legible.
 */
public final class MetricBar extends View {
    private static final float FULL_SCALE = 30f;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint good = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int value = 50;

    public MetricBar(Context context) {
        this(context, null);
    }

    public MetricBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setColor(Ui.color(context, R.color.seam));
        tick.setColor(Ui.color(context, R.color.dusk));
        good.setColor(Ui.color(context, R.color.edge));
        bad.setColor(Ui.color(context, R.color.enemy));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setValue(int score) {
        value = Math.max(0, Math.min(100, score));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
            ? MeasureSpec.getSize(heightMeasureSpec)
            : Ui.dp(getContext(), 8);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float centerY = getHeight() / 2f;
        float barHeight = Ui.dp(getContext(), 4);
        float radius = barHeight / 2f;
        float top = centerY - barHeight / 2f;
        float bottom = centerY + barHeight / 2f;
        canvas.drawRoundRect(0, top, width, bottom, radius, radius, track);

        float middle = width / 2f;
        float delta = value - 50f;
        float length = middle * Math.min(1f, Math.abs(delta) / FULL_SCALE);
        if (delta > 0) canvas.drawRoundRect(middle, top, middle + length, bottom, radius, radius, good);
        else if (delta < 0) canvas.drawRoundRect(middle - length, top, middle, bottom, radius, radius, bad);

        float tickHalfWidth = Math.max(1f, Ui.dp(getContext(), 1)) / 2f;
        float tickHalfHeight = Ui.dp(getContext(), 4);
        canvas.drawRect(middle - tickHalfWidth, centerY - tickHalfHeight, middle + tickHalfWidth, centerY + tickHalfHeight, tick);
    }
}
