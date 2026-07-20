package com.xiagu.bp;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BpApiClient {
    private static final String BASE_URL = "https://xia-gu-bp-live.yxf3173616612.chatgpt.site";
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    private BpApiClient() {}

    static void loadMeta(Callback<List<BpModels.Hero>> callback) {
        IO.execute(() -> {
            try {
                JSONObject payload = request("/api/meta");
                JSONArray rows = payload.getJSONArray("heroes");
                List<BpModels.Hero> heroes = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    BpModels.Hero hero = new BpModels.Hero();
                    hero.id = row.getInt("id");
                    hero.name = row.optString("name");
                    hero.roles = row.optString("roles");
                    hero.avatarUrl = row.optString("avatarUrl");
                    hero.tier = row.optString("tier");
                    hero.tierRole = row.optString("tierRole");
                    if (!row.isNull("tierScore")) hero.tierScore = row.optDouble("tierScore");
                    JSONArray positions = row.optJSONArray("positions");
                    if (positions != null) {
                        for (int j = 0; j < positions.length(); j++) {
                            String position = positions.optString(j);
                            if (!position.isBlank()) hero.positions.add(position);
                        }
                    }
                    if (hero.id > 0 && !hero.name.isBlank()) heroes.add(hero);
                }
                if (heroes.isEmpty()) throw new IllegalStateException("英雄列表为空");
                MAIN.post(() -> callback.onSuccess(heroes));
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(readableError(error)));
            }
        });
    }

    static void loadAnalyses(List<BpModels.Hero> selected, Callback<Map<Integer, BpModels.Analysis>> callback) {
        IO.execute(() -> {
            Map<Integer, BpModels.Analysis> results = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();
            Set<Integer> uniqueIds = new LinkedHashSet<>();
            for (BpModels.Hero hero : selected) uniqueIds.add(hero.id);

            for (Integer heroId : uniqueIds) {
                try {
                    JSONObject payload = request("/api/analysis?heroId=" + heroId);
                    results.put(heroId, parseAnalysis(payload));
                } catch (Exception error) {
                    failures.add(String.valueOf(heroId));
                }
            }

            if (!uniqueIds.isEmpty() && results.isEmpty()) {
                MAIN.post(() -> callback.onError("暂时无法同步英雄关系，请检查网络后重试。"));
            } else {
                MAIN.post(() -> callback.onSuccess(results));
            }
        });
    }

    private static BpModels.Analysis parseAnalysis(JSONObject payload) {
        BpModels.Analysis analysis = new BpModels.Analysis();
        analysis.heroId = payload.optInt("heroId");
        parseRelations(payload.optJSONArray("counters"), analysis.counters, "advantageIndex");
        parseRelations(payload.optJSONArray("counteredBy"), analysis.counteredBy, "advantageIndex");
        parseRelations(payload.optJSONArray("goodSynergies"), analysis.goodSynergies, "synergyIndex");
        parseRelations(payload.optJSONArray("badSynergies"), analysis.badSynergies, "synergyIndex");
        return analysis;
    }

    private static void parseRelations(JSONArray rows, List<BpModels.Relation> output, String valueKey) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            BpModels.Relation relation = new BpModels.Relation();
            relation.heroName = row.optString("heroName");
            relation.totalMatches = row.optInt("totalMatches");
            relation.value = row.optDouble(valueKey);
            if (!relation.heroName.isBlank()) output.add(relation);
        }
    }

    private static JSONObject request(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(18_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setUseCaches(false);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }
        return new JSONObject(body.toString());
    }

    private static String readableError(Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? "实时数据同步失败，请稍后重试。" : "实时数据同步失败：" + detail;
    }
}
