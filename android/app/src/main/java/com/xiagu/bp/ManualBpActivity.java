package com.xiagu.bp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Manual draft input for situations where screen recognition is inconvenient. */
public final class ManualBpActivity extends Activity {
    private final List<BpModels.Hero> roster = new ArrayList<>();
    private final List<BpModels.Hero> allies = new ArrayList<>();
    private final List<BpModels.Hero> enemies = new ArrayList<>();
    private final List<BpModels.Hero> bans = new ArrayList<>();
    private final Set<Integer> dismissedHeroIds = new LinkedHashSet<>();

    private LinearLayout allyList;
    private LinearLayout enemyList;
    private LinearLayout banList;
    private LinearLayout recommendationSection;
    private LinearLayout recommendationList;
    private RadioGroup laneGroup;
    private TextView dataStatus;
    private TextView status;
    private Button recommendButton;
    private Map<Integer, BpModels.Analysis> activeAnalyses = Collections.emptyMap();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manual_bp);
        bindViews();
        setupLaneButtons();
        setupActions();
        renderSelections();
        loadRoster();
    }

    private void bindViews() {
        allyList = findViewById(R.id.manualAllyList);
        enemyList = findViewById(R.id.manualEnemyList);
        banList = findViewById(R.id.manualBanList);
        recommendationSection = findViewById(R.id.manualRecommendationSection);
        recommendationList = findViewById(R.id.manualRecommendationList);
        laneGroup = findViewById(R.id.manualLaneGroup);
        dataStatus = findViewById(R.id.manualDataStatus);
        status = findViewById(R.id.manualStatus);
        recommendButton = findViewById(R.id.manualRecommendButton);
    }

    private void setupActions() {
        findViewById(R.id.manualBackButton).setOnClickListener(view -> finish());
        findViewById(R.id.manualClearButton).setOnClickListener(view -> clearDraft());
        findViewById(R.id.addManualAllyButton).setOnClickListener(view -> showHeroPicker("添加我方英雄", allies, 5));
        findViewById(R.id.addManualEnemyButton).setOnClickListener(view -> showHeroPicker("添加敌方英雄", enemies, 5));
        findViewById(R.id.addManualBanButton).setOnClickListener(view -> showHeroPicker("添加 BAN 英雄", bans, 10));
        recommendButton.setOnClickListener(view -> loadRelationsAndRecommend());
    }

    private void setupLaneButtons() {
        laneGroup.removeAllViews();
        String selected = AppPrefs.lane(this);
        for (String lane : AppPrefs.LANES) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setTag(lane);
            button.setText(lane);
            button.setTextColor(Color.WHITE);
            button.setTextSize(12);
            button.setGravity(Gravity.CENTER);
            button.setButtonDrawable(null);
            button.setBackgroundResource(R.drawable.bg_chip);
            button.setPadding(dp(14), 0, dp(14), 0);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            );
            params.setMarginEnd(dp(7));
            laneGroup.addView(button, params);
            button.setChecked(lane.equals(selected));
        }
        laneGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String lane) {
                AppPrefs.setLane(this, lane);
                invalidateRecommendations("已切换至" + lane + "，请重新生成推荐。");
            }
        });
    }

    private void loadRoster() {
        recommendButton.setEnabled(false);
        dataStatus.setText("正在同步天元英雄与巅峰千强梯度");
        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                roster.clear();
                roster.addAll(heroes);
                String tierDate = BpApiClient.latestTierDate();
                dataStatus.setText("实时数据已就绪 · " + heroes.size() + " 位英雄"
                    + (tierDate.isBlank() ? "" : " · " + tierDate));
                status.setText("选择敌我英雄、BAN 位与待补分路后生成推荐。");
                recommendButton.setEnabled(true);
            }

            @Override
            public void onError(String message) {
                dataStatus.setText("实时数据同步失败");
                status.setText(message);
                recommendButton.setEnabled(false);
            }
        });
    }

    private void showHeroPicker(String title, List<BpModels.Hero> target, int limit) {
        if (roster.isEmpty()) {
            status.setText("英雄数据仍在准备，请稍候。 ");
            return;
        }
        if (target.size() >= limit) {
            status.setText(title.replace("添加", "") + "已达到上限 " + limit + " 位。 ");
            return;
        }

        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(14));
        root.setBackgroundResource(R.drawable.bg_dialog);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(getColor(R.color.ink));
        heading.setTextSize(19);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(heading, matchWrap());

        EditText search = new EditText(this);
        search.setHint("输入英雄名称搜索");
        search.setSingleLine(true);
        search.setTextColor(getColor(R.color.ink));
        search.setHintTextColor(getColor(R.color.muted_2));
        search.setBackgroundResource(R.drawable.bg_outline);
        search.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        );
        searchParams.topMargin = dp(12);
        root.addView(search, searchParams);

        ScrollView scroll = new ScrollView(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(choices, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        );
        scrollParams.topMargin = dp(4);
        root.addView(scroll, scrollParams);

        Button close = new Button(this);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextColor(getColor(R.color.muted));
        close.setBackgroundResource(R.drawable.bg_outline);
        close.setOnClickListener(view -> dialog.dismiss());
        root.addView(close, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46)
        ));

        Runnable rebuild = () -> renderChoices(choices, search.getText().toString(), target, dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { rebuild.run(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        rebuild.run();

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.78f;
        window.setAttributes(attributes);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(440));
        int height = Math.min(getResources().getDisplayMetrics().heightPixels - dp(70), dp(680));
        window.setLayout(width, height);
    }

    private void renderChoices(
        LinearLayout container,
        String query,
        List<BpModels.Hero> target,
        Dialog dialog
    ) {
        container.removeAllViews();
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<Integer> unavailable = selectedHeroIds();
        int shown = 0;
        for (BpModels.Hero hero : roster) {
            if (unavailable.contains(hero.id)) continue;
            if (!normalized.isEmpty() && !hero.name.toLowerCase(Locale.ROOT).contains(normalized)) continue;
            Button choice = new Button(this);
            String detail = hero.roles == null || hero.roles.isBlank() ? "" : "  ·  " + hero.roles;
            String tier = hero.tier == null || hero.tier.isBlank() ? "" : "  " + hero.tier;
            choice.setText(hero.name + tier + detail);
            choice.setAllCaps(false);
            choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            choice.setTextColor(getColor(R.color.ink));
            choice.setTextSize(13);
            choice.setBackgroundResource(R.drawable.bg_outline);
            choice.setPadding(dp(14), 0, dp(12), 0);
            choice.setOnClickListener(view -> {
                target.add(hero);
                dialog.dismiss();
                renderSelections();
                invalidateRecommendations("已添加“" + hero.name + "”，请生成推荐。 ");
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            );
            params.bottomMargin = dp(7);
            container.addView(choice, params);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("没有匹配的可用英雄");
            empty.setTextColor(getColor(R.color.muted));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(30), 0, dp(30));
            container.addView(empty, matchWrap());
        }
    }

    private void renderSelections() {
        renderSelectedGroup(allyList, allies, "尚未选择我方英雄");
        renderSelectedGroup(enemyList, enemies, "尚未选择敌方英雄");
        renderSelectedGroup(banList, bans, "尚未设置 BAN 位");
    }

    private void renderSelectedGroup(LinearLayout container, List<BpModels.Hero> values, String emptyText) {
        container.removeAllViews();
        if (values.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(emptyText);
            empty.setTextColor(getColor(R.color.muted_2));
            empty.setTextSize(12);
            empty.setPadding(dp(3), dp(6), dp(3), dp(6));
            container.addView(empty, matchWrap());
            return;
        }
        for (BpModels.Hero hero : new ArrayList<>(values)) {
            Button chip = new Button(this);
            chip.setText("×  " + hero.name + (hero.roles.isBlank() ? "" : "   " + hero.roles));
            chip.setAllCaps(false);
            chip.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            chip.setTextColor(getColor(R.color.ink));
            chip.setTextSize(12);
            chip.setBackgroundResource(R.drawable.bg_skip);
            chip.setPadding(dp(13), 0, dp(10), 0);
            chip.setOnClickListener(view -> {
                values.removeIf(value -> value.id == hero.id);
                renderSelections();
                invalidateRecommendations("已移除“" + hero.name + "”，请重新生成推荐。 ");
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            );
            params.bottomMargin = dp(6);
            container.addView(chip, params);
        }
    }

    private void loadRelationsAndRecommend() {
        if (roster.isEmpty()) return;
        List<BpModels.Hero> selected = new ArrayList<>(allies);
        selected.addAll(enemies);
        recommendButton.setEnabled(false);
        status.setText(selected.isEmpty() ? "正在按当前梯度生成推荐…" : "正在同步所选英雄的对位与配合关系…");
        BpApiClient.loadAnalyses(selected, new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(Map<Integer, BpModels.Analysis> analyses) {
                activeAnalyses = analyses;
                dismissedHeroIds.clear();
                refreshRecommendations(null);
                recommendButton.setEnabled(true);
            }

            @Override
            public void onError(String message) {
                status.setText(message);
                recommendButton.setEnabled(true);
            }
        });
    }

    private void refreshRecommendations(String message) {
        BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
        lineup.allies.addAll(allies);
        lineup.enemies.addAll(enemies);
        lineup.bans.addAll(bans);
        List<BpModels.Recommendation> values = RecommendationEngine.recommend(
            roster,
            activeAnalyses,
            lineup,
            AppPrefs.lane(this),
            dismissedHeroIds
        );
        renderRecommendations(values);
        String suffix = dismissedHeroIds.isEmpty() ? "" : " · 已跳过 " + dismissedHeroIds.size() + " 位";
        status.setText((message == null ? "推荐已更新" : message) + suffix);
    }

    private void renderRecommendations(List<BpModels.Recommendation> values) {
        recommendationSection.setVisibility(View.VISIBLE);
        recommendationList.removeAllViews();
        if (values.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无符合该分路的可用候选，请调整阵容、BAN 位或待补位置。 ");
            empty.setTextColor(getColor(R.color.muted));
            empty.setPadding(0, dp(14), 0, dp(14));
            recommendationList.addView(empty, matchWrap());
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            BpModels.Recommendation value = values.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(index == 0 ? R.drawable.bg_step : R.drawable.bg_outline);
            row.setPadding(dp(13), dp(10), dp(10), dp(10));

            TextView copy = new TextView(this);
            copy.setText((index + 1) + "  " + value.hero.name + "   " + value.score + "\n" + value.summary);
            copy.setTextColor(getColor(R.color.ink));
            copy.setTextSize(12);
            copy.setLineSpacing(0, 1.12f);
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button dismiss = new Button(this);
            dismiss.setText("不想玩");
            dismiss.setAllCaps(false);
            dismiss.setTextColor(getColor(R.color.muted));
            dismiss.setTextSize(11);
            dismiss.setBackgroundResource(R.drawable.bg_skip);
            dismiss.setOnClickListener(view -> {
                dismissedHeroIds.add(value.hero.id);
                refreshRecommendations("已跳过“" + value.hero.name + "”，后续顺位已补上");
            });
            LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(dp(76), dp(40));
            dismissParams.setMarginStart(dp(8));
            row.addView(dismiss, dismissParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowParams.bottomMargin = dp(8);
            recommendationList.addView(row, rowParams);
        }
    }

    private void invalidateRecommendations(String message) {
        dismissedHeroIds.clear();
        activeAnalyses = Collections.emptyMap();
        recommendationList.removeAllViews();
        recommendationSection.setVisibility(View.GONE);
        status.setText(message);
    }

    private void clearDraft() {
        allies.clear();
        enemies.clear();
        bans.clear();
        renderSelections();
        invalidateRecommendations("阵容与 BAN 位已清空。 ");
    }

    private Set<Integer> selectedHeroIds() {
        Set<Integer> ids = new HashSet<>();
        for (BpModels.Hero hero : allies) ids.add(hero.id);
        for (BpModels.Hero hero : enemies) ids.add(hero.id);
        for (BpModels.Hero hero : bans) ids.add(hero.id);
        return ids;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
