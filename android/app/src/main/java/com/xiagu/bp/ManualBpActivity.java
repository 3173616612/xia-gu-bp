package com.xiagu.bp;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manual draft board: allies left, enemies right, aligned by lane, with the lane spine choosing the
 * lane to fill. Recommendations refresh by themselves; relations are fetched once per hero and cached.
 */
public final class ManualBpActivity extends Activity {
    private static final int SLOT_COUNT = 5;
    private static final int MAX_BANS = 10;
    private static final long REFRESH_DELAY_MS = 200;
    private static final String STATE_ALLIES = "manual_ally_ids";
    private static final String STATE_ENEMIES = "manual_enemy_ids";
    private static final String STATE_BANS = "manual_ban_ids";

    private final List<BpModels.Hero> roster = new ArrayList<>();
    private final BpModels.Hero[] allySlots = new BpModels.Hero[SLOT_COUNT];
    private final BpModels.Hero[] enemySlots = new BpModels.Hero[SLOT_COUNT];
    private final List<BpModels.Hero> bans = new ArrayList<>();
    private final Set<Integer> dismissedHeroIds = new LinkedHashSet<>();
    private final Map<Integer, BpModels.Analysis> analysisCache = new HashMap<>();
    private final Set<Integer> inFlight = new HashSet<>();
    private final View[] rows = new View[SLOT_COUNT];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refreshRecommendations;

    private LinearLayout draftRows;
    private LinearLayout banList;
    private LinearLayout recommendationList;
    private TextView dataStatus;
    private TextView status;
    private TextView banCount;
    private TextView recTitle;
    private Button retryButton;
    private Runnable retryAction;
    private Runnable undoAction;
    private boolean rosterReady;
    private boolean relationFailure;
    private String transientMessage;
    private int[] pendingAllyIds;
    private int[] pendingEnemyIds;
    private int[] pendingBanIds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manual_bp);
        Ui.padForSystemBars(findViewById(R.id.manualRoot));
        bindViews();
        buildRows();
        setupActions();
        if (savedInstanceState != null) {
            pendingAllyIds = savedInstanceState.getIntArray(STATE_ALLIES);
            pendingEnemyIds = savedInstanceState.getIntArray(STATE_ENEMIES);
            pendingBanIds = savedInstanceState.getIntArray(STATE_BANS);
        }
        renderDraft();
        renderBans();
        loadRoster();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!rosterReady) {
            if (pendingAllyIds != null) outState.putIntArray(STATE_ALLIES, pendingAllyIds);
            if (pendingEnemyIds != null) outState.putIntArray(STATE_ENEMIES, pendingEnemyIds);
            if (pendingBanIds != null) outState.putIntArray(STATE_BANS, pendingBanIds);
            return;
        }
        outState.putIntArray(STATE_ALLIES, ids(Arrays.asList(allySlots)));
        outState.putIntArray(STATE_ENEMIES, ids(Arrays.asList(enemySlots)));
        outState.putIntArray(STATE_BANS, ids(bans));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        draftRows = findViewById(R.id.manualDraftRows);
        banList = findViewById(R.id.manualBanList);
        recommendationList = findViewById(R.id.manualRecommendationList);
        dataStatus = findViewById(R.id.manualDataStatus);
        status = findViewById(R.id.manualStatus);
        banCount = findViewById(R.id.manualBanCount);
        recTitle = findViewById(R.id.manualRecTitle);
        retryButton = findViewById(R.id.manualRecommendButton);
    }

    private void buildRows() {
        LayoutInflater inflater = LayoutInflater.from(this);
        draftRows.removeAllViews();
        for (int index = 0; index < SLOT_COUNT; index++) {
            View row = inflater.inflate(R.layout.item_draft_row, draftRows, false);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (index > 0) params.topMargin = Ui.dp(this, 6);
            draftRows.addView(row, params);
            rows[index] = row;
        }
    }

    private void setupActions() {
        findViewById(R.id.manualBackButton).setOnClickListener(view -> finish());
        findViewById(R.id.manualClearButton).setOnClickListener(view -> clearDraft());
        retryButton.setOnClickListener(view -> {
            if (retryAction != null) retryAction.run();
        });
        findViewById(R.id.manualMatchupAnalysisButton).setOnClickListener(view -> {
            if (!rosterReady) {
                status.setText("英雄数据还在准备，请稍候。");
                return;
            }
            BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
            lineup.allies.addAll(allyHeroes());
            lineup.enemies.addAll(enemyHeroes());
            lineup.bans.addAll(bans);
            Map<Integer, BpModels.Analysis> known = new LinkedHashMap<>();
            for (BpModels.Hero hero : selectedHeroes()) {
                BpModels.Analysis analysis = analysisCache.get(hero.id);
                if (analysis != null) known.put(hero.id, analysis);
            }
            MatchupAnalysisActivity.open(this, roster, lineup, known, allySlots, enemySlots);
        });
    }

    private void loadRoster() {
        retryButton.setVisibility(View.GONE);
        dataStatus.setText("正在同步英雄与梯度…");
        status.setText("英雄数据准备好后即可录入阵容。");
        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                if (isFinishing() || isDestroyed()) return;
                roster.clear();
                roster.addAll(heroes);
                rosterReady = true;
                String tierDate = BpApiClient.latestTierDate();
                dataStatus.setText(heroes.size() + " 位英雄" + (tierDate.isBlank() ? "" : "，梯度更新于 " + shortDate(tierDate)));
                restorePendingState();
                PinyinIndex.warm(roster);
                renderDraft();
                renderBans();
                scheduleRefresh(0);
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                dataStatus.setText("英雄数据同步失败");
                status.setText(message);
                showRetry("重新同步", ManualBpActivity.this::loadRoster);
            }
        });
    }

    private void restorePendingState() {
        Map<Integer, BpModels.Hero> byId = new HashMap<>();
        for (BpModels.Hero hero : roster) byId.put(hero.id, hero);
        if (pendingAllyIds != null) {
            for (int i = 0; i < Math.min(SLOT_COUNT, pendingAllyIds.length); i++) allySlots[i] = byId.get(pendingAllyIds[i]);
        }
        if (pendingEnemyIds != null) {
            for (int i = 0; i < Math.min(SLOT_COUNT, pendingEnemyIds.length); i++) enemySlots[i] = byId.get(pendingEnemyIds[i]);
        }
        if (pendingBanIds != null) {
            for (int id : pendingBanIds) {
                BpModels.Hero hero = byId.get(id);
                if (hero != null && bans.size() < MAX_BANS) bans.add(hero);
            }
        }
        pendingAllyIds = null;
        pendingEnemyIds = null;
        pendingBanIds = null;
    }

    // ---- Draft board -------------------------------------------------------------------------

    private void renderDraft() {
        String target = AppPrefs.lane(this);
        for (int index = 0; index < SLOT_COUNT; index++) {
            View row = rows[index];
            String lane = AppPrefs.LANES.get(index);
            bindSlot(row, true, index, lane, lane.equals(target));
            bindSlot(row, false, index, lane, false);
            TextView pill = row.findViewById(R.id.lanePill);
            View touch = row.findViewById(R.id.laneTouch);
            boolean isTarget = lane.equals(target);
            pill.setText(lane);
            pill.setTypeface(null, isTarget ? Typeface.BOLD : Typeface.NORMAL);
            // The whole spine cell is the touch target; the pill mirrors its pressed/activated state.
            touch.setActivated(isTarget);
            touch.setContentDescription(lane + (isTarget ? "，当前待补位置" : "，设为待补位置"));
            touch.setOnClickListener(view -> setTargetLane(lane));
        }
    }

    private void bindSlot(View row, boolean ally, int laneIndex, String lane, boolean isTarget) {
        BpModels.Hero hero = ally ? allySlots[laneIndex] : enemySlots[laneIndex];
        LinearLayout slot = row.findViewById(ally ? R.id.allySlot : R.id.enemySlot);
        HeroAvatarView avatar = row.findViewById(ally ? R.id.allyAvatar : R.id.enemyAvatar);
        TextView name = row.findViewById(ally ? R.id.allyName : R.id.enemyName);
        TextView meta = row.findViewById(ally ? R.id.allyMeta : R.id.enemyMeta);
        String side = ally ? "我方" : "敌方";

        int edge = Ui.dp(this, 7);
        int inner = Ui.dp(this, 8);
        if (hero != null) {
            slot.setBackgroundResource(ally ? R.drawable.bg_slot_ally : R.drawable.bg_slot_enemy);
            avatar.setVisibility(View.VISIBLE);
            HeroAvatars.bind(avatar, hero);
            avatar.setShowTier(true);
            name.setText(hero.name);
            name.setTextColor(getColor(R.color.frost));
            name.setTypeface(null, Typeface.BOLD);
            name.setTextSize(14);
            name.setGravity((ally ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
            boolean unusual = !hero.positions.isEmpty() && !hero.supports(lane);
            meta.setVisibility(unusual ? View.VISIBLE : View.GONE);
            meta.setText("非常规分路");
            meta.setTextColor(getColor(R.color.caution_text));
            meta.setGravity((ally ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
            slot.setContentDescription(side + lane + "，" + hero.name + (unusual ? "，非常规分路" : "") + "。点按替换或移除");
        } else {
            slot.setBackgroundResource(isTarget ? R.drawable.bg_slot_target : R.drawable.bg_slot_empty);
            avatar.setVisibility(View.GONE);
            HeroAvatars.bind(avatar, null);
            name.setText(isTarget ? "待补位置" : "＋ 选择英雄");
            name.setTextColor(getColor(isTarget ? R.color.crown : R.color.dusk));
            name.setTypeface(null, isTarget ? Typeface.BOLD : Typeface.NORMAL);
            name.setTextSize(isTarget ? 14 : 13);
            name.setGravity(Gravity.CENTER);
            meta.setVisibility(isTarget ? View.VISIBLE : View.GONE);
            meta.setText("推荐见下方");
            meta.setTextColor(getColor(R.color.crown_dim));
            meta.setGravity(Gravity.CENTER);
            slot.setContentDescription(side + lane + (isTarget ? "，待补位置" : "") + "，点按选择英雄");
        }
        // Background drawables can reset padding; keep the stripe side a little roomier.
        if (ally) slot.setPadding(edge, 0, inner, 0);
        else slot.setPadding(inner, 0, edge, 0);
        slot.setOnClickListener(view -> showSlotPicker(ally, laneIndex));
    }

    private void setTargetLane(String lane) {
        if (lane.equals(AppPrefs.lane(this))) return;
        AppPrefs.setLane(this, lane);
        dismissedHeroIds.clear();
        renderDraft();
        scheduleRefresh(0);
    }

    private void renderBans() {
        banList.removeAllViews();
        int size = Ui.dp(this, 36);
        int gap = Ui.dp(this, 8);
        for (BpModels.Hero hero : new ArrayList<>(bans)) {
            HeroAvatarView avatar = new HeroAvatarView(this);
            HeroAvatars.bind(avatar, hero);
            avatar.setBanned(true);
            avatar.setClickable(true);
            avatar.setFocusable(true);
            avatar.setForeground(getDrawable(R.drawable.bg_row_pressable));
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            avatar.setContentDescription("已 BAN " + hero.name + "，点按取消");
            avatar.setOnClickListener(view -> {
                bans.removeIf(value -> value.id == hero.id);
                renderBans();
                onDraftChanged(null);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(gap);
            banList.addView(avatar, params);
        }
        if (bans.size() < MAX_BANS) {
            FrameLayout add = new FrameLayout(this);
            add.setBackgroundResource(R.drawable.bg_add_tile);
            add.setClickable(true);
            add.setFocusable(true);
            add.setContentDescription("添加 BAN 英雄");
            ImageView plus = new ImageView(this);
            plus.setImageResource(R.drawable.ic_plus);
            plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            add.addView(plus, new FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20), Gravity.CENTER));
            add.setOnClickListener(view -> showBanPicker());
            banList.addView(add, new LinearLayout.LayoutParams(size, size));
        }
        banCount.setText(bans.size() + "/" + MAX_BANS);
    }

    private void showSlotPicker(boolean ally, int laneIndex) {
        if (!rosterReady) {
            status.setText("英雄数据还在准备，请稍候。");
            return;
        }
        BpModels.Hero[] slots = ally ? allySlots : enemySlots;
        BpModels.Hero current = slots[laneIndex];
        String lane = AppPrefs.LANES.get(laneIndex);
        HeroPickerSheet.Request request = new HeroPickerSheet.Request();
        request.title = current == null ? "选择英雄" : "替换英雄";
        request.context = (ally ? "我方" : "敌方") + lane;
        request.contextDot = ally ? R.drawable.dot_ally : R.drawable.dot_enemy;
        request.lane = lane;
        request.unavailable.addAll(selectedHeroIds());
        request.current = current;
        if (current != null) {
            request.clearLabel = "移除" + current.name;
            request.onClear = () -> {
                slots[laneIndex] = null;
                onDraftChanged(null);
            };
        }
        HeroPickerSheet.show(this, roster, request, hero -> {
            slots[laneIndex] = hero;
            onDraftChanged(null);
        });
    }

    private void showBanPicker() {
        if (!rosterReady) {
            status.setText("英雄数据还在准备，请稍候。");
            return;
        }
        if (bans.size() >= MAX_BANS) {
            status.setText("BAN 位最多 10 个。");
            return;
        }
        HeroPickerSheet.Request request = new HeroPickerSheet.Request();
        request.title = "添加 BAN";
        request.context = "已 BAN 的英雄不会出现在推荐里";
        request.contextDot = R.drawable.dot_neutral;
        request.unavailable.addAll(selectedHeroIds());
        HeroPickerSheet.show(this, roster, request, hero -> {
            if (bans.size() >= MAX_BANS || bans.stream().anyMatch(value -> value.id == hero.id)) return;
            bans.add(hero);
            renderBans();
            onDraftChanged(null);
        });
    }

    private void onDraftChanged(String message) {
        dismissedHeroIds.clear();
        undoAction = null;
        transientMessage = message;
        renderDraft();
        scheduleRefresh(REFRESH_DELAY_MS);
    }

    private void clearDraft() {
        if (allyHeroes().isEmpty() && enemyHeroes().isEmpty() && bans.isEmpty()) return;
        BpModels.Hero[] previousAllies = allySlots.clone();
        BpModels.Hero[] previousEnemies = enemySlots.clone();
        List<BpModels.Hero> previousBans = new ArrayList<>(bans);
        Arrays.fill(allySlots, null);
        Arrays.fill(enemySlots, null);
        bans.clear();
        renderBans();
        onDraftChanged("阵容和 BAN 位已清空。");
        undoAction = () -> {
            System.arraycopy(previousAllies, 0, allySlots, 0, SLOT_COUNT);
            System.arraycopy(previousEnemies, 0, enemySlots, 0, SLOT_COUNT);
            bans.clear();
            bans.addAll(previousBans);
            renderBans();
            onDraftChanged("已恢复清空前的阵容。");
        };
    }

    // ---- Recommendations ---------------------------------------------------------------------

    private void scheduleRefresh(long delayMs) {
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, delayMs);
    }

    /** Fetches relations only for heroes not cached yet, then re-scores with whatever is known. */
    private void refreshRecommendations() {
        if (!rosterReady) return;
        List<BpModels.Hero> missing = new ArrayList<>();
        for (BpModels.Hero hero : selectedHeroes()) {
            if (!analysisCache.containsKey(hero.id) && !inFlight.contains(hero.id)) missing.add(hero);
        }
        if (!missing.isEmpty()) {
            relationFailure = false;
            for (BpModels.Hero hero : missing) inFlight.add(hero.id);
            BpApiClient.loadAnalyses(missing, new BpApiClient.Callback<>() {
                @Override
                public void onSuccess(Map<Integer, BpModels.Analysis> analyses) {
                    for (BpModels.Hero hero : missing) inFlight.remove(hero.id);
                    if (isFinishing() || isDestroyed()) return;
                    analysisCache.putAll(analyses);
                    if (analyses.size() < missing.size()) relationFailure = true;
                    renderRecommendations();
                }

                @Override
                public void onError(String message) {
                    for (BpModels.Hero hero : missing) inFlight.remove(hero.id);
                    if (isFinishing() || isDestroyed()) return;
                    relationFailure = true;
                    renderRecommendations();
                }
            });
        }
        renderRecommendations();
    }

    private void renderRecommendations() {
        if (!rosterReady) return;
        String lane = AppPrefs.lane(this);
        int laneIndex = AppPrefs.LANES.indexOf(lane);
        BpModels.Hero replacing = laneIndex >= 0 ? allySlots[laneIndex] : null;

        // The hero being replaced is neither a teammate nor a candidate for its own slot.
        BpModels.DetectedLineup lineup = new BpModels.DetectedLineup();
        for (BpModels.Hero hero : allySlots) if (hero != null && hero != replacing) lineup.allies.add(hero);
        lineup.enemies.addAll(enemyHeroes());
        lineup.bans.addAll(bans);
        Set<Integer> excluded = new HashSet<>(dismissedHeroIds);
        if (replacing != null) excluded.add(replacing.id);
        List<BpModels.Recommendation> values = RecommendationEngine.recommend(roster, analysisCache, lineup, lane, excluded);

        recTitle.setText(replacing == null ? "为" + lane + "推荐" : "替换" + replacing.name + "的候选");
        renderStatus(lineup);
        recommendationList.removeAllViews();
        if (values.isEmpty()) {
            TextView empty = Ui.text(this, R.style.Text_Caption,
                "这个分路暂时没有可推荐的英雄。试试取消部分 BAN、恢复跳过的英雄，或换一个待补位置。");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = Ui.dp(this, 14);
            recommendationList.addView(empty, params);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int index = 0; index < values.size(); index++) {
            View card = inflater.inflate(R.layout.item_recommendation, recommendationList, false);
            bindRecommendation(card, values.get(index), index, lane, lineup);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
            params.topMargin = Ui.dp(this, 10);
            recommendationList.addView(card, params);
        }
    }

    private void renderStatus(BpModels.DetectedLineup lineup) {
        String message;
        boolean showRetry = false;
        if (!inFlight.isEmpty()) {
            message = "正在同步 " + inFlight.size() + " 位英雄的克制与配合关系…";
        } else if (relationFailure) {
            message = "部分英雄的关系没有同步成功，这些关系暂不计分。";
            showRetry = true;
        } else if (transientMessage != null) {
            message = transientMessage;
        } else if (lineup.enemies.isEmpty() && lineup.allies.isEmpty()) {
            message = "还没有录入英雄，先按当前梯度排序。录入双方阵容后会自动更新。";
        } else {
            List<String> parts = new ArrayList<>();
            if (!lineup.enemies.isEmpty()) parts.add("敌方 " + lineup.enemies.size() + " 人的克制");
            if (!lineup.allies.isEmpty()) parts.add("我方 " + lineup.allies.size() + " 人的配合");
            parts.add("当前梯度");
            message = "综合" + String.join("、", parts);
        }
        if (!dismissedHeroIds.isEmpty()) message += "。已跳过 " + dismissedHeroIds.size() + " 位";
        // A confirmation waits until relations finish syncing, then shows once.
        if (inFlight.isEmpty()) transientMessage = null;
        status.setText(message);
        if (showRetry) {
            showRetry("重新同步", () -> {
                relationFailure = false;
                retryButton.setVisibility(View.GONE);
                scheduleRefresh(0);
            });
        } else if (undoAction != null) {
            showRetry("撤销清空", undoAction);
        } else {
            retryButton.setVisibility(View.GONE);
        }
    }

    private void showRetry(String label, Runnable action) {
        retryAction = action;
        retryButton.setText(label);
        retryButton.setVisibility(View.VISIBLE);
    }

    private void bindRecommendation(View card, BpModels.Recommendation value, int index,
                                    String lane, BpModels.DetectedLineup lineup) {
        boolean first = index == 0;
        int paddingStart = Ui.dp(this, 14);
        card.setBackgroundResource(first ? R.drawable.bg_card_first : R.drawable.bg_surface);
        card.setPadding(paddingStart, Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 10));

        TextView rank = card.findViewById(R.id.recRank);
        rank.setText(String.valueOf(index + 1));
        rank.setTextColor(getColor(first ? R.color.crown : R.color.dusk));

        HeroAvatarView avatar = card.findViewById(R.id.recAvatar);
        HeroAvatars.bind(avatar, value.hero);
        avatar.setShowTier(true);
        avatar.setCornerRadiusDp(10);
        avatar.setRing(first ? getColor(R.color.crown) : 0, 2);

        ((TextView) card.findViewById(R.id.recName)).setText(value.hero.name);
        ((TextView) card.findViewById(R.id.recMeta)).setText(recommendationMeta(value));

        TextView score = card.findViewById(R.id.recScore);
        score.setText(String.valueOf(value.score));
        score.setTextColor(getColor(first ? R.color.crown : R.color.frost));

        boolean hasEnemies = !lineup.enemies.isEmpty();
        boolean hasAllies = !lineup.allies.isEmpty();
        bindMetric(card.findViewById(R.id.metricMatchup), "对敌", value.matchupScore, hasEnemies);
        bindMetric(card.findViewById(R.id.metricSynergy), "配合", value.synergyScore, hasAllies);
        bindMetric(card.findViewById(R.id.metricTier), "梯度", value.tierScore, true);
        bindMetric(card.findViewById(R.id.metricLift), "提升", value.counterLiftScore, hasEnemies);

        card.findViewById(R.id.recSummary).setContentDescription("第 " + (index + 1) + " 名，" + value.hero.name
            + "，推荐分 " + value.score
            + (hasEnemies ? "，对敌 " + value.matchupScore : "")
            + (hasAllies ? "，配合 " + value.synergyScore : "")
            + "，梯度 " + value.tierScore
            + (hasEnemies ? "，提升 " + value.counterLiftScore : ""));

        Button ban = card.findViewById(R.id.recBan);
        ban.setContentDescription("把" + value.hero.name + "设为 BAN");
        ban.setOnClickListener(view -> {
            if (bans.size() >= MAX_BANS) {
                status.setText("BAN 位最多 10 个。");
                return;
            }
            if (selectedHeroIds().contains(value.hero.id)) return;
            bans.add(value.hero);
            renderBans();
            onDraftChanged("已 BAN“" + value.hero.name + "”。");
        });

        Button skip = card.findViewById(R.id.recSkip);
        skip.setContentDescription("不想玩" + value.hero.name + "，换下一位");
        skip.setOnClickListener(view -> {
            dismissedHeroIds.add(value.hero.id);
            transientMessage = "已跳过“" + value.hero.name + "”，后面的候选按顺位补上";
            renderRecommendations();
        });

        Button add = card.findViewById(R.id.recAdd);
        add.setText("选入" + lane);
        add.setContentDescription("把" + value.hero.name + "选入我方" + lane);
        add.setOnClickListener(view -> {
            int laneIndex = AppPrefs.LANES.indexOf(lane);
            if (laneIndex < 0) return;
            allySlots[laneIndex] = value.hero;
            onDraftChanged("已把“" + value.hero.name + "”选入我方" + lane + "。");
        });
    }

    private CharSequence recommendationMeta(BpModels.Recommendation value) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        String positions = Ui.positions(value.hero);
        if (!positions.isEmpty()) text.append(positions);
        if (value.totalRelations > 0) {
            if (text.length() > 0) text.append("　");
            text.append("关系数据 ").append(String.valueOf(value.knownRelations)).append("/").append(String.valueOf(value.totalRelations));
        }
        if (value.negativePenalty > 0) {
            if (text.length() > 0) text.append("　");
            int start = text.length();
            text.append("负向扣 ").append(String.valueOf(value.negativePenalty));
            text.setSpan(new ForegroundColorSpan(getColor(R.color.enemy_text)), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void bindMetric(View metric, String label, int value, boolean applicable) {
        ((TextView) metric.findViewById(R.id.metricLabel)).setText(label);
        TextView number = metric.findViewById(R.id.metricValue);
        MetricBar bar = metric.findViewById(R.id.metricBar);
        number.setText(applicable ? String.valueOf(value) : "—");
        number.setTextColor(getColor(applicable ? R.color.frost : R.color.dusk));
        bar.setValue(applicable ? value : 50);
    }

    // ---- Helpers -----------------------------------------------------------------------------

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

    private List<BpModels.Hero> selectedHeroes() {
        List<BpModels.Hero> heroes = allyHeroes();
        heroes.addAll(enemyHeroes());
        return heroes;
    }

    private Set<Integer> selectedHeroIds() {
        Set<Integer> ids = new HashSet<>();
        for (BpModels.Hero hero : allySlots) if (hero != null) ids.add(hero.id);
        for (BpModels.Hero hero : enemySlots) if (hero != null) ids.add(hero.id);
        for (BpModels.Hero hero : bans) ids.add(hero.id);
        return ids;
    }

    private static int[] ids(List<BpModels.Hero> heroes) {
        int[] output = new int[heroes.size()];
        for (int i = 0; i < heroes.size(); i++) output[i] = heroes.get(i) == null ? 0 : heroes.get(i).id;
        return output;
    }

    private static String shortDate(String date) {
        return date.length() == 10 && date.charAt(4) == '-' ? date.substring(5) : date;
    }
}
