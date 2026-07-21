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
                AvatarRecognitionEngine.recognize(
                    AvatarFixtureActivity.this,
                    fixture,
                    heroes,
                    true,
                    new AvatarRecognitionEngine.Callback() {
                        @Override
                        public void onReady(BpModels.DetectedLineup lineup) {
                            fixture.recycle();
                            String report = "allies=" + names(lineup.allies)
                                + "\nenemies=" + names(lineup.enemies)
                                + "\nbans=" + names(lineup.bans)
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

            @Override
            public void onError(String message) {
                fixture.recycle();
                show(message);
            }
        });
    }

    private void show(String value) {
        Log.i(TAG, value.replace('\n', ';'));
        output.setText(value);
    }

    private String names(List<BpModels.Hero> heroes) {
        List<String> names = new ArrayList<>();
        for (BpModels.Hero hero : heroes) names.add(hero.name);
        return String.join("、", names);
    }
}
