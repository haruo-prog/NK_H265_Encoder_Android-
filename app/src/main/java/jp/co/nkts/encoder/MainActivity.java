package jp.co.nkts.encoder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 1001;

    private Uri selectedVideoUri;
    private String selectedVideoName = "";
    private boolean encodingInProgress = false;

    private TextView fileText;
    private Spinner presetSpinner;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView statusText;
    private TextView logText;
    private Button pickButton;
    private Button encodeButton;

    private enum ResolutionPreset {
        HIGH("高解像度 1080p目安"),
        MEDIUM("中解像度 720p目安"),
        LOW("低解像度 480p目安");

        final String label;
        ResolutionPreset(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
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
                setBusy(false, success ? "完了" : "失敗");
                updateProgress(success ? 100 : progress);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(EncodeForegroundService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    private View createContentView() {
        int padding = dp(20);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF8FAFC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("NK H.265 Encoder");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF111827);
        root.addView(title);

        TextView subtitle = makeInfoText("MP4はAndroid公式Media3でH.265変換します。\nバックグラウンド変換・進行バー対応 v1.3.0");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        pickButton = makeButton("動画を選択");
        pickButton.setOnClickListener(v -> pickVideo());
        root.addView(pickButton);

        fileText = makeInfoText("未選択");
        fileText.setPadding(0, dp(12), 0, dp(20));
        root.addView(fileText);

        TextView presetLabel = makeSectionLabel("解像度");
        root.addView(presetLabel);

        presetSpinner = new Spinner(this);
        ArrayAdapter<ResolutionPreset> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ResolutionPreset.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        presetSpinner.setSelection(ResolutionPreset.LOW.ordinal());
        root.addView(presetSpinner);

        encodeButton = makeButton("H.265 MP4に変換開始");
        LinearLayout.LayoutParams encodeParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        encodeParams.setMargins(0, dp(24), 0, 0);
        encodeButton.setLayoutParams(encodeParams);
        encodeButton.setOnClickListener(v -> startEncodeService());
        root.addView(encodeButton);

        TextView progressLabel = makeSectionLabel("エンコード進行状況");
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

        logText = makeInfoText("ここに変換状況が表示されます。");
        logText.setTextSize(13);
        logText.setBackgroundColor(0xFFE2E8F0);
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(logText);

        TextView credit = makeInfoText("© 株式会社NKテクニカルサポート");
        credit.setGravity(Gravity.CENTER_HORIZONTAL);
        credit.setTextColor(0xFF64748B);
        credit.setPadding(0, dp(26), 0, dp(10));
        root.addView(credit);

        return scrollView;
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

    private void pickVideo() {
        if (encodingInProgress) {
            Toast.makeText(this, "変換中は動画を変更できません。", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/mp4", "video/avi", "video/x-msvideo", "video/*"});
        startActivityForResult(intent, REQUEST_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            selectedVideoUri = data.getData();
            selectedVideoName = getDisplayName(selectedVideoUri);
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (selectedVideoUri != null && flags != 0) {
                try { getContentResolver().takePersistableUriPermission(selectedVideoUri, flags); } catch (Exception ignored) {}
            }
            fileText.setText("選択中: " + selectedVideoName);
            appendLog("動画を選択しました: " + selectedVideoName);
        }
    }

    private void startEncodeService() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "先に動画を選択してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (encodingInProgress) {
            Toast.makeText(this, "現在変換中です。", Toast.LENGTH_SHORT).show();
            return;
        }

        ResolutionPreset preset = (ResolutionPreset) presetSpinner.getSelectedItem();
        if (preset == null) preset = ResolutionPreset.LOW;

        updateProgress(0);
        setBusy(true, "バックグラウンドエンコードを開始します...");
        appendLog("変換プリセット: " + preset.label);
        appendLog("バックグラウンドサービスを起動します。");

        Intent serviceIntent = new Intent(this, EncodeForegroundService.class);
        serviceIntent.setAction(EncodeForegroundService.ACTION_START);
        serviceIntent.putExtra(EncodeForegroundService.EXTRA_URI, selectedVideoUri);
        serviceIntent.putExtra(EncodeForegroundService.EXTRA_NAME, selectedVideoName);
        serviceIntent.putExtra(EncodeForegroundService.EXTRA_PRESET, preset.name());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
        else startService(serviceIntent);
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
        presetSpinner.setEnabled(!busy);
        statusText.setText(status);
    }

    private void appendLog(String message) {
        String current = logText.getText().toString();
        if ("ここに変換状況が表示されます。".equals(current)) current = "";
        logText.setText(current + (current.isEmpty() ? "" : "\n") + message);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
