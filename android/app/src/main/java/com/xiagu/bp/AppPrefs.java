package com.xiagu.bp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

final class AppPrefs {
    static final List<String> LANES = List.of("对抗路", "打野", "中路", "发育路", "游走");
    private static final String FILE = "xia_gu_bp_settings";
    private static final String KEY_LANE = "target_lane";
    private static final String KEY_OUR_SIDE_LEFT = "our_side_left";

    private AppPrefs() {}

    static String lane(Context context) {
        String lane = prefs(context).getString(KEY_LANE, "游走");
        return LANES.contains(lane) ? lane : "游走";
    }

    static void setLane(Context context, String lane) {
        if (LANES.contains(lane)) prefs(context).edit().putString(KEY_LANE, lane).apply();
    }

    static boolean ourSideLeft(Context context) {
        return prefs(context).getBoolean(KEY_OUR_SIDE_LEFT, true);
    }

    static void setOurSideLeft(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_OUR_SIDE_LEFT, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
