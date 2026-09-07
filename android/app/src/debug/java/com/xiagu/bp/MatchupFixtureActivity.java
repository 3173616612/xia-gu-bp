package com.xiagu.bp;

import android.app.Activity;
import android.os.Bundle;
import java.util.List;
import java.util.LinkedHashMap;

/** Offline deterministic UI fixture; never packaged in the release manifest. */
public final class MatchupFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var roster = new java.util.ArrayList<BpModels.Hero>();
        var lineup = new BpModels.DetectedLineup();
        var data = new LinkedHashMap<Integer, BpModels.Analysis>();
        String[] names = {"关羽", "镜", "西施", "孙尚香", "庄周", "吕布", "赵云", "武则天", "后羿", "鲁班大师"};
        for (int i = 0; i < 10; i++) {
            var hero = new BpModels.Hero(); hero.id = 9000 + i; hero.name = names[i];
            hero.positions.add(MatchupAnalysisEngine.LANES[i % 5]); hero.roles = hero.positions.get(0);
            roster.add(hero); (i < 5 ? lineup.allies : lineup.enemies).add(hero);
            var a = new BpModels.Analysis(); a.heroId = hero.id; data.put(hero.id, a);
        }
        for (int i = 0; i < 5; i++) for (int j = 5; j < 10; j++) {
            if ((i + j) % 4 == 0) continue;
            var r = new BpModels.Relation(); r.heroName = names[j]; r.value = 1.2 + i; r.totalMatches = 30 + 80 * i;
            ((i + j) % 2 == 0 ? data.get(9000 + i).counters : data.get(9000 + i).counteredBy).add(r);
        }
        for (int i = 0; i < 4; i++) {
            int x = MatchupAnalysisEngine.COMBOS[i][0], y = MatchupAnalysisEngine.COMBOS[i][1];
            var r = new BpModels.Relation(); r.heroName = names[y]; r.value = 2.7; r.totalMatches = 350;
            (i % 2 == 0 ? data.get(9000 + x).goodSynergies : data.get(9000 + x).badSynergies).add(r);
        }
        try {
            var snapshot = new MatchupSnapshot(); snapshot.roster.addAll(roster);
            snapshot.lineup.allies.addAll(lineup.allies); snapshot.lineup.enemies.addAll(lineup.enemies);
            snapshot.analyses.putAll(data); snapshot.allies = MatchupAnalysisEngine.assignLanes(lineup.allies);
            snapshot.enemies = MatchupAnalysisEngine.assignLanes(lineup.enemies);
            // Saved-instance path keeps the fixture offline, including the missing-data state.
            var intent = new android.content.Intent(this, MatchupAnalysisActivity.class)
                .putExtra("matchup_snapshot", snapshot.encode()).putExtra("debug_offline", true);
            startActivity(intent); finish();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
