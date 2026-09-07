package com.xiagu.bp;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reproducible edge-preserving screenshot augmentations; debug-only, no screenshots uploaded. */
public final class AvatarRegressionActivity extends Activity {
    private Bitmap source;
    private List<BpModels.Hero> roster;
    private int index;
    private final StringBuilder report = new StringBuilder();
    private final int[][] cases = {{2044,1000,0}, {1333,1000,0}, {1500,1000,0}, {1600,1000,0},
        {1778,1000,0}, {2167,1000,0}, {2222,1000,0}, {2333,1000,0}, {2667,1000,0}, {2222,1000,1}};
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); status = new TextView(this); status.setText("头像回归测试中…"); setContentView(status);
        Bitmap input = BitmapFactory.decodeFile(new File(getFilesDir(), "bp_fixture.png").getPath());
        if (input == null) { status.setText("缺少 bp_fixture.png"); return; }
        // The checked QA asset is a 2555x1250 screenshot inside a 2880x1250 letterbox.
        source = input.getWidth() == 2880 ? Bitmap.createBitmap(input, 162, 0, 2555, 1250) : input;
        if (source != input) input.recycle();
        BpApiClient.loadOriginalAvatarRoster(new BpApiClient.Callback<>() {
            public void onSuccess(List<BpModels.Hero> heroes) { roster = heroes; next(); }
            public void onError(String error) { status.setText(error); save(error); }
        });
    }

    private void next() {
        if (index == cases.length) { status.setText(report); source.recycle(); return; }
        int[] size = cases[index++]; Bitmap frame = reframe(size[0], size[1], size[2] == 1);
        long start = android.os.SystemClock.elapsedRealtime();
        AvatarRecognitionEngine.recognize(this, frame, roster, roster, true, new AvatarRecognitionEngine.Callback() {
            public void onReady(BpModels.DetectedLineup lineup) {
                long elapsed = android.os.SystemClock.elapsedRealtime() - start;
                String allies = names(lineup.allies), enemies = names(lineup.enemies), bans = names(lineup.bans);
                Set<String> expectedAllies = Set.of("墨子", "赵云", "百里守约", "沈梦溪", "芈月");
                Set<String> expectedEnemies = Set.of("曹操", "苍");
                Set<String> expectedBans = Set.of("马超", "盾山", "元流之子(辅助)", "裴擒虎", "阿轲");
                boolean picksOk = nameSet(lineup.allies).equals(expectedAllies) && nameSet(lineup.enemies).equals(expectedEnemies);
                boolean bansOk = nameSet(lineup.bans).equals(expectedBans);
                report.append(size[0]).append('x').append(size[1]).append(size[2] == 1 ? "+one-sided-black" : "")
                    .append(" picks=").append(picksOk ? "PASS" : "FAIL").append(" bans=").append(bansOk ? "PASS" : "FAIL")
                    .append(" ms=").append(elapsed).append("\nallies=").append(allies).append("\nenemies=").append(enemies)
                    .append("\nbans=").append(bans).append("\nlayout=").append(lineup.layoutProfile)
                    .append("\nslots=").append(String.join(" | ", lineup.slotTrace)).append("\n\n");
                frame.recycle(); save(report.toString()); status.setText("完成 " + index + "/" + cases.length); next();
            }
            public void onError(String error) { frame.recycle(); report.append("ERROR ").append(error).append('\n'); save(report.toString()); next(); }
        });
    }

    private Bitmap reframe(int width, int height, boolean inset) {
        int extra = inset ? 70 : 0;
        Bitmap bitmap = Bitmap.createBitmap(width + extra, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int edge = (int) Math.round(source.getHeight() * .53);
        int targetEdge = (int) Math.round(height * .53);
        canvas.drawBitmap(source, new Rect(0,0,edge,source.getHeight()), new Rect(extra,0,extra+targetEdge,height), paint);
        canvas.drawBitmap(source, new Rect(source.getWidth()-edge,0,source.getWidth(),source.getHeight()),
            new Rect(width-targetEdge+extra,0,width+extra,height), paint);
        canvas.drawBitmap(source, new Rect(edge,0,source.getWidth()-edge,source.getHeight()),
            new Rect(targetEdge+extra,0,width-targetEdge+extra,height), paint);
        return bitmap;
    }
    private Set<String> nameSet(List<BpModels.Hero> heroes) { Set<String> n = new LinkedHashSet<>(); for (var h : heroes) n.add(h.name); return n; }
    private String names(List<BpModels.Hero> heroes) { return String.join("、", nameSet(heroes)); }
    private void save(String text) {
        try (var out = new java.io.FileOutputStream(new File(getFilesDir(), "avatar_regression.txt"))) {out.write(text.getBytes(StandardCharsets.UTF_8));}
        catch (Exception e) {android.util.Log.e("AvatarRegression", "report write failed", e);}
    }
}
