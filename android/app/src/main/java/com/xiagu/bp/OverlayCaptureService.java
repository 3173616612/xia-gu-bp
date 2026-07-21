package com.xiagu.bp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OverlayCaptureService extends Service {
    static final String ACTION_START = "com.xiagu.bp.action.START";
    static final String ACTION_STOP = "com.xiagu.bp.action.STOP";
    static final String EXTRA_RESULT_CODE = "projection_result_code";
    static final String EXTRA_RESULT_DATA = "projection_result_data";

    private static final int NOTIFICATION_ID = 7301;
    private static final String CHANNEL_ID = "bp_projection";

    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private WindowManager windowManager;
    private View panel;
    private Button bubble;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams bubbleParams;
    private TextView overlayStatus;
    private TextView detectedAllies;
    private TextView detectedEnemies;
    private TextView detectedBans;
    private TextView detectionIssues;
    private TextView recommendationOne;
    private TextView recommendationTwo;
    private TextView recommendationThree;
    private View detectedSection;
    private View recommendationSection;
    private Button scanButton;
    private Button switchTeamSideButton;
    private RadioGroup laneGroup;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private TextRecognizer laneRecognizer;
    private volatile boolean captureRequested;
    private int captureWidth;
    private int captureHeight;
    private int densityDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        laneRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        captureThread = new HandlerThread("bp-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        startProjectionForeground();
        if (projection == null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(() -> {
                        if (overlayStatus != null) overlayStatus.setText("截图会话已结束，请返回应用重新授权。");
                        stopSelf();
                    });
                }
            }, captureHandler);
            ensureCaptureSurface();
        }
        if (bubble == null) createOverlays();
        return START_NOT_STICKY;
    }

    private void startProjectionForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("峡谷 BP 悬浮助手")
            .setContentText(getString(R.string.projection_notification))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "屏幕识别服务",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("仅在用户点击悬浮按钮时截取一帧并进行本地头像识别");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void createOverlays() {
        bubble = new Button(this);
        bubble.setText("BP");
        bubble.setAllCaps(false);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(16);
        bubble.setGravity(Gravity.CENTER);
        bubble.setMinWidth(0);
        bubble.setMinHeight(0);
        bubble.setPadding(0, 0, 0, 0);
        bubble.setContentDescription("展开峡谷 BP 悬浮助手");
        bubble.setBackgroundResource(R.drawable.bg_overlay_button);
        bubble.setElevation(dp(12));
        bubble.setOnClickListener(view -> expandPanel());

        bubbleParams = baseParams(dp(58), dp(58));
        bubbleParams.gravity = Gravity.TOP | Gravity.END;
        bubbleParams.x = dp(14);
        bubbleParams.y = dp(160);
        windowManager.addView(bubble, bubbleParams);
        attachBubbleGestures();

        FrameLayout overlayRoot = new FrameLayout(this);
        panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, overlayRoot, false);
        panelParams = baseParams(dp(330), panelHeight());
        panelParams.gravity = Gravity.TOP | Gravity.END;
        panelParams.x = dp(12);
        panelParams.y = dp(64);
        panel.setVisibility(View.GONE);
        windowManager.addView(panel, panelParams);
        bindPanel();
    }

    private WindowManager.LayoutParams baseParams(int width, int height) {
        int type = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        return new WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
    }

    private int panelHeight() {
        DisplayMetrics metrics = screenMetrics();
        return Math.max(dp(360), Math.min(metrics.heightPixels - dp(32), dp(590)));
    }

    private void bindPanel() {
        overlayStatus = panel.findViewById(R.id.overlayStatus);
        detectedAllies = panel.findViewById(R.id.detectedAllies);
        detectedEnemies = panel.findViewById(R.id.detectedEnemies);
        detectedBans = panel.findViewById(R.id.detectedBans);
        detectionIssues = panel.findViewById(R.id.detectionIssues);
        recommendationOne = panel.findViewById(R.id.recommendationOne);
        recommendationTwo = panel.findViewById(R.id.recommendationTwo);
        recommendationThree = panel.findViewById(R.id.recommendationThree);
        detectedSection = panel.findViewById(R.id.detectedSection);
        recommendationSection = panel.findViewById(R.id.recommendationSection);
        scanButton = panel.findViewById(R.id.scanButton);
        switchTeamSideButton = panel.findViewById(R.id.switchTeamSideButton);
        laneGroup = panel.findViewById(R.id.overlayLaneGroup);

        setupLaneButtons();
        updateTeamSideLabel();
        panel.findViewById(R.id.collapseOverlayButton).setOnClickListener(view -> collapsePanel());
        panel.findViewById(R.id.stopOverlayButton).setOnClickListener(view -> stopSelf());
        scanButton.setOnClickListener(view -> requestCapture());
        switchTeamSideButton.setOnClickListener(view -> {
            AppPrefs.setOurSideLeft(this, !AppPrefs.ourSideLeft(this));
            updateTeamSideLabel();
        });
        panel.findViewById(R.id.openFullPanelButton).setOnClickListener(view -> {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra(MainActivity.EXTRA_OPEN_WEB, true);
            startActivity(open);
            collapsePanel();
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
            button.setTextSize(11);
            button.setGravity(Gravity.CENTER);
            button.setButtonDrawable(null);
            button.setBackgroundResource(R.drawable.bg_chip);
            button.setPadding(dp(12), 0, dp(12), 0);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            );
            params.setMarginEnd(dp(6));
            laneGroup.addView(button, params);
            button.setChecked(lane.equals(selected));
        }
        laneGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String lane) AppPrefs.setLane(this, lane);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachBubbleGestures() {
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        moved = false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        moved |= Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4);
                        bubbleParams.x = Math.max(0, startX - Math.round(dx));
                        bubbleParams.y = Math.max(0, startY + Math.round(dy));
                        windowManager.updateViewLayout(bubble, bubbleParams);
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> {
                        if (!moved) view.performClick();
                        return true;
                    }
                    default -> { return false; }
                }
            }
        });
    }

    private void expandPanel() {
        panelParams.height = panelHeight();
        windowManager.updateViewLayout(panel, panelParams);
        panel.setVisibility(View.VISIBLE);
        bubble.setVisibility(View.GONE);
    }

    private void collapsePanel() {
        if (panel != null) panel.setVisibility(View.GONE);
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
    }

    private void requestCapture() {
        if (projection == null) {
            overlayStatus.setText("截图授权已经失效，请返回应用重新启动悬浮助手。");
            return;
        }
        overlayStatus.setText("正在隐藏悬浮窗并截取当前画面…");
        scanButton.setEnabled(false);
        panel.setVisibility(View.INVISIBLE);
        bubble.setVisibility(View.INVISIBLE);
        main.postDelayed(() -> {
            ensureCaptureSurface();
            captureRequested = true;
        }, 220);
    }

    private void ensureCaptureSurface() {
        if (projection == null) return;
        DisplayMetrics metrics = screenMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int dpi = metrics.densityDpi;
        if (width == captureWidth && height == captureHeight && imageReader != null && virtualDisplay != null) return;

        captureWidth = width;
        captureHeight = height;
        densityDpi = dpi;
        ImageReader replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        replacement.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "XiaGuBPAvatar",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                replacement.getSurface(),
                null,
                captureHandler
            );
        } else {
            virtualDisplay.resize(width, height, dpi);
            virtualDisplay.setSurface(replacement.getSurface());
        }
        ImageReader old = imageReader;
        imageReader = replacement;
        if (old != null) old.close();
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        if (!captureRequested) {
            image.close();
            return;
        }
        captureRequested = false;
        Bitmap bitmap;
        try {
            bitmap = imageToBitmap(image, captureWidth, captureHeight);
        } finally {
            image.close();
        }
        main.post(() -> restorePanel("截图完成，正在定位两侧已选与顶部 BAN 槽…"));
        detectLaneThenRecognize(bitmap);
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void detectLaneThenRecognize(Bitmap bitmap) {
        int left = Math.max(0, Math.round(bitmap.getWidth() * 0.24f));
        int top = 0;
        int width = Math.min(bitmap.getWidth() - left, Math.round(bitmap.getWidth() * 0.52f));
        int height = Math.min(bitmap.getHeight(), Math.max(1, Math.round(bitmap.getHeight() * 0.16f)));
        Bitmap laneStrip = Bitmap.createBitmap(bitmap, left, top, width, height);
        InputImage image = InputImage.fromBitmap(laneStrip, 0);
        laneRecognizer.process(image)
            .addOnSuccessListener(text -> {
                laneStrip.recycle();
                recognizePortraits(bitmap, LaneTextParser.detect(text));
            })
            .addOnFailureListener(error -> {
                laneStrip.recycle();
                // Lane text is optional. Portrait recognition and the manual lane switch still work.
                recognizePortraits(bitmap, null);
            });
    }

    private void recognizePortraits(Bitmap bitmap, String suggestedLane) {
        overlayStatus.setText("正在同步 131 位英雄头像库；中央候选区已排除…");
        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                AvatarRecognitionEngine.recognize(
                    OverlayCaptureService.this,
                    bitmap,
                    heroes,
                    AppPrefs.ourSideLeft(OverlayCaptureService.this),
                    new AvatarRecognitionEngine.Callback() {
                        @Override
                        public void onReady(BpModels.DetectedLineup lineup) {
                            bitmap.recycle();
                            lineup.suggestedLane = suggestedLane;
                            handleRecognizedLineup(lineup, heroes);
                        }

                        @Override
                        public void onError(String message) {
                            bitmap.recycle();
                            finishWithError(message);
                        }
                    }
                );
            }

            @Override
            public void onError(String message) {
                bitmap.recycle();
                finishWithError(message);
            }
        });
    }

    private void handleRecognizedLineup(BpModels.DetectedLineup lineup, List<BpModels.Hero> heroes) {
        if (lineup.suggestedLane != null) {
            AppPrefs.setLane(this, lineup.suggestedLane);
            setupLaneButtons();
        }
        showDetected(lineup);
        overlayStatus.setText("已识别画面，正在计算对位与队友配合…");
        List<BpModels.Hero> selected = new ArrayList<>();
        selected.addAll(lineup.allies);
        selected.addAll(lineup.enemies);
        BpApiClient.loadAnalyses(selected, new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(Map<Integer, BpModels.Analysis> analyses) {
                List<BpModels.Recommendation> recommendations = RecommendationEngine.recommend(
                    heroes,
                    analyses,
                    lineup,
                    AppPrefs.lane(OverlayCaptureService.this)
                );
                showRecommendations(recommendations);
                overlayStatus.setText("头像识别完成 · " + AppPrefs.lane(OverlayCaptureService.this) + "推荐已更新");
                scanButton.setEnabled(true);
            }

            @Override
            public void onError(String message) {
                finishWithError(message);
            }
        });
    }

    private void showDetected(BpModels.DetectedLineup lineup) {
        detectedSection.setVisibility(View.VISIBLE);
        detectedAllies.setText("我方：" + heroNames(lineup.allies));
        detectedEnemies.setText("敌方：" + heroNames(lineup.enemies));
        detectedBans.setText("BAN：" + heroNames(lineup.bans));
        if (lineup.issues.isEmpty()) {
            detectionIssues.setVisibility(View.GONE);
        } else {
            detectionIssues.setVisibility(View.VISIBLE);
            detectionIssues.setText(String.join("\n", lineup.issues));
        }
    }

    private void showRecommendations(List<BpModels.Recommendation> recommendations) {
        recommendationSection.setVisibility(View.VISIBLE);
        TextView[] rows = {recommendationOne, recommendationTwo, recommendationThree};
        for (int i = 0; i < rows.length; i++) {
            if (i < recommendations.size()) {
                BpModels.Recommendation value = recommendations.get(i);
                rows[i].setVisibility(View.VISIBLE);
                rows[i].setText((i + 1) + "  " + value.hero.name + "   " + value.score + "\n" + value.summary);
            } else {
                rows[i].setVisibility(View.GONE);
            }
        }
        if (recommendations.isEmpty()) {
            recommendationOne.setVisibility(View.VISIBLE);
            recommendationOne.setText("暂无符合该分路的可用候选，请在完整面板中核对已选与 BAN 位。");
        }
    }

    private void finishWithError(String message) {
        restorePanel(message);
        scanButton.setEnabled(true);
    }

    private void restorePanel(String status) {
        if (panel != null) panel.setVisibility(View.VISIBLE);
        if (bubble != null) bubble.setVisibility(View.GONE);
        if (overlayStatus != null) overlayStatus.setText(status);
    }

    private String heroNames(List<BpModels.Hero> heroes) {
        if (heroes.isEmpty()) return "未识别";
        List<String> names = new ArrayList<>();
        for (BpModels.Hero hero : heroes) names.add(hero.name);
        return String.join("、", names);
    }

    private void updateTeamSideLabel() {
        switchTeamSideButton.setText(AppPrefs.ourSideLeft(this) ? "我方在左" : "我方在右");
    }

    private DisplayMetrics screenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    @Override
    public void onDestroy() {
        captureRequested = false;
        if (bubble != null) {
            windowManager.removeView(bubble);
            bubble = null;
        }
        if (panel != null) {
            windowManager.removeView(panel);
            panel = null;
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (laneRecognizer != null) laneRecognizer.close();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
