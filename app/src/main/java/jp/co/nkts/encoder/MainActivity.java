package jp.co.nkts.encoder;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_VIDEO = 1001;
    private static final int COPY_BUFFER_SIZE = 256 * 1024;

    private Uri selectedVideoUri;
    private String selectedVideoName = "";
    private volatile boolean encodingInProgress = false;
    private Transformer media3Transformer;

    private TextView fileText;
    private Spinner presetSpinner;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView logText;
    private Button pickButton;
    private Button encodeButton;

    private enum ResolutionPreset {
        HIGH("高解像度 1080p目安", 1920, 1080, 24, "veryfast", "160k"),
        MEDIUM("中解像度 720p目安", 1280, 720, 27, "veryfast", "128k"),
        LOW("低解像度 480p目安", 854, 480, 31, "ultrafast", "96k");

        final String label;
        final int width;
        final int height;
        final int crf;
        final String speedPreset;
        final String audioBitrate;

        ResolutionPreset(String label, int width, int height, int crf, String speedPreset, String audioBitrate) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.crf = crf;
            this.speedPreset = speedPreset;
            this.audioBitrate = audioBitrate;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
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

        TextView subtitle = new TextView(this);
        subtitle.setText("MP4はAndroid公式Media3でH.265変換します。\nAVIはFFmpeg互換ルートを使用します。v1.2.0");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF475569);
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
        ArrayAdapter<ResolutionPreset> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                ResolutionPreset.values()
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        root.addView(presetSpinner);

        encodeButton = makeButton("H.265 MP4に変換開始");
        LinearLayout.LayoutParams encodeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        encodeParams.setMargins(0, dp(24), 0, 0);
        encodeButton.setLayoutParams(encodeParams);
        encodeButton.setOnClickListener(v -> startEncode());
        root.addView(encodeButton);

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(18), 0, dp(6));
        root.addView(progressBar, progressParams);

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

        return scrollView;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            button.setBackgroundResource(getResources().getIdentifier("button_bg", "drawable", getPackageName()));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        button.setLayoutParams(params);
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
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "video/mp4",
                "video/avi",
                "video/x-msvideo",
                "video/*"
        });
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
                try {
                    getContentResolver().takePersistableUriPermission(selectedVideoUri, flags);
                } catch (Exception ignored) {
                    // Some providers do not support persistable permissions. Immediate encoding still works.
                }
            }
            fileText.setText("選択中: " + selectedVideoName);
            appendLog("動画を選択しました: " + selectedVideoName);
        }
    }

    private void startEncode() {
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

        setBusy(true, "入力ファイルを準備中...");
        appendLog("変換プリセット: " + preset.label);

        ResolutionPreset finalPreset = preset;
        Thread worker = new Thread(() -> prepareAndRunEncode(finalPreset), "nk-encoder-worker");
        worker.start();
    }

    private void prepareAndRunEncode(ResolutionPreset preset) {
        File inputFile = null;
        try {
            inputFile = copyUriToCache(selectedVideoUri, selectedVideoName);
            if (!inputFile.exists() || inputFile.length() == 0) {
                throw new IOException("入力ファイルのコピーに失敗しました。");
            }

            final File preparedInputFile = inputFile;
            final File outputFile = createOutputFile(preset);

            if (isMp4Like(preparedInputFile.getName())) {
                runOnUiThread(() -> {
                    statusText.setText("Media3で変換中... 画面を閉じずにお待ちください。");
                    appendLog("Media3開始 / FFmpegKitは使用しません");
                    appendLog("入力サイズ: " + formatBytes(preparedInputFile.length()));
                    appendLog("出力予定: " + outputFile.getName());
                    appendLog("出力コーデック: H.265 / HEVC, 音声: AAC");
                });
                runMedia3Encode(preparedInputFile, outputFile, preset);
            } else {
                runOnUiThread(() -> {
                    statusText.setText("FFmpeg互換ルートで変換中...");
                    appendLog("AVI/非MP4のためFFmpeg互換ルートを使用します");
                    appendLog("入力サイズ: " + formatBytes(preparedInputFile.length()));
                    appendLog("出力予定: " + outputFile.getName());
                });
                runFfmpegEncode(preparedInputFile, outputFile, preset);
            }
        } catch (Throwable e) {
            deleteQuietly(inputFile);
            runOnUiThread(() -> {
                setBusy(false, "エラー: " + safeMessage(e));
                appendLog("エラー: " + e);
            });
        }
    }

    private void runMedia3Encode(File inputFile, File outputFile, ResolutionPreset preset) {
        runOnUiThread(() -> {
            try {
                MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile));
                EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                        .setEffects(new Effects(
                                Collections.emptyList(),
                                Collections.singletonList(Presentation.createForHeight(preset.height))))
                        .build();

                media3Transformer = new Transformer.Builder(this)
                        .setVideoMimeType(MimeTypes.VIDEO_H265)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .build();

                media3Transformer.addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult exportResult) {
                        handleEncodeSuccess(inputFile, outputFile, "Media3変換成功");
                    }

                    @Override
                    public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                        deleteQuietly(inputFile);
                        runOnUiThread(() -> {
                            setBusy(false, "Media3変換に失敗しました: " + safeMessage(exportException));
                            appendLog("Media3 error: " + exportException);
                        });
                    }
                });

                media3Transformer.start(editedMediaItem, outputFile.getAbsolutePath());
            } catch (Throwable transformerStartError) {
                deleteQuietly(inputFile);
                setBusy(false, "Media3を開始できませんでした: " + safeMessage(transformerStartError));
                appendLog("Media3 start error: " + transformerStartError);
            }
        });
    }

    private void runFfmpegEncode(File inputFile, File outputFile, ResolutionPreset preset) {
        String[] args = buildFfmpegArguments(inputFile, outputFile, preset);
        try {
            FFmpegKit.executeWithArgumentsAsync(args, session -> {
                try {
                    if (ReturnCode.isSuccess(session.getReturnCode()) && outputFile.exists() && outputFile.length() > 0) {
                        handleEncodeSuccess(inputFile, outputFile, "FFmpeg互換ルート変換成功");
                    } else {
                        runOnUiThread(() -> {
                            setBusy(false, "変換に失敗しました。FFmpegKitがこの端末で起動できない可能性があります。");
                            appendLog("変換失敗: returnCode=" + session.getReturnCode());
                            if (!TextUtils.isEmpty(session.getFailStackTrace())) {
                                appendLog(session.getFailStackTrace());
                            }
                        });
                    }
                } catch (Throwable callbackError) {
                    runOnUiThread(() -> {
                        setBusy(false, "終了処理でエラー: " + safeMessage(callbackError));
                        appendLog("callbackError: " + callbackError);
                    });
                } finally {
                    deleteQuietly(inputFile);
                }
            });
        } catch (Throwable ffmpegStartError) {
            deleteQuietly(inputFile);
            runOnUiThread(() -> {
                setBusy(false, "FFmpegKitを開始できませんでした。MP4はMedia3で再試行してください。");
                appendLog("ffmpegStartError: " + ffmpegStartError);
            });
        }
    }

    private void handleEncodeSuccess(File inputFile, File outputFile, String label) {
        Uri savedUri = saveToMediaStore(outputFile);
        deleteQuietly(inputFile);
        runOnUiThread(() -> {
            setBusy(false, "完了: Movies / NK Encoder に保存しました。" +
                    (savedUri != null ? "\n保存URI: " + savedUri : ""));
            appendLog(label + ": " + outputFile.getAbsolutePath());
            appendLog("出力サイズ: " + formatBytes(outputFile.length()));
        });
    }

    private String[] buildFfmpegArguments(File inputFile, File outputFile, ResolutionPreset preset) {
        String scaleFilter = "scale=w='min(" + preset.width + ",iw)':h='min(" + preset.height + ",ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p";
        return new String[]{
                "-y",
                "-nostdin",
                "-hide_banner",
                "-i", inputFile.getAbsolutePath(),
                "-map", "0:v:0",
                "-map", "0:a?",
                "-dn",
                "-sn",
                "-vf", scaleFilter,
                "-c:v", "libx265",
                "-tag:v", "hvc1",
                "-preset", preset.speedPreset,
                "-crf", String.valueOf(preset.crf),
                "-threads", "1",
                "-x265-params", "log-level=error:pools=1:frame-threads=1:wpp=0",
                "-c:a", "aac",
                "-b:a", preset.audioBitrate,
                "-ac", "2",
                "-map_metadata", "-1",
                "-max_muxing_queue_size", "9999",
                "-movflags", "+faststart",
                outputFile.getAbsolutePath()
        };
    }

    private File copyUriToCache(Uri uri, String displayName) throws IOException {
        String extension = getSafeExtension(displayName);
        File inputFile = new File(getCacheDir(), "nk_encoder_input_" + System.currentTimeMillis() + extension);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(inputFile)) {
            if (in == null) throw new IOException("入力ファイルを開けませんでした。");
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return inputFile;
    }

    private File createOutputFile(ResolutionPreset preset) throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = getFilesDir();
        File encoderDir = new File(dir, "NK_Encoder");
        if (!encoderDir.exists() && !encoderDir.mkdirs()) {
            throw new IOException("出力フォルダを作成できませんでした。");
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(encoderDir, "encoded_" + preset.name().toLowerCase(Locale.US) + "_" + timestamp + ".mp4");
    }

    private Uri saveToMediaStore(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Uri.fromFile(file);
        }
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NK Encoder");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;

        try (InputStream in = new FileInputStream(file);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) return null;
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            appendLogOnUiThread("MediaStore保存エラー: " + e);
            return null;
        }
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
        } catch (Exception ignored) {
        }
        return "unknown_video";
    }

    private String getSafeExtension(String displayName) {
        if (displayName == null) return ".mp4";
        String lower = displayName.toLowerCase(Locale.US);
        if (lower.endsWith(".avi")) return ".avi";
        if (lower.endsWith(".mp4")) return ".mp4";
        if (lower.endsWith(".m4v")) return ".m4v";
        if (lower.endsWith(".mov")) return ".mov";
        if (lower.endsWith(".mkv")) return ".mkv";
        return ".mp4";
    }

    private boolean isMp4Like(String filename) {
        if (filename == null) return true;
        String lower = filename.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov");
    }

    private void setBusy(boolean busy, String status) {
        encodingInProgress = busy;
        pickButton.setEnabled(!busy);
        encodeButton.setEnabled(!busy);
        presetSpinner.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(status);
    }

    private void appendLog(String message) {
        String current = logText.getText().toString();
        if ("ここに変換状況が表示されます。".equals(current)) current = "";
        logText.setText(current + (current.isEmpty() ? "" : "\n") + message);
    }

    private void appendLogOnUiThread(String message) {
        runOnUiThread(() -> appendLog(message));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) return "unknown";
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message) ? throwable.getClass().getSimpleName() : message;
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
