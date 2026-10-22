package com.xiagu.bp;

import android.os.Build;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hero search by name, full pinyin or pinyin initials ("wzj" → 王昭君). Pinyin uses the platform ICU
 * transliterator, public from Android 10; older devices fall back to name matching only.
 */
final class PinyinIndex {
    private static final Map<String, String[]> KEYS = new ConcurrentHashMap<>();
    private static final ExecutorService WARMER = Executors.newSingleThreadExecutor();
    private static final String[] NONE = {"", ""};

    private PinyinIndex() {}

    /** Pre-computes keys off the main thread so the first keystroke stays smooth. */
    static void warm(List<BpModels.Hero> heroes) {
        if (Build.VERSION.SDK_INT < 29) return;
        List<BpModels.Hero> copy = new java.util.ArrayList<>(heroes);
        WARMER.execute(() -> {
            for (BpModels.Hero hero : copy) keys(hero.name);
        });
    }

    static boolean matches(BpModels.Hero hero, String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return true;
        String name = hero.name.toLowerCase(Locale.ROOT);
        if (name.contains(normalized)) return true;
        if (!isAscii(normalized)) return false;
        String[] keys = keys(hero.name);
        return keys[1].startsWith(normalized) || keys[0].startsWith(normalized)
            || (normalized.length() >= 2 && (keys[0].contains(normalized) || keys[1].contains(normalized)));
    }

    /** Lower rank sorts first: exact name, then name prefix, then pinyin hits. */
    static int rank(BpModels.Hero hero, String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return 0;
        String name = hero.name.toLowerCase(Locale.ROOT);
        if (name.equals(normalized)) return 0;
        if (name.startsWith(normalized)) return 1;
        if (name.contains(normalized)) return 2;
        String[] keys = keys(hero.name);
        if (keys[1].equals(normalized) || keys[0].equals(normalized)) return 1;
        if (keys[1].startsWith(normalized)) return 2;
        if (keys[0].startsWith(normalized)) return 3;
        return 4;
    }

    private static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("'", "");
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 127) return false;
        return true;
    }

    /** {full pinyin, initials}, both lower-case ASCII letters only. */
    private static String[] keys(String name) {
        if (name == null || name.isEmpty() || Build.VERSION.SDK_INT < 29) return NONE;
        String[] cached = KEYS.get(name);
        if (cached != null) return cached;
        String[] computed = Api29.transliterate(name);
        KEYS.put(name, computed);
        return computed;
    }

    /** Isolated so devices below Android 10 never load the ICU transliterator class. */
    private static final class Api29 {
        private static android.icu.text.Transliterator transliterator;
        private static boolean unavailable;

        static synchronized String[] transliterate(String name) {
            try {
                if (unavailable) return NONE;
                if (transliterator == null) {
                    transliterator = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII");
                }
                String latin = transliterator.transliterate(name).toLowerCase(Locale.ROOT);
                StringBuilder full = new StringBuilder();
                StringBuilder initials = new StringBuilder();
                boolean atWordStart = true;
                for (int i = 0; i < latin.length(); i++) {
                    char c = latin.charAt(i);
                    if (c >= 'a' && c <= 'z') {
                        full.append(c);
                        if (atWordStart) initials.append(c);
                        atWordStart = false;
                    } else {
                        atWordStart = true;
                    }
                }
                return new String[]{full.toString(), initials.toString()};
            } catch (Throwable error) {
                unavailable = true;
                return NONE;
            }
        }
    }
}
