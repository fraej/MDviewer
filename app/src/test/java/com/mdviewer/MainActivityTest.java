package com.mdviewer;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.navigation.NavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWebView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "notnight")
public class MainActivityTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private WebView webView;
    private File directory;

    @Before
    public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("app_prefs", 0).edit().clear().commit();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO);
        directory = new File(RuntimeEnvironment.getApplication().getCacheDir(), "linked notes");
        assertTrue(directory.mkdirs() || directory.isDirectory());
        File initial = new File(directory, "start.md");
        Files.write(initial.toPath(), "Start[^1]\n\n[^1]: Footnote".getBytes(StandardCharsets.UTF_8));
        Intent intent = new Intent(Intent.ACTION_VIEW, fileUri(initial));
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup();
        activity = controller.get();
        webView = activity.findViewById(R.id.webview);
    }

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    @Test
    public void systemLightSystemRestoresFollowingSystemWithoutRecreation() {
        NavigationView navigation = activity.findViewById(R.id.navigation_view);
        SharedPreferences preferences = activity.getSharedPreferences("app_prefs", 0);
        assertTrue(navigation.getMenu().performIdentifierAction(R.id.nav_theme_light, 0));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, preferences.getInt("theme_mode", -99));
        assertTrue(navigation.getMenu().performIdentifierAction(R.id.nav_theme_system, 0));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, preferences.getInt("theme_mode", -99));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode());
    }

    @Test
    public void localDocumentLinkUsesMarkdownTemplateAndDocumentBaseUrl() throws Exception {
        File linked = new File(directory, "next note.md");
        Files.write(linked.toPath(), "# Linked".getBytes(StandardCharsets.UTF_8));
        Uri uri = fileUri(linked);
        assertTrue(navigate(uri, true));
        ShadowWebView.LoadDataWithBaseURL load = shadowOf(webView).getLastLoadDataWithBaseURL();
        assertEquals(uri.toString(), load.baseUrl);
        assertTrue(load.data.contains("RAW_MARKDOWN_BASE64 = 'IyBMaW5rZWQK'"));
        assertTrue(load.data.contains("purify.min.js"));
        assertTrue(activity.getSharedPreferences("app_prefs", 0).getString("recent_files", "")
                .contains("next%20note.md"));
    }

    @Test
    public void sameDocumentFootnoteDoesNotReloadMarkdown() {
        ShadowWebView.LoadDataWithBaseURL load = shadowOf(webView).getLastLoadDataWithBaseURL();
        Uri anchor = Uri.parse(load.baseUrl).buildUpon().fragment("fn1").build();
        assertFalse(navigate(anchor, true));
        assertSame(load, shadowOf(webView).getLastLoadDataWithBaseURL());
    }

    @Test
    public void missingAndUnsupportedDocumentsDoNotReplaceCurrentDocument() throws Exception {
        ShadowWebView.LoadDataWithBaseURL load = shadowOf(webView).getLastLoadDataWithBaseURL();
        assertTrue(navigate(fileUri(new File(directory, "missing.md")), true));
        assertSame(load, shadowOf(webView).getLastLoadDataWithBaseURL());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Open file"));
        File html = new File(directory, "unsafe.html");
        Files.write(html.toPath(), "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        assertTrue(navigate(fileUri(html), true));
        assertSame(load, shadowOf(webView).getLastLoadDataWithBaseURL());
    }

    @Test
    public void subframesAndExecutableSchemesCannotNavigateViewer() {
        ShadowWebView.LoadDataWithBaseURL load = shadowOf(webView).getLastLoadDataWithBaseURL();
        assertTrue(navigate(Uri.parse("https://example.com"), false));
        assertTrue(navigate(Uri.parse("javascript:alert(1)"), true));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertSame(load, shadowOf(webView).getLastLoadDataWithBaseURL());
    }

    private static Uri fileUri(File file) {
        // java.io.File uses the host OS path format under Robolectric (including Windows).
        return Uri.parse(file.toURI().toString());
    }

    private boolean navigate(Uri uri, boolean mainFrame) {
        return webView.getWebViewClient().shouldOverrideUrlLoading(webView, new WebResourceRequest() {
            public Uri getUrl() { return uri; }
            public boolean isForMainFrame() { return mainFrame; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return true; }
            public String getMethod() { return "GET"; }
            public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        });
    }
}
