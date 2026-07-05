package jp.co.nkts.encoder;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 1001;
    private static final int REQUEST_NOTIFY = 2001;

    private final ArrayList<Uri> selectedUris = new ArrayList<>();
    private final ArrayList<String> selectedNames = new ArrayList<>();
    private boolean encodingInProgress = false;

    private TextView fileText;
    private Spinner presetSpinner;
    private Spinner modeSpinner;
    private Spinner bitrateSpinner;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView statusText;
    private TextView logText;
    private TextView historyText;
    private Button pickButton;
    private Button encodeButton;
    private Button cancelButton;

    private enum ResolutionPreset {
        HIGH("高解像度 1080p目安"), MEDIUM("中解像度 720p目安"), LOW("低解像度 480p目安");
        final String label;
        ResolutionPreset(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private enum EncodeMode {
        FAST("高速モード / 内蔵エンコーダ優先"), STANDARD("標準モード"), QUALITY("画質優先モード");
        final String label;
        EncodeMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private enum BitrateOption {
        AUTO("自動 / 端末推奨", 0),
        B800("800 kbps / 容量最小", 800),
        B1200("1.2 Mbps / 480p推奨", 1200),
        B2500("2.5 Mbps / 720p推奨", 2500),
        B5000("5 Mbps / 1080p推奨", 5000);
        final String label;
        final int kbps;
        BitrateOption(String label, int kbps) { this.label = label; this.kbps = kbps; }
        @Override public String toString() { return label; }
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!EncodeForegroundService.ACTION_STATUS.equals(intent.getAction())) return;
            String message = intent.getStringExtra(EncodeForegroundService.EXTRA_MESSAGE);
            int progress = intent.getIntExtra(EncodeForegroundService.EXTRA_PROGRESS, 0);
            boolean done = intent.getBooleanExtra(EncodeForegroundService.EXTRA_DONE, false);
            boolean success = intent.getBooleanExtra(EncodeForegroundService.EXTRA_SUCCESS, false);
            updateProgress(progress);
            if (!TextUtils.isEmpty(message)) {
                statusText.setText(message);
                appendLog(message);
            }
            if (done) {
                setBusy(false, success ? "完了" : "停止または失敗");
                updateProgress(success ? 100 : progress);
                loadHistory();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        requestNotifyPermissionIfNeeded();
        loadHistory();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(EncodeForegroundService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        loadHistory();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    private ScrollView createContentView() {
        int padding = dp(20);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF8FAFC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);

        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("nk_logo", "drawable", getPackageName()));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(104), dp(104));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logo, logoParams);

        TextView title = new TextView(this);
        title.setText("NK H.265 Encoder");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF111827);
        title.setPadding(0, dp(10), 0, 0);
        root.addView(title);

        TextView subtitle = makeInfoText("一括変換・ビットレート指定・履歴対応 v1.5.0");
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        pickButton = makeButton("動画を選択 / 複数選択可");
        pickButton.setOnClickListener(v -> pickVideos());
        root.addView(pickButton);

        fileText = makeInfoText("未選択");
        fileText.setPadding(0, dp(12), 0, dp(18));
        root.addView(fileText);

        root.addView(makeSectionLabel("解像度"));
        presetSpinner = makeSpinner(ResolutionPreset.values());
        presetSpinner.setSelection(ResolutionPreset.LOW.ordinal());
        root.addView(presetSpinner);

        TextView modeLabel = makeSectionLabel("エンコードモード");
        modeLabel.setPadding(0, dp(16), 0, 0);
        root.addView(modeLabel);
        modeSpinner = makeSpinner(EncodeMode.values());
        modeSpinner.setSelection(EncodeMode.FAST.ordinal());
        root.addView(modeSpinner);

        TextView bitrateLabel = makeSectionLabel("出力ビットレート");
        bitrateLabel.setPadding(0, dp(16), 0, 0);
        root.addView(bitrateLabel);
        bitrateSpinner = makeSpinner(BitrateOption.values());
        bitrateSpinner.setSelection(BitrateOption.B1200.ordinal());
        root.addView(bitrateSpinner);

        encodeButton = makeButton("一括H.265変換開始");
        LinearLayout.LayoutParams encodeParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        encodeParams.setMargins(0, dp(24), 0, 0);
        encodeButton.setLayoutParams(encodeParams);
        encodeButton.setOnClickListener(v -> startEncodeService());
        root.addView(encodeButton);

        cancelButton = makeButton("キャンセル");
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(v -> cancelEncode());
        root.addView(cancelButton);

        TextView progressLabel = makeSectionLabel("全体進行状況");
        progressLabel.setPadding(0, dp(22), 0, dp(8));
        root.addView(progressLabel);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        progressText = makeInfoText("0%");
        progressText.setGravity(Gravity.CENTER_HORIZONTAL);
        progressText.setPadding(0, dp(8), 0, dp(6));
        root.addView(progressText);

        statusText = makeInfoText("待機中");
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(statusText);

        TextView logLabel = makeSectionLabel("処理ログ");
        logLabel.setPadding(0, dp(24), 0, dp(8));
        root.addView(logLabel);
        logText = makePanelText("ここに変換状況が表示されます。");
        root.addView(logText);

        TextView historyLabel = makeSectionLabel("変換履歴");
        historyLabel.setPadding(0, dp(24), 0, dp(8));
        root.addView(historyLabel);
        historyText = makePanelText("履歴はまだありません。");
        root.addView(historyText);

        TextView credit = makeInfoText("© 株式会社NKテクニカルサポート");
        credit.setGravity(Gravity.CENTER_HORIZONTAL);
        credit.setTextColor(0xFF64748B);
        credit.setPadding(0, dp(26), 0, dp(10));
        root.addView(credit);
        return scrollView;
    }

    private <T> Spinner makeSpinner(T[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setBackgroundResource(getResources().getIdentifier("button_bg", "drawable", getPackageName()));
        button.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private TextView makeSectionLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(0xFF111827);
        return view;
    }

    private TextView makeInfoText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(0xFF334155);
        return view;
    }

    private TextView makePanelText(String text) {
        TextView view = makeInfoText(text);
        view.setTextSize(13);
        view.setBackgroundColor(0xFFE2E8F0);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        return view;
    }

    private void pickVideos() {
        if (encodingInProgress) {
            Toast.makeText(this, "変換中は動画を変更できません。", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/mp4", "video/avi", "video/x-msvideo", "video/*"});
        startActivityForResult(intent, REQUEST_PICK_VIDEO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VIDEO || resultCode != RESULT_OK || data == null) return;
        selectedUris.clear();
        selectedNames.clear();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) addSelectedUri(clipData.getItemAt(i).getUri(), data.getFlags());
        } else if (data.getData() != null) {
            addSelectedUri(data.getData(), data.getFlags());
        }
        fileText.setText("選択中: " + selectedUris.size() + "件\n" + TextUtils.join("\n", selectedNames));
        appendLog("動画を選択しました: " + selectedUris.size() + "件");
    }

    private void addSelectedUri(Uri uri, int flags) {
        if (uri == null) return;
        selectedUris.add(uri);
        selectedNames.add(getDisplayName(uri));
        int persistFlags = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (persistFlags != 0) {
            try { getContentResolver().takePersistableUriPermission(uri, persistFlags); } catch (Exception ignored) {}
        }
    }

    private void startEncodeService() {
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, "先に動画を選択してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (encodingInProgress) {
            Toast.makeText(this, "現在変換中です。", Toast.LENGTH_SHORT).show();
            return;
        }
        requestNotifyPermissionIfNeeded();
        ResolutionPreset preset = (ResolutionPreset) presetSpinner.getSelectedItem();
        EncodeMode mode = (EncodeMode) modeSpinner.getSelectedItem();
        BitrateOption bitrate = (BitrateOption) bitrateSpinner.getSelectedItem();
        if (preset == null) preset = ResolutionPreset.LOW;
        if (mode == null) mode = EncodeMode.FAST;
        if (bitrate == null) bitrate = BitrateOption.B1200;

        updateProgress(0);
        setBusy(true, "一括エンコードを開始します...");
        appendLog("件数: " + selectedUris.size());
        appendLog("変換プリセット: " + preset.label);
        appendLog("エンコードモード: " + mode.label);
        appendLog("ビットレート: " + bitrate.label);

        Intent serviceIntent = new Intent(this, EncodeForegroundService.class);
        serviceIntent.setAction(EncodeForegroundService.ACTION_START);
        serviceIntent.putParcelableArrayListExtra(EncodeForegroundService.EXTRA_URIS, new ArrayList<>(selectedUris));
        serviceIntent.putStringArrayListExtra(EncodeForegroundService.EXTRA_NAMES, new ArrayList<>(selectedNames));
        serviceIntent.putExtra(EncodeForegroundService.EXTRA_PRESET, preset.name());
        serviceIntent.putExtra(EncodeForegroundService.EXTRA_MODE, mode.name());
        serviceIntent.putExtra(EncodeForegroundService.EXTRA_BITRATE_KBPS, bitrate.kbps);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
        else startService(serviceIntent);
    }

    private void cancelEncode() {
        Intent intent = new Intent(this, EncodeForegroundService.class);
        intent.setAction(EncodeForegroundService.ACTION_CANCEL);
        startService(intent);
        appendLog("キャンセルを要求しました。");
    }

    private void requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFY);
        }
    }

    private void updateProgress(int progress) {
        int safe = Math.max(0, Math.min(100, progress));
        progressBar.setProgress(safe);
        progressText.setText(safe + "%");
    }

    private String getDisplayName(Uri uri) {
        if (uri == null) return "unknown_video";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (!TextUtils.isEmpty(name)) return name;
                }
            }
        } catch (Exception ignored) {}
        return "unknown_video";
    }

    private void setBusy(boolean busy, String status) {
        encodingInProgress = busy;
        pickButton.setEnabled(!busy);
        encodeButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        presetSpinner.setEnabled(!busy);
        modeSpinner.setEnabled(!busy);
        bitrateSpinner.setEnabled(!busy);
        statusText.setText(status);
    }

    private void appendLog(String message) {
        String current = logText.getText().toString();
        if ("ここに変換状況が表示されます。".equals(current)) current = "";
        logText.setText(current + (current.isEmpty() ? "" : "\n") + message);
    }

    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences("nk_encoder", MODE_PRIVATE);
        String history = prefs.getString("history", "");
        historyText.setText(TextUtils.isEmpty(history) ? "履歴はまだありません。" : history);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
