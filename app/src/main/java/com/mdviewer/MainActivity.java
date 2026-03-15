package com.mdviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private WebView webView;

    private final ActivityResultLauncher<String[]> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    processMarkdownUri(uri);
                }
            }
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Hide the system bars for a clean start
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
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
                // Scrolling down: hide system bars
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
                windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else if (scrollY < oldScrollY) {
                // Scrolling up: show system bars
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url != null && ("http".equals(url.getScheme()) || "https".equals(url.getScheme()))) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, url);
                    startActivity(intent);
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, request);
            }

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
        String type = intent.getType();

        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                processMarkdownUri(data);
            }
        } else if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type) || "text/markdown".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    saveAndOpenSharedMarkdown(sharedText);
                }
            }
        } else if (Intent.ACTION_MAIN.equals(action)) {
            filePickerLauncher.launch(new String[]{"text/markdown", "text/plain", "application/octet-stream"});
        } else {
            renderMarkdown("", "file:///android_asset/");
        }
    }

    private void processMarkdownUri(Uri uri) {
        String markdown = readTextFromUri(uri);
        String baseUrl = "file:///android_asset/";
        
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                baseUrl = "file://" + path.substring(0, path.lastIndexOf('/') + 1);
            }
        } else if ("content".equals(uri.getScheme())) {
            String realPath = getRealPathFromURI(uri);
            if (realPath != null) {
                baseUrl = "file://" + realPath.substring(0, realPath.lastIndexOf('/') + 1);
            }
        }
        
        renderMarkdown(markdown, baseUrl);
    }

    private void saveAndOpenSharedMarkdown(String text) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "shared_markdown_" + timeStamp + ".md";

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(text.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Saved to Downloads: " + fileName, Toast.LENGTH_SHORT).show();
                    processMarkdownUri(uri);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving shared markdown", e);
                Toast.makeText(this, "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
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
