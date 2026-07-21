package com.xiagu.bp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Debug-only end-to-end check: Tianyuan data -> local recommendation engine. */
public final class DirectDataFixtureActivity extends Activity {
    private static final String TAG = "DirectDataFixture";
    private static final String[] ENEMY_NAMES = {
        "关羽", "元流之子(坦克)", "武则天", "后羿", "鲁班大师"
    };
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setGravity(Gravity.CENTER);
        output.setTextSize(18);
        output.setText("天元之弈直连与本地推荐测试中…");
        setContentView(output);

        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                runRecommendation(heroes);
            }

            @Override
            public void onError(String message) {
                show("error=" + message);
            }
        });
    }

    private void runRecommendation(List<BpModels.Hero> heroes) {
        BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
        List<String> missing = new ArrayList<>();
        for (String name : ENEMY_NAMES) {
            BpModels.Hero hero = find(heroes, name);
            if (hero == null) missing.add(name);
            else lineup.enemies.add(hero);
        }
        if (!missing.isEmpty()) {
            show("missing=" + String.join("、", missing));
            return;
        }

        BpApiClient.loadAnalyses(lineup.enemies, new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(Map<Integer, BpModels.Analysis> analyses) {
                List<BpModels.Recommendation> recommendations = RecommendationEngine.recommend(
                    heroes,
                    analyses,
                    lineup,
                    "游走"
                );
                int tiered = 0;
                for (BpModels.Hero hero : heroes) if (hero.tierScore != null) tiered++;
                int relations = 0;
                for (BpModels.Analysis analysis : analyses.values()) {
                    relations += analysis.counters.size();
                    relations += analysis.counteredBy.size();
                    relations += analysis.goodSynergies.size();
                    relations += analysis.badSynergies.size();
                }
                List<String> ranked = new ArrayList<>();
                for (BpModels.Recommendation value : recommendations) {
                    ranked.add(value.hero.name + ":" + value.score
                        + "(敌" + value.matchupScore + ",配" + value.synergyScore
                        + ",梯" + value.tierScore + ")");
                }
                show("source_host=tianyuanzhiyi.com"
                    + "\nalgorithm=on-device RecommendationEngine"
                    + "\nhero_count=" + heroes.size()
                    + "\ntier_date=" + BpApiClient.latestTierDate()
                    + "\ntiered_count=" + tiered
                    + "\nanalysis_count=" + analyses.size()
                    + "\nrelation_rows=" + relations
                    + "\nenemies=" + String.join("、", ENEMY_NAMES)
                    + "\nrecommendations=" + String.join(" | ", ranked));
            }

            @Override
            public void onError(String message) {
                show("error=" + message);
            }
        });
    }

    private BpModels.Hero find(List<BpModels.Hero> heroes, String name) {
        for (BpModels.Hero hero : heroes) if (name.equals(hero.name)) return hero;
        return null;
    }

    private void show(String value) {
        Log.i(TAG, value.replace('\n', ';'));
        try (FileOutputStream stream = new FileOutputStream(
            new File(getExternalFilesDir(null), "direct_data_report.txt")
        )) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            Log.e(TAG, "cannot write direct data report", error);
        }
        output.setText(value);
    }
}
