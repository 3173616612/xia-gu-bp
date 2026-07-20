package com.xiagu.bp;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class OcrHeroParser {
    private OcrHeroParser() {}

    static BpModels.DetectedLineup parse(
        Text result,
        List<BpModels.Hero> heroes,
        int imageWidth,
        int imageHeight,
        boolean ourSideLeft
    ) {
        BpModels.DetectedLineup output = new BpModels.DetectedLineup();
        output.rawText = result.getText();
        List<Token> tokens = new ArrayList<>();
        List<Rect> banAnchors = new ArrayList<>();

        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) continue;
                Token token = new Token(line.getText(), box);
                tokens.add(token);
                String normalized = normalize(line.getText());
                if (normalized.contains("ban") || normalized.contains("禁用") || normalized.contains("已禁")) {
                    banAnchors.add(box);
                }
            }
        }

        output.suggestedLane = detectLane(tokens);
        Map<String, Integer> aliasCount = new HashMap<>();
        for (BpModels.Hero hero : heroes) aliasCount.merge(baseAlias(hero.name), 1, Integer::sum);

        Map<Integer, BpModels.DetectedHero> detected = new LinkedHashMap<>();
        Set<String> ambiguousAliases = new HashSet<>();
        for (Token token : tokens) {
            Match best = null;
            boolean fuzzyTie = false;
            Set<String> tokenAmbiguousAliases = new HashSet<>();
            for (BpModels.Hero hero : heroes) {
                Match candidate = match(token, hero, aliasCount);
                if (candidate == null) continue;
                if (candidate.ambiguous) {
                    tokenAmbiguousAliases.add(candidate.alias);
                    continue;
                }
                if (best == null || candidate.confidence > best.confidence) {
                    best = candidate;
                    fuzzyTie = false;
                } else if (candidate.fuzzy && best.fuzzy && candidate.confidence == best.confidence) {
                    fuzzyTie = true;
                }
            }
            if (best == null || fuzzyTie) {
                ambiguousAliases.addAll(tokenAmbiguousAliases);
                continue;
            }
            BpModels.DetectedHero previous = detected.get(best.hero.id);
            if (previous == null || best.confidence > previous.confidence) {
                BpModels.DetectedHero value = new BpModels.DetectedHero();
                value.hero = best.hero;
                value.confidence = best.confidence;
                value.centerX = token.box.centerX();
                value.centerY = token.box.centerY();
                detected.put(best.hero.id, value);
            }
        }

        List<BpModels.DetectedHero> ordered = new ArrayList<>(detected.values());
        ordered.sort(Comparator.comparingInt((BpModels.DetectedHero value) -> value.centerY)
            .thenComparingInt(value -> value.centerX));

        double confidenceTotal = 0;
        boolean hasMultiRole = false;
        boolean hasLowConfidence = false;
        for (BpModels.DetectedHero value : ordered) {
            confidenceTotal += value.confidence;
            hasMultiRole |= value.hero.positions.size() > 1;
            hasLowConfidence |= value.confidence < 0.8;
            if (isBan(value, banAnchors, imageHeight)) {
                output.bans.add(value.hero);
                continue;
            }
            boolean onLeft = value.centerX < imageWidth / 2;
            if (onLeft == ourSideLeft) output.allies.add(value.hero);
            else output.enemies.add(value.hero);
        }

        output.confidence = ordered.isEmpty() ? 0 : confidenceTotal / ordered.size();
        if (ordered.isEmpty()) output.issues.add("没有识别到英雄名称，请确认画面中显示了英雄文字。");
        if (!ambiguousAliases.isEmpty()) output.issues.add("存在版本不明确的英雄：" + String.join("、", ambiguousAliases));
        if (hasLowConfidence) output.issues.add("部分名称为低置信度识别，建议打开完整面板核对。");
        if (hasMultiRole) output.issues.add("检测到多位置英雄，已按各分路可能性软加权。");
        if (output.allies.size() != 5 || output.enemies.size() != 5) {
            output.issues.add("当前识别为我方 " + output.allies.size() + " 人、敌方 " + output.enemies.size() + " 人；不强制补齐五路，按画面现有阵容继续计算。");
        }
        return output;
    }

    private static boolean isBan(BpModels.DetectedHero hero, List<Rect> anchors, int height) {
        if (hero.centerY < height * 0.16) return true;
        for (Rect anchor : anchors) {
            int dx = hero.centerX - anchor.centerX();
            int dy = hero.centerY - anchor.centerY();
            double distance = Math.sqrt((double) dx * dx + (double) dy * dy);
            if (distance < height * 0.2) return true;
        }
        return false;
    }

    private static String detectLane(List<Token> tokens) {
        for (Token token : tokens) {
            String value = normalize(token.text);
            if (value.contains("对抗路")) return "对抗路";
            if (value.contains("发育路")) return "发育路";
            if (value.contains("中路")) return "中路";
            if (value.contains("打野")) return "打野";
            if (value.contains("游走") || value.contains("辅助")) return "游走";
        }
        return null;
    }

    private static Match match(Token token, BpModels.Hero hero, Map<String, Integer> aliasCount) {
        String text = normalize(token.text);
        String full = normalize(hero.name);
        String alias = baseAlias(hero.name);
        if (text.isBlank() || full.isBlank()) return null;
        if (text.contains(full)) return new Match(hero, 1.0, false, false, alias);
        if (!alias.equals(full) && text.contains(alias)) {
            boolean ambiguous = aliasCount.getOrDefault(alias, 0) > 1;
            return new Match(hero, 0.86, ambiguous, false, alias);
        }
        if (alias.length() >= 3 && aliasCount.getOrDefault(alias, 0) == 1 && bestWindowDistance(text, alias) <= 1) {
            return new Match(hero, 0.7, false, true, alias);
        }
        if (alias.length() == 2 && text.length() <= 3 && bestWindowDistance(text, alias) <= 1) {
            return new Match(hero, 0.62, false, true, alias);
        }
        return null;
    }

    private static int bestWindowDistance(String text, String target) {
        if (text.length() <= target.length() + 1) return levenshtein(text, target);
        int best = Integer.MAX_VALUE;
        for (int length = Math.max(1, target.length() - 1); length <= target.length() + 1; length++) {
            for (int start = 0; start + length <= text.length(); start++) {
                best = Math.min(best, levenshtein(text.substring(start, start + length), target));
            }
        }
        return best;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static String baseAlias(String name) {
        String base = name.replaceAll("[（(].*?[）)]", "");
        return normalize(base);
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

    private static final class Token {
        final String text;
        final Rect box;

        Token(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    private static final class Match {
        final BpModels.Hero hero;
        final double confidence;
        final boolean ambiguous;
        final boolean fuzzy;
        final String alias;

        Match(BpModels.Hero hero, double confidence, boolean ambiguous, boolean fuzzy, String alias) {
            this.hero = hero;
            this.confidence = confidence;
            this.ambiguous = ambiguous;
            this.fuzzy = fuzzy;
            this.alias = alias;
        }
    }
}
