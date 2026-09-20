package com.colo.tiksave;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 45;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private EditText linkInput;
    private String pendingManualUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(14,14,16));
        getWindow().setNavigationBarColor(Color.rgb(14,14,16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(14,14,16));

        TextView title = new TextView(this);
        title.setText("TikSave");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        root.addView(title, matchWrap(dp(8)));

        TextView subtitle = new TextView(this);
        subtitle.setText("Compartí un enlace desde TikTok: la descarga arranca sola en segundo plano y se muestra en una notificación.");
        subtitle.setTextColor(Color.rgb(185,185,192));
        subtitle.setTextSize(15);
        root.addView(subtitle, matchWrap(dp(24)));

        linkInput = new EditText(this);
        linkInput.setHint("https://www.tiktok.com/@usuario/video/...");
        linkInput.setHintTextColor(Color.rgb(120,120,128));
        linkInput.setTextColor(Color.WHITE);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setBackgroundColor(Color.rgb(30,30,34));
        root.addView(linkInput, matchWrap(dp(14)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button paste = new Button(this);
        paste.setText("Pegar");
        paste.setOnClickListener(v -> paste());
        actions.addView(paste, weightWrap(1f, dp(6)));

        Button download = new Button(this);
        download.setText("Descargar");
        download.setOnClickListener(v -> startManual(linkInput.getText().toString()));
        actions.addView(download, weightWrap(1f, 0));
        root.addView(actions, matchWrap(dp(20)));

        TextView note = new TextView(this);
        note.setText("Los enlaces compartidos no abren esta pantalla. TikSave prioriza el stream sin watermark y usa fallback silencioso si no está disponible.");
        note.setTextColor(Color.rgb(145,145,154));
        note.setTextSize(13);
        root.addView(note, matchWrap(0));
        setContentView(root);
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        String url = extractTikTokUrl(text == null ? null : text.toString());
        if (url == null) Toast.makeText(this, "No hay un enlace de TikTok válido", Toast.LENGTH_SHORT).show();
        else linkInput.setText(url);
    }

    private void startManual(String raw) {
        String url = extractTikTokUrl(raw);
        if (url == null) {
            Toast.makeText(this, "Pegá un enlace válido de TikTok", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingManualUrl = url;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            startServiceNow(url);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && pendingManualUrl != null) {
            String url = pendingManualUrl;
            pendingManualUrl = null;
            startServiceNow(url);
        }
    }

    private void startServiceNow(String url) {
        Intent service = new Intent(this, DownloadService.class).putExtra(DownloadService.EXTRA_URL, url);
        startForegroundService(service);
        Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show();
    }

    private static String extractTikTokUrl(String text) {
        if (text == null) return null;
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            Uri uri = Uri.parse(candidate);
            String host = uri.getHost();
            if (host == null) continue;
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) return candidate;
        }
        return null;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams matchWrap(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0,0,0,bottom); return p;
    }
    private LinearLayout.LayoutParams weightWrap(float weight, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(0,0,right,0); return p;
    }
}
