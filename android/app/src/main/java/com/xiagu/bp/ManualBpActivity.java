package com.xiagu.bp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Web-style manual draft: allies on the left, enemies on the right, aligned by lane. */
public final class ManualBpActivity extends Activity {
    private static final int SLOT_COUNT = 5;

    private final List<BpModels.Hero> roster = new ArrayList<>();
    private final BpModels.Hero[] allySlots = new BpModels.Hero[SLOT_COUNT];
    private final BpModels.Hero[] enemySlots = new BpModels.Hero[SLOT_COUNT];
    private final List<BpModels.Hero> bans = new ArrayList<>();
    private final Set<Integer> dismissedHeroIds = new LinkedHashSet<>();
    private final Map<Integer, Bitmap> avatarCache = new HashMap<>();
    private final Map<Integer, String> seedAssetByHeroId = new HashMap<>();

    private LinearLayout draftRows;
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
        indexSeedAvatars();
        bindViews();
        setupLaneButtons();
        setupActions();
        renderDraft();
        renderBans();
        loadRoster();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        avatarCache.clear();
    }

    private void bindViews() {
        draftRows = findViewById(R.id.manualDraftRows);
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
        findViewById(R.id.addManualBanButton).setOnClickListener(view -> showBanPicker());
        recommendButton.setOnClickListener(view -> loadRelationsAndRecommend());
        findViewById(R.id.manualMatchupAnalysisButton).setOnClickListener(view -> {
            if (roster.isEmpty()) return;
            BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
            lineup.allies.addAll(allyHeroes()); lineup.enemies.addAll(enemyHeroes()); lineup.bans.addAll(bans);
            MatchupAnalysisActivity.open(this, roster, lineup, activeAnalyses, allySlots, enemySlots);
        });
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
                status.setText("左侧录入我方，右侧录入敌方，再选择待补分路。");
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

    private void renderDraft() {
        draftRows.removeAllViews();
        for (int index = 0; index < SLOT_COUNT; index++) {
            final int laneIndex = index;
            String lane = AppPrefs.LANES.get(index);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            View ally = createDraftSlot(allySlots[index], true, lane, () -> showSlotPicker(true, laneIndex));
            row.addView(ally, new LinearLayout.LayoutParams(0, dp(78), 1));

            TextView laneLabel = new TextView(this);
            laneLabel.setText(lane);
            laneLabel.setTextColor(getColor(R.color.muted));
            laneLabel.setTextSize(10);
            laneLabel.setGravity(Gravity.CENTER);
            row.addView(laneLabel, new LinearLayout.LayoutParams(dp(52), dp(78)));

            View enemy = createDraftSlot(enemySlots[index], false, lane, () -> showSlotPicker(false, laneIndex));
            row.addView(enemy, new LinearLayout.LayoutParams(0, dp(78), 1));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(78)
            );
            if (index > 0) rowParams.topMargin = dp(8);
            draftRows.addView(row, rowParams);
        }
    }

    private View createDraftSlot(BpModels.Hero hero, boolean ally, String lane, Runnable click) {
        LinearLayout slot = new LinearLayout(this);
        slot.setOrientation(LinearLayout.HORIZONTAL);
        slot.setGravity(Gravity.CENTER_VERTICAL);
        slot.setPadding(dp(9), dp(8), dp(9), dp(8));
        slot.setBackgroundResource(ally ? R.drawable.bg_slot_ally : R.drawable.bg_slot_enemy);
        slot.setClickable(true);
        slot.setFocusable(true);
        slot.setContentDescription((ally ? "我方" : "敌方") + lane + "，" + (hero == null ? "选择英雄" : hero.name));
        slot.setOnClickListener(view -> click.run());

        if (hero != null) {
            ImageView avatar = heroAvatar(hero, 46);
            if (avatar != null) {
                LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(46), dp(46));
                avatarParams.setMarginEnd(dp(8));
                slot.addView(avatar, avatarParams);
            }
        }

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(hero == null ? "选择英雄" : hero.name);
        name.setTextColor(getColor(R.color.ink));
        name.setTextSize(hero == null ? 12 : 13);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        copy.addView(name, matchWrap());

        TextView meta = new TextView(this);
        String detail = hero == null ? "点击录入" : heroMeta(hero, false);
        meta.setText(detail);
        meta.setTextColor(ally ? getColor(R.color.cyan) : getColor(R.color.danger));
        meta.setTextSize(9);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams metaParams = matchWrap();
        metaParams.topMargin = dp(3);
        copy.addView(meta, metaParams);

        slot.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return slot;
    }

    private void renderBans() {
        banList.removeAllViews();
        if (bans.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚未设置 BAN 英雄");
            empty.setTextColor(getColor(R.color.muted_2));
            empty.setTextSize(11);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            banList.addView(empty, new LinearLayout.LayoutParams(dp(132), dp(48)));
            return;
        }
        for (BpModels.Hero hero : new ArrayList<>(bans)) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(7), dp(5), dp(10), dp(5));
            chip.setBackgroundResource(R.drawable.bg_skip);
            chip.setClickable(true);
            chip.setContentDescription("移除 BAN 英雄" + hero.name);

            ImageView avatar = heroAvatar(hero, 34);
            if (avatar != null) chip.addView(avatar, new LinearLayout.LayoutParams(dp(34), dp(34)));

            TextView label = new TextView(this);
            label.setText(hero.name + "\n点击移除");
            label.setTextColor(getColor(R.color.ink));
            label.setTextSize(10);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(dp(7), 0, 0, 0);
            chip.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
            chip.setOnClickListener(view -> {
                bans.removeIf(value -> value.id == hero.id);
                renderBans();
                invalidateRecommendations("已移除 BAN 英雄“" + hero.name + "”。");
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48)
            );
            params.setMarginEnd(dp(8));
            banList.addView(chip, params);
        }
    }

    private void showSlotPicker(boolean ally, int laneIndex) {
        BpModels.Hero current = ally ? allySlots[laneIndex] : enemySlots[laneIndex];
        String lane = AppPrefs.LANES.get(laneIndex);
        showHeroPicker(
            "选择" + lane + "英雄",
            lane,
            current,
            hero -> {
                if (ally) allySlots[laneIndex] = hero;
                else enemySlots[laneIndex] = hero;
                renderDraft();
                invalidateRecommendations("已将“" + hero.name + "”录入" + (ally ? "我方" : "敌方") + lane + "。");
            },
            current == null ? null : () -> {
                if (ally) allySlots[laneIndex] = null;
                else enemySlots[laneIndex] = null;
                renderDraft();
                invalidateRecommendations("已清空" + (ally ? "我方" : "敌方") + lane + "。");
            }
        );
    }

    private void showBanPicker() {
        if (bans.size() >= 10) {
            status.setText("BAN 位已达到 10 位上限。");
            return;
        }
        showHeroPicker(
            "选择已被 BAN 的英雄",
            null,
            null,
            hero -> {
                bans.add(hero);
                renderBans();
                invalidateRecommendations("已将“" + hero.name + "”设为 BAN。");
            },
            null
        );
    }

    private void showHeroPicker(
        String title,
        String laneFilter,
        BpModels.Hero current,
        HeroSelectionHandler onSelected,
        Runnable onClear
    ) {
        if (roster.isEmpty()) {
            status.setText("英雄数据仍在准备，请稍候。");
            return;
        }

        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(14));
        root.setBackgroundResource(R.drawable.bg_dialog);

        View handle = new View(this);
        handle.setBackgroundColor(getColor(R.color.line));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(44), dp(3));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        root.addView(handle, handleParams);

        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headingCopy = new LinearLayout(this);
        headingCopy.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = new TextView(this);
        eyebrow.setText("英雄池 · " + roster.size() + " 名");
        eyebrow.setTextColor(getColor(R.color.muted));
        eyebrow.setTextSize(10);
        headingCopy.addView(eyebrow, matchWrap());
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(getColor(R.color.ink));
        heading.setTextSize(19);
        heading.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(3);
        headingCopy.addView(heading, titleParams);
        headingRow.addView(headingCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button close = new Button(this);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextSize(11);
        close.setTextColor(getColor(R.color.muted));
        close.setBackgroundResource(R.drawable.bg_outline);
        close.setOnClickListener(view -> dialog.dismiss());
        headingRow.addView(close, new LinearLayout.LayoutParams(dp(68), dp(40)));
        root.addView(headingRow, matchWrap());

        EditText search = new EditText(this);
        search.setHint("搜索英雄，如：庄周");
        search.setSingleLine(true);
        search.setTextColor(getColor(R.color.ink));
        search.setHintTextColor(getColor(R.color.muted_2));
        search.setBackgroundResource(R.drawable.bg_outline);
        search.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        );
        searchParams.topMargin = dp(14);
        root.addView(search, searchParams);

        TextView resultStatus = new TextView(this);
        resultStatus.setTextColor(getColor(R.color.muted_2));
        resultStatus.setTextSize(10);
        LinearLayout.LayoutParams resultParams = matchWrap();
        resultParams.topMargin = dp(9);
        resultParams.bottomMargin = dp(5);
        root.addView(resultStatus, resultParams);

        if (onClear != null) {
            Button clear = new Button(this);
            clear.setText("移除此位置的英雄");
            clear.setAllCaps(false);
            clear.setTextColor(getColor(R.color.danger));
            clear.setTextSize(11);
            clear.setBackgroundResource(R.drawable.bg_skip);
            clear.setOnClickListener(view -> {
                onClear.run();
                dialog.dismiss();
            });
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            );
            clearParams.bottomMargin = dp(7);
            root.addView(clear, clearParams);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        GridLayout choices = new GridLayout(this);
        choices.setColumnCount(3);
        choices.setPadding(0, dp(2), 0, dp(8));
        scroll.addView(choices, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        ));

        Runnable rebuild = () -> renderHeroChoices(
            choices,
            resultStatus,
            search.getText().toString(),
            laneFilter,
            current,
            hero -> {
                onSelected.onSelect(hero);
                dialog.dismiss();
            }
        );
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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.8f;
        attributes.gravity = Gravity.BOTTOM;
        window.setAttributes(attributes);
        int width = getResources().getDisplayMetrics().widthPixels - dp(12);
        int height = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.78f), dp(720));
        window.setLayout(width, height);
    }

    private void renderHeroChoices(
        GridLayout container,
        TextView resultStatus,
        String query,
        String laneFilter,
        BpModels.Hero current,
        HeroSelectionHandler onSelected
    ) {
        container.removeAllViews();
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<Integer> unavailable = selectedHeroIds();
        if (current != null) unavailable.remove(current.id);

        List<BpModels.Hero> matches = new ArrayList<>();
        for (BpModels.Hero hero : roster) {
            if (unavailable.contains(hero.id)) continue;
            if (laneFilter != null && !hero.supports(laneFilter)) continue;
            if (!normalized.isEmpty() && !hero.name.toLowerCase(Locale.ROOT).contains(normalized)) continue;
            matches.add(hero);
        }
        matches.sort(Comparator
            .comparingDouble((BpModels.Hero hero) -> hero.tierScore == null ? -1 : hero.tierScore).reversed()
            .thenComparing(hero -> hero.name));

        String scope = laneFilter == null ? "全部英雄" : "已筛选 " + laneFilter;
        resultStatus.setText(scope + "  ·  " + matches.size() + " 个结果");
        for (BpModels.Hero hero : matches) {
            LinearLayout card = createHeroChoice(hero, current != null && current.id == hero.id, onSelected);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            );
            params.width = 0;
            params.height = dp(124);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            container.addView(card, params);
        }

        if (matches.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有匹配的可用英雄");
            empty.setTextColor(getColor(R.color.muted));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(36), 0, dp(36));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(0, 3)
            );
            params.width = GridLayout.LayoutParams.MATCH_PARENT;
            container.addView(empty, params);
        }
    }

    private LinearLayout createHeroChoice(
        BpModels.Hero hero,
        boolean selected,
        HeroSelectionHandler onSelected
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(6), dp(8), dp(6), dp(6));
        card.setBackgroundResource(selected ? R.drawable.bg_step : R.drawable.bg_outline);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(hero.name + " " + heroMeta(hero, true));
        card.setOnClickListener(view -> onSelected.onSelect(hero));

        ImageView avatar = heroAvatar(hero, 56);
        if (avatar != null) card.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView name = new TextView(this);
        name.setText(hero.name);
        name.setTextColor(getColor(R.color.ink));
        name.setTextSize(11);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = matchWrap();
        nameParams.topMargin = dp(5);
        card.addView(name, nameParams);

        TextView meta = new TextView(this);
        meta.setText(heroMeta(hero, true));
        meta.setTextColor(getColor(R.color.muted_2));
        meta.setTextSize(8);
        meta.setGravity(Gravity.CENTER);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams metaParams = matchWrap();
        metaParams.topMargin = dp(2);
        card.addView(meta, metaParams);
        return card;
    }

    private void loadRelationsAndRecommend() {
        if (roster.isEmpty()) return;
        List<BpModels.Hero> selected = allyHeroes();
        selected.addAll(enemyHeroes());
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
        lineup.allies.addAll(allyHeroes());
        lineup.enemies.addAll(enemyHeroes());
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
            empty.setText("暂无符合该分路的可用候选，请调整阵容、BAN 位或待补位置。");
            empty.setTextColor(getColor(R.color.muted));
            empty.setPadding(0, dp(14), 0, dp(14));
            recommendationList.addView(empty, matchWrap());
            return;
        }

        int targetLaneIndex = laneIndex(AppPrefs.lane(this));
        for (int index = 0; index < values.size(); index++) {
            BpModels.Recommendation value = values.get(index);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(index == 0 ? R.drawable.bg_step : R.drawable.bg_outline);
            card.setPadding(dp(12), dp(12), dp(12), dp(10));

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            ImageView avatar = heroAvatar(value.hero, 48);
            if (avatar != null) {
                LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(48), dp(48));
                avatarParams.setMarginEnd(dp(10));
                top.addView(avatar, avatarParams);
            }

            LinearLayout heroCopy = new LinearLayout(this);
            heroCopy.setOrientation(LinearLayout.VERTICAL);
            TextView heroName = new TextView(this);
            heroName.setText((index == 0 ? "首选 · " : (index + 1) + " · ") + value.hero.name);
            heroName.setTextColor(getColor(R.color.ink));
            heroName.setTextSize(15);
            heroName.setTypeface(null, Typeface.BOLD);
            heroCopy.addView(heroName, matchWrap());
            TextView role = new TextView(this);
            role.setText(heroMeta(value.hero, false));
            role.setTextColor(getColor(R.color.muted));
            role.setTextSize(10);
            LinearLayout.LayoutParams roleParams = matchWrap();
            roleParams.topMargin = dp(3);
            heroCopy.addView(role, roleParams);
            top.addView(heroCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView score = new TextView(this);
            score.setText(String.valueOf(value.score));
            score.setTextColor(index == 0 ? getColor(R.color.gold) : getColor(R.color.blue));
            score.setTextSize(24);
            score.setTypeface(null, Typeface.BOLD);
            score.setGravity(Gravity.CENTER);
            score.setContentDescription("推荐分 " + value.score);
            top.addView(score, new LinearLayout.LayoutParams(dp(52), dp(48)));
            card.addView(top, matchWrap());

            LinearLayout metrics = new LinearLayout(this);
            metrics.setGravity(Gravity.CENTER);
            metrics.addView(metric("对敌", value.matchupScore), new LinearLayout.LayoutParams(0, dp(42), 1));
            metrics.addView(metric("配合", value.synergyScore), new LinearLayout.LayoutParams(0, dp(42), 1));
            metrics.addView(metric("梯度", value.tierScore), new LinearLayout.LayoutParams(0, dp(42), 1));
            LinearLayout.LayoutParams metricsParams = matchWrap();
            metricsParams.topMargin = dp(8);
            card.addView(metrics, metricsParams);

            TextView summary = new TextView(this);
            summary.setText(value.summary);
            summary.setTextColor(getColor(R.color.muted));
            summary.setTextSize(10);
            summary.setLineSpacing(0, 1.12f);
            LinearLayout.LayoutParams summaryParams = matchWrap();
            summaryParams.topMargin = dp(7);
            card.addView(summary, summaryParams);

            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            actions.setPadding(0, dp(9), 0, 0);

            Button ban = actionButton("设为 BAN", R.drawable.bg_skip);
            ban.setOnClickListener(view -> {
                if (bans.size() < 10 && selectedHeroIds().add(value.hero.id)) {
                    bans.add(value.hero);
                    renderBans();
                    invalidateRecommendations("已将“" + value.hero.name + "”设为 BAN，请重新生成推荐。");
                }
            });
            actions.addView(ban, actionParams(84));

            Button dismiss = actionButton("不想玩", R.drawable.bg_skip);
            dismiss.setOnClickListener(view -> {
                dismissedHeroIds.add(value.hero.id);
                refreshRecommendations("已跳过“" + value.hero.name + "”，后续顺位已补上");
            });
            actions.addView(dismiss, actionParams(76));

            Button add = actionButton("加入" + AppPrefs.lane(this), R.drawable.bg_primary);
            add.setOnClickListener(view -> {
                if (targetLaneIndex >= 0) {
                    allySlots[targetLaneIndex] = value.hero;
                    renderDraft();
                    invalidateRecommendations("已将“" + value.hero.name + "”加入我方" + AppPrefs.lane(this) + "。");
                }
            });
            actions.addView(add, actionParams(92));
            card.addView(actions, matchWrap());

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.bottomMargin = dp(9);
            recommendationList.addView(card, cardParams);
        }
    }

    private TextView metric(String label, int value) {
        TextView view = new TextView(this);
        view.setText(label + "\n" + value);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getColor(R.color.muted));
        view.setTextSize(10);
        return view;
    }

    private Button actionButton(String text, int background) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(getColor(R.color.ink));
        button.setTextSize(10);
        button.setBackgroundResource(background);
        button.setPadding(dp(5), 0, dp(5), 0);
        return button;
    }

    private LinearLayout.LayoutParams actionParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(40));
        params.setMarginStart(dp(7));
        return params;
    }

    private void invalidateRecommendations(String message) {
        dismissedHeroIds.clear();
        activeAnalyses = Collections.emptyMap();
        recommendationList.removeAllViews();
        recommendationSection.setVisibility(View.GONE);
        status.setText(message);
    }

    private void clearDraft() {
        Arrays.fill(allySlots, null);
        Arrays.fill(enemySlots, null);
        bans.clear();
        renderDraft();
        renderBans();
        invalidateRecommendations("阵容与 BAN 位已清空。");
    }

    private List<BpModels.Hero> allyHeroes() {
        List<BpModels.Hero> heroes = new ArrayList<>();
        for (BpModels.Hero hero : allySlots) if (hero != null) heroes.add(hero);
        return heroes;
    }

    private List<BpModels.Hero> enemyHeroes() {
        List<BpModels.Hero> heroes = new ArrayList<>();
        for (BpModels.Hero hero : enemySlots) if (hero != null) heroes.add(hero);
        return heroes;
    }

    private Set<Integer> selectedHeroIds() {
        Set<Integer> ids = new HashSet<>();
        for (BpModels.Hero hero : allySlots) if (hero != null) ids.add(hero.id);
        for (BpModels.Hero hero : enemySlots) if (hero != null) ids.add(hero.id);
        for (BpModels.Hero hero : bans) ids.add(hero.id);
        return ids;
    }

    private int laneIndex(String lane) {
        for (int index = 0; index < AppPrefs.LANES.size(); index++) {
            if (AppPrefs.LANES.get(index).equals(lane)) return index;
        }
        return -1;
    }

    private String heroMeta(BpModels.Hero hero, boolean compact) {
        String tier = hero.tier == null || hero.tier.isBlank() ? "未定级" : hero.tier;
        String roles = hero.roles == null || hero.roles.isBlank() ? "多位置" : hero.roles;
        return compact ? tier + " · " + roles : roles + " · " + tier;
    }

    private void indexSeedAvatars() {
        try {
            String[] names = getAssets().list("avatar_seed");
            if (names == null) return;
            for (String name : names) {
                int dash = name.indexOf('-');
                if (dash <= 0) continue;
                try {
                    seedAssetByHeroId.put(Integer.parseInt(name.substring(0, dash)), name);
                } catch (NumberFormatException ignored) {
                    // Ignore unexpected asset names.
                }
            }
        } catch (Exception ignored) {
            // Text-only cards remain usable if assets cannot be indexed.
        }
    }

    private ImageView heroAvatar(BpModels.Hero hero, int sizeDp) {
        Bitmap bitmap = loadAvatar(hero);
        if (bitmap == null) return null;
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundResource(R.drawable.bg_avatar_frame);
        image.setClipToOutline(true);
        image.setContentDescription(hero.name + "头像");
        image.setMinimumWidth(dp(sizeDp));
        image.setMinimumHeight(dp(sizeDp));
        return image;
    }

    private Bitmap loadAvatar(BpModels.Hero hero) {
        int heroId = hero.id;
        Bitmap cached = avatarCache.get(heroId);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap decoded = null;
        File directory = new File(getFilesDir(), "avatar-library");
        String currentKey = heroId + "-" + AvatarRecognitionEngine.shortHash(hero.avatarUrl) + ".img";
        File current = new File(directory, currentKey);
        if (current.isFile()) decoded = BitmapFactory.decodeFile(current.getAbsolutePath());

        if (decoded == null) {
            try (InputStream input = getAssets().open("avatar_seed/" + currentKey)) {
                decoded = BitmapFactory.decodeStream(input);
            } catch (Exception ignored) {
                // A version update may change the URL fingerprint before the new portrait is cached.
            }
        }
        if (decoded == null) {
            File[] files = directory.listFiles((dir, name) -> name.startsWith(heroId + "-") && name.endsWith(".img"));
            if (files != null && files.length > 0) decoded = BitmapFactory.decodeFile(files[0].getAbsolutePath());
        }
        if (decoded == null) {
            String fallbackAsset = seedAssetByHeroId.get(heroId);
            if (fallbackAsset != null) {
                try (InputStream input = getAssets().open("avatar_seed/" + fallbackAsset)) {
                    decoded = BitmapFactory.decodeStream(input);
                } catch (Exception ignored) {
                    // Text-only card remains available.
                }
            }
        }
        if (decoded != null) avatarCache.put(heroId, decoded);
        return decoded;
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

    private interface HeroSelectionHandler {
        void onSelect(BpModels.Hero hero);
    }
}
