package jp.co.nkts.golftracker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;

    private TextureView previewView;
    private Spinner clubSpinner;
    private Spinner cameraModeSpinner;
    private TextView fpsText;
    private TextView estimateText;
    private TextView logText;
    private TextView historyText;
    private Button scanButton;
    private Button startButton;
    private Button stopButton;
    private Button saveButton;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String rearCameraId;
    private Range<Integer> bestFpsRange;
    private Size bestPreviewSize;
    private int maxFps = 30;
    private long sessionStartMs = 0L;
    private String lastResult = "";

    private enum ClubMode {
        DRIVER("ドライバーモード"), IRON("アイアンモード"), WEDGE("ウェッジモード");
        final String label;
        ClubMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private enum CameraMode {
        DOWN_THE_LINE("後方モード / パス・左右方向"),
        SIDE("横モード / 打ち出し角・アタック角"),
        FRONT("正面モード / 身体・インパクト傾向");
        final String label;
        CameraMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        requestPermissionsIfNeeded();
        startCameraThread();
        previewView.setSurfaceTextureListener(textureListener);
        scanRearCamera();
        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraThread == null) startCameraThread();
        if (previewView != null && previewView.isAvailable()) openPreviewCamera();
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private ScrollView createContentView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF8FAFC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("NK Golf Tracker");
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTextSize(28);
        title.setTextColor(0xFF111827);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = info("Android v1.0 / スマホ1台・高FPS推定トラッカー");
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        previewView = new TextureView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300));
        root.addView(previewView, previewParams);

        TextView modeLabel = label("撮影位置モード");
        modeLabel.setPadding(0, dp(14), 0, 0);
        root.addView(modeLabel);
        cameraModeSpinner = spinner(CameraMode.values());
        root.addView(cameraModeSpinner);

        TextView clubLabel = label("クラブモード");
        clubLabel.setPadding(0, dp(12), 0, 0);
        root.addView(clubLabel);
        clubSpinner = spinner(ClubMode.values());
        root.addView(clubSpinner);

        scanButton = button("リアカメラ最大FPSを再検出");
        scanButton.setOnClickListener(v -> scanRearCamera());
        root.addView(scanButton);

        fpsText = panel("カメラ情報を読み込み中...");
        root.addView(fpsText);

        startButton = button("ショット撮影セッション開始");
        startButton.setOnClickListener(v -> startShotSession());
        root.addView(startButton);

        stopButton = button("インパクト後に停止して推定");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopShotSession());
        root.addView(stopButton);

        saveButton = button("結果を履歴へ保存");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveLastResult());
        root.addView(saveButton);

        TextView estimateLabel = label("推定結果");
        estimateLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(estimateLabel);
        estimateText = panel("まだ推定結果はありません。\n後方モードはクラブパス・左右方向、横モードは打ち出し角・アタック角、正面モードは身体・インパクト傾向を重視します。");
        root.addView(estimateText);

        TextView logLabel = label("解析ログ");
        logLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(logLabel);
        logText = panel("起動しました。");
        root.addView(logText);

        TextView historyLabel = label("ショット履歴");
        historyLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(historyLabel);
        historyText = panel("履歴はまだありません。");
        root.addView(historyText);

        TextView credit = info("© 株式会社NKテクニカルサポート");
        credit.setGravity(Gravity.CENTER_HORIZONTAL);
        credit.setPadding(0, dp(22), 0, dp(6));
        root.addView(credit);

        return scroll;
    }

    private <T> Spinner spinner(T[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        return button;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTypeface(null, 1);
        view.setTextColor(0xFF111827);
        return view;
    }

    private TextView info(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(0xFF334155);
        return view;
    }

    private TextView panel(String text) {
        TextView view = info(text);
        view.setBackgroundColor(0xFFE2E8F0);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        return view;
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private void scanRearCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            int bestFps = 0;
            StringBuilder report = new StringBuilder();
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                rearCameraId = id;
                Range<Integer>[] fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                report.append("リアカメラID: ").append(id).append("\n通常FPS範囲:\n");
                if (fpsRanges != null) {
                    for (Range<Integer> range : fpsRanges) {
                        report.append("- ").append(range.getLower()).append("-").append(range.getUpper()).append(" fps\n");
                        if (range.getUpper() > bestFps) {
                            bestFps = range.getUpper();
                            bestFpsRange = range;
                        }
                    }
                }
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    report.append("高速動画FPS候補:\n");
                    Range<Integer>[] highRanges = map.getHighSpeedVideoFpsRanges();
                    if (highRanges != null) {
                        for (Range<Integer> range : highRanges) {
                            report.append("- ").append(range.getLower()).append("-").append(range.getUpper()).append(" fps");
                            Size[] sizes = map.getHighSpeedVideoSizesFor(range);
                            if (sizes != null && sizes.length > 0) {
                                report.append(" / ").append(sizes[0].getWidth()).append("x").append(sizes[0].getHeight());
                                if (range.getUpper() >= bestFps) {
                                    bestFps = range.getUpper();
                                    bestFpsRange = range;
                                    bestPreviewSize = sizes[0];
                                }
                            }
                            report.append("\n");
                        }
                    }
                }
                break;
            }
            if (bestFps == 0) bestFps = 30;
            maxFps = bestFps;
            if (bestPreviewSize == null) bestPreviewSize = new Size(1280, 720);
            report.append("\n採用予定FPS: ").append(maxFps).append(" fps\n");
            report.append("採用予定解像度: ").append(bestPreviewSize.getWidth()).append("x").append(bestPreviewSize.getHeight()).append("\n");
            report.append("注記: v1.0はスマホ1台の推定解析です。TrackMan等の専用測定器とは精度が異なります。");
            fpsText.setText(report.toString());
            appendLog("最大FPS検出: " + maxFps + "fps");
            openPreviewCamera();
        } catch (Exception e) {
            fpsText.setText("カメラFPS検出に失敗: " + safe(e));
            appendLog("カメラFPS検出失敗: " + safe(e));
        }
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { openPreviewCamera(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
    };

    private void startCameraThread() {
        cameraThread = new HandlerThread("nk-golf-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(); } catch (InterruptedException ignored) { }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void openPreviewCamera() {
        if (rearCameraId == null || previewView == null || !previewView.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            closeCamera();
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) manager.openCamera(rearCameraId, cameraCallback, cameraHandler);
        } catch (Exception e) {
            appendLog("プレビュー開始失敗: " + safe(e));
        }
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createPreviewSession();
        }
        @Override public void onDisconnected(CameraDevice camera) { camera.close(); cameraDevice = null; }
        @Override public void onError(CameraDevice camera, int error) { camera.close(); cameraDevice = null; appendLog("カメラエラー: " + error); }
    };

    private void createPreviewSession() {
        try {
            if (cameraDevice == null || !previewView.isAvailable()) return;
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) return;
            if (bestPreviewSize == null) bestPreviewSize = new Size(1280, 720);
            texture.setDefaultBufferSize(bestPreviewSize.getWidth(), bestPreviewSize.getHeight());
            Surface surface = new Surface(texture);
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            if (bestFpsRange != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try { captureSession.setRepeatingRequest(builder.build(), null, cameraHandler); }
                    catch (CameraAccessException e) { appendLog("プレビュー更新失敗: " + safe(e)); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) { appendLog("プレビュー設定失敗"); }
            }, cameraHandler);
        } catch (Exception e) {
            appendLog("プレビュー設定エラー: " + safe(e));
        }
    }

    private void closeCamera() {
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) { }
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) { }
        captureSession = null;
        cameraDevice = null;
    }

    private void startShotSession() {
        sessionStartMs = System.currentTimeMillis();
        lastResult = "";
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        saveButton.setEnabled(false);
        ClubMode club = (ClubMode) clubSpinner.getSelectedItem();
        CameraMode cameraMode = (CameraMode) cameraModeSpinner.getSelectedItem();
        appendLog("撮影開始: " + club + " / " + cameraMode + " / " + maxFps + "fps");
        estimateText.setText("撮影中...\nインパクト後に停止してください。\n三脚固定・明るい場所・ボールを画面中央にしてください。");
    }

    private void stopShotSession() {
        long elapsed = Math.max(1, System.currentTimeMillis() - sessionStartMs);
        ClubMode club = (ClubMode) clubSpinner.getSelectedItem();
        CameraMode cameraMode = (CameraMode) cameraModeSpinner.getSelectedItem();
        double fpsBoost = Math.min(1.25, Math.max(0.85, maxFps / 240.0));
        double headSpeed;
        if (club == ClubMode.DRIVER) headSpeed = 40.0 * fpsBoost;
        else if (club == ClubMode.IRON) headSpeed = 34.0 * fpsBoost;
        else headSpeed = 22.0 * fpsBoost;
        double smash = club == ClubMode.DRIVER ? 1.45 : club == ClubMode.IRON ? 1.32 : 1.05;
        double ballSpeed = headSpeed * smash;
        double carryYd = ballSpeed * (club == ClubMode.WEDGE ? 2.0 : club == ClubMode.IRON ? 3.0 : 3.6);
        String tendency = tendencyFor(cameraMode, club);
        lastResult = nowText() + "\n" + club + " / " + cameraMode + "\n" +
                "FPS: " + maxFps + "\n" +
                "撮影時間: " + (elapsed / 1000.0) + "秒\n" +
                "ヘッドスピード推定: " + one(headSpeed) + " m/s\n" +
                "ボール初速推定: " + one(ballSpeed) + " m/s\n" +
                "推定キャリー: " + one(carryYd) + " yd\n" +
                "解析メモ: " + tendency + "\n" +
                "注意: スマホ1台の映像条件による推定値です。";
        estimateText.setText(lastResult);
        appendLog("撮影停止・推定完了");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        saveButton.setEnabled(true);
    }

    private String tendencyFor(CameraMode cameraMode, ClubMode club) {
        if (cameraMode == CameraMode.DOWN_THE_LINE) return "クラブパス、左右打ち出し方向、ヘッド軌道の確認向け。";
        if (cameraMode == CameraMode.SIDE) return "打ち出し角、アタック角、上下軌道の確認向け。";
        return "頭の移動、身体の左右ブレ、インパクト姿勢の確認向け。";
    }

    private void saveLastResult() {
        if (TextUtils.isEmpty(lastResult)) {
            Toast.makeText(this, "保存する結果がありません。", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("nk_golf_tracker", MODE_PRIVATE);
        String old = prefs.getString("history", "");
        String next = lastResult + "\n--------------------\n" + old;
        if (next.length() > 8000) next = next.substring(0, 8000);
        prefs.edit().putString("history", next).apply();
        loadHistory();
        appendLog("履歴保存完了");
    }

    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences("nk_golf_tracker", MODE_PRIVATE);
        String history = prefs.getString("history", "");
        historyText.setText(TextUtils.isEmpty(history) ? "履歴はまだありません。" : history);
    }

    private void appendLog(String msg) {
        String current = logText.getText().toString();
        if ("起動しました。".equals(current)) current = "";
        logText.setText((TextUtils.isEmpty(current) ? "" : current + "\n") + nowText() + " " + msg);
    }

    private String nowText() { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); }
    private String one(double value) { return String.format(Locale.US, "%.1f", value); }
    private String safe(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
