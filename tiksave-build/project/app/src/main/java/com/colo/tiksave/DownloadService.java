package com.colo.tiksave;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class DownloadService extends Service {
    public static final String EXTRA_URL = "url";
    private static final String TAG = "TikSaveService";
    private static final String CHANNEL_ACTIVE = "tiksave_downloads";
    private static final String CHANNEL_DONE = "tiksave_completed";
    private static final int NOTIFICATION_ID = 2001;

    private static final String CLEAN_FORMAT =
            "best[format_id!^=download][vcodec=h264]/best[format_id!^=download][vcodec!^=bytevc2]";
    private static final String FALLBACK_FORMAT =
            "best[vcodec!^=bytevc2]/best";

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private NotificationManager notificationManager;
    private volatile int lastProgress = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        if (url == null || url.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!busy.compareAndSet(false, true)) {
            updateActiveNotification(0, "Ya hay una descarga en curso", true);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildActiveNotification(0, "Preparando descarga…", true));
        new Thread(() -> runDownload(url, startId), "TikSaveForegroundDownload").start();
        return START_NOT_STICKY;
    }

    private void runDownload(String url, int startId) {
        try {
            updateActiveNotification(0, "Preparando motor local…", true);
            YoutubeDL.getInstance().init(getApplicationContext());
            try {
                YoutubeDL.getInstance().updateYoutubeDL(
                        getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
            } catch (Exception updateError) {
                Log.w(TAG, "yt-dlp update skipped", updateError);
            }

            DownloadResult result = downloadWithSilentFallback(url);
            updateActiveNotification(100, "Guardando en Descargas/TikSave…", true);
            Uri saved = publishToDownloads(result.file);

            getSharedPreferences("tiksave", MODE_PRIVATE).edit()
                    .putString("last_download_source", result.clean ? "NO_WATERMARK" : "FALLBACK_WATERMARK")
                    .apply();
            result.file.delete();

            Notification done = buildCompletedNotification(saved);
            stopForeground(STOP_FOREGROUND_REMOVE);
            notificationManager.notify(NOTIFICATION_ID, done);
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            stopForeground(STOP_FOREGROUND_REMOVE);
            notificationManager.notify(NOTIFICATION_ID, buildErrorNotification(cleanError(e)));
        } finally {
            busy.set(false);
            stopSelf(startId);
        }
    }

    private DownloadResult downloadWithSilentFallback(String url) throws Exception {
        File tempDir = getTempDirectory();
        cleanDirectory(tempDir);
        try {
            return new DownloadResult(executeDownload(url, tempDir, CLEAN_FORMAT), true);
        } catch (Exception cleanError) {
            Log.i(TAG, "Clean stream unavailable; silent fallback", cleanError);
            cleanDirectory(tempDir);
            return new DownloadResult(executeDownload(url, tempDir, FALLBACK_FORMAT), false);
        }
    }

    private File executeDownload(String url, File tempDir, String format) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-part");
        request.addOption("--restrict-filenames");
        request.addOption("-f", format);
        request.addOption("-o", new File(tempDir, "TikSave_%(id)s.%(ext)s").getAbsolutePath());

        Function3<Float, Long, String, Unit> callback = new Function3<Float, Long, String, Unit>() {
            @Override
            public Unit invoke(Float progress, Long etaInSeconds, String line) {
                int p = Math.max(0, Math.min(100, Math.round(progress == null ? 0f : progress)));
                long eta = etaInSeconds == null ? 0L : etaInSeconds;
                if (p != lastProgress) {
                    lastProgress = p;
                    String text = eta > 0
                            ? String.format(Locale.ROOT, "Descargando… %d%% · %d s", p, eta)
                            : String.format(Locale.ROOT, "Descargando… %d%%", p);
                    updateActiveNotification(p, text, false);
                }
                return Unit.INSTANCE;
            }
        };

        YoutubeDL.getInstance().execute(request, "TikSaveDownload", callback);
        File newest = newestMediaFile(tempDir);
        if (newest == null || newest.length() <= 0) {
            throw new Exception("yt-dlp terminó sin generar un video");
        }
        return newest;
    }

    private File getTempDirectory() throws Exception {
        File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = getCacheDir();
        File dir = new File(base, "TikSave-temp");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("No se pudo crear el directorio temporal");
        return dir;
    }

    private Uri publishToDownloads(File source) throws Exception {
        String ext = extension(source.getName());
        String mime = "mp4".equalsIgnoreCase(ext) ? "video/mp4" : "video/*";

        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/TikSave");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Android no pudo crear el archivo final");

        boolean success = false;
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new Exception("No se pudo abrir el archivo final");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            success = true;
        } finally {
            if (!success) resolver.delete(uri, null, null);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    private void createChannels() {
        NotificationChannel active = new NotificationChannel(
                CHANNEL_ACTIVE, "Descargas", NotificationManager.IMPORTANCE_LOW);
        active.setDescription("Progreso de descargas de TikSave");
        active.setSound(null, null);

        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE, "Descargas completadas", NotificationManager.IMPORTANCE_DEFAULT);
        done.setDescription("Avisos cuando TikSave termina una descarga");

        notificationManager.createNotificationChannel(active);
        notificationManager.createNotificationChannel(done);
    }

    private Notification buildActiveNotification(int progress, String text, boolean indeterminate) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ACTIVE)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("TikSave")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setColor(Color.WHITE)
                .setProgress(100, progress, indeterminate)
                .build();
    }

    private void updateActiveNotification(int progress, String text, boolean indeterminate) {
        notificationManager.notify(NOTIFICATION_ID, buildActiveNotification(progress, text, indeterminate));
    }

    private Notification buildCompletedNotification(Uri saved) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(saved, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 11, view, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("TikSave")
                .setContentText("Descarga completada · Descargas/TikSave")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setColor(Color.WHITE)
                .build();
    }

    private Notification buildErrorNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("TikSave")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
    }

    private static void cleanDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) cleanDirectory(file);
            file.delete();
        }
    }

    private static File newestMediaFile(File dir) {
        File[] files = dir.listFiles(file -> file.isFile()
                && !file.getName().endsWith(".part")
                && !file.getName().endsWith(".ytdl"));
        if (files == null || files.length == 0) return null;
        File newest = files[0];
        for (File file : files) if (file.lastModified() > newest.lastModified()) newest = file;
        return newest;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "mp4";
    }

    private String cleanError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "No se pudo descargar el video.";
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("private") || lower.contains("login")) return "El video no está disponible públicamente.";
        if (lower.contains("unsupported url")) return "TikTok no reconoció ese enlace.";
        if (lower.contains("network") || lower.contains("http") || lower.contains("timed out")) return "No se pudo conectar con TikTok.";
        return "No se pudo descargar el video.";
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class DownloadResult {
        final File file;
        final boolean clean;
        DownloadResult(File file, boolean clean) {
            this.file = file;
            this.clean = clean;
        }
    }
}
