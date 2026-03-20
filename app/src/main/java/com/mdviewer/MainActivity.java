package com.mdviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREF_RECENT_FILES = "recent_files";
    private static final int MAX_RECENT_FILES = 5;

    private WebView webView;
    private DrawerLayout drawerLayout;
    private SharedPreferences sharedPreferences;
    private NavigationView navigationView;
    private Uri currentUri;

    private final ActivityResultLauncher<String[]> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    // Request persistent permission so we can open it again from Recents
                    try {
                        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not take persistable permission", e);
                    }
                    processFileUri(uri);
                    addRecentFile(uri);
                }
            }
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE);
        int themeMode = sharedPreferences.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(themeMode);

        super.onCreate(savedInstanceState);
        
        // Hide the system bars for a clean start
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        navigationView = findViewById(R.id.navigation_view);

        topAppBar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        // Exclude the middle of the left edge from the system's "back" gesture 
        // so the drawer can be pulled out by swiping from there.
        drawerLayout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top;
            int density = (int) getResources().getDisplayMetrics().density;
            
            // Android limits gesture exclusion to 200dp per edge.
            // We will exclude the middle 200dp of the left edge.
            int exclusionHeightPx = 200 * density;
            int startY = (height - exclusionHeightPx) / 2;
            int widthPx = 60 * density; // 60dp width

            List<Rect> exclusionRects = new ArrayList<>();
            exclusionRects.add(new Rect(0, startY, widthPx, startY + exclusionHeightPx));
            drawerLayout.setSystemGestureExclusionRects(exclusionRects);
        });

        if (themeMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            navigationView.setCheckedItem(R.id.nav_theme_system);
        } else if (themeMode == AppCompatDelegate.MODE_NIGHT_NO) {
            navigationView.setCheckedItem(R.id.nav_theme_light);
        } else if (themeMode == AppCompatDelegate.MODE_NIGHT_YES) {
            navigationView.setCheckedItem(R.id.nav_theme_dark);
        }

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_open_file) {
                filePickerLauncher.launch(new String[]{"text/markdown", "text/plain", "application/octet-stream", "image/svg+xml"});
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }

            // Handle theme switching
            int newMode = themeMode;
            if (id == R.id.nav_theme_light) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (id == R.id.nav_theme_dark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else if (id == R.id.nav_theme_system) {
                newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }

            if (id == R.id.nav_theme_light || id == R.id.nav_theme_dark || id == R.id.nav_theme_system) {
                if (themeMode != newMode) {
                    sharedPreferences.edit().putInt("theme_mode", newMode).apply();
                    AppCompatDelegate.setDefaultNightMode(newMode);
                }
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }

            // Handle recent file clicks (they don't have static IDs)
            if (item.getIntent() != null && item.getIntent().getData() != null) {
                processFileUri(item.getIntent().getData());
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }

            return false;
        });

        updateRecentFilesMenu();

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

        // Set initial background color to prevent white flash
        boolean isDark = (themeMode == AppCompatDelegate.MODE_NIGHT_YES) || 
                        (themeMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM && 
                         (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        webView.setBackgroundColor(isDark ? Color.BLACK : Color.WHITE);

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

        if (savedInstanceState != null) {
            currentUri = savedInstanceState.getParcelable("current_uri", Uri.class);
        }

        if (currentUri != null) {
            processFileUri(currentUri);
        } else {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentUri != null) {
            outState.putParcelable("current_uri", currentUri);
        }
    }

    private void addRecentFile(Uri uri) {
        String uriString = uri.toString();
        List<String> recentFiles = getRecentFiles();
        
        // Remove if already exists (to move it to top)
        recentFiles.remove(uriString);
        
        // Add to top
        recentFiles.add(0, uriString);
        
        // Limit size
        if (recentFiles.size() > MAX_RECENT_FILES) {
            recentFiles = recentFiles.subList(0, MAX_RECENT_FILES);
        }
        
        saveRecentFiles(recentFiles);
        updateRecentFilesMenu();
    }

    private List<String> getRecentFiles() {
        String json = sharedPreferences.getString(PREF_RECENT_FILES, "[]");
        List<String> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                list.add(array.getString(i));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing recent files", e);
        }
        return list;
    }

    private void saveRecentFiles(List<String> list) {
        JSONArray array = new JSONArray(list);
        sharedPreferences.edit().putString(PREF_RECENT_FILES, array.toString()).apply();
    }

    private void updateRecentFilesMenu() {
        Menu menu = navigationView.getMenu();
        MenuItem recentHeaderItem = menu.findItem(R.id.nav_recent_header);
        if (recentHeaderItem == null) return;
        
        Menu subMenu = recentHeaderItem.getSubMenu();
        if (subMenu == null) return;
        
        subMenu.clear();
        List<String> recentFiles = getRecentFiles();
        
        if (recentFiles.isEmpty()) {
            subMenu.add(R.string.no_recent_files).setEnabled(false);
        } else {
            for (String uriString : recentFiles) {
                Uri uri = Uri.parse(uriString);
                String name = getFileName(uri);
                MenuItem item = subMenu.add(name);
                Intent intent = new Intent();
                intent.setData(uri);
                item.setIntent(intent);
                item.setIcon(R.drawable.ic_menu); // Reuse icon for now
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) result = cursor.getString(idx);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file name", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result;
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
                processFileUri(data);
                addRecentFile(data);
            }
        } else if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type) || "text/markdown".equals(type)) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    saveAndOpenSharedMarkdown(sharedText);
                }
            }
        } else if (Intent.ACTION_MAIN.equals(action)) {
            drawerLayout.openDrawer(GravityCompat.START);
        } else {
            renderMarkdown("", "file:///android_asset/");
        }
    }

    private void processFileUri(Uri uri) {
        currentUri = uri;
        String mimeType = getContentResolver().getType(uri);
        boolean isSvg = false;

        if ("image/svg+xml".equals(mimeType)) {
            isSvg = true;
        } else {
            String path = uri.getPath();
            if (path != null && path.toLowerCase().endsWith(".svg")) {
                isSvg = true;
            } else {
                String realPath = getRealPathFromURI(uri);
                if (realPath != null && realPath.toLowerCase().endsWith(".svg")) {
                    isSvg = true;
                }
            }
        }

        if (isSvg) {
            renderSvg(uri);
        } else {
            processMarkdownUri(uri);
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
                    processFileUri(uri);
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

    private String loadAssetTemplate(String fileName) {
        try (InputStream is = getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error loading " + fileName, e);
            return "<html><body><h1>Error loading " + fileName + "</h1></body></html>";
        }
    }

    private void renderMarkdown(String markdown, String baseUrl) {
        String htmlTemplate = loadAssetTemplate("template.html");
        String base64Markdown = Base64.encodeToString(markdown.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        htmlTemplate = htmlTemplate.replace("{{MARKDOWN_BASE64}}", base64Markdown);
        webView.loadDataWithBaseURL(baseUrl, htmlTemplate, "text/html", "utf-8", null);
    }

    private void renderSvg(Uri uri) {
        String svgContent = readTextFromUri(uri);
        String base64Svg = Base64.encodeToString(svgContent.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        
        String htmlTemplate = loadAssetTemplate("svg_template.html");
        htmlTemplate = htmlTemplate.replace("{{SVG_BASE64}}", base64Svg);

        webView.loadDataWithBaseURL(null, htmlTemplate, "text/html", "utf-8", null);
    }
}