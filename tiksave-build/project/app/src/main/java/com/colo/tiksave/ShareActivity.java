package com.colo.tiksave;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShareActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 44;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private String pendingUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingUrl = extractUrl(getIntent());
        if (pendingUrl == null) {
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            startAndFinish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) startAndFinish();
    }

    private void startAndFinish() {
        Intent service = new Intent(this, DownloadService.class).putExtra(DownloadService.EXTRA_URL, pendingUrl);
        startForegroundService(service);
        finish();
        overridePendingTransition(0, 0);
    }

    private static String extractUrl(Intent intent) {
        if (intent == null) return null;
        String raw = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) raw = intent.getStringExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_VIEW.equals(intent.getAction())) raw = intent.getDataString();
        return extractTikTokUrl(raw);
    }

    private static String extractTikTokUrl(String text) {
        if (text == null) return null;
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            while (!candidate.isEmpty() && ".,)]};".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            Uri uri = Uri.parse(candidate);
            String host = uri.getHost();
            if (host == null) continue;
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) return candidate;
        }
        return null;
    }
}
