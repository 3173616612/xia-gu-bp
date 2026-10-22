package com.xiagu.bp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * Rounded hero portrait with optional team ring, tier badge and BAN treatment (greyscale + red slash).
 * Shows the hero's first character until the portrait is decoded, and a dashed outline for empty slots.
 */
public final class HeroAvatarView extends View {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint initialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();
    private final RectF ringRect = new RectF();
    private final RectF badgeRect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final ColorMatrixColorFilter bannedFilter;

    private Bitmap portrait;
    private BitmapShader shader;
    private int token;
    private int heroId = -1;
    private String initial = "";
    private String tier = "";
    private boolean showTier;
    private boolean banned;
    private boolean dashedWhenEmpty = true;
    private int ringColor;
    private float ringWidth;
    private float radius;
    private int badgeOutlineColor;

    public HeroAvatarView(Context context) {
        this(context, null);
    }

    public HeroAvatarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        radius = Ui.dp(context, 8);
        fillPaint.setColor(Ui.color(context, R.color.deck_2));
        ringPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(Ui.dp(context, 1.2f));
        dashPaint.setColor(Ui.color(context, R.color.seam_2));
        float dash = Ui.dp(context, 3.5f);
        dashPaint.setPathEffect(new DashPathEffect(new float[]{dash, dash * 0.8f}, 0));
        initialPaint.setColor(Ui.color(context, R.color.mist));
        initialPaint.setTextAlign(Paint.Align.CENTER);
        initialPaint.setFakeBoldText(true);
        slashPaint.setColor(Ui.color(context, R.color.enemy));
        slashPaint.setStrokeWidth(Ui.dp(context, 2));
        slashPaint.setStrokeCap(Paint.Cap.ROUND);
        badgeStroke.setStyle(Paint.Style.STROKE);
        badgeStroke.setStrokeWidth(Math.max(1, Ui.dp(context, 1)));
        badgeText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, context.getResources().getDisplayMetrics()));
        badgeOutlineColor = Ui.color(context, R.color.deck);
        ColorMatrix greyscale = new ColorMatrix();
        greyscale.setSaturation(0);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.62f, 0.62f, 0.62f, 1f);
        greyscale.postConcat(dim);
        bannedFilter = new ColorMatrixColorFilter(greyscale);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Resets the view for a new hero and returns the token the async portrait load must present. */
    int bindHero(BpModels.Hero hero) {
        token++;
        heroId = hero == null ? -1 : hero.id;
        initial = hero == null || hero.name.isEmpty() ? "" : hero.name.substring(0, 1);
        tier = Ui.tier(hero);
        portrait = null;
        shader = null;
        invalidate();
        return token;
    }

    void setPortrait(Bitmap bitmap, int bindToken) {
        if (bindToken != token) return;
        portrait = bitmap;
        shader = bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        updateShaderMatrix();
        invalidate();
    }

    void setCornerRadiusDp(float value) {
        radius = Ui.dp(getContext(), value);
        invalidate();
    }

    void setRing(int color, float widthDp) {
        ringColor = color;
        ringWidth = color == 0 ? 0 : Ui.dp(getContext(), widthDp);
        updateGeometry();
        invalidate();
    }

    void setShowTier(boolean value) {
        showTier = value;
        invalidate();
    }

    void setBanned(boolean value) {
        banned = value;
        invalidate();
    }

    void setDashedWhenEmpty(boolean value) {
        dashedWhenEmpty = value;
        invalidate();
    }

    void setBadgeOutlineColor(int color) {
        badgeOutlineColor = color;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateGeometry();
    }

    private void updateGeometry() {
        float inset = ringWidth;
        imageRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        ringRect.set(ringWidth / 2f, ringWidth / 2f, getWidth() - ringWidth / 2f, getHeight() - ringWidth / 2f);
        initialPaint.setTextSize(Math.max(1, imageRect.height() * 0.42f));
        updateShaderMatrix();
    }

    private void updateShaderMatrix() {
        if (shader == null || portrait == null || imageRect.width() <= 0 || imageRect.height() <= 0) return;
        float scale = Math.max(imageRect.width() / portrait.getWidth(), imageRect.height() / portrait.getHeight());
        float dx = imageRect.left + (imageRect.width() - portrait.getWidth() * scale) / 2f;
        float dy = imageRect.top + (imageRect.height() - portrait.getHeight() * scale) / 2f;
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(dx, dy);
        shader.setLocalMatrix(shaderMatrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = Math.max(0, radius - ringWidth);
        if (heroId < 0) {
            if (dashedWhenEmpty) {
                float half = dashPaint.getStrokeWidth() / 2f;
                canvas.drawRoundRect(half, half, getWidth() - half, getHeight() - half, radius, radius, dashPaint);
            }
            return;
        }
        if (shader != null) {
            imagePaint.setShader(shader);
            imagePaint.setColorFilter(banned ? bannedFilter : null);
            canvas.drawRoundRect(imageRect, r, r, imagePaint);
        } else {
            canvas.drawRoundRect(imageRect, r, r, fillPaint);
            if (!initial.isEmpty()) {
                Paint.FontMetrics metrics = initialPaint.getFontMetrics();
                float baseline = imageRect.centerY() - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(initial, imageRect.centerX(), baseline, initialPaint);
            }
        }
        if (ringWidth > 0) {
            ringPaint.setColor(ringColor);
            ringPaint.setStrokeWidth(ringWidth);
            canvas.drawRoundRect(ringRect, radius - ringWidth / 2f, radius - ringWidth / 2f, ringPaint);
        }
        if (banned) {
            float pad = Ui.dp(getContext(), 3);
            canvas.drawLine(getWidth() - pad, pad, pad, getHeight() - pad, slashPaint);
        }
        if (showTier && !tier.isEmpty()) drawTierBadge(canvas);
    }

    private void drawTierBadge(Canvas canvas) {
        Context context = getContext();
        float height = Ui.dp(context, 15);
        float padding = Ui.dp(context, 4);
        float width = badgeText.measureText(tier) + padding * 2;
        float right = getWidth() - Ui.dp(context, 1);
        float bottom = getHeight() - Ui.dp(context, 1);
        badgeRect.set(right - width, bottom - height, right, bottom);
        float corner = Ui.dp(context, 5);
        float outline = Ui.dp(context, 1.5f);
        badgeFill.setColor(badgeOutlineColor);
        canvas.drawRoundRect(badgeRect.left - outline, badgeRect.top - outline, badgeRect.right + outline,
            badgeRect.bottom + outline, corner + outline, corner + outline, badgeFill);

        int fill, text, stroke;
        switch (tier) {
            case "T0" -> { fill = Ui.color(context, R.color.crown); text = Ui.color(context, R.color.crown_ink); stroke = 0; }
            case "T0.5" -> { fill = Ui.color(context, R.color.crown_soft); text = Ui.color(context, R.color.crown); stroke = Ui.color(context, R.color.crown); }
            case "T1" -> { fill = Ui.color(context, R.color.ally_soft); text = Ui.color(context, R.color.ally_text); stroke = Ui.color(context, R.color.ally_line); }
            default -> { fill = Ui.color(context, R.color.deck_2); text = Ui.color(context, R.color.mist); stroke = Ui.color(context, R.color.seam_2); }
        }
        badgeFill.setColor(fill);
        canvas.drawRoundRect(badgeRect, corner, corner, badgeFill);
        if (stroke != 0) {
            badgeStroke.setColor(stroke);
            float half = badgeStroke.getStrokeWidth() / 2f;
            canvas.drawRoundRect(badgeRect.left + half, badgeRect.top + half, badgeRect.right - half,
                badgeRect.bottom - half, corner, corner, badgeStroke);
        }
        badgeText.setColor(text);
        Paint.FontMetrics metrics = badgeText.getFontMetrics();
        float baseline = badgeRect.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(tier, badgeRect.centerX(), baseline, badgeText);
    }
}
