package com.pvtdate.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String TAG = "PvtDate";
    private static final String WEATHER_HOST = "wttr.in";
    private static final String API_HOST = "api.github.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 (KHTML, like Gecko) pvtdate/1.1.1";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int MAX_PROXY_BYTES = 4 * 1024 * 1024;
    private static final int REQ_WRITE_STORAGE = 1001;

    private WebView webView;
    private Runnable pendingStorageAction;

    @SuppressWarnings("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            try {
                File dir = getDir("webview", Context.MODE_PRIVATE);
                s.setDatabasePath(dir.getAbsolutePath());
            } catch (Exception ignore) {
            }
        }

        webView.setBackgroundColor(0xFF041F16);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url == null ? null : Uri.parse(url));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return proxyIntercept(request.getUrl() == null ? null : request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return proxyIntercept(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                toast(message);
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                if (url == null) return;
                if (url.startsWith("file:")) return;
                startDownload(url, guessName(url, contentDisposition));
            }
        });

        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.addJavascriptInterface(new PvtBridge(), "PvtBridge");
        setContentView(webView);
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase();
        if (scheme.equals("http") || scheme.equals("https")) {
            if (isProxiedHost(uri.getHost())) return false;
            openExternal(uri);
            return true;
        }
        if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms")) {
            openExternal(uri);
            return true;
        }
        return false;
    }

    private boolean isProxiedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals(WEATHER_HOST) || host.endsWith("." + WEATHER_HOST) || host.equals(API_HOST);
    }

    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("No app available to open this link");
        } catch (Exception e) {
            toast("Couldn't open link");
        }
    }

    private WebResourceResponse proxyIntercept(String url) {
        if (url == null) return null;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null || !scheme.equalsIgnoreCase("https")) return null;
            if (!isProxiedHost(uri.getHost())) return null;
        } catch (Exception e) {
            return null;
        }
        return proxyFetch(url);
    }

    private WebResourceResponse proxyFetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            String mime = conn.getContentType();
            if (mime == null) mime = "application/json";
            int semi = mime.indexOf(';');
            if (semi >= 0) mime = mime.substring(0, semi).trim();
            if (mime.isEmpty()) mime = "application/json";

            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_PROXY_BYTES) break;
            }
            byte[] body = out.toByteArray();

            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");

            if (code >= 400 || body.length == 0) {
                return new WebResourceResponse("application/json", "UTF-8", code >= 400 ? code : 404,
                        "OK", headers, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            }
            return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers,
                    new ByteArrayInputStream(body));
        } catch (Exception e) {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("application/json", "UTF-8", 503, "Unavailable", headers,
                    new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void startDownload(String url, String fileName) {
        String safe = sanitize(fileName);
        if (safe == null || safe.isEmpty()) safe = "pvtdate-download";
        try {
            if (url.startsWith("data:")) {
                toast("This download type isn't supported here");
                return;
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(safe);
            req.setDescription("Downloading…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            req.addRequestHeader("User-Agent", USER_AGENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "PvtDate/" + safe);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PvtDate");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("no dir");
                req.setDestinationUri(Uri.fromFile(new File(dir, safe)));
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IOException("no download manager");
            dm.enqueue(req);
            toast("Downloading to Downloads/PvtDate");
        } catch (Exception e) {
            toast("Couldn't start download");
        }
    }

    private void writeBackup(final String json, final String fileName) {
        new Thread(() -> {
            boolean ok = false;
            try {
                String safe = sanitize(fileName);
                if (safe == null || safe.isEmpty()) safe = "pvtdate-backup.json";
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "PvtDate");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("no dir");
                File f = new File(dir, safe);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.close();
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                if (success) toast("Backup saved to Downloads/PvtDate");
                else toast("Couldn't save backup");
            });
        }).start();
    }

    private static String guessName(String url, String contentDisposition) {
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split("filename=");
            if (parts.length > 1) {
                String name = parts[1].replace("\"", "").trim();
                int sc = name.indexOf(';');
                if (sc >= 0) name = name.substring(0, sc);
                if (!name.isEmpty()) return name;
            }
        }
        String path = Uri.parse(url).getLastPathSegment();
        return path == null ? "download" : path;
    }

    private static String sanitize(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 96; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ' ') sb.append(c);
        }
        return sb.toString().trim();
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){try{var s=document.getElementById('statusSheet');" +
                    "if(s&&s.classList.contains('show')){var b=document.getElementById('sheetBackdrop');" +
                    "if(b)b.click();return 'closed-sheet';}" +
                    "var m=document.getElementById('menuOverlay');" +
                    "if(m&&m.classList.contains('show')){m.classList.remove('show');return 'closed-menu';}" +
                    "var a=document.querySelector('.screen.active');" +
                    "if(a&&a.id!=='screen-home'){var n=document.querySelector('.nav-item[data-screen=\"home\"]');" +
                    "if(n)n.click();return 'home';}return 'exit';}catch(e){return 'exit';}})(",
                    value -> {
                        if (value == null || value.contains("exit")) {
                            finish();
                        }
                    });
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("PvtBridge");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /**
     * Runs {@code action} immediately on API 29+ (scoped storage) or when
     * WRITE_EXTERNAL_STORAGE is already granted; otherwise asks for it first.
     */
    private void withStoragePermission(Runnable action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        pendingStorageAction = action;
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQ_WRITE_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE) {
            Runnable action = pendingStorageAction;
            pendingStorageAction = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (action != null) action.run();
            } else {
                toast("Storage permission is needed to save files");
            }
        }
    }

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "1.1.1" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.1.1";
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private class PvtBridge {

        @JavascriptInterface
        public String version() {
            return appVersionName();
        }

        @JavascriptInterface
        public void toast(String msg) {
            if (msg != null) toast_(msg);
        }

        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, String.valueOf(msg));
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null) return;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                runOnUiThread(() -> openExternal(uri));
            }
        }

        @JavascriptInterface
        public void downloadApk(String url, String fileName) {
            if (url == null) return;
            final String name = (fileName == null || fileName.isEmpty()) ? "pvtdate.apk" : fileName;
            runOnUiThread(() -> withStoragePermission(() -> startDownload(url, name)));
        }

        @JavascriptInterface
        public void saveBackup(String json, String fileName) {
            if (json == null) return;
            runOnUiThread(() -> withStoragePermission(() -> writeBackup(json, fileName)));
        }

        @JavascriptInterface
        public void shareText(String text, String subject) {
            if (text == null) return;
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_SUBJECT, subject == null ? "pvtdate backup" : subject);
                    intent.putExtra(Intent.EXTRA_TEXT, text);
                    Intent chooser = Intent.createChooser(intent, "Share backup");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Exception e) {
                    toast("Couldn't share");
                }
            });
        }
    }

    private void toast_(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
