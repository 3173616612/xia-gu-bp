package com.xiagu.bp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;

import java.util.Locale;

/** Small shared helpers so every screen draws evidence, tiers and spacing the same way. */
final class Ui {
    private Ui() {}

    static final double EVEN_THRESHOLD = 0.25;

    enum Tone { GOOD, BAD, EVEN, NONE }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int color(Context context, int colorRes) {
        return context.getColor(colorRes);
    }

    static TextView text(Context context, int styleRes, CharSequence value) {
        TextView view = new TextView(context, null, 0, styleRes);
        view.setText(value);
        return view;
    }

    static GradientDrawable rounded(Context context, int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != 0) drawable.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        return drawable;
    }

    /** Signed index with a real minus sign, e.g. +1.24 / −0.86. */
    static String signed(double value) {
        String formatted = String.format(Locale.CHINA, "%+.2f", value);
        return formatted.replace('-', '−');
    }

    static String signedShort(double value) {
        String formatted = String.format(Locale.CHINA, "%+.1f", value);
        return formatted.replace('-', '−');
    }

    static Tone tone(MatchupAnalysisEngine.Evidence evidence) {
        if (evidence == null || !evidence.known()) return Tone.NONE;
        if (evidence.value() > EVEN_THRESHOLD) return Tone.GOOD;
        if (evidence.value() < -EVEN_THRESHOLD) return Tone.BAD;
        return Tone.EVEN;
    }

    static int toneText(Context context, Tone tone) {
        return switch (tone) {
            case GOOD -> color(context, R.color.edge);
            case BAD -> color(context, R.color.enemy_text);
            case EVEN -> color(context, R.color.caution);
            case NONE -> color(context, R.color.dusk);
        };
    }

    static GradientDrawable tonePill(Context context, Tone tone, float radiusDp) {
        return switch (tone) {
            case GOOD -> rounded(context, color(context, R.color.edge_soft), color(context, R.color.edge_line), radiusDp);
            case BAD -> rounded(context, color(context, R.color.enemy_soft), color(context, R.color.enemy_line), radiusDp);
            case EVEN -> rounded(context, color(context, R.color.caution_soft), color(context, R.color.caution_line), radiusDp);
            case NONE -> rounded(context, 0x00000000, color(context, R.color.seam), radiusDp);
        };
    }

    /** Heat-map cell: stronger evidence gets a stronger fill. Unknown stays outlined, never neutral-looking. */
    static GradientDrawable heatCell(Context context, MatchupAnalysisEngine.Evidence evidence) {
        Tone tone = tone(evidence);
        if (tone == Tone.NONE) return rounded(context, 0x00000000, color(context, R.color.seam), 8);
        if (tone == Tone.EVEN) return rounded(context, color(context, R.color.caution_soft), 0, 8);
        double strength = Math.abs(evidence.value());
        int fill;
        if (tone == Tone.GOOD) fill = strength >= 2 ? 0xFF146B57 : strength >= 1 ? 0xFF0F4A3E : color(context, R.color.edge_soft);
        else fill = strength >= 2 ? 0xFF8E2238 : strength >= 1 ? 0xFF5C1B2C : color(context, R.color.enemy_soft);
        return rounded(context, fill, 0, 8);
    }

    static int heatText(Context context, MatchupAnalysisEngine.Evidence evidence) {
        Tone tone = tone(evidence);
        if (tone == Tone.GOOD) return Math.abs(evidence.value()) >= 1 ? 0xFFDDFBF1 : color(context, R.color.edge);
        if (tone == Tone.BAD) return Math.abs(evidence.value()) >= 1 ? 0xFFFFE4E9 : color(context, R.color.enemy_text);
        return toneText(context, tone);
    }

    static String counterLabel(Tone tone) {
        return switch (tone) {
            case GOOD -> "我方有利";
            case BAD -> "我方不利";
            case EVEN -> "接近均势";
            case NONE -> "暂无数据";
        };
    }

    static String synergyLabel(Tone tone) {
        return switch (tone) {
            case GOOD -> "配合良好";
            case BAD -> "配合较差";
            case EVEN -> "配合中性";
            case NONE -> "暂无数据";
        };
    }

    static String tier(BpModels.Hero hero) {
        return hero == null || hero.tier == null ? "" : hero.tier.trim();
    }

    static String positions(BpModels.Hero hero) {
        if (hero == null) return "";
        if (!hero.positions.isEmpty()) return String.join("/", hero.positions);
        return hero.roles == null ? "" : hero.roles;
    }

    static String heroName(BpModels.Hero hero) {
        return hero == null ? "待确定" : hero.name;
    }

    /**
     * Pads a root view by the system bars and display cutout. Needed because targetSdk 35 draws
     * activities edge to edge on Android 15.
     */
    static void padForSystemBars(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int insetLeft, insetTop, insetRight, insetBottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetLeft = bars.left;
                insetTop = bars.top;
                insetRight = bars.right;
                insetBottom = bars.bottom;
            } else {
                insetLeft = insets.getSystemWindowInsetLeft();
                insetTop = insets.getSystemWindowInsetTop();
                insetRight = insets.getSystemWindowInsetRight();
                insetBottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left + insetLeft, top + insetTop, right + insetRight, bottom + insetBottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
