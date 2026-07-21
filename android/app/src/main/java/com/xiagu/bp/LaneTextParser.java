package com.xiagu.bp;

import com.google.mlkit.vision.text.Text;

import java.util.Locale;

/** OCR is intentionally limited to lane text. Hero identities are never inferred from text. */
final class LaneTextParser {
    private LaneTextParser() {}

    static String detect(Text result) {
        if (result == null) return null;
        return detect(result.getText());
    }

    static String detect(String rawText) {
        String value = normalize(rawText);
        if (value.contains("请选择您的对抗路英雄") || value.contains("对抗路")) return "对抗路";
        if (value.contains("请选择您的发育路英雄") || value.contains("发育路")) return "发育路";
        if (value.contains("请选择您的中路英雄") || value.contains("中路")) return "中路";
        if (value.contains("请选择您的打野英雄") || value.contains("打野")) return "打野";
        if (value.contains("请选择您的游走英雄") || value.contains("游走") || value.contains("辅助")) return "游走";
        return null;
    }

    private static String normalize(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char character = lower.charAt(i);
            if (Character.isLetterOrDigit(character)) output.append(character);
        }
        return output.toString();
    }
}
