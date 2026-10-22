package com.xiagu.bp;

import android.app.Dialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bottom-sheet hero picker shared by manual BP and lane correction: search, lane filter, 4-column grid. */
final class HeroPickerSheet {
    private static final String ALL = "全部";

    interface OnPick {
        void onPick(BpModels.Hero hero);
    }

    static final class Request {
        String title = "选择英雄";
        String context = "";
        /** One of R.drawable.dot_ally / dot_enemy / dot_neutral, or 0 for none. */
        int contextDot;
        /** Initial lane filter; null shows every hero. */
        String lane;
        final Set<Integer> unavailable = new HashSet<>();
        BpModels.Hero current;
        /** Label for the destructive action (e.g. "移除当前英雄"); hidden when null. */
        String clearLabel;
        Runnable onClear;
    }

    private HeroPickerSheet() {}

    static Dialog show(Context context, List<BpModels.Hero> roster, Request request, OnPick onPick) {
        Dialog dialog = new Dialog(context, R.style.Theme_XiaGuBP_Sheet);
        View root = LayoutInflater.from(dialog.getContext()).inflate(R.layout.sheet_hero_picker, null, false);
        TextView title = root.findViewById(R.id.pickerTitle);
        TextView subtitle = root.findViewById(R.id.pickerContext);
        EditText search = root.findViewById(R.id.pickerSearch);
        ImageButton clearSearch = root.findViewById(R.id.pickerSearchClear);
        RadioGroup lanes = root.findViewById(R.id.pickerLanes);
        TextView count = root.findViewById(R.id.pickerCount);
        Button clear = root.findViewById(R.id.pickerClear);
        GridView grid = root.findViewById(R.id.pickerGrid);
        TextView empty = root.findViewById(R.id.pickerEmpty);

        title.setText(request.title);
        subtitle.setText(request.context);
        subtitle.setVisibility(request.context == null || request.context.isEmpty() ? View.GONE : View.VISIBLE);
        if (request.contextDot != 0) subtitle.setCompoundDrawablesRelativeWithIntrinsicBounds(request.contextDot, 0, 0, 0);
        root.findViewById(R.id.pickerClose).setOnClickListener(view -> dialog.dismiss());

        if (request.clearLabel != null && request.onClear != null) {
            clear.setVisibility(View.VISIBLE);
            clear.setText(request.clearLabel);
            clear.setOnClickListener(view -> {
                request.onClear.run();
                dialog.dismiss();
            });
        }

        HeroAdapter adapter = new HeroAdapter(dialog.getContext(), request.current, hero -> {
            dialog.dismiss();
            onPick.onPick(hero);
        });
        grid.setAdapter(adapter);

        final String[] lane = {request.lane};
        Runnable apply = () -> {
            String query = search.getText().toString();
            List<BpModels.Hero> matches = new ArrayList<>();
            for (BpModels.Hero hero : roster) {
                boolean isCurrent = request.current != null && request.current.id == hero.id;
                if (!isCurrent && request.unavailable.contains(hero.id)) continue;
                if (lane[0] != null && !hero.supports(lane[0])) continue;
                if (!PinyinIndex.matches(hero, query)) continue;
                matches.add(hero);
            }
            Comparator<BpModels.Hero> byTier = Comparator
                .comparingDouble((BpModels.Hero hero) -> hero.tierScore == null ? -1 : hero.tierScore).reversed()
                .thenComparing(hero -> hero.name);
            if (query.trim().isEmpty()) matches.sort(byTier);
            else matches.sort(Comparator.comparingInt((BpModels.Hero hero) -> PinyinIndex.rank(hero, query)).thenComparing(byTier));
            adapter.setItems(matches);
            grid.setSelection(0);
            String scope = lane[0] == null ? "全部英雄" : lane[0];
            count.setText(scope + "可选 " + matches.size() + " 位" + (query.trim().isEmpty() ? "，按梯度排序" : ""));
            clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
            if (matches.isEmpty()) {
                empty.setVisibility(View.VISIBLE);
                empty.setText(query.trim().isEmpty()
                    ? "这个分路暂时没有可选英雄。已选和已 BAN 的英雄不会出现在这里。"
                    : "没有找到“" + query.trim() + "”。" + (lane[0] == null ? "试试拼音首字母。" : "试试切到「全部」。"));
            } else {
                empty.setVisibility(View.GONE);
            }
        };

        List<String> options = new ArrayList<>();
        options.add(ALL);
        options.addAll(AppPrefs.LANES);
        for (String option : options) {
            RadioButton chip = new RadioButton(dialog.getContext(), null, 0, R.style.Widget_XiaGu_Chip);
            chip.setId(View.generateViewId());
            chip.setText(option);
            chip.setTag(option);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(context, 32));
            params.setMarginEnd(Ui.dp(context, 6));
            lanes.addView(chip, params);
            boolean selected = request.lane == null ? ALL.equals(option) : option.equals(request.lane);
            if (selected) lanes.check(chip.getId());
        }
        lanes.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked == null || !(checked.getTag() instanceof String option)) return;
            lane[0] = ALL.equals(option) ? null : option;
            apply.run();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { apply.run(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        clearSearch.setOnClickListener(view -> search.setText(""));
        apply.run();
        PinyinIndex.warm(roster);

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int height = Math.min(Math.round(metrics.heightPixels * 0.82f), Ui.dp(context, 720));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, height);
        }
        return dialog;
    }

    private static final class HeroAdapter extends BaseAdapter {
        private final Context context;
        private final BpModels.Hero current;
        private final OnPick onPick;
        private final List<BpModels.Hero> items = new ArrayList<>();

        HeroAdapter(Context context, BpModels.Hero current, OnPick onPick) {
            this.context = context;
            this.current = current;
            this.onPick = onPick;
        }

        void setItems(List<BpModels.Hero> heroes) {
            items.clear();
            items.addAll(heroes);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null
                ? convertView
                : LayoutInflater.from(context).inflate(R.layout.item_hero_option, parent, false);
            BpModels.Hero hero = items.get(position);
            HeroAvatarView avatar = view.findViewById(R.id.optionAvatar);
            TextView name = view.findViewById(R.id.optionName);
            HeroAvatars.bind(avatar, hero);
            avatar.setShowTier(true);
            avatar.setCornerRadiusDp(10);
            name.setText(hero.name);
            boolean isCurrent = current != null && current.id == hero.id;
            view.setActivated(isCurrent);
            avatar.setRing(isCurrent ? Ui.color(context, R.color.crown) : 0, 2);
            avatar.setBadgeOutlineColor(Ui.color(context, isCurrent ? R.color.crown_soft : R.color.deck));
            String tier = Ui.tier(hero);
            view.setContentDescription(hero.name + (tier.isEmpty() ? "" : "，梯度 " + tier)
                + (hero.positions.isEmpty() ? "" : "，" + Ui.positions(hero)) + (isCurrent ? "，当前选择" : ""));
            view.setOnClickListener(clicked -> onPick.onPick(hero));
            return view;
        }
    }
}
