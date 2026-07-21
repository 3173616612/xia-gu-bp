package com.xiagu.bp;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Debug-only runner for a real BP screenshot pushed to the app's external files directory. */
public final class AvatarFixtureActivity extends Activity {
    private static final String TAG = "AvatarFixture";
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setGravity(Gravity.CENTER);
        output.setTextSize(20);
        output.setText("真实 BP 截图头像识别中…");
        setContentView(output);

        Bitmap fixture = BitmapFactory.decodeFile(new java.io.File(getExternalFilesDir(null), "bp_fixture.png").getAbsolutePath());
        if (fixture == null) {
            show("fixture missing");
            return;
        }
        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                List<BpModels.Hero> originalAvatars = application().originalAvatarRoster();
                if (!originalAvatars.isEmpty()) {
                    recognize(fixture, heroes, originalAvatars);
                    return;
                }
                BpApiClient.loadOriginalAvatarRoster(new BpApiClient.Callback<>() {
                    @Override
                    public void onSuccess(List<BpModels.Hero> fallbackAvatars) {
                        recognize(fixture, heroes, fallbackAvatars);
                    }

                    @Override
                    public void onError(String message) {
                        fixture.recycle();
                        show(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                fixture.recycle();
                show(message);
            }
        });
    }

    private XiaGuBpApplication application() {
        return (XiaGuBpApplication) getApplication();
    }

    private void recognize(
        Bitmap fixture,
        List<BpModels.Hero> heroes,
        List<BpModels.Hero> originalAvatars
    ) {
        AvatarRecognitionEngine.recognize(
            AvatarFixtureActivity.this,
            fixture,
            heroes,
            originalAvatars,
            true,
            new AvatarRecognitionEngine.Callback() {
                @Override
                public void onReady(BpModels.DetectedLineup lineup) {
                    fixture.recycle();
                    String report = "avatar_source=https://tianyuanzhiyi.com/api/allheroes"
                        + "\nallies=" + names(lineup.allies)
                        + "\nenemies=" + names(lineup.enemies)
                        + "\nbans=" + names(lineup.bans)
                        + "\nlayout=" + lineup.layoutProfile
                        + "\nslots=" + String.join(" | ", lineup.slotTrace)
                        + "\nconfidence=" + lineup.confidence
                        + "\nissues=" + String.join(" | ", lineup.issues);
                    show(report);
                }

                @Override
                public void onError(String message) {
                    fixture.recycle();
                    show(message);
                }
            }
        );
    }

    private void show(String value) {
        Log.i(TAG, value.replace('\n', ';'));
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(
            new java.io.File(getExternalFilesDir(null), "avatar_fixture_report.txt")
        )) {
            stream.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) {
            Log.e(TAG, "cannot write fixture report", error);
        }
        output.setText(value);
    }

    private String names(List<BpModels.Hero> heroes) {
        List<String> names = new ArrayList<>();
        for (BpModels.Hero hero : heroes) names.add(hero.name);
        return String.join("、", names);
    }
}
