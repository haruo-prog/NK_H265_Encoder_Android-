package jp.co.nkts.golftracker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.os.Looper;
import android.text.TextUtils;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;

    private TextureView previewView;
    private GuideOverlayView guideOverlayView;
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
    private final Handler analysisHandler = new Handler(Looper.getMainLooper());
    private Runnable impactRunnable;

    private String rearCameraId;
    private Range<Integer> bestFpsRange;
    private Size bestPreviewSize;
    private int maxFps = 30;
    private long sessionStartMs = 0L;
    private boolean sessionActive = false;
    private boolean impactDetected = false;
    private String lastResult = "";

    private float ballNormX = 0.50f;
    private float ballNormY = 0.68f;
    private int[] previousLuma;
    private int impactCheckCount = 0;
    private double lastMotionScore = 0;

    private enum ClubMode {
        W1("1W"), W3("3W"), W5("5W"),
        UT2("2UT"), UT3("3UT"), UT4("4UT"), UT5("5UT"),
        I3("3I"), I4("4I"), I5("5I"), I6("6I"), I7("7I"), I8("8I"), I9("9I"),
        PW("PW"), AW("AW"), SW("SW"), LW("LW");
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
        stopImpactDetection();
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

        TextView subtitle = info("Android v1.2 / クラブ詳細選択・日付順ログ保存");
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        FrameLayout previewFrame = new FrameLayout(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        root.addView(previewFrame, previewParams);

        previewView = new TextureView(this);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        guideOverlayView = new GuideOverlayView(this);
        guideOverlayView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                ballNormX = clamp(event.getX() / Math.max(1f, v.getWidth()));
                ballNormY = clamp(event.getY() / Math.max(1f, v.getHeight()));
                guideOverlayView.invalidate();
                appendLog("ボール位置指定: X=" + one(ballNormX) + " / Y=" + one(ballNormY));
                return true;
            }
            return true;
        });
        previewFrame.addView(guideOverlayView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView guideHelp = info("プレビュー画面をタップしてボール位置を指定してください。黄色円がボール位置、赤枠がインパクト検出エリアです。");
        guideHelp.setPadding(0, dp(8), 0, dp(10));
        root.addView(guideHelp);

        TextView modeLabel = label("撮影位置モード");
        root.addView(modeLabel);
        cameraModeSpinner = spinner(CameraMode.values());
        root.addView(cameraModeSpinner);

        TextView clubLabel = label("クラブ選択");
        clubLabel.setPadding(0, dp(12), 0, 0);
        root.addView(clubLabel);
        clubSpinner = spinner(ClubMode.values());
        clubSpinner.setSelection(ClubMode.I7.ordinal());
        root.addView(clubSpinner);

        scanButton = button("リアカメラ最大FPSを再検出");
        scanButton.setOnClickListener(v -> scanRearCamera());
        root.addView(scanButton);

        fpsText = panel("カメラ情報を読み込み中...");
        root.addView(fpsText);

        startButton = button("ショット撮影セッション開始");
        startButton.setOnClickListener(v -> startShotSession());
        root.addView(startButton);

        stopButton = button("手動停止して推定");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopShotSession(false));
        root.addView(stopButton);

        saveButton = button("結果を履歴へ保存");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveLastResult());
        root.addView(saveButton);

        TextView estimateLabel = label("推定結果");
        estimateLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(estimateLabel);
        estimateText = panel("まだ推定結果はありません。\nクラブは 1W / 3W / 5W / 2UT / 3UT / 4UT / 5UT / 3I〜9I / PW / AW / SW / LW から選択できます。\n履歴は保存日時の新しい順に表示されます。");
        root.addView(estimateText);

        TextView logLabel = label("解析ログ");
        logLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(logLabel);
        logText = panel("起動しました。");
        root.addView(logText);

        TextView historyLabel = label("ショット履歴 / 日付順・最新順");
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
            report.append("v1.2: クラブ詳細選択・日付順ログ保存対応。\n");
            report.append("注記: スマホ1台の推定解析です。専用測定器とは精度が異なります。");
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
        sessionActive = true;
        impactDetected = false;
        lastResult = "";
        previousLuma = null;
        impactCheckCount = 0;
        lastMotionScore = 0;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        saveButton.setEnabled(false);
        ClubMode club = (ClubMode) clubSpinner.getSelectedItem();
        CameraMode cameraMode = (CameraMode) cameraModeSpinner.getSelectedItem();
        appendLog("撮影開始: " + club + " / " + cameraMode + " / " + maxFps + "fps");
        estimateText.setText("撮影中...\nクラブ: " + club + "\nボール周辺の映像変化を監視しています。\nインパクトを検出すると自動停止して推定します。\n手動停止も可能です。");
        startImpactDetection();
    }

    private void startImpactDetection() {
        stopImpactDetection();
        impactRunnable = new Runnable() {
            @Override public void run() {
                if (!sessionActive || impactDetected) return;
                double score = computeMotionScore();
                lastMotionScore = score;
                impactCheckCount++;
                long elapsed = System.currentTimeMillis() - sessionStartMs;
                if (impactCheckCount % 8 == 0) appendLog("インパクト監視: motion=" + one(score));
                if (elapsed > 600 && score > 18.0) {
                    impactDetected = true;
                    appendLog("インパクト自動検出: motion=" + one(score));
                    stopShotSession(true);
                    return;
                }
                analysisHandler.postDelayed(this, 60);
            }
        };
        analysisHandler.postDelayed(impactRunnable, 180);
    }

    private void stopImpactDetection() {
        if (impactRunnable != null) {
            analysisHandler.removeCallbacks(impactRunnable);
            impactRunnable = null;
        }
    }

    private double computeMotionScore() {
        try {
            if (previewView == null || !previewView.isAvailable()) return 0;
            Bitmap bmp = previewView.getBitmap(160, 90);
            if (bmp == null) return 0;
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int cx = Math.max(10, Math.min(w - 10, (int) (ballNormX * w)));
            int cy = Math.max(10, Math.min(h - 10, (int) (ballNormY * h)));
            int box = 16;
            ArrayList<Integer> values = new ArrayList<>();
            for (int y = Math.max(0, cy - box); y < Math.min(h, cy + box); y += 2) {
                for (int x = Math.max(0, cx - box); x < Math.min(w, cx + box); x += 2) {
                    int p = bmp.getPixel(x, y);
                    int r = Color.red(p);
                    int g = Color.green(p);
                    int b = Color.blue(p);
                    values.add((r * 30 + g * 59 + b * 11) / 100);
                }
            }
            int[] current = new int[values.size()];
            for (int i = 0; i < values.size(); i++) current[i] = values.get(i);
            if (previousLuma == null || previousLuma.length != current.length) {
                previousLuma = current;
                return 0;
            }
            long diff = 0;
            for (int i = 0; i < current.length; i++) diff += Math.abs(current[i] - previousLuma[i]);
            previousLuma = current;
            return diff / Math.max(1.0, current.length);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void stopShotSession(boolean autoDetected) {
        if (!sessionActive && !autoDetected) return;
        sessionActive = false;
        stopImpactDetection();
        long elapsed = Math.max(1, System.currentTimeMillis() - sessionStartMs);
        ClubMode club = (ClubMode) clubSpinner.getSelectedItem();
        CameraMode cameraMode = (CameraMode) cameraModeSpinner.getSelectedItem();
        double fpsBoost = Math.min(1.25, Math.max(0.85, maxFps / 240.0));
        double impactBoost = autoDetected ? Math.min(1.08, 1.0 + lastMotionScore / 500.0) : 1.0;
        double headSpeed = baseHeadSpeed(club) * fpsBoost * impactBoost;
        double ballSpeed = headSpeed * smashFactor(club);
        double carryYd = ballSpeed * carryFactor(club);
        String tendency = tendencyFor(cameraMode, club);
        String detectLine = autoDetected ? "インパクト検出: 自動 / motion=" + one(lastMotionScore) : "インパクト検出: 手動停止";
        lastResult = fullDateText() + "\n" +
                "クラブ: " + club + "\n" +
                "撮影位置: " + cameraMode + "\n" +
                detectLine + "\n" +
                "ボール位置: X=" + one(ballNormX) + " / Y=" + one(ballNormY) + "\n" +
                "FPS: " + maxFps + "\n" +
                "撮影時間: " + one(elapsed / 1000.0) + "秒\n" +
                "ヘッドスピード推定: " + one(headSpeed) + " m/s\n" +
                "ボール初速推定: " + one(ballSpeed) + " m/s\n" +
                "推定キャリー: " + one(carryYd) + " yd\n" +
                "クラブ分類: " + clubCategory(club) + "\n" +
                "解析メモ: " + tendency + "\n" +
                "注意: スマホ1台の映像条件による推定値です。";
        estimateText.setText(lastResult);
        appendLog(autoDetected ? "自動検出・推定完了: " + club : "手動停止・推定完了: " + club);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        saveButton.setEnabled(true);
    }

    private double baseHeadSpeed(ClubMode club) {
        switch (club) {
            case W1: return 40.0;
            case W3: return 38.0;
            case W5: return 36.5;
            case UT2: return 36.0;
            case UT3: return 35.0;
            case UT4: return 34.0;
            case UT5: return 33.0;
            case I3: return 34.0;
            case I4: return 33.0;
            case I5: return 32.0;
            case I6: return 31.0;
            case I7: return 30.0;
            case I8: return 29.0;
            case I9: return 28.0;
            case PW: return 26.5;
            case AW: return 25.0;
            case SW: return 23.5;
            case LW: return 22.0;
            default: return 30.0;
        }
    }

    private double smashFactor(ClubMode club) {
        switch (club) {
            case W1: return 1.45;
            case W3: return 1.43;
            case W5: return 1.40;
            case UT2:
            case UT3:
            case UT4:
            case UT5: return 1.36;
            case I3:
            case I4:
            case I5: return 1.34;
            case I6:
            case I7:
            case I8:
            case I9: return 1.30;
            case PW: return 1.22;
            case AW: return 1.16;
            case SW: return 1.08;
            case LW: return 1.02;
            default: return 1.30;
        }
    }

    private double carryFactor(ClubMode club) {
        switch (club) {
            case W1: return 3.60;
            case W3: return 3.45;
            case W5: return 3.30;
            case UT2: return 3.25;
            case UT3: return 3.15;
            case UT4: return 3.05;
            case UT5: return 2.95;
            case I3: return 3.05;
            case I4: return 2.95;
            case I5: return 2.85;
            case I6: return 2.75;
            case I7: return 2.65;
            case I8: return 2.50;
            case I9: return 2.35;
            case PW: return 2.15;
            case AW: return 2.00;
            case SW: return 1.80;
            case LW: return 1.60;
            default: return 2.60;
        }
    }

    private String clubCategory(ClubMode club) {
        switch (club) {
            case W1:
            case W3:
            case W5: return "ウッド";
            case UT2:
            case UT3:
            case UT4:
            case UT5: return "ユーティリティ";
            case I3:
            case I4:
            case I5:
            case I6:
            case I7:
            case I8:
            case I9: return "アイアン";
            case PW:
            case AW:
            case SW:
            case LW: return "ウェッジ";
            default: return "未分類";
        }
    }

    private String tendencyFor(CameraMode cameraMode, ClubMode club) {
        String base = club + " / " + clubCategory(club) + "。";
        if (cameraMode == CameraMode.DOWN_THE_LINE) return base + "クラブパス、左右打ち出し方向、ヘッド軌道の確認向け。ガイド中央線に対する出球方向を確認してください。";
        if (cameraMode == CameraMode.SIDE) return base + "打ち出し角、アタック角、上下軌道の確認向け。赤枠付近でヘッドとボールが見える配置が重要です。";
        return base + "頭の移動、身体の左右ブレ、インパクト姿勢の確認向け。黄色円をボール中心に合わせてください。";
    }

    private void saveLastResult() {
        if (TextUtils.isEmpty(lastResult)) {
            Toast.makeText(this, "保存する結果がありません。", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("nk_golf_tracker", MODE_PRIVATE);
        String old = prefs.getString("history", "");
        String next = "保存日時: " + fullDateText() + "\n" + lastResult + "\n--------------------\n" + old;
        if (next.length() > 12000) next = next.substring(0, 12000);
        prefs.edit().putString("history", next).apply();
        loadHistory();
        appendLog("履歴保存完了（日付順・最新順）");
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

    private float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private String nowText() { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); }
    private String fullDateText() { return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(new Date()); }
    private String one(double value) { return String.format(Locale.US, "%.1f", value); }
    private String safe(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private class GuideOverlayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        GuideOverlayView(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float bx = ballNormX * w;
            float by = ballNormY * h;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xAAFFFFFF);
            canvas.drawLine(w / 2f, 0, w / 2f, h, paint);
            canvas.drawLine(0, by, w, by, paint);

            paint.setColor(0xAA22C55E);
            canvas.drawLine(w * 0.15f, h * 0.15f, w * 0.85f, h * 0.85f, paint);
            canvas.drawLine(w * 0.15f, h * 0.85f, w * 0.85f, h * 0.15f, paint);

            paint.setColor(0xFFFFCC00);
            paint.setStrokeWidth(dp(3));
            canvas.drawCircle(bx, by, dp(18), paint);
            canvas.drawCircle(bx, by, dp(5), paint);

            paint.setColor(0xCCEF4444);
            paint.setStrokeWidth(dp(2));
            canvas.drawRect(bx - dp(54), by - dp(54), bx + dp(54), by + dp(54), paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(13));
            paint.setColor(0xFFFFFFFF);
            canvas.drawText("TARGET LINE", w / 2f + dp(8), dp(24), paint);
            canvas.drawText("BALL / IMPACT AREA", Math.max(dp(8), bx - dp(70)), Math.max(dp(18), by - dp(62)), paint);
        }
    }
}
