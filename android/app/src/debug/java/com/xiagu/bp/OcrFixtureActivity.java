package com.xiagu.bp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A debug-only BP screen used to verify screenshot capture and Chinese OCR end to end. */
public final class OcrFixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(40), dp(6), dp(40), dp(8));
        screen.setBackgroundColor(Color.rgb(5, 14, 29));

        TextView title = label("巅峰千强 · BP 识别测试", 20, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        screen.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(38)
        ));

        LinearLayout bans = new LinearLayout(this);
        bans.setGravity(Gravity.CENTER);
        bans.addView(label("BAN 盾山", 18, Color.rgb(248, 113, 141)));
        screen.addView(bans, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(36)
        ));

        LinearLayout teams = new LinearLayout(this);
        teams.setOrientation(LinearLayout.HORIZONTAL);
        teams.setPadding(0, dp(6), 0, 0);
        LinearLayout allies = column("我方", new String[]{"廉颇", "海月", "公孙离", "宫本武藏"}, Color.rgb(72, 213, 208));
        LinearLayout enemies = column("敌方", new String[]{"关羽", "元流之子(坦克)", "武则天", "后羿", "鲁班大师"}, Color.rgb(248, 113, 141));
        teams.addView(allies, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        teams.addView(enemies, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        screen.addView(teams, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        ));

        setContentView(screen);
    }

    private LinearLayout column(String heading, String[] names, int accent) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(label(heading, 17, accent), rowParams());
        for (String name : names) column.addView(label(name, 19, Color.WHITE), rowParams());
        return column;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(38)
        );
        params.setMargins(dp(10), dp(1), dp(10), dp(1));
        return params;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(13, 27, 50));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
