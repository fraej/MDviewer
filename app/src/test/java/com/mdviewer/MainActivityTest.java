package com.mdviewer;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONObject;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    @Test
    public void comicBookArchiveIsServedPageByPageToTheViewer() throws Exception {
        // The extension says RAR, but the file is detected as ZIP from its signature.
        File book = new File(directory, "issue 1.cbr");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(book))) {
            for (String name : new String[]{"p10.avif", "p2.png", "ComicInfo.xml"}) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        assertTrue(navigate(fileUri(book), true));
        String base = assertViewerLoaded("book");

        JSONArray pages = new JSONObject(read(intercept(base + "pages.json"))).getJSONArray("pages");
        assertEquals(2, pages.length());
        assertEquals("p2.png", pages.getJSONObject(0).getString("name"));
        assertEquals("page/1", pages.getJSONObject(1).getString("url"));
        WebResourceResponse page = intercept(base + "page/1");
        assertEquals("image/avif", page.getMimeType());
        assertEquals("p10.avif", read(page));
        assertEquals(404, intercept(base + "page/2").getStatusCode());
        assertEquals(404, intercept(base + "file").getStatusCode());
    }

    @Test
    public void avifImageIsDecodedByTheWebViewNotThePlatform() throws Exception {
        File image = new File(directory, "photo.avif");
        byte[] avif = {0, 0, 0, 0x1C, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f', 0, 0, 0, 0};
        Files.write(image.toPath(), avif);
        assertTrue(navigate(fileUri(image), true));
        String base = assertViewerLoaded("image");
        JSONArray pages = new JSONObject(read(intercept(base + "pages.json"))).getJSONArray("pages");
        assertEquals("file", pages.getJSONObject(0).getString("url"));
        WebResourceResponse file = intercept(base + "file");
        assertEquals("image/avif", file.getMimeType());
        assertEquals(avif.length, read(file).length());
    }

    @Test
    public void djvuIsDetectedBySignatureAndOldContentIsReleased() throws Exception {
        File djvu = new File(directory, "scan.djvu");
        Files.write(djvu.toPath(), "AT&TFORM\0\0\0\0DJVM".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(navigate(fileUri(djvu), true));
        String base = assertViewerLoaded("djvu");
        WebResourceResponse file = intercept(base + "file");
        assertEquals("image/vnd.djvu", file.getMimeType());
        assertTrue(read(file).startsWith("AT&TFORM"));

        File note = new File(directory, "note.md");
        Files.write(note.toPath(), "# Note".getBytes(StandardCharsets.UTF_8));
        assertTrue(navigate(fileUri(note), true));
        assertEquals(404, intercept(base + "file").getStatusCode());
    }

    @Test
    public void viewerOriginOnlyServesViewerAssets() throws Exception {
        String origin = "https://" + ViewerContent.HOST;
        assertTrue(read(intercept(origin + "/assets/viewer.js")).contains("pages.json"));
        assertEquals("text/css", intercept(origin + "/assets/viewer.css").getMimeType());
        assertEquals(404, intercept(origin + "/assets/template.html").getStatusCode());
        assertEquals(404, intercept(origin + "/viewer/unknown/file").getStatusCode());
    }

    @Test
    public void detectsViewerKinds() {
        assertEquals(ViewerContent.Kind.BOOK, ViewerContent.detectKind("a.cbz", null, null));
        assertEquals(ViewerContent.Kind.BOOK,
                ViewerContent.detectKind("a.bin", null, new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}));
        assertEquals(ViewerContent.Kind.DJVU, ViewerContent.detectKind("1", "image/vnd.djvu", null));
        assertEquals(ViewerContent.Kind.IMAGE, ViewerContent.detectKind("1", "image/heic", null));
        assertEquals(ViewerContent.Kind.IMAGE, ViewerContent.detectKind("a.JPG", null, null));
        assertNull(ViewerContent.detectKind("a.svg", "image/svg+xml", "<svg".getBytes(StandardCharsets.UTF_8)));
        assertNull(ViewerContent.detectKind("BMW.md", "text/markdown", "BMW notes".getBytes(StandardCharsets.UTF_8)));
    }

    private String assertViewerLoaded(String kind) {
        ShadowWebView.LoadDataWithBaseURL load = shadowOf(webView).getLastLoadDataWithBaseURL();
        assertTrue(load.baseUrl, load.baseUrl.startsWith("https://" + ViewerContent.HOST + "/viewer/"));
        assertTrue(load.data.contains("data-kind=\"" + kind + "\""));
        return load.baseUrl;
    }

    private WebResourceResponse intercept(String url) {
        return webView.getWebViewClient().shouldInterceptRequest(webView, request(Uri.parse(url), false));
    }

    private static String read(WebResourceResponse response) throws Exception {
        assertNotNull(response.getData());
        try (InputStream in = response.getData()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    private static Uri fileUri(File file) {
        // java.io.File uses the host OS path format under Robolectric (including Windows).
        return Uri.parse(file.toURI().toString());
    }

    private boolean navigate(Uri uri, boolean mainFrame) {
        return webView.getWebViewClient().shouldOverrideUrlLoading(webView, request(uri, mainFrame));
    }

    private static WebResourceRequest request(Uri uri, boolean mainFrame) {
        return new WebResourceRequest() {
            public Uri getUrl() { return uri; }
            public boolean isForMainFrame() { return mainFrame; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return true; }
            public String getMethod() { return "GET"; }
            public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }
}
