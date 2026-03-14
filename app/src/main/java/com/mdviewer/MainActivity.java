package com.mdviewer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // minSdk is 33 (Tiramisu), so we only need to check for granular media permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            }, 1);
        }

        webView = findViewById(R.id.webview);
        WebSettings webSettings = webView.getSettings();
        // JavaScript is required for markdown-it rendering
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setBlockNetworkLoads(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);

        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), webView);
            if (scrollY > oldScrollY && scrollY > 0) {
                // Scrolling down: go full screen
                WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
                if (getSupportActionBar() != null) getSupportActionBar().hide();
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
                windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else if (scrollY < oldScrollY) {
                // Scrolling up: show bars
                WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
                if (getSupportActionBar() != null) getSupportActionBar().show();
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null && "file".equals(url.getScheme())) {
                    String path = url.getPath();
                    if (path != null && !path.startsWith("/android_asset/")) {
                        try {
                            String mimeType = getMimeType(path);
                            java.io.File file = new java.io.File(path);
                            if (file.exists()) {
                                java.io.FileInputStream stream = new java.io.FileInputStream(file);
                                return new WebResourceResponse(mimeType, "UTF-8", stream);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error intercepting request for: " + path, e);
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        handleIntent(getIntent());
    }

    private String getMimeType(String path) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".png")) return "image/png";
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) return "image/jpeg";
        if (lowerPath.endsWith(".gif")) return "image/gif";
        if (lowerPath.endsWith(".svg")) return "image/svg+xml";
        if (lowerPath.endsWith(".webp")) return "image/webp";
        if (lowerPath.endsWith(".bmp")) return "image/bmp";
        if (lowerPath.endsWith(".mp4")) return "video/mp4";
        if (lowerPath.endsWith(".webm")) return "video/webm";
        if (lowerPath.endsWith(".ogg")) return "video/ogg";
        if (lowerPath.endsWith(".mp3")) return "audio/mpeg";
        if (lowerPath.endsWith(".wav")) return "audio/x-wav";
        if (lowerPath.endsWith(".flac")) return "audio/flac";
        if (lowerPath.endsWith(".css")) return "text/css";
        if (lowerPath.endsWith(".js")) return "application/javascript";
        return "*/*";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        Uri data = intent.getData();

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            String markdown = readTextFromUri(data);
            String baseUrl = "file:///android_asset/";
            
            if ("file".equals(data.getScheme())) {
                String path = data.getPath();
                if (path != null) {
                    baseUrl = "file://" + path.substring(0, path.lastIndexOf('/') + 1);
                }
            } else if ("content".equals(data.getScheme())) {
                String realPath = getRealPathFromURI(data);
                if (realPath != null) {
                    baseUrl = "file://" + realPath.substring(0, realPath.lastIndexOf('/') + 1);
                }
            }
            
            renderMarkdown(markdown, baseUrl);
        } else {
            renderMarkdown("# Welcome\n\nOpen a markdown file to view it. (Offline Mode)", "file:///android_asset/");
        }
    }

    private String readTextFromUri(Uri uri) {
        StringBuilder stringBuilder = new StringBuilder();
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            if (inputStream == null) return "Error: Could not open stream";
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading text from URI: " + uri, e);
            return "Error reading file: " + e.getMessage();
        }
        return stringBuilder.toString();
    }

    private String getRealPathFromURI(Uri contentUri) {
        String[] proj = { android.provider.MediaStore.MediaColumns.DATA };
        try (android.database.Cursor cursor = getContentResolver().query(contentUri, proj, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column_index = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATA);
                return cursor.getString(column_index);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting real path from URI: " + contentUri, e);
        }
        return null;
    }

    private void renderMarkdown(String markdown, String baseUrl) {
        String htmlTemplate;
        try (InputStream is = getAssets().open("template.html");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            htmlTemplate = sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error loading template.html", e);
            htmlTemplate = "<html><body><h1>Error loading template.html</h1></body></html>";
        }

        String base64Markdown = Base64.encodeToString(markdown.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        htmlTemplate = htmlTemplate.replace("{{MARKDOWN_BASE64}}", base64Markdown);

        webView.loadDataWithBaseURL(baseUrl, htmlTemplate, "text/html", "utf-8", null);
    }
}
