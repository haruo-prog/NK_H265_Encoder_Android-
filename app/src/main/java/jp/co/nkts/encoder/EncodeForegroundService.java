package jp.co.nkts.encoder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class EncodeForegroundService extends Service {
    public static final String ACTION_START = "jp.co.nkts.encoder.action.START";
    public static final String ACTION_CANCEL = "jp.co.nkts.encoder.action.CANCEL";
    public static final String ACTION_STATUS = "jp.co.nkts.encoder.action.STATUS";
    public static final String EXTRA_URIS = "extra_uris";
    public static final String EXTRA_NAMES = "extra_names";
    public static final String EXTRA_PRESET = "extra_preset";
    public static final String EXTRA_MODE = "extra_mode";
    public static final String EXTRA_BITRATE_KBPS = "extra_bitrate_kbps";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_SUCCESS = "success";

    private static final int NOTIFICATION_ID = 265;
    private static final String CHANNEL_ID = "nk_h265_encoder_channel";
    private static final int COPY_BUFFER_SIZE = 256 * 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean cancelRequested = false;
    private Transformer transformer;
    private Runnable progressRunnable;
    private ArrayList<Uri> queueUris = new ArrayList<>();
    private ArrayList<String> queueNames = new ArrayList<>();
    private int totalCount = 0;
    private int currentIndex = 0;
    private File activeInputFile;
    private File activeOutputFile;
    private long activeOriginalBytes = 0L;
    private long activeStartMs = 0L;

    public enum Preset {
        HIGH("高解像度 1080p目安", 1920, 1080, 24, "veryfast", "160k"),
        MEDIUM("中解像度 720p目安", 1280, 720, 27, "veryfast", "128k"),
        LOW("低解像度 480p目安", 854, 480, 31, "ultrafast", "96k");
        final String label;
        final int width;
        final int height;
        final int crf;
        final String speedPreset;
        final String audioBitrate;
        Preset(String label, int width, int height, int crf, String speedPreset, String audioBitrate) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.crf = crf;
            this.speedPreset = speedPreset;
            this.audioBitrate = audioBitrate;
        }
    }

    public enum EncodeMode {
        FAST("高速モード"), STANDARD("標準モード"), QUALITY("画質優先モード");
        final String label;
        EncodeMode(String label) { this.label = label; }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelCurrent();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) {
            broadcast("すでにエンコード中です。", currentOverallProgress(0), false, false);
            return START_STICKY;
        }

        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
        ArrayList<String> names = intent.getStringArrayListExtra(EXTRA_NAMES);
        if (uris == null || uris.isEmpty()) {
            broadcast("入力動画がありません。", 0, true, false);
            return START_NOT_STICKY;
        }

        queueUris = uris;
        queueNames = names == null ? new ArrayList<>() : names;
        totalCount = queueUris.size();
        currentIndex = 0;
        cancelRequested = false;
        running = true;

        Preset preset = parsePreset(intent.getStringExtra(EXTRA_PRESET));
        EncodeMode mode = parseMode(intent.getStringExtra(EXTRA_MODE));
        int bitrateKbps = intent.getIntExtra(EXTRA_BITRATE_KBPS, 0);

        startForeground(NOTIFICATION_ID, buildNotification("一括エンコード準備中", 0, false));
        broadcast("一括エンコード開始: " + totalCount + "件", 0, false, false);
        new Thread(() -> processNext(preset, mode, bitrateKbps), "nk-batch-encoder").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopProgressPolling();
        try { if (transformer != null) transformer.cancel(); } catch (Throwable ignored) {}
        deleteQuietly(activeInputFile);
        super.onDestroy();
    }

    private void processNext(Preset preset, EncodeMode mode, int bitrateKbps) {
        if (cancelRequested) {
            finishCanceled();
            return;
        }
        if (currentIndex >= totalCount) {
            finishAllSuccess();
            return;
        }

        Uri uri = queueUris.get(currentIndex);
        String name = currentIndex < queueNames.size() ? queueNames.get(currentIndex) : "unknown_video";
        activeInputFile = null;
        activeOutputFile = null;
        activeOriginalBytes = 0L;
        activeStartMs = System.currentTimeMillis();

        try {
            broadcast("[" + (currentIndex + 1) + "/" + totalCount + "] 準備中: " + name, currentOverallProgress(1), false, false);
            activeInputFile = copyUriToCache(uri, name);
            if (!activeInputFile.exists() || activeInputFile.length() == 0) throw new IOException("入力ファイルのコピーに失敗しました。");
            activeOriginalBytes = activeInputFile.length();
            activeOutputFile = createOutputFile(preset, mode, bitrateKbps);
            broadcast("元サイズ: " + formatBytes(activeOriginalBytes), currentOverallProgress(3), false, false);
            broadcast("指定ビットレート: " + bitrateLabel(bitrateKbps), currentOverallProgress(4), false, false);

            if (isMp4Like(activeInputFile.getName())) {
                String encoder = findHevcEncoderName();
                broadcast("HEVCエンコーダ候補: " + encoder, currentOverallProgress(5), false, false);
                mainHandler.post(() -> runMedia3Encode(activeInputFile, activeOutputFile, preset, mode, bitrateKbps, name));
            } else {
                broadcast("FFmpeg互換ルートで変換します。", currentOverallProgress(5), false, false);
                runFfmpegEncode(activeInputFile, activeOutputFile, preset, mode, bitrateKbps, name);
            }
        } catch (Throwable e) {
            handleItemError(name, e, preset, mode, bitrateKbps);
        }
    }

    private void runMedia3Encode(File inputFile, File outputFile, Preset preset, EncodeMode mode, int bitrateKbps, String displayName) {
        try {
            int targetHeight = effectiveHeight(preset, mode);
            broadcast("実行解像度: 高さ " + targetHeight + "px", currentOverallProgress(6), false, false);
            broadcast("Media3 / MediaCodecで変換中", currentOverallProgress(7), false, false);
            if (bitrateKbps > 0) {
                broadcast("ビットレート指定はFFmpeg互換ルートで厳密適用。Media3では端末エンコーダ制御優先。", currentOverallProgress(8), false, false);
            }

            MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile));
            EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                    .setEffects(new Effects(Collections.emptyList(), Collections.singletonList(Presentation.createForHeight(targetHeight))))
                    .build();

            transformer = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build();

            transformer.addListener(new Transformer.Listener() {
                @Override public void onCompleted(Composition composition, ExportResult exportResult) {
                    handleItemSuccess(displayName, inputFile, outputFile, preset, mode, bitrateKbps);
                }
                @Override public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                    handleItemError(displayName, exportException, preset, mode, bitrateKbps);
                }
            });

            transformer.start(editedMediaItem, outputFile.getAbsolutePath());
            startProgressPolling();
        } catch (Throwable e) {
            handleItemError(displayName, e, preset, mode, bitrateKbps);
        }
    }

    private void startProgressPolling() {
        stopProgressPolling();
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (!running || transformer == null || cancelRequested) return;
                try {
                    ProgressHolder holder = new ProgressHolder();
                    int state = transformer.getProgress(holder);
                    int itemProgress = state == Transformer.PROGRESS_STATE_AVAILABLE ? Math.max(5, Math.min(99, holder.progress)) : 10;
                    int overall = currentOverallProgress(itemProgress);
                    broadcast("[" + (currentIndex + 1) + "/" + totalCount + "] エンコード中: " + itemProgress + "%", overall, false, false);
                    updateNotification("変換中 " + overall + "%", overall, false);
                } catch (Throwable ignored) {
                    int overall = currentOverallProgress(10);
                    broadcast("エンコード中...", overall, false, false);
                }
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(progressRunnable);
    }

    private void stopProgressPolling() {
        if (progressRunnable != null) {
            mainHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void runFfmpegEncode(File inputFile, File outputFile, Preset preset, EncodeMode mode, int bitrateKbps, String displayName) {
        String[] args = buildFfmpegArguments(inputFile, outputFile, preset, mode, bitrateKbps);
        try {
            FFmpegKit.executeWithArgumentsAsync(args, session -> {
                if (cancelRequested) {
                    finishCanceled();
                    return;
                }
                if (ReturnCode.isSuccess(session.getReturnCode()) && outputFile.exists() && outputFile.length() > 0) {
                    handleItemSuccess(displayName, inputFile, outputFile, preset, mode, bitrateKbps);
                } else {
                    String message = "FFmpeg互換ルートの変換に失敗しました。";
                    if (!TextUtils.isEmpty(session.getFailStackTrace())) message += " " + session.getFailStackTrace();
                    handleItemError(displayName, new RuntimeException(message), preset, mode, bitrateKbps);
                }
            });
        } catch (Throwable e) {
            handleItemError(displayName, e, preset, mode, bitrateKbps);
        }
    }

    private void handleItemSuccess(String displayName, File inputFile, File outputFile, Preset preset, EncodeMode mode, int bitrateKbps) {
        stopProgressPolling();
        saveToMediaStore(outputFile);
        long outputBytes = outputFile.exists() ? outputFile.length() : 0L;
        long elapsed = Math.max(1L, System.currentTimeMillis() - activeStartMs);
        double reduction = activeOriginalBytes > 0 ? (1.0 - ((double) outputBytes / (double) activeOriginalBytes)) * 100.0 : 0.0;
        String result = "完了: " + displayName + " / 元 " + formatBytes(activeOriginalBytes) + " → 変換後 " + formatBytes(outputBytes) + " / 削減率 " + String.format(Locale.US, "%.1f", reduction) + "% / " + (elapsed / 1000) + "秒";
        saveHistory(result);
        broadcast(result, currentOverallProgress(100), false, true);
        deleteQuietly(inputFile);
        currentIndex++;
        processNext(preset, mode, bitrateKbps);
    }

    private void handleItemError(String displayName, Throwable e, Preset preset, EncodeMode mode, int bitrateKbps) {
        stopProgressPolling();
        String logPath = writeErrorLog(displayName, e);
        String message = "失敗: " + displayName + " / エラーログ保存: " + logPath;
        saveHistory(message);
        broadcast(message, currentOverallProgress(100), false, false);
        deleteQuietly(activeInputFile);
        currentIndex++;
        processNext(preset, mode, bitrateKbps);
    }

    private void cancelCurrent() {
        cancelRequested = true;
        broadcast("キャンセル処理中...", currentOverallProgress(0), false, false);
        stopProgressPolling();
        try { if (transformer != null) transformer.cancel(); } catch (Throwable ignored) {}
        try { FFmpegKit.cancel(); } catch (Throwable ignored) {}
        finishCanceled();
    }

    private void finishCanceled() {
        if (!running && cancelRequested) return;
        running = false;
        cancelRequested = true;
        deleteQuietly(activeInputFile);
        saveHistory("キャンセル: " + nowText());
        broadcast("キャンセルしました。", currentOverallProgress(0), true, false);
        updateNotification("キャンセルしました", currentOverallProgress(0), true);
        stopForeground(false);
        stopSelf();
    }

    private void finishAllSuccess() {
        running = false;
        broadcast("一括変換が完了しました。", 100, true, true);
        updateNotification("一括変換完了", 100, true);
        stopForeground(false);
        stopSelf();
    }

    private int currentOverallProgress(int itemProgress) {
        if (totalCount <= 0) return 0;
        return Math.max(0, Math.min(100, (int) (((currentIndex * 100.0) + itemProgress) / totalCount)));
    }

    private int effectiveHeight(Preset preset, EncodeMode mode) {
        if (mode == EncodeMode.FAST) {
            if (preset == Preset.HIGH) return 720;
            if (preset == Preset.MEDIUM) return 540;
            return 480;
        }
        return preset.height;
    }

    private String findHevcEncoderName() {
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            String fallback = "未検出";
            for (MediaCodecInfo info : infos) {
                if (!info.isEncoder()) continue;
                boolean supportsHevc = false;
                for (String type : info.getSupportedTypes()) {
                    if (MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(type)) supportsHevc = true;
                }
                if (!supportsHevc) continue;
                String name = info.getName();
                if (isLikelyHardware(info, name)) return name + " / hardware";
                fallback = name + " / software候補";
            }
            return fallback;
        } catch (Throwable e) {
            return "検出失敗: " + safeMessage(e);
        }
    }

    private boolean isLikelyHardware(MediaCodecInfo info, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try { return info.isHardwareAccelerated(); } catch (Throwable ignored) {}
        }
        String lower = name.toLowerCase(Locale.US);
        return !(lower.startsWith("omx.google") || lower.startsWith("c2.android") || lower.contains("software"));
    }

    private String[] buildFfmpegArguments(File inputFile, File outputFile, Preset preset, EncodeMode mode, int bitrateKbps) {
        ArrayList<String> args = new ArrayList<>();
        String scaleFilter = "scale=w='min(" + preset.width + ",iw)':h='min(" + effectiveHeight(preset, mode) + ",ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p";
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int threads = mode == EncodeMode.QUALITY ? Math.min(4, cores) : Math.min(8, cores);
        int crf = preset.crf;
        String speedPreset = preset.speedPreset;
        if (mode == EncodeMode.FAST) { crf += 2; speedPreset = "ultrafast"; }
        if (mode == EncodeMode.QUALITY) { crf -= 2; speedPreset = "slow"; }
        Collections.addAll(args, "-y", "-nostdin", "-hide_banner", "-i", inputFile.getAbsolutePath(), "-map", "0:v:0", "-map", "0:a?", "-dn", "-sn", "-vf", scaleFilter, "-c:v", "libx265", "-tag:v", "hvc1", "-preset", speedPreset);
        if (bitrateKbps > 0) Collections.addAll(args, "-b:v", bitrateKbps + "k", "-maxrate", bitrateKbps + "k", "-bufsize", (bitrateKbps * 2) + "k");
        else Collections.addAll(args, "-crf", String.valueOf(crf));
        Collections.addAll(args, "-threads", String.valueOf(threads), "-x265-params", "log-level=error:pools=" + threads + ":frame-threads=1", "-c:a", "aac", "-b:a", preset.audioBitrate, "-ac", "2", "-map_metadata", "-1", "-max_muxing_queue_size", "9999", "-movflags", "+faststart", outputFile.getAbsolutePath());
        return args.toArray(new String[0]);
    }

    private File copyUriToCache(Uri uri, String displayName) throws IOException {
        String extension = getSafeExtension(displayName);
        File inputFile = new File(getCacheDir(), "nk_encoder_input_" + System.currentTimeMillis() + extension);
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(inputFile)) {
            if (in == null) throw new IOException("入力ファイルを開けませんでした。");
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        return inputFile;
    }

    private File createOutputFile(Preset preset, EncodeMode mode, int bitrateKbps) throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = getFilesDir();
        File encoderDir = new File(dir, "NK_Encoder");
        if (!encoderDir.exists() && !encoderDir.mkdirs()) throw new IOException("出力フォルダを作成できませんでした。");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String br = bitrateKbps > 0 ? bitrateKbps + "k" : "auto";
        return new File(encoderDir, "encoded_" + preset.name().toLowerCase(Locale.US) + "_" + mode.name().toLowerCase(Locale.US) + "_" + br + "_" + timestamp + ".mp4");
    }

    private Uri saveToMediaStore(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Uri.fromFile(file);
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NK Encoder");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (InputStream in = new FileInputStream(file); OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) return null;
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return null;
        }
    }

    private String writeErrorLog(String displayName, Throwable e) {
        try {
            File dir = new File(getExternalFilesDir(null), "error_logs");
            if (!dir.exists()) dir.mkdirs();
            File log = new File(dir, "error_" + System.currentTimeMillis() + ".txt");
            StringWriter sw = new StringWriter();
            if (e != null) e.printStackTrace(new PrintWriter(sw));
            try (FileOutputStream out = new FileOutputStream(log)) {
                String text = "file=" + displayName + "\ntime=" + nowText() + "\n" + sw;
                out.write(text.getBytes());
            }
            return log.getAbsolutePath();
        } catch (Exception ex) {
            return "保存失敗: " + safeMessage(ex);
        }
    }

    private void saveHistory(String line) {
        SharedPreferences prefs = getSharedPreferences("nk_encoder", MODE_PRIVATE);
        String old = prefs.getString("history", "");
        String next = nowText() + "\n" + line + "\n\n" + old;
        if (next.length() > 6000) next = next.substring(0, 6000);
        prefs.edit().putString("history", next).apply();
    }

    private Notification buildNotification(String text, int progress, boolean done) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setContentTitle("NK H.265 Encoder").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(pendingIntent).setOngoing(!done).setOnlyAlertOnce(true);
        if (done) builder.setProgress(0, 0, false);
        else builder.setProgress(100, Math.max(0, Math.min(100, progress)), false);
        return builder.build();
    }

    private void updateNotification(String text, int progress, boolean done) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text, progress, done));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "NK H.265 Encoder", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("エンコード進行状況");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void broadcast(String message, int progress, boolean done, boolean success) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, Math.max(0, Math.min(100, progress)));
        intent.putExtra(EXTRA_DONE, done);
        intent.putExtra(EXTRA_SUCCESS, success);
        sendBroadcast(intent);
    }

    private Preset parsePreset(String name) { try { return Preset.valueOf(name); } catch (Exception ignored) { return Preset.LOW; } }
    private EncodeMode parseMode(String name) { try { return EncodeMode.valueOf(name); } catch (Exception ignored) { return EncodeMode.FAST; } }

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

    private String bitrateLabel(int kbps) { return kbps > 0 ? kbps + " kbps" : "自動"; }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private String nowText() { return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(new Date()); }
    private String safeMessage(Throwable throwable) { if (throwable == null) return "unknown"; String message = throwable.getMessage(); return TextUtils.isEmpty(message) ? throwable.getClass().getSimpleName() : message; }
    private void deleteQuietly(File file) { if (file != null && file.exists()) { //noinspection ResultOfMethodCallIgnored
            file.delete(); } }
}
