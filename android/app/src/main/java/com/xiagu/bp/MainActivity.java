package com.xiagu.bp;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQUEST_OVERLAY = 4101;
    private static final int REQUEST_CAPTURE = 4102;
    private static final int REQUEST_NOTIFICATIONS = 4103;

    private Button startAssistantButton;
    private TextView overlayPermissionStatus;
    private TextView capturePermissionStatus;
    private TextView mainStatus;
    private TextView dataStatus;
    private RadioGroup laneGroup;
    private RadioGroup teamSideGroup;
    private final XiaGuBpApplication.Listener bootstrapListener = this::showBootstrapState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupLaneButtons(laneGroup, AppPrefs.lane(this));
        setupTeamSide();
        setupActions();
        refreshPermissionState();
        startAssistantButton.setEnabled(false);
        startAssistantButton.setText("正在初始化头像库…");
        application().addBootstrapListener(bootstrapListener);
    }

    private void bindViews() {
        startAssistantButton = findViewById(R.id.startAssistantButton);
        overlayPermissionStatus = findViewById(R.id.overlayPermissionStatus);
        capturePermissionStatus = findViewById(R.id.capturePermissionStatus);
        mainStatus = findViewById(R.id.mainStatus);
        dataStatus = findViewById(R.id.dataStatus);
        laneGroup = findViewById(R.id.laneGroup);
        teamSideGroup = findViewById(R.id.teamSideGroup);
    }

    private void setupActions() {
        findViewById(R.id.grantOverlayButton).setOnClickListener(view -> requestOverlayPermission());
        startAssistantButton.setOnClickListener(view -> startAssistantFlow());
        findViewById(R.id.manualBpButton).setOnClickListener(view ->
            startActivity(new Intent(this, ManualBpActivity.class))
        );
        findViewById(R.id.sourceLink).setOnClickListener(view ->
            openUri(Uri.parse("https://tianyuanzhiyi.com/"), "未找到可打开网页的应用。")
        );
        findViewById(R.id.authorLink).setOnClickListener(view ->
            openUri(Uri.parse("mailto:3173616612@qq.com"), "未找到可发送邮件的应用。")
        );
        findViewById(R.id.donateButton).setOnClickListener(view -> showDonationDialog());
    }

    private void openUri(Uri uri, String failureMessage) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            mainStatus.setText(failureMessage);
        }
    }

    private void showDonationDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_donation);
        dialog.setCanceledOnTouchOutside(true);
        dialog.findViewById(R.id.closeDonationButton).setOnClickListener(view -> dialog.dismiss());
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.76f;
        window.setAttributes(attributes);
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
        window.setLayout(Math.min(availableWidth, dp(390)), WindowManager.LayoutParams.WRAP_CONTENT);
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

    private XiaGuBpApplication application() {
        return (XiaGuBpApplication) getApplication();
    }

    private void showBootstrapState(XiaGuBpApplication.BootstrapState state) {
        switch (state.phase()) {
            case SYNCING -> {
                dataStatus.setText("实时数据 · 正在同步英雄库");
                startAssistantButton.setEnabled(false);
                startAssistantButton.setText("正在同步英雄库…");
            }
            case BUILDING -> {
                dataStatus.setText("头像识别 · 正在准备 " + state.heroCount() + " 位英雄");
                startAssistantButton.setEnabled(false);
                startAssistantButton.setText("正在初始化头像库…");
            }
            case READY -> {
                dataStatus.setText("实时数据已就绪 · " + state.heroCount() + " 位英雄");
                startAssistantButton.setEnabled(true);
                startAssistantButton.setText("启动悬浮助手");
            }
            case ERROR -> {
                dataStatus.setText("数据初始化失败");
                mainStatus.setText("头像库未完成初始化，请联网后重新启动应用。\n" + state.message());
                startAssistantButton.setEnabled(false);
                startAssistantButton.setText("头像库尚未就绪");
            }
        }
    }

    @Override
    protected void onDestroy() {
        application().removeBootstrapListener(bootstrapListener);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
