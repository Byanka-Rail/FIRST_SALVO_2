package com.byankarail.firstsalvo2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BUILTIN_VERSION = "3.43.1";
    private static final String SHELL_VERSION = "1.1.0";
    private static final String META_URL = "https://raw.githubusercontent.com/Byanka-Rail/FIRST_SALVO_2/main/latest.json";
    private static final String FALLBACK_DOWNLOAD = "https://github.com/Byanka-Rail/FIRST_SALVO_2/releases/latest/download/FIRST_SALVO_2.html";
    private static final String BASE_URL = "https://firstsalvo.local/";
    private static final String ACTIVE_FILE = "game_active.html";
    private static final String BACKUP_FILE = "game_backup.html";
    private static final long MAX_GAME_BYTES = 8L * 1024L * 1024L;

    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int FILE_CHOOSER_REQUEST = 9104;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("fs2_shell", MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(2, 5, 10));
        setContentView(webView);
        configureWebView();
        enterImmersive();
        loadCurrentGame();

        main.postDelayed(() -> checkForUpdate(false), 1800);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        webView.addJavascriptInterface(new UpdaterBridge(), "AndroidUpdater");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectUpdateButton();
                enterImmersive();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
            }
        });
    }

    private void enterImmersive() {
        webView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private String currentVersion() {
        return prefs.getString("active_version", BUILTIN_VERSION);
    }

    private void loadCurrentGame() {
        io.execute(() -> {
            try {
                String html;
                File active = new File(getFilesDir(), ACTIVE_FILE);
                if (active.isFile()) html = readFile(active);
                else html = readAsset("FIRST_SALVO_2_BUILTIN.html");
                final String page = html;
                main.post(() -> webView.loadDataWithBaseURL(BASE_URL, page, "text/html", "UTF-8", BASE_URL));
            } catch (Exception e) {
                main.post(() -> showFatal("게임 HTML을 열 수 없습니다.\n" + e.getMessage()));
            }
        });
    }

    private void injectUpdateButton() {
        String js = "(function(){" +
            "if(window.__FS2_NATIVE_MENU__)return;window.__FS2_NATIVE_MENU__=1;" +
            "var b=document.createElement('button');b.id='fs2NativeUpdate';" +
            "b.textContent='UPDATE';" +
            "b.style.cssText='position:fixed;left:12px;bottom:20px;z-index:2147483647;padding:7px 10px;border:1px solid rgba(125,255,176,.42);border-radius:2px;background:rgba(2,10,7,.88);color:rgba(190,255,215,.88);font:10px ui-monospace,monospace;letter-spacing:1px;';" +
            "b.onclick=function(){try{AndroidUpdater.openMenu()}catch(e){}};document.body.appendChild(b);" +
            "setInterval(function(){b.style.display=(document.body.classList.contains('title')?'block':'none')},500);" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    public class UpdaterBridge {
        @JavascriptInterface
        public void openMenu() {
            runOnUiThread(MainActivity.this::showUpdateMenu);
        }
    }

    private void showUpdateMenu() {
        String msg = "게임 콘텐츠  v" + currentVersion() + "\nAndroid 셸  v" + SHELL_VERSION +
            "\n\nGitHub의 최신 FIRST_SALVO_2.html을 확인합니다.\n게임 세이브(localStorage)는 업데이트와 분리되어 유지됩니다.";
        new AlertDialog.Builder(this)
            .setTitle("FIRST SALVO 2 · 업데이트")
            .setMessage(msg)
            .setPositiveButton("업데이트 확인", (d, w) -> checkForUpdate(true))
            .setNeutralButton("이전 정상본", (d, w) -> rollback())
            .setNegativeButton("닫기", null)
            .show();
    }

    private void checkForUpdate(boolean userInitiated) {
        io.execute(() -> {
            try {
                String metaUrl = META_URL + (META_URL.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis();
                String raw = readUrl(metaUrl, 512 * 1024);
                JSONObject meta = new JSONObject(raw);
                String version = meta.optString("version", "").trim();
                String download = meta.optString("download", FALLBACK_DOWNLOAD).trim();
                if (download.isEmpty()) download = FALLBACK_DOWNLOAD;
                String notes = meta.optString("notes", "").trim();
                String sha256 = meta.optString("sha256", "").trim();
                if (version.isEmpty()) throw new Exception("latest.json에 version이 없습니다.");

                if (compareVersions(version, currentVersion()) > 0) {
                    final String v = version, dl = download, n = notes, sha = sha256;
                    main.post(() -> showUpdateFound(v, dl, n, sha));
                } else if (userInitiated) {
                    main.post(() -> toast("현재 v" + currentVersion() + " · 최신 버전입니다."));
                }
            } catch (Exception e) {
                if (userInitiated) main.post(() -> toastLong("업데이트 확인 실패: " + e.getMessage()));
            }
        });
    }

    private void showUpdateFound(String version, String download, String notes, String sha256) {
        String text = "현재 v" + currentVersion() + " → 최신 v" + version;
        if (!notes.isEmpty()) text += "\n\n" + notes;
        new AlertDialog.Builder(this)
            .setTitle("새 게임 업데이트")
            .setMessage(text)
            .setPositiveButton("업데이트", (d, w) -> downloadAndInstall(version, download, sha256))
            .setNegativeButton("나중에", null)
            .show();
    }

    private void downloadAndInstall(String version, String download, String sha256) {
        toast("업데이트 다운로드 중…");
        io.execute(() -> {
            try {
                String downloadUrl = download + (download.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis();
                byte[] bytes = readUrlBytes(downloadUrl, MAX_GAME_BYTES);
                String html = new String(bytes, StandardCharsets.UTF_8);
                validateGameHtml(html);
                if (!sha256.isEmpty()) {
                    String actual = sha256(bytes);
                    if (!actual.equalsIgnoreCase(sha256.replace("sha256:", "").trim())) {
                        throw new Exception("SHA-256 검증 실패");
                    }
                }

                File active = new File(getFilesDir(), ACTIVE_FILE);
                File backup = new File(getFilesDir(), BACKUP_FILE);
                if (active.isFile()) {
                    copyFile(active, backup);
                    prefs.edit().putString("backup_version", currentVersion()).apply();
                } else {
                    if (backup.exists()) backup.delete();
                    prefs.edit().putString("backup_version", BUILTIN_VERSION).apply();
                }

                File tmp = new File(getFilesDir(), ACTIVE_FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                    out.flush();
                    out.getFD().sync();
                }
                if (active.exists() && !active.delete()) throw new Exception("기존 게임 파일 교체 실패");
                if (!tmp.renameTo(active)) throw new Exception("새 게임 파일 적용 실패");
                prefs.edit().putString("active_version", version).apply();

                main.post(() -> {
                    toast("v" + version + " 업데이트 완료");
                    loadCurrentGame();
                });
            } catch (Exception e) {
                main.post(() -> toastLong("업데이트 실패: " + e.getMessage()));
            }
        });
    }

    private void rollback() {
        io.execute(() -> {
            try {
                File active = new File(getFilesDir(), ACTIVE_FILE);
                File backup = new File(getFilesDir(), BACKUP_FILE);
                String backupVersion = prefs.getString("backup_version", BUILTIN_VERSION);
                if (backup.isFile()) {
                    copyFile(backup, active);
                    if (!backup.delete()) backup.deleteOnExit();
                    prefs.edit().putString("active_version", backupVersion).remove("backup_version").apply();
                    main.post(() -> { toast("v" + backupVersion + "로 복구했습니다."); loadCurrentGame(); });
                } else if (active.isFile()) {
                    if (!active.delete()) throw new Exception("다운로드 버전 삭제 실패");
                    prefs.edit().putString("active_version", BUILTIN_VERSION).remove("backup_version").apply();
                    main.post(() -> { toast("내장 v" + BUILTIN_VERSION + "로 복구했습니다."); loadCurrentGame(); });
                } else {
                    main.post(() -> toast("이미 내장 기준본 v" + BUILTIN_VERSION + "입니다."));
                }
            } catch (Exception e) {
                main.post(() -> toastLong("복구 실패: " + e.getMessage()));
            }
        });
    }

    private void validateGameHtml(String html) throws Exception {
        if (html.length() < 100_000) throw new Exception("다운로드 파일이 너무 작습니다.");
        String u = html.toUpperCase(Locale.ROOT);
        if (!u.contains("FIRST SALVO 2") || !u.contains("<SCRIPT")) {
            throw new Exception("FIRST SALVO 2 게임 HTML이 아닙니다.");
        }
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.replaceFirst("^[vV]", "").split("[^0-9]+");
        String[] bb = b.replaceFirst("^[vV]", "").split("[^0-9]+");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = i < aa.length && !aa[i].isEmpty() ? Integer.parseInt(aa[i]) : 0;
            int y = i < bb.length && !bb[i].isEmpty() ? Integer.parseInt(bb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            return new String(readAll(in, MAX_GAME_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in, MAX_GAME_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static String readUrl(String url, long max) throws Exception {
        return new String(readUrlBytes(url, max), StandardCharsets.UTF_8);
    }

    private static byte[] readUrlBytes(String urlString, long max) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setConnectTimeout(12000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", "FIRST-SALVO-2-Android/1.1");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        c.setRequestProperty("Pragma", "no-cache");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            return readAll(in, max);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, long max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) throw new Exception("파일 크기 제한 초과");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            out.flush();
            out.getFD().sync();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format(Locale.ROOT, "%02x", b));
        return s.toString();
    }

    private void showFatal(String msg) {
        new AlertDialog.Builder(this).setTitle("FIRST SALVO 2").setMessage(msg).setPositiveButton("종료", (d, w) -> finish()).show();
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    private void toastLong(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        io.shutdownNow();
        super.onDestroy();
    }
}
