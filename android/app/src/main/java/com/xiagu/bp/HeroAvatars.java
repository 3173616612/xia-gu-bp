package com.xiagu.bp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Portrait loading shared by every screen and the overlay. Same lookup order as the recognition
 * library: current cached portrait, bundled seed with the current fingerprint, any cached version,
 * then any bundled seed for the hero. Decoding happens off the main thread.
 */
final class HeroAvatars {
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<Integer, Bitmap> CACHE = new LruCache<>(cacheBytes()) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static volatile Map<Integer, String> seedIndex;

    private HeroAvatars() {}

    private static int cacheBytes() {
        long max = Runtime.getRuntime().maxMemory();
        return (int) Math.min(16L * 1024 * 1024, Math.max(4L * 1024 * 1024, max / 8));
    }

    /** Binds a hero (or null for an empty slot) to the view; the portrait arrives asynchronously. */
    static void bind(HeroAvatarView view, BpModels.Hero hero) {
        int token = view.bindHero(hero);
        if (hero == null) return;
        Bitmap cached = CACHE.get(hero.id);
        if (cached != null && !cached.isRecycled()) {
            view.setPortrait(cached, token);
            return;
        }
        Context app = view.getContext().getApplicationContext();
        DECODER.execute(() -> {
            Bitmap bitmap;
            try {
                bitmap = load(app, hero);
            } catch (Throwable error) {
                bitmap = null; // A broken portrait must never take the screen down; the placeholder stays.
            }
            if (bitmap != null) CACHE.put(hero.id, bitmap);
            final Bitmap result = bitmap;
            MAIN.post(() -> view.setPortrait(result, token));
        });
    }

    static Bitmap load(Context context, BpModels.Hero hero) {
        int heroId = hero.id;
        Bitmap cached = CACHE.get(heroId);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap decoded = null;
        File directory = new File(context.getFilesDir(), "avatar-library");
        String currentKey = heroId + "-" + AvatarRecognitionEngine.shortHash(hero.avatarUrl == null ? "" : hero.avatarUrl) + ".img";
        File current = new File(directory, currentKey);
        if (current.isFile()) decoded = BitmapFactory.decodeFile(current.getAbsolutePath());

        if (decoded == null) {
            try (InputStream input = context.getAssets().open("avatar_seed/" + currentKey)) {
                decoded = BitmapFactory.decodeStream(input);
            } catch (Exception ignored) {
                // A version update may change the fingerprint before the new portrait is cached.
            }
        }
        if (decoded == null) {
            File[] files = directory.listFiles((dir, name) -> name.startsWith(heroId + "-") && name.endsWith(".img"));
            if (files != null && files.length > 0) decoded = BitmapFactory.decodeFile(files[0].getAbsolutePath());
        }
        if (decoded == null) {
            String fallbackAsset = seedIndex(context).get(heroId);
            if (fallbackAsset != null) {
                try (InputStream input = context.getAssets().open("avatar_seed/" + fallbackAsset)) {
                    decoded = BitmapFactory.decodeStream(input);
                } catch (Exception ignored) {
                    // The view keeps its initial-letter placeholder.
                }
            }
        }
        return decoded;
    }

    private static Map<Integer, String> seedIndex(Context context) {
        Map<Integer, String> index = seedIndex;
        if (index != null) return index;
        synchronized (HeroAvatars.class) {
            if (seedIndex != null) return seedIndex;
            Map<Integer, String> built = new HashMap<>();
            try {
                String[] names = context.getAssets().list("avatar_seed");
                if (names != null) {
                    for (String name : names) {
                        int dash = name.indexOf('-');
                        if (dash <= 0) continue;
                        try {
                            built.put(Integer.parseInt(name.substring(0, dash)), name);
                        } catch (NumberFormatException ignored) {
                            // Ignore unexpected asset names.
                        }
                    }
                }
            } catch (Exception ignored) {
                // Placeholders remain usable when assets cannot be listed.
            }
            seedIndex = built;
            return built;
        }
    }
}
