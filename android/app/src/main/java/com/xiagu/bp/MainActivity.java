package com.xiagu.bp;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/** Launch pad: data status, floating-assistant setup and start, manual BP entry, about. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OVERLAY = 4101;
    private static final int REQUEST_CAPTURE = 4102;
    private static final int REQUEST_NOTIFICATIONS = 4103;

    private Button startAssistantButton;
    private Button grantOverlayButton;
    private ImageView overlayPermissionIcon;
    private ImageView capturePermissionIcon;
    private TextView overlayPermissionStatus;
    private TextView capturePermissionStatus;
    private TextView mainStatus;
    private TextView dataStatus;
    private RadioGroup laneGroup;
    private RadioGroup teamSideGroup;
    private DraftScreenDiagram diagram;
    private final XiaGuBpApplication.Listener bootstrapListener = this::showBootstrapState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.padForSystemBars(findViewById(R.id.mainRoot));
        bindViews();
        setupLaneButtons(AppPrefs.lane(this));
        setupTeamSide();
        setupActions();
        showVersion();
        refreshPermissionState();
        application().addBootstrapListener(bootstrapListener);
    }

    private void bindViews() {
        startAssistantButton = findViewById(R.id.startAssistantButton);
        grantOverlayButton = findViewById(R.id.grantOverlayButton);
        overlayPermissionIcon = findViewById(R.id.overlayPermissionIcon);
        capturePermissionIcon = findViewById(R.id.capturePermissionIcon);
        overlayPermissionStatus = findViewById(R.id.overlayPermissionStatus);
        capturePermissionStatus = findViewById(R.id.capturePermissionStatus);
        mainStatus = findViewById(R.id.mainStatus);
        dataStatus = findViewById(R.id.dataStatus);
        laneGroup = findViewById(R.id.laneGroup);
        teamSideGroup = findViewById(R.id.teamSideGroup);
        diagram = findViewById(R.id.draftDiagram);
        findViewById(R.id.mainLogo).setClipToOutline(true);
        TextView source = findViewById(R.id.sourceLinkText);
        source.setPaintFlags(source.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        TextView author = findViewById(R.id.authorLinkText);
        author.setPaintFlags(author.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
    }

    private void setupActions() {
        grantOverlayButton.setOnClickListener(view -> requestOverlayPermission());
        startAssistantButton.setOnClickListener(view -> startAssistantFlow());
        findViewById(R.id.manualBpButton).setOnClickListener(view ->
            startActivity(new Intent(this, ManualBpActivity.class))
        );
        findViewById(R.id.sourceLink).setOnClickListener(view ->
            openUri(Uri.parse("https://tianyuanzhiyi.com/"), "没有找到可以打开网页的应用。")
        );
        findViewById(R.id.authorLink).setOnClickListener(view ->
            openUri(Uri.parse("mailto:3173616612@qq.com"), "没有找到可以发送邮件的应用。")
        );
        findViewById(R.id.donateButton).setOnClickListener(view -> showDonationDialog());
    }

    private void showVersion() {
        TextView version = findViewById(R.id.versionText);
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            version.setText("版本 " + name);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            version.setVisibility(View.GONE);
        }
    }

    private void openUri(Uri uri, String failureMessage) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            mainStatus.setText(failureMessage);
        }
    }

    private void showDonationDialog() {
        Dialog dialog = new Dialog(this, R.style.Theme_XiaGuBP_Dialog);
        dialog.setContentView(R.layout.dialog_donation);
        dialog.setCanceledOnTouchOutside(true);
        dialog.findViewById(R.id.closeDonationButton).setOnClickListener(view -> dialog.dismiss());
        dialog.show();

        Window window = dialog.getWindow();
        if (window == null) return;
        int availableWidth = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32);
        window.setLayout(Math.min(availableWidth, Ui.dp(this, 360)), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void setupTeamSide() {
        boolean left = AppPrefs.ourSideLeft(this);
        teamSideGroup.check(left ? R.id.teamLeft : R.id.teamRight);
        diagram.setOurSideLeft(left);
        teamSideGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean ourLeft = checkedId == R.id.teamLeft;
            AppPrefs.setOurSideLeft(this, ourLeft);
            diagram.setOurSideLeft(ourLeft);
        });
    }

    private void setupLaneButtons(String selected) {
        laneGroup.removeAllViews();
        for (int index = 0; index < AppPrefs.LANES.size(); index++) {
            String lane = AppPrefs.LANES.get(index);
            RadioButton button = new RadioButton(this, null, 0, R.style.Widget_XiaGu_Segment);
            button.setId(View.generateViewId());
            button.setTag(lane);
            button.setText(lane);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.MATCH_PARENT, 1f);
            if (index > 0) params.setMarginStart(Ui.dp(this, 2));
            laneGroup.addView(button, params);
            if (lane.equals(selected)) laneGroup.check(button.getId());
        }
        laneGroup.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            View checked = radioGroup.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String lane) AppPrefs.setLane(this, lane);
        });
    }

    private void startAssistantFlow() {
        if (!Settings.canDrawOverlays(this)) {
            mainStatus.setText("先允许“显示在其他应用上层”，返回后再点一次启动。");
            requestOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        capturePermissionStatus.setText("等待系统确认");
        mainStatus.setText("请在系统弹窗中允许屏幕共享。英雄头像只在本机识别，截图不会上传。");
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
        try {
            startActivityForResult(intent, REQUEST_OVERLAY);
        } catch (ActivityNotFoundException exception) {
            mainStatus.setText("无法打开权限设置，请在系统设置里为峡谷 BP 开启“显示在其他应用上层”。");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                capturePermissionStatus.setText("已取消，悬浮助手没有启动");
                capturePermissionIcon.setImageResource(R.drawable.ic_alert_circle);
                mainStatus.setText("没有获得屏幕录制授权，悬浮助手没有启动。");
                return;
            }
            Intent service = new Intent(this, OverlayCaptureService.class);
            service.setAction(OverlayCaptureService.ACTION_START);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(OverlayCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            capturePermissionStatus.setText("本次已允许，只在点识别时截取一帧");
            capturePermissionIcon.setImageResource(R.drawable.ic_check_circle);
            mainStatus.setText("悬浮助手已启动。切回游戏，在选将界面点 BP 悬浮球。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
    }

    private void refreshPermissionState() {
        boolean granted = Settings.canDrawOverlays(this);
        overlayPermissionStatus.setText(granted ? "已允许" : "未允许，悬浮窗需要这项权限");
        overlayPermissionStatus.setTextColor(getColor(granted ? R.color.mist : R.color.caution_text));
        overlayPermissionIcon.setImageResource(granted ? R.drawable.ic_check_circle : R.drawable.ic_alert_circle);
        grantOverlayButton.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private XiaGuBpApplication application() {
        return (XiaGuBpApplication) getApplication();
    }

    private void showBootstrapState(XiaGuBpApplication.BootstrapState state) {
        switch (state.phase()) {
            case SYNCING -> {
                setDataStatus("正在同步英雄库", R.color.caution);
                startAssistantButton.setEnabled(false);
                startAssistantButton.setText("正在同步英雄库…");
            }
            case BUILDING -> {
                setDataStatus("正在准备 " + state.heroCount() + " 位英雄头像", R.color.caution);
                startAssistantButton.setEnabled(false);
                startAssistantButton.setText("正在准备头像库…");
            }
            case READY -> {
                setDataStatus(state.heroCount() + " 位英雄已就绪", R.color.edge);
                startAssistantButton.setEnabled(true);
                startAssistantButton.setText("启动悬浮助手");
            }
            case ERROR -> {
                setDataStatus("数据同步失败", R.color.enemy);
                mainStatus.setText("头像库没有准备好。请检查网络后重新打开应用。\n" + state.message());
                startAssistantButton.setEnabled(false);
                startAssistantButton.setText("头像库未就绪");
            }
        }
    }

    private void setDataStatus(String text, int dotColorRes) {
        dataStatus.setText(text);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(getColor(dotColorRes));
        int size = Ui.dp(this, 7);
        dot.setSize(size, size);
        dataStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    }

    @Override
    protected void onDestroy() {
        application().removeBootstrapListener(bootstrapListener);
        super.onDestroy();
    }
}
