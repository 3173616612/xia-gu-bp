package com.xiagu.bp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Post-scan report. Five lanes, cross-lane matrix and duo evidence share exactly one data model. */
public final class MatchupAnalysisActivity extends Activity {
    private static final String SNAPSHOT = "matchup_snapshot";
    private MatchupSnapshot draft;
    private LinearLayout root, body;
    private TextView syncStatus;
    private int tab, generation;
    private boolean loading;
    private String message = "";

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
        render();
        boolean offlineFixture = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && getIntent().getBooleanExtra("debug_offline", false);
        if (!offlineFixture && (state == null || draft.analyses.isEmpty() || state.getBoolean("loading"))) refresh();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        try { out.putString(SNAPSHOT, draft.encode()); out.putInt("tab", tab); out.putString("message", message); out.putBoolean("loading", loading); } catch (Exception ignored) { }
        super.onSaveInstanceState(out);
    }

    private void render() {
        boolean compact = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(R.color.background));
        root = column(); root.setPadding(dp(18), dp(16), dp(18), dp(28)); scroll.addView(root); setContentView(scroll);
        // Insets are applied once to the root, including display cutouts and gesture navigation.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(dp(18) + bars.left, dp(16) + bars.top, dp(18) + bars.right, dp(28) + bars.bottom);
            }
            return insets;
        });
        LinearLayout nav = row(); root.addView(nav);
        Button back = button("‹ 返回", false); back.setOnClickListener(v -> finish()); nav.addView(back, new LinearLayout.LayoutParams(dp(78), dp(44)));
        TextView tag = text(compact ? "阵容对位 · 我方在左 / 敌方在右" : "DRAFT INSIGHT", compact ? 18 : 11, R.color.cyan);
        tag.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        nav.addView(tag, weight(44));
        if (!compact) heading(root, "阵容对位", "我方在左 · 敌方在右  /  不限本分路");
        syncStatus = text(message.isEmpty() ? "天元之弈 · 巅峰千强关系数据" : message, 12, R.color.muted); root.addView(syncStatus);
        LinearLayout actions = row(); margin(root, actions, 12);
        Button edit = button(draft.lanesConfirmed ? "校正分路 / 英雄" : "确认分路 / 英雄", false);
        edit.setOnClickListener(v -> editAssignments()); actions.addView(edit, weight(46));
        Button refresh = button(loading ? "同步中…" : "刷新数据", false); refresh.setEnabled(!loading);
        refresh.setOnClickListener(v -> refresh()); actions.addView(refresh, weight(46));
        TextView notice = text(draft.lanesConfirmed ? "按已确认分路展示；此处校正仅影响本页分析。"
            : "分路为暂定推断，不按选将顺序排列。多位置或非常规阵容请先确认。", 12, R.color.warning);
        margin(root, notice, 10);
        List<BpModels.Hero> looseA = MatchupAnalysisEngine.unassigned(draft.lineup.allies, draft.allies);
        List<BpModels.Hero> looseE = MatchupAnalysisEngine.unassigned(draft.lineup.enemies, draft.enemies);
        if (!looseA.isEmpty() || !looseE.isEmpty()) margin(root,
            text("待分配 · 我方 " + names(looseA) + " / 敌方 " + names(looseE), 12, R.color.warning), 6);
        LinearLayout tabs = row(); margin(root, tabs, 18);
        String[] titles = {"五路对位", "组合联动", "全对位"};
        for (int i = 0; i < titles.length; i++) {
            final int selected = i; Button b = button(titles[i], tab == i);
            b.setOnClickListener(v -> {tab = selected; render();}); tabs.addView(b, weight(48));
        }
        body = column(); margin(root, body, 8);
        if (tab == 0) renderLanes(); else if (tab == 1) renderCombos(); else renderMatrix();
        margin(root, text("克制指数正值有利于我方，负值不利于我方。配合指数正值表示该组合配合良好，敌方同理。指数不是胜率；未列出不代表均势。组合展示各项证据，不推算真实 2v2 胜率。", 11, R.color.muted), 20);
        TextView source = text("数据来源：天元之弈 ↗\ntianyuanzhiyi.com", 12, R.color.cyan);
        source.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://tianyuanzhiyi.com/"))));
        margin(root, source, 14);
    }

    private void renderLanes() {
        for (int lane = 0; lane < 5; lane++) {
            LinearLayout card = card(body);
            TextView title = text(MatchupAnalysisEngine.LANES[lane], 13, R.color.cyan); card.addView(title);
            BpModels.Hero a = draft.allies[lane], e = draft.enemies[lane];
            heading(card, name(a) + "  vs  " + name(e), "");
            evidence(card, MatchupAnalysisEngine.counter(a, e, draft.analyses), false);
            if (a == null || e == null) card.addView(text("分路尚未齐全，可在上方补录或调整。", 12, R.color.muted));
        }
    }

    private void renderCombos() {
        for (int c = 0; c < MatchupAnalysisEngine.COMBOS.length; c++) {
            int x = MatchupAnalysisEngine.COMBOS[c][0], y = MatchupAnalysisEngine.COMBOS[c][1];
            BpModels.Hero[] ours = {draft.allies[x], draft.allies[y]}, theirs = {draft.enemies[x], draft.enemies[y]};
            LinearLayout card = card(body);
            heading(card, MatchupAnalysisEngine.COMBO_NAMES[c] + "联动", name(ours[0]) + " + " + name(ours[1]) + "\nvs " + name(theirs[0]) + " + " + name(theirs[1]));
            int favorable = 0, unfavorable = 0, known = 0;
            double totalIndex = 0;
            for (BpModels.Hero a : ours) for (BpModels.Hero e : theirs) {
                MatchupAnalysisEngine.Evidence value = MatchupAnalysisEngine.counter(a, e, draft.analyses);
                if (value.known()) { known++; totalIndex += value.value(); if (value.value() > .25) favorable++; else if (value.value() < -.25) unfavorable++; }
            }
            double mean = known == 0 ? 0 : totalIndex / known;
            String trend = known == 0 ? "暂无对敌判断" : mean > .25 ? "已知对敌关系偏有利" : mean < -.25 ? "已知对敌关系偏不利" : "已知对敌关系接近均势";
            card.addView(text(trend + " · 有利 " + favorable + " / 不利 " + unfavorable
                + (known == 0 ? "" : "\n交叉克制均值 " + signed(mean)) + "\n关系覆盖 " + known + "/4", 14, R.color.ink));
            for (BpModels.Hero a : ours) for (BpModels.Hero e : theirs) {
                margin(card, text(name(a) + " → " + name(e), 12, R.color.muted), 10);
                evidence(card, MatchupAnalysisEngine.counter(a, e, draft.analyses), false);
            }
            margin(card, text("我方组合配合", 13, R.color.cyan), 14);
            MatchupAnalysisEngine.Evidence ally = MatchupAnalysisEngine.synergy(ours[0], ours[1], draft.analyses);
            MatchupAnalysisEngine.Evidence enemy = MatchupAnalysisEngine.synergy(theirs[0], theirs[1], draft.analyses);
            evidence(card, ally, true);
            margin(card, text("敌方组合配合", 13, R.color.danger), 10); evidence(card, enemy, true);
            if (ally.known() && enemy.known()) {
                double difference = ally.value() - enemy.value();
                margin(card, text("配合指数差 " + signed(difference) + " · " + (difference > .25 ? "我方更高" : difference < -.25 ? "敌方更高" : "双方接近"), 12, R.color.ink), 12);
            } else margin(card, text("配合数据不完整，暂不比较双方组合强弱。", 12, R.color.muted), 12);
        }
    }

    private void renderMatrix() {
        margin(body, text("行 = 我方 · 列 = 敌方\n可横向滑动；点击单元格查看证据。包含所有已录入英雄，不遗漏待分路英雄。", 12, R.color.muted), 8);
        if (draft.lineup.allies.isEmpty() || draft.lineup.enemies.isEmpty()) {
            margin(body, text("双方均录入英雄后显示全对位。", 14, R.color.warning), 20); return;
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this); LinearLayout table = column(); scroll.addView(table); margin(body, scroll, 16);
        LinearLayout header = row(); table.addView(header); header.addView(cell("我方 ↓ / 敌方 →", R.color.muted));
        for (BpModels.Hero e : draft.lineup.enemies) header.addView(cell(e.name, R.color.danger));
        for (BpModels.Hero a : draft.lineup.allies) {
            LinearLayout row = row(); table.addView(row); row.addView(cell(a.name, R.color.cyan));
            for (BpModels.Hero e : draft.lineup.enemies) {
                MatchupAnalysisEngine.Evidence value = MatchupAnalysisEngine.counter(a, e, draft.analyses);
                TextView cell = cell(value.known() ? signed(value.value()) + "\n" + value.label() : "—\n暂无数据", tone(value));
                cell.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(a.name + " → " + e.name)
                    .setMessage(detail(value, false)).setPositiveButton("知道了", null).show());
                row.addView(cell);
            }
        }
    }

    private void editAssignments() {
        LinearLayout content = column(); content.setPadding(dp(14), dp(8), dp(14), dp(8));
        content.addView(text("点击英雄调整分路或补录。左右分列，不强制套入常规阵容。", 13, R.color.ink));
        ScrollView scroller = new ScrollView(this); scroller.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("校正阵容与分路").setView(scroller)
            .setPositiveButton("确认当前分路", (d, w) -> {draft.lanesConfirmed = true; render();}).setNegativeButton("返回", null).create();
        for (int i = 0; i < 5; i++) {
            final int lane = i;
            content.addView(text(MatchupAnalysisEngine.LANES[i], 12, R.color.cyan));
            LinearLayout line = row(); content.addView(line);
            Button a = button("我 · " + name(draft.allies[i]), false), e = button("敌 · " + name(draft.enemies[i]), false);
            a.setOnClickListener(v -> {dialog.dismiss(); chooseHero(true, lane);});
            e.setOnClickListener(v -> {dialog.dismiss(); chooseHero(false, lane);});
            line.addView(a, weight(46)); line.addView(e, weight(46));
        }
        dialog.show();
    }

    private void chooseHero(boolean ally, int lane) {
        LinearLayout content = column(); content.setPadding(dp(14), 0, dp(14), 0);
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint("搜索英雄，可选非常规分路"); content.addView(search);
        ListView list = new ListView(this); content.addView(list, new LinearLayout.LayoutParams(-1,
            Math.max(dp(100), Math.min(dp(290), getResources().getDisplayMetrics().heightPixels - dp(210)))));
        List<BpModels.Hero> choices = new ArrayList<>(); List<String> labels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels); list.setAdapter(adapter);
        Runnable filter = () -> {
            choices.clear(); labels.clear(); choices.add(null); labels.add("空缺 / 未确定");
            String query = search.getText().toString().trim();
            List<BpModels.Hero> opposite = ally ? draft.lineup.enemies : draft.lineup.allies;
            for (BpModels.Hero h : draft.roster) {
                if (!h.name.contains(query) || opposite.stream().anyMatch(other -> other.id == h.id)
                    || draft.lineup.bans.stream().anyMatch(b -> b.id == h.id)) continue;
                choices.add(h); labels.add(h.name + "  · " + h.roles);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable s) { }
        }); filter.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle((ally ? "我方 · " : "敌方 · ") + MatchupAnalysisEngine.LANES[lane])
            .setView(content).setNegativeButton("取消", null).create();
        list.setOnItemClickListener((parent, view, index, id) -> {
            BpModels.Hero[] slots = ally ? draft.allies : draft.enemies;
            List<BpModels.Hero> team = ally ? draft.lineup.allies : draft.lineup.enemies;
            BpModels.Hero previous = slots[lane], selected = choices.get(index);
            MatchupAnalysisEngine.moveToLane(slots, lane, selected);
            if (previous != null && java.util.Arrays.stream(slots).noneMatch(h -> h != null && h.id == previous.id)) team.removeIf(h -> h.id == previous.id);
            if (selected != null && team.stream().noneMatch(h -> h.id == selected.id)) {
                if (team.size() >= 5) { slots[lane] = previous; Toast.makeText(this, "已有五位英雄，请先清空需要替换的分路", Toast.LENGTH_LONG).show(); return; }
                team.add(selected);
            }
            draft.lanesConfirmed = false; dialog.dismiss(); render(); refresh(); editAssignments();
        }); dialog.show();
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
                message = "巅峰千强 · 已同步 " + data.size() + "/" + selected.size() + " 位 · " + draft.fetchedAt;
                if (data.size() < selected.size()) message += "\n部分英雄请求失败，未返回关系标为暂无数据。";
                render();
            }
            public void onError(String error) {
                if (isFinishing() || isDestroyed() || request != generation) return;
                loading = false; message = "同步失败 · " + (draft.analyses.isEmpty() ? "暂无关系数据" : "保留上次结果，非实时") + "\n" + error; render();
            }
        });
    }

    private void evidence(LinearLayout parent, MatchupAnalysisEngine.Evidence e, boolean synergy) {
        TextView label = text(detail(e, synergy), 13, tone(e)); parent.addView(label);
    }
    private String detail(MatchupAnalysisEngine.Evidence e, boolean synergy) {
        if (!e.known()) return "暂无数据 · 未收录关系或英雄尚未确定";
        String label = synergy ? e.value() > .25 ? "正向配合" : e.value() < -.25 ? "负向配合" : "配合接近中性" : e.label();
        return label + "  " + signed(e.value()) + "\n" + (synergy ? "配合指数" : "克制指数")
            + " · 样本 " + (e.matches() > 0 ? e.matches() + " 场" : "未提供")
            + (e.matches() > 0 && e.matches() < 100 ? " · 小样本谨慎参考" : "");
    }
    private int tone(MatchupAnalysisEngine.Evidence e) { return !e.known() ? R.color.muted : e.value() > .25 ? R.color.cyan : e.value() < -.25 ? R.color.danger : R.color.warning; }
    private String signed(double value) { return String.format(Locale.CHINA, "%+.2f", value); }
    private String name(BpModels.Hero hero) { return hero == null ? "待确定" : hero.name; }
    private String names(List<BpModels.Hero> heroes) { List<String> n = new ArrayList<>(); for (BpModels.Hero h : heroes) n.add(h.name); return n.isEmpty() ? "无" : String.join("、", n); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setBaselineAligned(false); return l; }
    private int color(int id) { return getColor(id); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color(color)); t.setLineSpacing(dp(3), 1); return t; }
    private LinearLayout.LayoutParams weight(int height) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(height), 1); p.setMargins(dp(2), dp(2), dp(2), dp(2)); return p; }
    private void margin(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(child, p); }
    private GradientDrawable bg(int c) { GradientDrawable d = new GradientDrawable(); d.setColor(color(c)); d.setCornerRadius(dp(16)); d.setStroke(dp(1), color(R.color.line)); return d; }
    private Button button(String value, boolean active) { Button b = new Button(this); b.setText(value); b.setTextSize(12); b.setAllCaps(false); b.setTextColor(color(active ? R.color.background : R.color.ink)); b.setBackground(bg(active ? R.color.cyan : R.color.panel)); b.setPadding(dp(5), 0, dp(5), 0); return b; }
    private LinearLayout card(LinearLayout parent) { LinearLayout c = column(); c.setPadding(dp(16), dp(16), dp(16), dp(16)); c.setBackground(bg(R.color.panel)); margin(parent, c, 12); return c; }
    private void heading(LinearLayout parent, String title, String subtitle) { TextView t = text(title, parent == root ? 29 : 19, R.color.ink); t.setTypeface(null, Typeface.BOLD); margin(parent, t, 12); if (!subtitle.isEmpty()) margin(parent, text(subtitle, 13, R.color.muted), 5); }
    private TextView cell(String value, int color) { TextView t = text(value, 12, color); t.setGravity(Gravity.CENTER); t.setPadding(dp(6), dp(6), dp(6), dp(6)); t.setBackground(bg(R.color.panel)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(112), dp(76)); p.setMargins(dp(2), dp(2), dp(2), dp(2)); t.setLayoutParams(p); return t; }
}
