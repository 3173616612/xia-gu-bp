package com.xiagu.bp;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Post-scan report. Five lanes, duo combos and the cross matrix share exactly one data model. */
public final class MatchupAnalysisActivity extends Activity {
    private static final String SNAPSHOT = "matchup_snapshot";
    private MatchupSnapshot draft;
    private int tab, generation;
    private boolean loading;
    private String message = "";

    private TextView syncStatus;
    private TextView noticeText;
    private TextView unassigned;
    private Button editButton;
    private ImageButton refreshButton;
    private RadioGroup tabs;
    private LinearLayout body;

    static void open(Context context, List<BpModels.Hero> roster, BpModels.DetectedLineup lineup,
                     Map<Integer, BpModels.Analysis> analyses, BpModels.Hero[] allyLanes, BpModels.Hero[] enemyLanes) {
        try {
            MatchupSnapshot d = new MatchupSnapshot();
            d.roster.addAll(roster); d.lineup.allies.addAll(lineup.allies); d.lineup.enemies.addAll(lineup.enemies);
            d.lineup.bans.addAll(lineup.bans); if (analyses != null) d.analyses.putAll(analyses);
            d.allies = allyLanes == null ? MatchupAnalysisEngine.assignLanes(lineup.allies) : allyLanes.clone();
            d.enemies = enemyLanes == null ? MatchupAnalysisEngine.assignLanes(lineup.enemies) : enemyLanes.clone();
            d.lanesConfirmed = allyLanes != null; d.tierDate = BpApiClient.latestTierDate();
            Intent intent = new Intent(context, MatchupAnalysisActivity.class).putExtra(SNAPSHOT, d.encode());
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) { Toast.makeText(context, "无法打开对位分析，请重新识别", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            draft = MatchupSnapshot.decode(state == null ? getIntent().getStringExtra(SNAPSHOT) : state.getString(SNAPSHOT));
            tab = state == null ? 0 : state.getInt("tab");
            if (state != null) message = state.getString("message", "");
        } catch (Exception e) { Toast.makeText(this, "阵容记录已失效，请重新识别", Toast.LENGTH_LONG).show(); finish(); return; }
        setContentView(R.layout.activity_matchup);
        Ui.padForSystemBars(findViewById(R.id.matchupRoot));
        bindViews();
        render();
        boolean offlineFixture = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && getIntent().getBooleanExtra("debug_offline", false);
        if (!offlineFixture && (state == null || draft.analyses.isEmpty() || state.getBoolean("loading"))) refresh();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        try { out.putString(SNAPSHOT, draft.encode()); out.putInt("tab", tab); out.putString("message", message); out.putBoolean("loading", loading); } catch (Exception ignored) { }
        super.onSaveInstanceState(out);
    }

    private void bindViews() {
        syncStatus = findViewById(R.id.matchupSyncStatus);
        noticeText = findViewById(R.id.matchupNoticeText);
        unassigned = findViewById(R.id.matchupUnassigned);
        editButton = findViewById(R.id.matchupEditButton);
        refreshButton = findViewById(R.id.matchupRefresh);
        tabs = findViewById(R.id.matchupTabs);
        body = findViewById(R.id.matchupBody);
        findViewById(R.id.matchupBack).setOnClickListener(v -> finish());
        refreshButton.setOnClickListener(v -> refresh());
        editButton.setOnClickListener(v -> editAssignments());
        findViewById(R.id.matchupSource).setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://tianyuanzhiyi.com/"))); }
            catch (ActivityNotFoundException ignored) { Toast.makeText(this, "没有找到可以打开网页的应用", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void render() {
        syncStatus.setText(message.isEmpty() ? "巅峰千强关系数据" : message);
        refreshButton.setEnabled(!loading);
        refreshButton.setAlpha(loading ? 0.4f : 1f);
        noticeText.setText(draft.lanesConfirmed
            ? "按已确认的分路展示，这里的校正只影响本页分析。"
            : "分路由系统推断，不按选将顺序排列。多位置或非常规阵容请先校正。");
        editButton.setText(draft.lanesConfirmed ? "校正分路" : "确认分路");
        List<BpModels.Hero> looseA = MatchupAnalysisEngine.unassigned(draft.lineup.allies, draft.allies);
        List<BpModels.Hero> looseE = MatchupAnalysisEngine.unassigned(draft.lineup.enemies, draft.enemies);
        if (!looseA.isEmpty() || !looseE.isEmpty()) {
            unassigned.setVisibility(View.VISIBLE);
            unassigned.setText("待分配：我方 " + names(looseA) + "，敌方 " + names(looseE) + "。它们仍会出现在全对位里。");
        } else {
            unassigned.setVisibility(View.GONE);
        }

        tabs.setOnCheckedChangeListener(null);
        tabs.check(tab == 1 ? R.id.matchupTabCombos : tab == 2 ? R.id.matchupTabMatrix : R.id.matchupTabLanes);
        tabs.setOnCheckedChangeListener((group, checkedId) -> {
            tab = checkedId == R.id.matchupTabCombos ? 1 : checkedId == R.id.matchupTabMatrix ? 2 : 0;
            render();
        });

        body.removeAllViews();
        if (tab == 0) renderLanes(); else if (tab == 1) renderCombos(); else renderMatrix();
    }

    // ---- Five lanes ---------------------------------------------------------------------------

    private void renderLanes() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int lane = 0; lane < 5; lane++) {
            View item = inflater.inflate(R.layout.item_lane_duel, body, false);
            BpModels.Hero a = draft.allies[lane], e = draft.enemies[lane];
            MatchupAnalysisEngine.Evidence evidence = MatchupAnalysisEngine.counter(a, e, draft.analyses);
            Ui.Tone tone = Ui.tone(evidence);

            ((TextView) item.findViewById(R.id.duelLane)).setText(MatchupAnalysisEngine.LANES[lane]);
            TextView sample = item.findViewById(R.id.duelSample);
            if (a == null || e == null) {
                sample.setText("分路未齐全，可校正补录");
                sample.setTextColor(color(R.color.caution_text));
            } else if (evidence.known()) {
                boolean small = evidence.matches() > 0 && evidence.matches() < 100;
                sample.setText(evidence.matches() > 0 ? "样本 " + evidence.matches() + " 场" + (small ? "，小样本" : "") : "样本数未提供");
                sample.setTextColor(color(small ? R.color.caution : R.color.dusk));
            } else {
                sample.setText("—");
                sample.setTextColor(color(R.color.dusk));
            }

            bindDuelSide(item.findViewById(R.id.duelAllyAvatar), item.findViewById(R.id.duelAllyName), a, R.color.ally);
            bindDuelSide(item.findViewById(R.id.duelEnemyAvatar), item.findViewById(R.id.duelEnemyName), e, R.color.enemy);

            View pill = item.findViewById(R.id.duelPill);
            pill.setBackground(Ui.tonePill(this, tone, 10));
            TextView value = item.findViewById(R.id.duelValue);
            value.setText(evidence.known() ? Ui.signed(evidence.value()) : "—");
            value.setTextColor(Ui.toneText(this, tone));
            TextView label = item.findViewById(R.id.duelLabel);
            label.setText(Ui.counterLabel(tone));
            label.setTextColor(Ui.toneText(this, tone));

            View row = item.findViewById(R.id.duelRow);
            row.setContentDescription(MatchupAnalysisEngine.LANES[lane] + "，" + Ui.heroName(a) + " 对 " + Ui.heroName(e)
                + "，" + (evidence.known() ? "克制指数 " + Ui.signed(evidence.value()) + "，" : "") + Ui.counterLabel(tone));
            if (a != null && e != null) row.setOnClickListener(v -> showEvidence(a, e, evidence, false));
            else row.setOnClickListener(v -> editAssignments());

            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) item.getLayoutParams();
            params.topMargin = Ui.dp(this, 14);
            body.addView(item, params);
        }
    }

    private void bindDuelSide(HeroAvatarView avatar, TextView name, BpModels.Hero hero, int ringColor) {
        HeroAvatars.bind(avatar, hero);
        avatar.setRing(hero == null ? 0 : color(ringColor), 1.5f);
        name.setText(Ui.heroName(hero));
        name.setTextColor(color(hero == null ? R.color.dusk : R.color.frost));
        name.setTypeface(null, hero == null ? Typeface.NORMAL : Typeface.BOLD);
    }

    // ---- Duo combos ---------------------------------------------------------------------------

    private void renderCombos() {
        for (int c = 0; c < MatchupAnalysisEngine.COMBOS.length; c++) {
            int x = MatchupAnalysisEngine.COMBOS[c][0], y = MatchupAnalysisEngine.COMBOS[c][1];
            BpModels.Hero[] ours = {draft.allies[x], draft.allies[y]}, theirs = {draft.enemies[x], draft.enemies[y]};
            LinearLayout card = column();
            card.setBackgroundResource(R.drawable.bg_surface);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));

            int favorable = 0, unfavorable = 0, known = 0;
            double totalIndex = 0;
            for (BpModels.Hero a : ours) for (BpModels.Hero e : theirs) {
                MatchupAnalysisEngine.Evidence value = MatchupAnalysisEngine.counter(a, e, draft.analyses);
                if (value.known()) { known++; totalIndex += value.value(); if (value.value() > .25) favorable++; else if (value.value() < -.25) unfavorable++; }
            }
            double mean = known == 0 ? 0 : totalIndex / known;
            String trend = known == 0 ? "暂无对敌判断" : mean > .25 ? "已知对敌关系偏有利" : mean < -.25 ? "已知对敌关系偏不利" : "已知对敌关系接近均势";
            int trendColor = known == 0 ? R.color.dusk : mean > .25 ? R.color.edge : mean < -.25 ? R.color.enemy_text : R.color.caution;

            LinearLayout header = row();
            TextView title = Ui.text(this, R.style.Text_Heading, MatchupAnalysisEngine.COMBO_NAMES[c] + "联动");
            if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            header.addView(Ui.text(this, R.style.Text_Micro, "关系覆盖 " + known + "/4"));
            card.addView(header);

            TextView verdict = Ui.text(this, R.style.Text_Caption, trend + (known == 0 ? "" : "：有利 " + favorable + "，不利 " + unfavorable
                + "，交叉均值 " + Ui.signed(mean)));
            verdict.setTextColor(color(trendColor));
            margin(card, verdict, 4);

            LinearLayout pairs = row();
            pairs.setGravity(Gravity.CENTER_VERTICAL);
            pairs.addView(pairAvatars(ours, R.color.ally));
            TextView versus = Ui.text(this, R.style.Text_Number, "VS");
            versus.setTextColor(color(R.color.dusk));
            versus.setTextSize(12);
            versus.setGravity(Gravity.CENTER);
            pairs.addView(versus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            pairs.addView(pairAvatars(theirs, R.color.enemy));
            margin(card, pairs, 12);

            // 2 × 2 cross grid: rows are ours, columns are theirs.
            for (BpModels.Hero a : ours) {
                LinearLayout line = row();
                for (int k = 0; k < theirs.length; k++) {
                    BpModels.Hero e = theirs[k];
                    MatchupAnalysisEngine.Evidence value = MatchupAnalysisEngine.counter(a, e, draft.analyses);
                    View cell = crossCell(a, e, value);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
                    if (k > 0) params.setMarginStart(dp(6));
                    line.addView(cell, params);
                }
                margin(card, line, 6);
            }

            MatchupAnalysisEngine.Evidence ally = MatchupAnalysisEngine.synergy(ours[0], ours[1], draft.analyses);
            MatchupAnalysisEngine.Evidence enemy = MatchupAnalysisEngine.synergy(theirs[0], theirs[1], draft.analyses);
            margin(card, synergyLine("我方组合配合", R.color.ally, ours, ally), 12);
            margin(card, synergyLine("敌方组合配合", R.color.enemy, theirs, enemy), 6);
            TextView difference;
            if (ally.known() && enemy.known()) {
                double gap = ally.value() - enemy.value();
                difference = Ui.text(this, R.style.Text_Caption, "配合指数差 " + Ui.signed(gap) + "，"
                    + (gap > .25 ? "我方更高" : gap < -.25 ? "敌方更高" : "双方接近"));
            } else {
                difference = Ui.text(this, R.style.Text_Caption, "配合数据不完整，暂不比较双方组合强弱。");
            }
            margin(card, difference, 8);
            margin(body, card, 14);
        }
    }

    private View pairAvatars(BpModels.Hero[] heroes, int ringColor) {
        LinearLayout pair = row();
        pair.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < heroes.length; i++) {
            HeroAvatarView avatar = new HeroAvatarView(this);
            HeroAvatars.bind(avatar, heroes[i]);
            avatar.setRing(heroes[i] == null ? 0 : color(ringColor), 1.5f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
            if (i > 0) params.setMarginStart(dp(4));
            pair.addView(avatar, params);
        }
        TextView names = Ui.text(this, R.style.Text_Caption, Ui.heroName(heroes[0]) + " + " + Ui.heroName(heroes[1]));
        names.setTextColor(color(R.color.frost));
        names.setMaxLines(2);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.setMarginStart(dp(6));
        names.setMaxWidth(dp(92));
        pair.addView(names, nameParams);
        return pair;
    }

    private View crossCell(BpModels.Hero a, BpModels.Hero e, MatchupAnalysisEngine.Evidence value) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(Ui.heatCell(this, value));
        TextView names = Ui.text(this, R.style.Text_Micro, Ui.heroName(a) + " → " + Ui.heroName(e));
        names.setTextColor(Ui.heatText(this, value));
        names.setAlpha(0.85f);
        names.setMaxLines(1);
        names.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.setPadding(dp(4), 0, dp(4), 0);
        cell.addView(names);
        TextView number = Ui.text(this, R.style.Text_Number, value.known() ? Ui.signed(value.value()) : "暂无数据");
        number.setTextColor(Ui.heatText(this, value));
        number.setTextSize(value.known() ? 15 : 11);
        cell.addView(number);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setForeground(getDrawable(R.drawable.bg_row_pressable));
        cell.setContentDescription(Ui.heroName(a) + " 对 " + Ui.heroName(e) + "，" + (value.known() ? Ui.signed(value.value()) + "，" : "") + Ui.counterLabel(Ui.tone(value)));
        if (a != null && e != null) cell.setOnClickListener(v -> showEvidence(a, e, value, false));
        return cell;
    }

    private View synergyLine(String label, int labelColor, BpModels.Hero[] pair, MatchupAnalysisEngine.Evidence value) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(36));
        Ui.Tone tone = Ui.tone(value);
        TextView name = Ui.text(this, R.style.Text_Caption, label);
        name.setTextColor(color(labelColor));
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView verdict = Ui.text(this, R.style.Text_Caption, value.known() ? Ui.synergyLabel(tone) : "暂无数据");
        verdict.setTextColor(Ui.toneText(this, tone));
        line.addView(verdict);
        TextView number = Ui.text(this, R.style.Text_Number, value.known() ? Ui.signed(value.value()) : "—");
        number.setTextColor(Ui.toneText(this, tone));
        number.setGravity(Gravity.END);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT);
        line.addView(number, numberParams);
        line.setBackground(getDrawable(R.drawable.bg_row_pressable));
        line.setClickable(true);
        line.setFocusable(true);
        line.setContentDescription(label + "，" + (value.known() ? Ui.signed(value.value()) + "，" + Ui.synergyLabel(tone) : "暂无数据"));
        if (pair[0] != null && pair[1] != null) line.setOnClickListener(v -> showEvidence(pair[0], pair[1], value, true));
        return line;
    }

    // ---- Full matrix --------------------------------------------------------------------------

    private void renderMatrix() {
        margin(body, Ui.text(this, R.style.Text_Caption, "行是我方，列是敌方，包含所有已录入英雄。点格子查看样本量。"), 14);
        if (draft.lineup.allies.isEmpty() || draft.lineup.enemies.isEmpty()) {
            TextView empty = Ui.text(this, R.style.Text_Body, "双方都录入英雄后显示全对位。");
            empty.setTextColor(color(R.color.caution_text));
            margin(body, empty, 16);
            return;
        }
        int columns = draft.lineup.enemies.size();
        int headerWidth = dp(56);
        int gap = dp(4);
        int available = getResources().getDisplayMetrics().widthPixels - dp(32) - headerWidth;
        int cellWidth = Math.max(dp(56), (available - gap * columns) / columns);

        LinearLayout table = column();
        LinearLayout header = row();
        header.addView(new View(this), new LinearLayout.LayoutParams(headerWidth, 1));
        for (BpModels.Hero e : draft.lineup.enemies) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cellWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(gap);
            header.addView(matrixHead(e, R.color.enemy), params);
        }
        table.addView(header);
        for (BpModels.Hero a : draft.lineup.allies) {
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(matrixHead(a, R.color.ally), new LinearLayout.LayoutParams(headerWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (BpModels.Hero e : draft.lineup.enemies) {
                MatchupAnalysisEngine.Evidence value = MatchupAnalysisEngine.counter(a, e, draft.analyses);
                LinearLayout cell = column();
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(Ui.heatCell(this, value));
                TextView number = Ui.text(this, R.style.Text_Number, value.known() ? Ui.signedShort(value.value()) : "—");
                number.setTextColor(Ui.heatText(this, value));
                cell.addView(number);
                TextView label = Ui.text(this, R.style.Text_Micro, shortLabel(Ui.tone(value)));
                label.setTextColor(Ui.heatText(this, value));
                label.setTextSize(10);
                cell.addView(label);
                cell.setClickable(true);
                cell.setFocusable(true);
                cell.setForeground(getDrawable(R.drawable.bg_row_pressable));
                cell.setContentDescription(a.name + " 对 " + e.name + "，" + (value.known() ? Ui.signed(value.value()) + "，" : "") + Ui.counterLabel(Ui.tone(value)));
                cell.setOnClickListener(v -> showEvidence(a, e, value, false));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cellWidth, dp(52));
                params.setMarginStart(gap);
                line.addView(cell, params);
            }
            margin(table, line, 6);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(table);
        margin(body, scroll, 12);
        margin(body, legend(), 10);
    }

    private View matrixHead(BpModels.Hero hero, int ringColor) {
        LinearLayout head = column();
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        HeroAvatarView avatar = new HeroAvatarView(this);
        HeroAvatars.bind(avatar, hero);
        avatar.setCornerRadiusDp(6);
        avatar.setRing(color(ringColor), 1.5f);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView name = Ui.text(this, R.style.Text_Micro, hero.name);
        name.setTextColor(color(R.color.mist));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        head.addView(name, params);
        return head;
    }

    private View legend() {
        LinearLayout legend = row();
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.addView(legendItem(Ui.heatCell(this, new MatchupAnalysisEngine.Evidence(true, 2, 1, 1)), "我方有利"));
        legend.addView(legendItem(Ui.heatCell(this, new MatchupAnalysisEngine.Evidence(true, -2, 1, 1)), "我方不利"));
        legend.addView(legendItem(Ui.heatCell(this, new MatchupAnalysisEngine.Evidence(true, 0, 1, 1)), "接近均势"));
        legend.addView(legendItem(Ui.heatCell(this, MatchupAnalysisEngine.Evidence.missing()), "暂无数据"));
        return legend;
    }

    private View legendItem(android.graphics.drawable.Drawable swatch, String text) {
        TextView item = Ui.text(this, R.style.Text_Micro, text);
        item.setTextColor(color(R.color.mist));
        int size = dp(10);
        swatch.setBounds(0, 0, size, size);
        item.setCompoundDrawablesRelative(swatch, null, null, null);
        item.setCompoundDrawablePadding(dp(4));
        item.setPadding(0, 0, dp(12), 0);
        return item;
    }

    private static String shortLabel(Ui.Tone tone) {
        return switch (tone) {
            case GOOD -> "有利";
            case BAD -> "不利";
            case EVEN -> "均势";
            case NONE -> "无数据";
        };
    }

    // ---- Evidence and lane correction ---------------------------------------------------------

    private void showEvidence(BpModels.Hero a, BpModels.Hero b, MatchupAnalysisEngine.Evidence e, boolean synergy) {
        Dialog dialog = new Dialog(this, R.style.Theme_XiaGuBP_Dialog);
        dialog.setContentView(R.layout.dialog_evidence);
        Ui.Tone tone = Ui.tone(e);
        HeroAvatarView first = dialog.findViewById(R.id.evidenceFirst);
        HeroAvatarView second = dialog.findViewById(R.id.evidenceSecond);
        HeroAvatars.bind(first, a);
        HeroAvatars.bind(second, b);
        first.setRing(color(R.color.ally), 1.5f);
        second.setRing(color(synergy && draft.lineup.allies.contains(b) ? R.color.ally : R.color.enemy), 1.5f);
        ((TextView) dialog.findViewById(R.id.evidenceTitle)).setText(synergy ? a.name + " + " + b.name : a.name + " → " + b.name);
        TextView value = dialog.findViewById(R.id.evidenceValue);
        value.setText(e.known() ? Ui.signed(e.value()) : "—");
        value.setTextColor(Ui.toneText(this, tone));
        TextView label = dialog.findViewById(R.id.evidenceLabel);
        label.setText(synergy ? Ui.synergyLabel(tone) : Ui.counterLabel(tone));
        label.setTextColor(Ui.toneText(this, tone));
        ((TextView) dialog.findViewById(R.id.evidenceDetail)).setText(detail(e, synergy));
        ((TextView) dialog.findViewById(R.id.evidenceHint)).setText(synergy
            ? "配合指数为正表示这两位英雄一起时表现更好。指数不是胜率。"
            : "克制指数为正时对我方有利。指数不是胜率，未收录的关系不代表均势。");
        dialog.findViewById(R.id.evidenceClose).setOnClickListener(v -> dialog.dismiss());
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        sizeDialog(dialog, 340);
    }

    private String detail(MatchupAnalysisEngine.Evidence e, boolean synergy) {
        if (!e.known()) return "暂无数据：没有收录这组关系，或英雄还没确定。";
        String index = synergy ? "配合指数" : "克制指数";
        String samples = e.matches() > 0 ? "样本 " + e.matches() + " 场" : "样本数未提供";
        String caution = e.matches() > 0 && e.matches() < 100 ? "。小样本，谨慎参考" : "";
        return index + " " + Ui.signed(e.value()) + "，" + samples + caution + "。";
    }

    private void editAssignments() {
        Dialog dialog = new Dialog(this, R.style.Theme_XiaGuBP_Dialog);
        dialog.setContentView(R.layout.dialog_lane_editor);
        LinearLayout rows = dialog.findViewById(R.id.editorRows);
        for (int i = 0; i < 5; i++) {
            final int lane = i;
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView laneLabel = Ui.text(this, R.style.Text_Caption, MatchupAnalysisEngine.LANES[i]);
            line.addView(laneLabel, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            View ally = editorSlot(draft.allies[i], true, v -> { dialog.dismiss(); chooseHero(true, lane); });
            View enemy = editorSlot(draft.enemies[i], false, v -> { dialog.dismiss(); chooseHero(false, lane); });
            LinearLayout.LayoutParams allyParams = new LinearLayout.LayoutParams(0, dp(48), 1);
            LinearLayout.LayoutParams enemyParams = new LinearLayout.LayoutParams(0, dp(48), 1);
            enemyParams.setMarginStart(dp(6));
            line.addView(ally, allyParams);
            line.addView(enemy, enemyParams);
            margin(rows, line, i == 0 ? 0 : 6);
        }
        dialog.findViewById(R.id.editorCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.editorConfirm).setOnClickListener(v -> {
            draft.lanesConfirmed = true;
            dialog.dismiss();
            render();
        });
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        sizeDialog(dialog, 420);
    }

    private View editorSlot(BpModels.Hero hero, boolean ally, View.OnClickListener click) {
        LinearLayout slot = row();
        slot.setGravity(Gravity.CENTER_VERTICAL);
        slot.setBackgroundResource(hero == null ? R.drawable.bg_slot_empty : ally ? R.drawable.bg_slot_ally : R.drawable.bg_slot_enemy);
        slot.setPadding(dp(ally ? 7 : 6), 0, dp(6), 0);
        slot.setClickable(true);
        slot.setFocusable(true);
        slot.setOnClickListener(click);
        slot.setContentDescription((ally ? "我方" : "敌方") + "，" + Ui.heroName(hero) + "，点按更换");
        if (hero != null) {
            HeroAvatarView avatar = new HeroAvatarView(this);
            HeroAvatars.bind(avatar, hero);
            avatar.setCornerRadiusDp(6);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(32), dp(32));
            params.setMarginEnd(dp(6));
            slot.addView(avatar, params);
        }
        TextView name = Ui.text(this, hero == null ? R.style.Text_Caption : R.style.Text_Name, hero == null ? "＋ 待确定" : hero.name);
        if (hero == null) name.setTextColor(color(R.color.dusk));
        name.setTextSize(13);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        slot.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return slot;
    }

    private void chooseHero(boolean ally, int lane) {
        BpModels.Hero[] currentSlots = ally ? draft.allies : draft.enemies;
        HeroPickerSheet.Request request = new HeroPickerSheet.Request();
        request.title = "选择英雄";
        request.context = (ally ? "我方" : "敌方") + MatchupAnalysisEngine.LANES[lane] + "，可选非常规分路";
        request.contextDot = ally ? R.drawable.dot_ally : R.drawable.dot_enemy;
        request.lane = MatchupAnalysisEngine.LANES[lane];
        for (BpModels.Hero hero : ally ? draft.lineup.enemies : draft.lineup.allies) request.unavailable.add(hero.id);
        for (BpModels.Hero hero : draft.lineup.bans) request.unavailable.add(hero.id);
        request.current = currentSlots[lane];
        request.clearLabel = currentSlots[lane] == null ? null : "设为空缺";
        request.onClear = () -> applyChoice(ally, lane, null);
        HeroPickerSheet.show(this, draft.roster, request, hero -> applyChoice(ally, lane, hero));
    }

    private void applyChoice(boolean ally, int lane, BpModels.Hero selected) {
        BpModels.Hero[] slots = ally ? draft.allies : draft.enemies;
        List<BpModels.Hero> team = ally ? draft.lineup.allies : draft.lineup.enemies;
        BpModels.Hero previous = slots[lane];
        MatchupAnalysisEngine.moveToLane(slots, lane, selected);
        if (previous != null && java.util.Arrays.stream(slots).noneMatch(h -> h != null && h.id == previous.id)) team.removeIf(h -> h.id == previous.id);
        if (selected != null && team.stream().noneMatch(h -> h.id == selected.id)) {
            if (team.size() >= 5) { slots[lane] = previous; Toast.makeText(this, "已有五位英雄，请先清空需要替换的分路", Toast.LENGTH_LONG).show(); return; }
            team.add(selected);
        }
        draft.lanesConfirmed = false; render(); refresh(); editAssignments();
    }

    private void refresh() {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && getIntent().getBooleanExtra("debug_offline", false)) { message = "离线测试数据"; render(); return; }
        final int request = ++generation; loading = true; message = "正在同步巅峰千强关系…"; render();
        List<BpModels.Hero> selected = new ArrayList<>(draft.lineup.allies); selected.addAll(draft.lineup.enemies);
        BpApiClient.loadAnalyses(selected, new BpApiClient.Callback<>() {
            public void onSuccess(Map<Integer, BpModels.Analysis> data) {
                if (isFinishing() || isDestroyed() || request != generation) return;
                loading = false; draft.analyses.clear(); draft.analyses.putAll(data);
                draft.fetchedAt = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new java.util.Date());
                message = "巅峰千强关系，已同步 " + data.size() + "/" + selected.size() + " 位，" + draft.fetchedAt;
                if (data.size() < selected.size()) message += "\n部分英雄请求失败，缺失的关系标为暂无数据。";
                render();
            }
            public void onError(String error) {
                if (isFinishing() || isDestroyed() || request != generation) return;
                loading = false; message = "同步失败，" + (draft.analyses.isEmpty() ? "暂无关系数据" : "保留上次结果，非实时") + "\n" + error; render();
            }
        });
    }

    // ---- Small view helpers -------------------------------------------------------------------

    private void sizeDialog(Dialog dialog, int maxWidthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int available = getResources().getDisplayMetrics().widthPixels - dp(32);
        window.setLayout(Math.min(available, dp(maxWidthDp)), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private String names(List<BpModels.Hero> heroes) { List<String> n = new ArrayList<>(); for (BpModels.Hero h : heroes) n.add(h.name); return n.isEmpty() ? "无" : String.join("、", n); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setBaselineAligned(false); return l; }
    private int color(int id) { return getColor(id); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void margin(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p); }
}
