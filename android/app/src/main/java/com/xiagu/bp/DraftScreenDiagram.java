package com.xiagu.bp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Schematic of the in-game landscape draft screen: BAN rows on top, five pick slots on each side,
 * the hero browser in the middle (never sampled) and the floating BP button. It mirrors the
 * "我方在屏幕左侧/右侧" setting so the choice is visible, not abstract.
 */
public final class DraftScreenDiagram extends View {
    private static final float DESIGN_WIDTH = 329f;
    private static final float DESIGN_HEIGHT = 164f;

    private final Paint frameFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint banFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint allyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint allyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enemyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enemyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint browserFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean ourSideLeft = true;

    public DraftScreenDiagram(Context context) {
        this(context, null);
    }

    public DraftScreenDiagram(Context context, AttributeSet attrs) {
        super(context, attrs);
        frameFill.setColor(Ui.color(context, R.color.deck_sunk));
        frameStroke.setStyle(Paint.Style.STROKE);
        frameStroke.setStrokeWidth(1f);
        frameStroke.setColor(Ui.color(context, R.color.seam));
        banFill.setColor(Ui.color(context, R.color.seam));
        allyFill.setColor(Ui.color(context, R.color.ally_soft));
        allyStroke.setStyle(Paint.Style.STROKE);
        allyStroke.setStrokeWidth(1.2f);
        allyStroke.setColor(Ui.color(context, R.color.ally));
        enemyFill.setColor(Ui.color(context, R.color.enemy_soft));
        enemyStroke.setStyle(Paint.Style.STROKE);
        enemyStroke.setStrokeWidth(1.2f);
        enemyStroke.setColor(Ui.color(context, R.color.enemy));
        browserFill.setColor(0xFF1A2238);
        tileFill.setColor(0xFF222C47);
        bubbleFill.setColor(Ui.color(context, R.color.night));
        bubbleStroke.setStyle(Paint.Style.STROKE);
        bubbleStroke.setStrokeWidth(2f);
        bubbleStroke.setColor(Ui.color(context, R.color.crown));
        bubbleText.setColor(Ui.color(context, R.color.frost));
        bubbleText.setTextAlign(Paint.Align.CENTER);
        bubbleText.setTextSize(11f);
        bubbleText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        label.setTextSize(11f);
        updateDescription();
    }

    void setOurSideLeft(boolean value) {
        if (ourSideLeft == value) return;
        ourSideLeft = value;
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(ourSideLeft
            ? "示意图：游戏选将界面中，我方在屏幕左侧，敌方在右侧，顶部为 BAN 位"
            : "示意图：游戏选将界面中，我方在屏幕右侧，敌方在左侧，顶部为 BAN 位");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * DESIGN_HEIGHT / DESIGN_WIDTH);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = getWidth() / DESIGN_WIDTH;
        canvas.save();
        canvas.scale(scale, scale);

        canvas.drawRoundRect(0.5f, 0.5f, 328.5f, 146.5f, 14f, 14f, frameFill);
        canvas.drawRoundRect(0.5f, 0.5f, 328.5f, 146.5f, 14f, 14f, frameStroke);

        for (int i = 0; i < 5; i++) {
            canvas.drawRoundRect(16 + 14 * i, 11, 27 + 14 * i, 22, 2.5f, 2.5f, banFill);
            canvas.drawRoundRect(246 + 14 * i, 11, 257 + 14 * i, 22, 2.5f, 2.5f, banFill);
        }
        drawColumn(canvas, 16, ourSideLeft);
        drawColumn(canvas, 269, !ourSideLeft);

        canvas.drawRoundRect(92, 34, 237, 132, 6, 6, browserFill);
        for (int row = 0; row < 4; row++) {
            int columns = row == 3 ? 3 : 6;
            for (int column = 0; column < columns; column++) {
                float left = 100 + 20 * column;
                float top = 42 + 22 * row;
                canvas.drawRoundRect(left, top, left + 16, top + 16, 3, 3, tileFill);
            }
        }

        canvas.drawCircle(246, 84, 13, bubbleFill);
        canvas.drawCircle(246, 84, 13, bubbleStroke);
        canvas.drawText("BP", 246, 88, bubbleText);

        drawLabel(canvas, "我方", ourSideLeft ? 16 : 313, ourSideLeft ? Paint.Align.LEFT : Paint.Align.RIGHT, R.color.ally, true);
        drawLabel(canvas, "敌方", ourSideLeft ? 313 : 16, ourSideLeft ? Paint.Align.RIGHT : Paint.Align.LEFT, R.color.enemy, true);
        drawLabel(canvas, "中间英雄列表不参与识别", 164.5f, Paint.Align.CENTER, R.color.dusk, false);
        canvas.restore();
    }

    private void drawColumn(Canvas canvas, float left, boolean ours) {
        Paint fill = ours ? allyFill : enemyFill;
        Paint stroke = ours ? allyStroke : enemyStroke;
        for (int i = 0; i < 5; i++) {
            float top = 30 + 22 * i;
            canvas.drawRoundRect(left, top, left + 44, top + 18, 4, 4, fill);
            canvas.drawRoundRect(left, top, left + 44, top + 18, 4, 4, stroke);
        }
    }

    private void drawLabel(Canvas canvas, String text, float x, Paint.Align align, int colorRes, boolean bold) {
        label.setTextAlign(align);
        label.setColor(Ui.color(getContext(), colorRes));
        label.setFakeBoldText(bold);
        canvas.drawText(text, x, 160, label);
    }
}
