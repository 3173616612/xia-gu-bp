package com.xiagu.bp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Direct client for Tianyuanzhiyi. Recommendation scoring stays entirely on-device. */
final class BpApiClient {
    private static final String TAG = "TianyuanDirect";
    private static final String TIANYUAN_BASE_URL = "https://tianyuanzhiyi.com";
    private static final String ALL_HEROES_PATH = "/api/allheroes";
    private static final String TIER_PATH = "/api/global/tier?date=";
    private static final String ANALYSIS_PATH = "/api/hero/analysis?heroId=";
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final ExecutorService RELATION_IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String latestTierDate = "";

    interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    private BpApiClient() {}

    static String latestTierDate() {
        return latestTierDate;
    }

    static void loadOriginalAvatarRoster(Callback<List<BpModels.Hero>> callback) {
        IO.execute(() -> {
            try {
                List<BpModels.Hero> heroes = fetchOriginalHeroes();
                MAIN.post(() -> callback.onSuccess(heroes));
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(readableError("英雄头像", error)));
            }
        });
    }

    /** Loads original heroes plus the newest available summit-top-1000 tier snapshot. */
    static void loadMeta(Callback<List<BpModels.Hero>> callback) {
        IO.execute(() -> {
            try {
                List<BpModels.Hero> heroes = fetchOriginalHeroes();
                TierSnapshot tierSnapshot = fetchTierWithFallback();
                mergeTier(heroes, tierSnapshot.rows);
                latestTierDate = tierSnapshot.date;
                MAIN.post(() -> callback.onSuccess(heroes));
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(readableError("英雄与梯度", error)));
            }
        });
    }

    static void loadAnalyses(List<BpModels.Hero> selected, Callback<Map<Integer, BpModels.Analysis>> callback) {
        IO.execute(() -> {
            Map<Integer, BpModels.Analysis> results = new LinkedHashMap<>();
            Set<Integer> uniqueIds = new LinkedHashSet<>();
            for (BpModels.Hero hero : selected) uniqueIds.add(hero.id);

            Map<Integer, java.util.concurrent.Future<BpModels.Analysis>> requests = new LinkedHashMap<>();
            for (Integer heroId : uniqueIds) requests.put(heroId, RELATION_IO.submit(() -> {
                JSONObject payload = requestObject(TIANYUAN_BASE_URL + ANALYSIS_PATH + heroId);
                BpModels.Analysis analysis = parseAnalysis(payload);
                if (analysis.heroId != heroId) throw new IllegalStateException("英雄关系 ID 不匹配");
                return analysis;
            }));
            for (Map.Entry<Integer, java.util.concurrent.Future<BpModels.Analysis>> request : requests.entrySet()) {
                try { results.put(request.getKey(), request.getValue().get()); }
                catch (Exception error) { Log.w(TAG, "analysis failed for heroId=" + request.getKey() + ": " + error.getMessage()); }
            }

            if (!uniqueIds.isEmpty() && results.isEmpty()) {
                MAIN.post(() -> callback.onError("天元之弈英雄关系同步失败，请检查国内网络后重试。"));
            } else {
                MAIN.post(() -> callback.onSuccess(results));
            }
        });
    }

    private static List<BpModels.Hero> fetchOriginalHeroes() throws Exception {
        JSONArray rows = requestArray(TIANYUAN_BASE_URL + ALL_HEROES_PATH);
        List<BpModels.Hero> heroes = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            BpModels.Hero hero = new BpModels.Hero();
            hero.id = row.optInt("id");
            hero.name = row.optString("name").trim();
            hero.roles = row.optString("roles").trim();
            hero.avatarUrl = row.optString("avatarUrl").trim();
            addPositions(hero, hero.roles);
            if (hero.id > 0 && !hero.name.isBlank() && !hero.avatarUrl.isBlank()) heroes.add(hero);
        }
        if (heroes.isEmpty()) throw new IllegalStateException("英雄列表为空");
        return heroes;
    }

    private static TierSnapshot fetchTierWithFallback() throws Exception {
        Exception lastError = null;
        for (int daysAgo = 0; daysAgo <= 2; daysAgo++) {
            String date = chinaDate(daysAgo);
            try {
                JSONObject payload = requestObject(TIANYUAN_BASE_URL + TIER_PATH + date);
                JSONArray tiers = payload.optJSONArray("tiers");
                if (tiers == null || tiers.length() == 0) {
                    throw new IllegalStateException(date + " 梯度列表为空");
                }
                String queryDate = payload.optString("queryDate", date);
                return new TierSnapshot(queryDate.isBlank() ? date : queryDate, tiers);
            } catch (Exception error) {
                lastError = error;
            }
        }
        throw new IllegalStateException(
            "近三日巅峰千强梯度均不可用" + (lastError == null ? "" : "：" + lastError.getMessage())
        );
    }

    private static void mergeTier(List<BpModels.Hero> heroes, JSONArray tiers) {
        Map<Integer, JSONObject> byHeroId = new LinkedHashMap<>();
        for (int i = 0; i < tiers.length(); i++) {
            JSONObject row = tiers.optJSONObject(i);
            if (row != null && row.optInt("heroId") > 0) byHeroId.put(row.optInt("heroId"), row);
        }
        for (BpModels.Hero hero : heroes) {
            JSONObject tier = byHeroId.get(hero.id);
            if (tier == null) continue;
            hero.tier = tier.optString("tierInRole").trim();
            hero.tierRole = tier.optString("role").trim();
            if (!tier.isNull("finalNormalizedTierScore")) {
                double value = tier.optDouble("finalNormalizedTierScore", Double.NaN);
                if (Double.isFinite(value)) hero.tierScore = value;
            }
        }
    }

    private static void addPositions(BpModels.Hero hero, String roles) {
        if (roles == null || roles.isBlank()) return;
        for (String raw : roles.split("[/、,，]")) {
            String position = raw.trim();
            if (!position.isBlank() && !hero.positions.contains(position)) hero.positions.add(position);
        }
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
            relation.heroName = row.optString("heroName").trim();
            relation.totalMatches = row.optInt("totalMatches");
            relation.value = row.optDouble(valueKey);
            if (!relation.heroName.isBlank() && Double.isFinite(relation.value)) output.add(relation);
        }
    }

    private static String chinaDate(int daysAgo) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.ROOT);
        calendar.add(Calendar.DAY_OF_MONTH, -daysAgo);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(calendar.getTime());
    }

    private static JSONObject requestObject(String absoluteUrl) throws Exception {
        return new JSONObject(requestBody(absoluteUrl));
    }

    private static JSONArray requestArray(String absoluteUrl) throws Exception {
        return new JSONArray(requestBody(absoluteUrl));
    }

    private static String requestBody(String absoluteUrl) throws Exception {
        URL url = new URL(absoluteUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())
            || !"tianyuanzhiyi.com".equalsIgnoreCase(url.getHost())) {
            throw new SecurityException("数据请求被限制为天元之弈国内接口");
        }
        Log.i(TAG, "GET " + absoluteUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(18_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setUseCaches(false);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String readableError(String dataName, Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.isBlank()
            ? "天元之弈" + dataName + "同步失败，请稍后重试。"
            : "天元之弈" + dataName + "同步失败：" + detail;
    }

    private record TierSnapshot(String date, JSONArray rows) {}
}
