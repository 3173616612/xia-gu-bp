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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
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
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OverlayCaptureService extends Service {
    static final String ACTION_START = "com.xiagu.bp.action.START";
    static final String ACTION_STOP = "com.xiagu.bp.action.STOP";
    static final String EXTRA_RESULT_CODE = "projection_result_code";
    static final String EXTRA_RESULT_DATA = "projection_result_data";

    private static final int NOTIFICATION_ID = 7301;
    private static final String CHANNEL_ID = "bp_projection";
    private static final int VISIBLE_RECOMMENDATIONS = 3;

    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private WindowManager windowManager;
    private Context themed;
    private View panel;
    private TextView bubble;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams bubbleParams;
    private TextView overlayStatus;
    private TextView overlayEmpty;
    private LinearLayout detectedAllies;
    private LinearLayout detectedEnemies;
    private LinearLayout detectedBans;
    private TextView detectionIssues;
    private LinearLayout recommendationList;
    private View detectedSection;
    private View recommendationSection;
    private Button scanButton;
    private RadioGroup laneGroup;
    private RadioGroup sideGroup;

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
    private final Set<Integer> dismissedHeroIds = new HashSet<>();
    private final List<BpModels.Recommendation> visibleRecommendations = new ArrayList<>();
    private List<BpModels.Hero> activeRoster;
    private Map<Integer, BpModels.Analysis> activeAnalyses;
    private BpModels.DetectedLineup activeLineup;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        themed = new ContextThemeWrapper(this, R.style.Theme_XiaGuBP);
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
        bubble = new TextView(themed);
        bubble.setText("BP");
        bubble.setTextColor(getColor(R.color.frost));
        bubble.setTextSize(17);
        bubble.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        bubble.setGravity(Gravity.CENTER);
        bubble.setClickable(true);
        bubble.setFocusable(true);
        bubble.setContentDescription("展开峡谷 BP 悬浮助手");
        bubble.setBackgroundResource(R.drawable.bg_bubble);
        bubble.setElevation(dp(10));
        bubble.setOnClickListener(view -> expandPanel());

        bubbleParams = baseParams(dp(56), dp(56));
        bubbleParams.gravity = Gravity.TOP | Gravity.END;
        bubbleParams.x = dp(14);
        bubbleParams.y = dp(160);
        windowManager.addView(bubble, bubbleParams);
        attachBubbleGestures();

        FrameLayout overlayRoot = new FrameLayout(themed);
        panel = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, overlayRoot, false);
        panelParams = baseParams(panelWidth(), panelHeight());
        panelParams.gravity = Gravity.TOP | Gravity.END;
        panelParams.x = dp(12);
        panelParams.y = dp(12);
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

    private int panelWidth() {
        return Math.min(dp(340), screenMetrics().widthPixels - dp(24));
    }

    /** Fills the landscape height so the top recommendations never need scrolling. */
    private int panelHeight() {
        DisplayMetrics metrics = screenMetrics();
        return Math.max(dp(200), Math.min(metrics.heightPixels - dp(24), dp(560)));
    }

    private void bindPanel() {
        overlayStatus = panel.findViewById(R.id.overlayStatus);
        overlayEmpty = panel.findViewById(R.id.overlayEmpty);
        detectedAllies = panel.findViewById(R.id.detectedAllies);
        detectedEnemies = panel.findViewById(R.id.detectedEnemies);
        detectedBans = panel.findViewById(R.id.detectedBans);
        detectionIssues = panel.findViewById(R.id.detectionIssues);
        recommendationList = panel.findViewById(R.id.overlayRecommendationList);
        detectedSection = panel.findViewById(R.id.detectedSection);
        recommendationSection = panel.findViewById(R.id.recommendationSection);
        scanButton = panel.findViewById(R.id.scanButton);
        laneGroup = panel.findViewById(R.id.overlayLaneGroup);
        sideGroup = panel.findViewById(R.id.overlaySideGroup);

        setupLaneButtons();
        setupSideGroup();
        attachPanelDrag(panel.findViewById(R.id.overlayHeader));
        panel.findViewById(R.id.collapseOverlayButton).setOnClickListener(view -> collapsePanel());
        panel.findViewById(R.id.stopOverlayButton).setOnClickListener(view -> stopSelf());
        scanButton.setOnClickListener(view -> requestCapture());
        panel.findViewById(R.id.openMatchupAnalysisButton).setOnClickListener(view -> {
            if (activeLineup == null || activeRoster == null) return;
            MatchupAnalysisActivity.open(this, activeRoster, activeLineup, activeAnalyses, null, null);
            collapsePanel();
        });
        panel.findViewById(R.id.openFullPanelButton).setOnClickListener(view -> {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            collapsePanel();
        });
    }

    private void setupLaneButtons() {
        laneGroup.setOnCheckedChangeListener(null);
        laneGroup.removeAllViews();
        String selected = AppPrefs.lane(this);
        for (int index = 0; index < AppPrefs.LANES.size(); index++) {
            String lane = AppPrefs.LANES.get(index);
            RadioButton button = new RadioButton(themed, null, 0, R.style.Widget_XiaGu_Chip_Lane);
            button.setId(View.generateViewId());
            button.setTag(lane);
            button.setText(lane);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (index > 0) params.setMarginStart(dp(4));
            laneGroup.addView(button, params);
            if (lane.equals(selected)) laneGroup.check(button.getId());
        }
        laneGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            if (!(checked != null && checked.getTag() instanceof String lane)) return;
            AppPrefs.setLane(this, lane);
            // Switching lanes reuses the scanned lineup and relations; no new capture needed.
            if (activeLineup != null && activeAnalyses != null) refreshRecommendations("已换成" + lane + "推荐");
        });
    }

    private void setupSideGroup() {
        sideGroup.setOnCheckedChangeListener(null);
        sideGroup.check(AppPrefs.ourSideLeft(this) ? R.id.overlaySideLeft : R.id.overlaySideRight);
        sideGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean ourLeft = checkedId == R.id.overlaySideLeft;
            if (ourLeft == AppPrefs.ourSideLeft(this)) return;
            AppPrefs.setOurSideLeft(this, ourLeft);
            // The two columns were recognised correctly; only their owner changes, so swap and re-score.
            if (activeLineup != null) {
                swapSides(activeLineup);
                showDetected(activeLineup);
                if (activeAnalyses != null) refreshRecommendations("已互换我方与敌方");
            }
        });
    }

    private void swapSides(BpModels.DetectedLineup lineup) {
        List<BpModels.Hero> previousAllies = new ArrayList<>(lineup.allies);
        lineup.allies.clear();
        lineup.allies.addAll(lineup.enemies);
        lineup.enemies.clear();
        lineup.enemies.addAll(previousAllies);
        for (int i = 0; i < lineup.issues.size(); i++) {
            if (lineup.issues.get(i).startsWith("当前为我方 ")) {
                lineup.issues.set(i, "当前为我方 " + lineup.allies.size() + " 人、敌方 " + lineup.enemies.size()
                    + " 人；按画面现有阵容计算，不强制补齐五路。");
            }
        }
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
                        view.setPressed(true);
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
                        view.setPressed(false);
                        if (!moved) view.performClick();
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL -> {
                        view.setPressed(false);
                        return true;
                    }
                    default -> { return false; }
                }
            }
        });
    }

    /** The panel can be dragged by its header so it never has to cover the part of the game you need. */
    @SuppressLint("ClickableViewAccessibility")
    private void attachPanelDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = panelParams.x;
                        startY = panelParams.y;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        DisplayMetrics metrics = screenMetrics();
                        int maxX = Math.max(0, metrics.widthPixels - panelParams.width);
                        int maxY = Math.max(0, metrics.heightPixels - panelParams.height);
                        panelParams.x = clamp(startX - Math.round(event.getRawX() - downX), 0, maxX);
                        panelParams.y = clamp(startY + Math.round(event.getRawY() - downY), 0, maxY);
                        windowManager.updateViewLayout(panel, panelParams);
                        return true;
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        return true;
                    }
                    default -> { return false; }
                }
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        fitOverlaysToScreen();
    }

    private void fitOverlaysToScreen() {
        if (windowManager == null) return;
        DisplayMetrics metrics = screenMetrics();
        if (panel != null && panelParams != null) {
            panelParams.width = panelWidth();
            panelParams.height = panelHeight();
            panelParams.x = clamp(panelParams.x, 0, Math.max(0, metrics.widthPixels - panelParams.width));
            panelParams.y = clamp(panelParams.y, 0, Math.max(0, metrics.heightPixels - panelParams.height));
            windowManager.updateViewLayout(panel, panelParams);
        }
        if (bubble != null && bubbleParams != null) {
            bubbleParams.x = clamp(bubbleParams.x, 0, Math.max(0, metrics.widthPixels - bubbleParams.width));
            bubbleParams.y = clamp(bubbleParams.y, 0, Math.max(0, metrics.heightPixels - bubbleParams.height));
            windowManager.updateViewLayout(bubble, bubbleParams);
        }
    }

    private void expandPanel() {
        panelParams.width = panelWidth();
        panelParams.height = panelHeight();
        DisplayMetrics metrics = screenMetrics();
        panelParams.x = clamp(panelParams.x, 0, Math.max(0, metrics.widthPixels - panelParams.width));
        panelParams.y = clamp(panelParams.y, 0, Math.max(0, metrics.heightPixels - panelParams.height));
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
        resetRecommendationSession();
        overlayStatus.setText("正在隐藏悬浮窗并截取当前画面…");
        scanButton.setEnabled(false);
        scanButton.setText("识别中…");
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
            bitmap = imageToBitmap(image, image.getWidth(), image.getHeight());
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
        overlayStatus.setText("正从天元之弈直连梯度与英雄关系…");
        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                List<BpModels.Hero> originalAvatars = application().originalAvatarRoster();
                if (!originalAvatars.isEmpty()) {
                    recognizeWithOriginalAvatars(bitmap, suggestedLane, heroes, originalAvatars);
                    return;
                }
                BpApiClient.loadOriginalAvatarRoster(new BpApiClient.Callback<>() {
                    @Override
                    public void onSuccess(List<BpModels.Hero> fallbackAvatars) {
                        recognizeWithOriginalAvatars(bitmap, suggestedLane, heroes, fallbackAvatars);
                    }

                    @Override
                    public void onError(String message) {
                        bitmap.recycle();
                        finishWithError(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                bitmap.recycle();
                finishWithError(message);
            }
        });
    }

    private XiaGuBpApplication application() {
        return (XiaGuBpApplication) getApplication();
    }

    private void recognizeWithOriginalAvatars(
        Bitmap bitmap,
        String suggestedLane,
        List<BpModels.Hero> heroes,
        List<BpModels.Hero> originalAvatars
    ) {
        AvatarRecognitionEngine.recognize(
            OverlayCaptureService.this,
            bitmap,
            heroes,
            originalAvatars,
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

    private void handleRecognizedLineup(BpModels.DetectedLineup lineup, List<BpModels.Hero> heroes) {
        if (lineup.suggestedLane != null) {
            AppPrefs.setLane(this, lineup.suggestedLane);
            setupLaneButtons();
        }
        showDetected(lineup);
        activeRoster = heroes;
        activeLineup = lineup;
        activeAnalyses = java.util.Collections.emptyMap();
        overlayStatus.setText("已识别画面，正在计算对位与队友配合…");
        List<BpModels.Hero> selected = new ArrayList<>();
        selected.addAll(lineup.allies);
        selected.addAll(lineup.enemies);
        BpApiClient.loadAnalyses(selected, new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(Map<Integer, BpModels.Analysis> analyses) {
                if (activeLineup != lineup) return;
                activeRoster = heroes;
                activeAnalyses = analyses;
                refreshRecommendations(null);
                String tierDate = BpApiClient.latestTierDate();
                overlayStatus.setText(AppPrefs.lane(OverlayCaptureService.this) + "推荐已更新"
                    + (tierDate.isBlank() ? "" : "，梯度 " + shortDate(tierDate)));
                scanButton.setEnabled(true);
                scanButton.setText("重新识别");
            }

            @Override
            public void onError(String message) {
                finishWithError(message);
            }
        });
    }

    private void showDetected(BpModels.DetectedLineup lineup) {
        overlayEmpty.setVisibility(View.GONE);
        detectedSection.setVisibility(View.VISIBLE);
        fillStrip(detectedAllies, lineup.allies, getColor(R.color.ally), false, 5);
        fillStrip(detectedEnemies, lineup.enemies, getColor(R.color.enemy), false, 5);
        fillStrip(detectedBans, lineup.bans, 0, true, 0);
        detectedAllies.setContentDescription("我方：" + heroNames(lineup.allies));
        detectedEnemies.setContentDescription("敌方：" + heroNames(lineup.enemies));
        detectedBans.setContentDescription("BAN：" + heroNames(lineup.bans));
        if (lineup.issues.isEmpty()) {
            detectionIssues.setVisibility(View.GONE);
        } else {
            detectionIssues.setVisibility(View.VISIBLE);
            detectionIssues.setText(String.join("\n", lineup.issues));
        }
    }

    /** Portrait strip; unfilled pick slots are drawn dashed so missing recognitions are visible. */
    private void fillStrip(LinearLayout strip, List<BpModels.Hero> heroes, int ringColor, boolean banned, int minimumSlots) {
        strip.removeAllViews();
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        int size = dp(26);
        int count = Math.max(heroes.size(), minimumSlots);
        if (count == 0) {
            TextView none = new TextView(themed, null, 0, R.style.Text_Micro);
            none.setText("未识别到");
            strip.addView(none);
            return;
        }
        for (int i = 0; i < count; i++) {
            HeroAvatarView avatar = new HeroAvatarView(themed);
            avatar.setCornerRadiusDp(6);
            HeroAvatars.bind(avatar, i < heroes.size() ? heroes.get(i) : null);
            if (banned) avatar.setBanned(true);
            else if (ringColor != 0 && i < heroes.size()) avatar.setRing(ringColor, 1.5f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            if (i > 0) params.setMarginStart(dp(5));
            strip.addView(avatar, params);
        }
    }

    private void showRecommendations(List<BpModels.Recommendation> recommendations) {
        overlayEmpty.setVisibility(View.GONE);
        recommendationSection.setVisibility(View.VISIBLE);
        visibleRecommendations.clear();
        recommendationList.removeAllViews();
        if (recommendations.isEmpty()) {
            TextView empty = new TextView(themed, null, 0, R.style.Text_Caption);
            empty.setText("这个分路暂时没有可推荐的英雄。换一个分路，或重新识别阵容与 BAN 位。");
            empty.setPadding(0, dp(12), 0, dp(4));
            recommendationList.addView(empty);
            return;
        }
        boolean hasEnemies = activeLineup != null && !activeLineup.enemies.isEmpty();
        boolean hasAllies = activeLineup != null && !activeLineup.allies.isEmpty();
        LayoutInflater inflater = LayoutInflater.from(themed);
        int shown = Math.min(VISIBLE_RECOMMENDATIONS, recommendations.size());
        for (int i = 0; i < shown; i++) {
            BpModels.Recommendation value = recommendations.get(i);
            visibleRecommendations.add(value);
            View row = inflater.inflate(R.layout.item_overlay_recommendation, recommendationList, false);
            boolean first = i == 0;
            TextView rank = row.findViewById(R.id.ovRank);
            rank.setText(String.valueOf(i + 1));
            rank.setTextColor(getColor(first ? R.color.crown : R.color.dusk));
            HeroAvatarView avatar = row.findViewById(R.id.ovAvatar);
            HeroAvatars.bind(avatar, value.hero);
            avatar.setRing(first ? getColor(R.color.crown) : 0, 2);
            ((TextView) row.findViewById(R.id.ovName)).setText(value.hero.name);
            List<String> parts = new ArrayList<>();
            if (hasEnemies) parts.add("对敌 " + value.matchupScore);
            if (hasAllies) parts.add("配合 " + value.synergyScore);
            if (hasEnemies) parts.add("提升 " + value.counterLiftScore);
            else parts.add("梯度 " + value.tierScore);
            if (value.secondaryRole) parts.add("多位置");
            ((TextView) row.findViewById(R.id.ovMeta)).setText(String.join("  ", parts));
            TextView score = row.findViewById(R.id.ovScore);
            score.setText(String.valueOf(value.score));
            score.setTextColor(getColor(first ? R.color.crown : R.color.frost));
            row.findViewById(R.id.ovSummary).setContentDescription("第 " + (i + 1) + " 名，" + value.hero.name
                + "，推荐分 " + value.score + "，" + String.join("，", parts));
            Button skip = row.findViewById(R.id.ovSkip);
            skip.setContentDescription("不想玩" + value.hero.name + "，换下一位");
            final int index = i;
            skip.setOnClickListener(view -> dismissVisibleRecommendation(index));
            row.findViewById(R.id.ovDivider).setVisibility(i == shown - 1 ? View.GONE : View.VISIBLE);
            recommendationList.addView(row);
        }
    }

    private void dismissVisibleRecommendation(int index) {
        if (index < 0 || index >= visibleRecommendations.size()) return;
        BpModels.Hero removed = visibleRecommendations.get(index).hero;
        if (!dismissedHeroIds.add(removed.id)) return;
        refreshRecommendations("已跳过“" + removed.name + "”，后面的英雄按顺位补上");
    }

    private void refreshRecommendations(String status) {
        if (activeRoster == null || activeAnalyses == null || activeLineup == null) return;
        List<BpModels.Recommendation> recommendations = RecommendationEngine.recommend(
            activeRoster,
            activeAnalyses,
            activeLineup,
            AppPrefs.lane(OverlayCaptureService.this),
            dismissedHeroIds
        );
        showRecommendations(recommendations);
        if (status != null) {
            overlayStatus.setText(dismissedHeroIds.isEmpty() ? status : status + "，本次已跳过 " + dismissedHeroIds.size() + " 位");
        }
    }

    private void resetRecommendationSession() {
        dismissedHeroIds.clear();
        visibleRecommendations.clear();
        activeRoster = null;
        activeAnalyses = null;
        activeLineup = null;
        if (detectedSection != null) detectedSection.setVisibility(View.GONE);
        if (recommendationSection != null) recommendationSection.setVisibility(View.GONE);
    }

    private void finishWithError(String message) {
        restorePanel(message);
        scanButton.setEnabled(true);
        scanButton.setText(activeLineup == null ? "识别 BP 画面" : "重新识别");
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

    private static String shortDate(String date) {
        return date.length() == 10 && date.charAt(4) == '-' ? date.substring(5) : date;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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
