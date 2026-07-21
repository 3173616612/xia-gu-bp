package com.xiagu.bp;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    static final String EXTRA_OPEN_WEB = "open_web";
    private static final int REQUEST_OVERLAY = 4101;
    private static final int REQUEST_CAPTURE = 4102;
    private static final int REQUEST_NOTIFICATIONS = 4103;
    private static final String SITE_URL = "https://xia-gu-bp-live.yxf3173616612.chatgpt.site/";

    private ScrollView controlScreen;
    private WebView webView;
    private Button assistantTab;
    private Button webTab;
    private TextView overlayPermissionStatus;
    private TextView capturePermissionStatus;
    private TextView mainStatus;
    private TextView dataStatus;
    private RadioGroup laneGroup;
    private RadioGroup teamSideGroup;
    private boolean webLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupLaneButtons(laneGroup, AppPrefs.lane(this));
        setupTeamSide();
        setupWebView();
        setupActions();
        refreshPermissionState();
        syncDataStatus();

        if (getIntent().getBooleanExtra(EXTRA_OPEN_WEB, false)) showWeb();
    }

    private void bindViews() {
        controlScreen = findViewById(R.id.controlScreen);
        webView = findViewById(R.id.webView);
        assistantTab = findViewById(R.id.assistantTab);
        webTab = findViewById(R.id.webTab);
        overlayPermissionStatus = findViewById(R.id.overlayPermissionStatus);
        capturePermissionStatus = findViewById(R.id.capturePermissionStatus);
        mainStatus = findViewById(R.id.mainStatus);
        dataStatus = findViewById(R.id.dataStatus);
        laneGroup = findViewById(R.id.laneGroup);
        teamSideGroup = findViewById(R.id.teamSideGroup);
    }

    private void setupActions() {
        findViewById(R.id.grantOverlayButton).setOnClickListener(view -> requestOverlayPermission());
        findViewById(R.id.startAssistantButton).setOnClickListener(view -> startAssistantFlow());
        assistantTab.setOnClickListener(view -> showControls());
        webTab.setOnClickListener(view -> showWeb());
    }

    private void setupTeamSide() {
        boolean left = AppPrefs.ourSideLeft(this);
        teamSideGroup.check(left ? R.id.teamLeft : R.id.teamRight);
        teamSideGroup.setOnCheckedChangeListener((group, checkedId) ->
            AppPrefs.setOurSideLeft(this, checkedId == R.id.teamLeft)
        );
    }

    private void setupLaneButtons(RadioGroup group, String selected) {
        group.removeAllViews();
        int horizontalPadding = dp(14);
        for (String lane : AppPrefs.LANES) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setTag(lane);
            button.setText(lane);
            button.setTextColor(Color.WHITE);
            button.setTextSize(12);
            button.setGravity(android.view.Gravity.CENTER);
            button.setButtonDrawable(null);
            button.setBackgroundResource(R.drawable.bg_chip);
            button.setPadding(horizontalPadding, 0, horizontalPadding, 0);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            );
            params.setMarginEnd(dp(7));
            group.addView(button, params);
            button.setChecked(lane.equals(selected));
        }
        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            View checked = radioGroup.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String lane) AppPrefs.setLane(this, lane);
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if ("https".equalsIgnoreCase(target.getScheme())
                    && "xia-gu-bp-live.yxf3173616612.chatgpt.site".equalsIgnoreCase(target.getHost())) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, target));
                return true;
            }
        });
    }

    private void showControls() {
        controlScreen.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        assistantTab.setBackgroundResource(R.drawable.bg_primary);
        webTab.setBackgroundResource(R.drawable.bg_outline);
    }

    private void showWeb() {
        controlScreen.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        assistantTab.setBackgroundResource(R.drawable.bg_outline);
        webTab.setBackgroundResource(R.drawable.bg_primary);
        if (!webLoaded) {
            webLoaded = true;
            webView.loadUrl(SITE_URL);
        }
    }

    private void startAssistantFlow() {
        if (!Settings.canDrawOverlays(this)) {
            mainStatus.setText("请先允许悬浮窗权限，返回后再次点击启动。");
            requestOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        capturePermissionStatus.setText("屏幕识别权限 · 等待系统确认");
        mainStatus.setText("请在系统弹窗中允许一次屏幕共享；英雄头像只在设备内识别。 ");
        Intent captureIntent = Build.VERSION.SDK_INT >= 34
            ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            : manager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CAPTURE);
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())
        );
        startActivityForResult(intent, REQUEST_OVERLAY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                capturePermissionStatus.setText("屏幕识别权限 · 已取消");
                mainStatus.setText("未获得截图授权，悬浮助手尚未启动。");
                return;
            }
            Intent service = new Intent(this, OverlayCaptureService.class);
            service.setAction(OverlayCaptureService.ACTION_START);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            capturePermissionStatus.setText("屏幕识别权限 · 本次会话已授权");
            mainStatus.setText("悬浮助手已启动。现在可以切回游戏并点击 BP 按钮。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
    }

    private void refreshPermissionState() {
        boolean granted = Settings.canDrawOverlays(this);
        overlayPermissionStatus.setText(granted ? "悬浮窗权限 · 已授权" : "悬浮窗权限 · 未授权");
        overlayPermissionStatus.setTextColor(getColor(granted ? R.color.cyan : R.color.warning));
    }

    private void syncDataStatus() {
        dataStatus.setText("巅峰千强 · 正在同步英雄库");
        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(java.util.List<BpModels.Hero> heroes) {
                dataStatus.setText("巅峰千强 · 正在建立 " + heroes.size() + " 位头像库");
                AvatarRecognitionEngine.prewarm(MainActivity.this, heroes, new AvatarRecognitionEngine.PrewarmCallback() {
                    @Override
                    public void onReady(int count) {
                        dataStatus.setText("巅峰千强 · 实时头像 " + count + " 位");
                    }

                    @Override
                    public void onError(String message) {
                        dataStatus.setText("巅峰千强 · 头像库待联网补齐");
                    }
                });
            }

            @Override
            public void onError(String message) {
                dataStatus.setText("巅峰千强 · 暂时离线");
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack();
            else showControls();
            return;
        }
        super.onBackPressed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
