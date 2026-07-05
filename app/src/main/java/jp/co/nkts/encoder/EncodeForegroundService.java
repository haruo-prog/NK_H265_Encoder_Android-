package jp.co.nkts.encoder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
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
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class EncodeForegroundService extends Service {
    public static final String ACTION_START = "jp.co.nkts.encoder.action.START";
    public static final String ACTION_STATUS = "jp.co.nkts.encoder.action.STATUS";
    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_PRESET = "extra_preset";
    public static final String EXTRA_MODE = "extra_mode";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_SUCCESS = "success";

    private static final int NOTIFICATION_ID = 265;
    private static final String CHANNEL_ID = "nk_h265_encoder_channel";
    private static final int COPY_BUFFER_SIZE = 256 * 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private Transformer transformer;
    private File activeInputFile;
    private File activeOutputFile;
    private Runnable progressRunnable;

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

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) {
            broadcast("すでにエンコード中です。", 0, false, false);
            return START_STICKY;
        }
        Uri uri = intent.getParcelableExtra(EXTRA_URI);
        String name = intent.getStringExtra(EXTRA_NAME);
        Preset preset = parsePreset(intent.getStringExtra(EXTRA_PRESET));
        EncodeMode mode = parseMode(intent.getStringExtra(EXTRA_MODE));
        running = true;
        startForeground(NOTIFICATION_ID, buildNotification("エンコード準備中", 0, false));
        broadcast("バックグラウンドエンコードを開始しました。", 0, false, false);
        new Thread(() -> runEncode(uri, name, preset, mode), "nk-encoder-service-worker").start();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopProgressPolling();
        try { if (transformer != null) transformer.cancel(); } catch (Throwable ignored) {}
        deleteQuietly(activeInputFile);
        super.onDestroy();
    }

    private void runEncode(Uri uri, String displayName, Preset preset, EncodeMode mode) {
        try {
            if (uri == null) throw new IOException("入力URIがありません。");
            activeInputFile = copyUriToCache(uri, displayName);
            if (!activeInputFile.exists() || activeInputFile.length() == 0) throw new IOException("入力ファイルのコピーに失敗しました。");
            activeOutputFile = createOutputFile(preset, mode);
            broadcast("入力サイズ: " + formatBytes(activeInputFile.length()), 3, false, false);
            broadcast("モード: " + mode.label, 4, false, false);
            broadcast("CPUコア数: " + Runtime.getRuntime().availableProcessors(), 4, false, false);
            updateNotification("入力ファイル準備完了", 3, false);
            if (isMp4Like(activeInputFile.getName())) {
                String encoder = findHevcEncoderName();
                broadcast("HEVCエンコーダ候補: " + encoder, 5, false, false);
                broadcast("Media3 / MediaCodec で高速変換します。", 5, false, false);
                mainHandler.post(() -> runMedia3Encode(activeInputFile, activeOutputFile, preset, mode, encoder));
            } else {
                broadcast("AVI/非MP4のためFFmpeg互換ルートを使用します。", 5, false, false);
                runFfmpegEncode(activeInputFile, activeOutputFile, preset, mode);
            }
        } catch (Throwable e) {
            finishWithError("エラー: " + safeMessage(e), e);
        }
    }

    private void runMedia3Encode(File inputFile, File outputFile, Preset preset, EncodeMode mode, String encoderName) {
        try {
            int targetHeight = effectiveHeight(preset, mode);
            broadcast("実行解像度: 高さ " + targetHeight + "px", 6, false, false);
            if (encoderName.toLowerCase(Locale.US).contains("software") || encoderName.toLowerCase(Locale.US).contains("android")) {
                broadcast("注意: ソフトウェア系エンコーダの可能性があります。", 6, false, false);
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
                    finishWithSuccess(inputFile, outputFile, "Media3変換成功 / " + mode.label);
                }
                @Override public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                    finishWithError("Media3変換に失敗しました: " + safeMessage(exportException), exportException);
                }
            });
            transformer.start(editedMediaItem, outputFile.getAbsolutePath());
            startProgressPolling();
        } catch (Throwable e) {
            finishWithError("Media3を開始できませんでした: " + safeMessage(e), e);
        }
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
                    if (MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(type)) {
                        supportsHevc = true;
                        break;
                    }
                }
                if (!supportsHevc) continue;
                String name = info.getName();
                boolean hardware = isLikelyHardware(info, name);
                if (hardware) return name + " / hardware";
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

    private void startProgressPolling() {
        stopProgressPolling();
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (!running || transformer == null) return;
                try {
                    ProgressHolder holder = new ProgressHolder();
                    int state = transformer.getProgress(holder);
                    int progress = holder.progress;
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        progress = Math.max(5, Math.min(99, progress));
                        broadcast("エンコード中: " + progress + "%", progress, false, false);
                        updateNotification("エンコード中 " + progress + "%", progress, false);
                    } else {
                        broadcast("エンコード中...", 10, false, false);
                        updateNotification("エンコード中", 10, false);
                    }
                } catch (Throwable ignored) {
                    broadcast("エンコード中...", 10, false, false);
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

    private void runFfmpegEncode(File inputFile, File outputFile, Preset preset, EncodeMode mode) {
        String[] args = buildFfmpegArguments(inputFile, outputFile, preset, mode);
        try {
            FFmpegKit.executeWithArgumentsAsync(args, session -> {
                if (ReturnCode.isSuccess(session.getReturnCode()) && outputFile.exists() && outputFile.length() > 0) {
                    finishWithSuccess(inputFile, outputFile, "FFmpeg互換ルート変換成功 / " + mode.label);
                } else {
                    String message = "FFmpeg互換ルートの変換に失敗しました。";
                    if (!TextUtils.isEmpty(session.getFailStackTrace())) message += " " + session.getFailStackTrace();
                    finishWithError(message, null);
                }
            });
        } catch (Throwable e) {
            finishWithError("FFmpegKitを開始できませんでした: " + safeMessage(e), e);
        }
    }

    private void finishWithSuccess(File inputFile, File outputFile, String label) {
        stopProgressPolling();
        saveToMediaStore(outputFile);
        deleteQuietly(inputFile);
        running = false;
        broadcast(label + " / 保存完了: Movies / NK Encoder", 100, true, true);
        updateNotification("エンコード完了", 100, true);
        stopForeground(false);
        stopSelf();
    }

    private void finishWithError(String message, Throwable throwable) {
        stopProgressPolling();
        deleteQuietly(activeInputFile);
        running = false;
        String finalMessage = message;
        if (throwable != null) finalMessage += " / " + throwable.getClass().getSimpleName();
        broadcast(finalMessage, 0, true, false);
        updateNotification("エンコード失敗", 0, true);
        stopForeground(false);
        stopSelf();
    }

    private Notification buildNotification(String text, int progress, boolean done) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setContentTitle("NK H.265 Encoder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(!done)
                .setOnlyAlertOnce(true);
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

    private Preset parsePreset(String name) {
        try { return Preset.valueOf(name); } catch (Exception ignored) { return Preset.LOW; }
    }

    private EncodeMode parseMode(String name) {
        try { return EncodeMode.valueOf(name); } catch (Exception ignored) { return EncodeMode.FAST; }
    }

    private String[] buildFfmpegArguments(File inputFile, File outputFile, Preset preset, EncodeMode mode) {
        String scaleFilter = "scale=w='min(" + preset.width + ",iw)':h='min(" + effectiveHeight(preset, mode) + ",ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p";
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int threads = mode == EncodeMode.QUALITY ? Math.min(4, cores) : Math.min(8, cores);
        int crf = preset.crf;
        String speedPreset = preset.speedPreset;
        if (mode == EncodeMode.FAST) { crf += 2; speedPreset = "ultrafast"; }
        if (mode == EncodeMode.QUALITY) { crf -= 2; speedPreset = "slow"; }
        return new String[]{
                "-y", "-nostdin", "-hide_banner",
                "-i", inputFile.getAbsolutePath(),
                "-map", "0:v:0", "-map", "0:a?", "-dn", "-sn",
                "-vf", scaleFilter,
                "-c:v", "libx265", "-tag:v", "hvc1",
                "-preset", speedPreset,
                "-crf", String.valueOf(crf),
                "-threads", String.valueOf(threads),
                "-x265-params", "log-level=error:pools=" + threads + ":frame-threads=1",
                "-c:a", "aac", "-b:a", preset.audioBitrate, "-ac", "2",
                "-map_metadata", "-1", "-max_muxing_queue_size", "9999",
                "-movflags", "+faststart",
                outputFile.getAbsolutePath()
        };
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

    private File createOutputFile(Preset preset, EncodeMode mode) throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = getFilesDir();
        File encoderDir = new File(dir, "NK_Encoder");
        if (!encoderDir.exists() && !encoderDir.mkdirs()) throw new IOException("出力フォルダを作成できませんでした。");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(encoderDir, "encoded_" + preset.name().toLowerCase(Locale.US) + "_" + mode.name().toLowerCase(Locale.US) + "_" + timestamp + ".mp4");
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
}
