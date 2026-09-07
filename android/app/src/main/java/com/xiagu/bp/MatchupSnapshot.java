package com.xiagu.bp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small explicit, restorable payload; no static Activity/service reference or bitmap in Intents. */
final class MatchupSnapshot {
    final List<BpModels.Hero> roster = new ArrayList<>();
    final BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
    final Map<Integer, BpModels.Analysis> analyses = new LinkedHashMap<>();
    BpModels.Hero[] allies, enemies;
    String tierDate = "", fetchedAt = "";
    boolean lanesConfirmed;

    String encode() throws Exception {
        JSONObject root = new JSONObject();
        JSONArray heroes = new JSONArray();
        for (BpModels.Hero h : roster) {
            heroes.put(new JSONObject().put("id", h.id).put("name", h.name).put("roles", h.roles)
                .put("avatar", h.avatarUrl).put("tierRole", h.tierRole).put("positions", new JSONArray(h.positions)));
        }
        root.put("heroes", heroes).put("allies", ids(lineup.allies)).put("enemies", ids(lineup.enemies))
            .put("bans", ids(lineup.bans)).put("allyLanes", ids(java.util.Arrays.asList(allies)))
            .put("enemyLanes", ids(java.util.Arrays.asList(enemies))).put("tierDate", tierDate)
            .put("fetchedAt", fetchedAt).put("confirmed", lanesConfirmed);
        JSONArray data = new JSONArray();
        for (BpModels.Analysis a : analyses.values()) data.put(new JSONObject().put("id", a.heroId)
            .put("c", rows(a.counters)).put("b", rows(a.counteredBy))
            .put("g", rows(a.goodSynergies)).put("s", rows(a.badSynergies)));
        return root.put("data", data).toString();
    }

    static MatchupSnapshot decode(String encoded) throws Exception {
        JSONObject root = new JSONObject(encoded);
        MatchupSnapshot s = new MatchupSnapshot();
        Map<Integer, BpModels.Hero> byId = new LinkedHashMap<>();
        JSONArray heroes = root.getJSONArray("heroes");
        for (int i = 0; i < heroes.length(); i++) {
            JSONObject row = heroes.getJSONObject(i);
            BpModels.Hero h = new BpModels.Hero();
            h.id = row.getInt("id"); h.name = row.getString("name"); h.roles = row.optString("roles");
            h.avatarUrl = row.optString("avatar"); h.tierRole = row.optString("tierRole");
            JSONArray positions = row.getJSONArray("positions");
            for (int j = 0; j < positions.length(); j++) h.positions.add(positions.getString(j));
            s.roster.add(h); byId.put(h.id, h);
        }
        readIds(root.getJSONArray("allies"), byId, s.lineup.allies);
        readIds(root.getJSONArray("enemies"), byId, s.lineup.enemies);
        readIds(root.getJSONArray("bans"), byId, s.lineup.bans);
        s.allies = readLanes(root.getJSONArray("allyLanes"), byId);
        s.enemies = readLanes(root.getJSONArray("enemyLanes"), byId);
        s.tierDate = root.optString("tierDate"); s.fetchedAt = root.optString("fetchedAt");
        s.lanesConfirmed = root.optBoolean("confirmed");
        JSONArray data = root.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.getJSONObject(i);
            BpModels.Analysis a = new BpModels.Analysis(); a.heroId = row.getInt("id");
            readRows(row.getJSONArray("c"), a.counters); readRows(row.getJSONArray("b"), a.counteredBy);
            readRows(row.getJSONArray("g"), a.goodSynergies); readRows(row.getJSONArray("s"), a.badSynergies);
            s.analyses.put(a.heroId, a);
        }
        return s;
    }

    private static JSONArray ids(List<BpModels.Hero> heroes) {
        JSONArray a = new JSONArray(); for (BpModels.Hero h : heroes) a.put(h == null ? 0 : h.id); return a;
    }
    private static void readIds(JSONArray a, Map<Integer, BpModels.Hero> map, List<BpModels.Hero> out) {
        for (int i = 0; i < a.length(); i++) { BpModels.Hero h = map.get(a.optInt(i)); if (h != null) out.add(h); }
    }
    private static BpModels.Hero[] readLanes(JSONArray a, Map<Integer, BpModels.Hero> map) {
        BpModels.Hero[] result = new BpModels.Hero[5];
        for (int i = 0; i < 5; i++) result[i] = map.get(a.optInt(i)); return result;
    }
    private static JSONArray rows(List<BpModels.Relation> rows) throws Exception {
        JSONArray a = new JSONArray();
        for (BpModels.Relation r : rows) a.put(new JSONObject().put("n", r.heroName).put("v", r.value).put("m", r.totalMatches));
        return a;
    }
    private static void readRows(JSONArray a, List<BpModels.Relation> out) throws Exception {
        for (int i = 0; i < a.length(); i++) {
            JSONObject row = a.getJSONObject(i); BpModels.Relation r = new BpModels.Relation();
            r.heroName = row.getString("n"); r.value = row.getDouble("v"); r.totalMatches = row.getInt("m"); out.add(r);
        }
    }
}
