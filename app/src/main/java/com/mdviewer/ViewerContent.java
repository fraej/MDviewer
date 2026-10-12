package com.mdviewer;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Serves an image, comic book archive or DjVu document to viewer.html.
 *
 * <p>The viewer is loaded from a virtual https origin so that it can fetch the document, and so
 * that images (including AVIF) are decoded by the WebView's own codecs rather than the platform's.
 */
final class ViewerContent implements Closeable {

    static final String HOST = "appassets.androidplatform.net";

    enum Kind { IMAGE, BOOK, DJVU }

    private static final String TAG = "ViewerContent";

    private final ContentResolver resolver;
    private final Uri uri;
    private final Kind kind;
    private final String name;
    private final File cacheDir;
    private final String token = UUID.randomUUID().toString();
    private final Object openLock = new Object();
    private final Object lock = new Object();
    private ComicArchive archive;
    private boolean closed;

    ViewerContent(ContentResolver resolver, Uri uri, Kind kind, String name, File cacheRoot) {
        this.resolver = resolver;
        this.uri = uri;
        this.kind = kind;
        this.name = name != null ? name : "";
        this.cacheDir = new File(cacheRoot, token);
    }

    /**
     * Returns how a file should be viewed, or null if it is not an image, comic book or DjVu
     * document. File signatures take precedence because providers often report generic types.
     */
    static Kind detectKind(String name, String mimeType, byte[] header) {
        String extension = name == null ? "" : ComicArchive.extension(name);
        String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (startsWith(header, 0, "AT&TFORM")) return Kind.DJVU;
        if (ComicArchive.detectFormat(header) != null) return Kind.BOOK;
        if (isImageSignature(header)) return Kind.IMAGE;
        if (extension.equals("djvu") || extension.equals("djv") || type.equals("image/vnd.djvu")
                || type.equals("image/x-djvu")) {
            return Kind.DJVU;
        }
        if (extension.matches("cb[zr7]") || type.startsWith("application/vnd.comicbook")
                || type.matches("application/x-cb[zr7]")) {
            return Kind.BOOK;
        }
        if ((name != null && ComicArchive.IMAGE_NAME.matcher(name).matches())
                || (type.startsWith("image/") && !type.equals("image/svg+xml"))) {
            return Kind.IMAGE;
        }
        return null;
    }

    private static boolean isImageSignature(byte[] header) {
        return startsWith(header, 0, "\u0089PNG")
                || startsWith(header, 0, "ÿØÿ")
                || startsWith(header, 0, "GIF8")
                || (startsWith(header, 0, "RIFF") && startsWith(header, 8, "WEBP"))
                || (startsWith(header, 4, "ftypavif") || startsWith(header, 4, "ftypavis"));
    }

    private static boolean startsWith(byte[] data, int offset, String signature) {
        if (data == null || data.length < offset + signature.length()) return false;
        for (int i = 0; i < signature.length(); i++) {
            if ((data[offset + i] & 0xFF) != signature.charAt(i)) return false;
        }
        return true;
    }

    Kind getKind() {
        return kind;
    }

    String getBaseUrl() {
        return "https://" + HOST + "/viewer/" + token + "/";
    }

    /** Serves a request below {@link #getBaseUrl()}, or returns null if it is for other content. */
    WebResourceResponse serve(Uri url) {
        List<String> segments = url.getPathSegments();
        if (segments.size() < 3 || !"viewer".equals(segments.get(0)) || !token.equals(segments.get(1))) {
            return null;
        }
        String resource = segments.get(2);
        try {
            if (segments.size() == 3 && "pages.json".equals(resource)) {
                return new WebResourceResponse("application/json", "UTF-8",
                        new ByteArrayInputStream(pages().toString().getBytes(StandardCharsets.UTF_8)));
            }
            if (segments.size() == 3 && "file".equals(resource) && kind != Kind.BOOK) {
                InputStream stream = resolver.openInputStream(uri);
                if (stream == null) throw new FileNotFoundException(uri.toString());
                return new WebResourceResponse(kind == Kind.DJVU ? "image/vnd.djvu" : MainActivity.getMimeType(name),
                        null, stream);
            }
            if (segments.size() == 4 && "page".equals(resource) && kind == Kind.BOOK) {
                ComicArchive book = archive();
                int index = Integer.parseInt(segments.get(3));
                if (index >= 0 && index < book.getPageCount()) {
                    return new WebResourceResponse(MainActivity.getMimeType(book.getPageName(index)), null,
                            book.openPage(index));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Corrupt archives can also fail with unchecked exceptions, which would crash the app here.
            Log.e(TAG, "Cannot serve " + url, e);
        }
        return notFound();
    }

    static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    /** Lists the pages to show, or describes why they cannot be shown. */
    private JSONObject pages() {
        JSONObject result = new JSONObject();
        JSONArray pages = new JSONArray();
        try {
            if (kind == Kind.BOOK) {
                ComicArchive book = archive();
                for (int i = 0; i < book.getPageCount(); i++) {
                    pages.put(new JSONObject().put("url", "page/" + i).put("name", book.getPageName(i)));
                }
                if (book.getPageCount() == 0) result.put("error", "No images found in this archive.");
            } else {
                pages.put(new JSONObject().put("url", "file").put("name", name));
            }
            result.put("pages", pages);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Cannot open " + uri, e);
            try {
                result.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
            } catch (JSONException ignored) {
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    private ComicArchive archive() throws IOException {
        // Opening can take a while (large or remote files), so it does not block close().
        synchronized (openLock) {
            synchronized (lock) {
                if (closed) throw new IOException("The document was closed.");
                if (archive != null) return archive;
            }
            ComicArchive opened = ComicArchive.open(openChannel(), new File(cacheDir, "pages"));
            synchronized (lock) {
                if (closed) {
                    opened.close();
                    throw new IOException("The document was closed.");
                }
                archive = opened;
                return opened;
            }
        }
    }

    private FileChannel openChannel() throws IOException {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new FileNotFoundException(uri.toString());
            return FileChannel.open(new File(path).toPath(), StandardOpenOption.READ);
        }
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) throw new FileNotFoundException(uri.toString());
        if (descriptor.getStatSize() >= 0) {
            // A regular file can be read in place; closing the channel closes the descriptor.
            return new ParcelFileDescriptor.AutoCloseInputStream(descriptor).getChannel();
        }
        descriptor.close();
        // Some providers (for example cloud storage) return pipes, which cannot seek.
        File copy = new File(cacheDir, "archive");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new IOException("Cannot create " + cacheDir);
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(copy)) {
            if (in == null) throw new FileNotFoundException(uri.toString());
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                synchronized (lock) {
                    if (closed) throw new InterruptedIOException("The document was closed.");
                }
                out.write(buffer, 0, read);
            }
        }
        return FileChannel.open(copy.toPath(), StandardOpenOption.READ);
    }

    @Override
    public void close() {
        ComicArchive book;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            book = archive;
        }
        if (book != null) book.close();
        ComicArchive.deleteRecursively(cacheDir);
    }
}
