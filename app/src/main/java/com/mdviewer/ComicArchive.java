package com.mdviewer;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.exception.RarException;
import com.github.junrar.io.SeekableReadOnlyByteChannel;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.volume.Volume;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Lists and reads the image pages of a comic book archive (CBZ, CBR or CB7).
 *
 * <p>ZIP entries are read on demand. RAR and 7z entries are extracted in archive order on a
 * background thread because solid archives can only be decoded sequentially; reading a page
 * waits until that page has been extracted.
 */
final class ComicArchive implements Closeable {

    enum Format { ZIP, RAR, SEVEN_Z }

    static final Pattern IMAGE_NAME = Pattern.compile(
            ".*\\.(jpe?g|png|gif|webp|avif|bmp)$", Pattern.CASE_INSENSITIVE);

    private static final Predicate<String> IS_PAGE = name -> IMAGE_NAME.matcher(name).matches();

    /** Natural order, so that "page2" sorts before "page10". */
    static final Comparator<String> NATURAL_ORDER = (a, b) -> {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i, startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String numberA = a.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String numberB = b.substring(startB, j).replaceFirst("^0+(?=.)", "");
                int result = numberA.length() != numberB.length()
                        ? Integer.compare(numberA.length(), numberB.length())
                        : numberA.compareTo(numberB);
                if (result != 0) return result;
            } else {
                int result = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (result != 0) return result;
                i++;
                j++;
            }
        }
        int result = Integer.compare(a.length() - i, b.length() - j);
        return result != 0 ? result : a.compareTo(b);
    };

    private static final class Page {
        final String name;
        final int archiveIndex;
        final ZipArchiveEntry zipEntry;

        Page(String name, int archiveIndex, ZipArchiveEntry zipEntry) {
            this.name = name;
            this.archiveIndex = archiveIndex;
            this.zipEntry = zipEntry;
        }
    }

    private final Object lock = new Object();
    private final List<Page> pages = new ArrayList<>();
    private final File cacheDir;
    private ZipFile zipFile;
    private Thread extractor;
    private boolean[] extracted;
    private IOException extractionError;
    private boolean extractionFinished;
    private boolean closed;

    /** Returns the format identified by the file signature, or null if it is not an archive. */
    static Format detectFormat(byte[] header) {
        if (startsWith(header, 'P', 'K', 3, 4) || startsWith(header, 'P', 'K', 5, 6)) return Format.ZIP;
        if (startsWith(header, 'R', 'a', 'r', '!', 0x1A, 0x07)) return Format.RAR;
        if (startsWith(header, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C)) return Format.SEVEN_Z;
        return null;
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data == null || data.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) return false;
        }
        return true;
    }

    static ComicArchive open(File file, File cacheDir) throws IOException {
        return open(FileChannel.open(file.toPath(), StandardOpenOption.READ), cacheDir, IS_PAGE);
    }

    /**
     * Opens an archive and takes ownership of {@code channel}, which must be seekable.
     * Extracted pages are written to {@code cacheDir}, which is deleted on close.
     */
    static ComicArchive open(FileChannel channel, File cacheDir) throws IOException {
        return open(channel, cacheDir, IS_PAGE);
    }

    static ComicArchive open(File file, File cacheDir, Predicate<String> isPage) throws IOException {
        return open(FileChannel.open(file.toPath(), StandardOpenOption.READ), cacheDir, isPage);
    }

    private static ComicArchive open(FileChannel channel, File cacheDir, Predicate<String> isPage)
            throws IOException {
        ComicArchive archive = new ComicArchive(cacheDir);
        try {
            ByteBuffer header = ByteBuffer.allocate(8);
            while (header.hasRemaining() && channel.read(header, header.position()) > 0) {
                // Read the signature without moving the channel position.
            }
            Format format = detectFormat(header.position() > 0 ? header.array() : null);
            if (format == null) throw new IOException("Not a ZIP, RAR or 7z archive.");
            if (format == Format.ZIP) {
                archive.openZip(channel, isPage);
            } else if (format == Format.RAR) {
                archive.openRar(channel, isPage);
            } else {
                archive.openSevenZ(channel, isPage);
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            archive.close();
            throw e;
        }
        return archive;
    }

    private ComicArchive(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    int getPageCount() {
        return pages.size();
    }

    String getPageName(int index) {
        return pages.get(index).name;
    }

    /** Opens a page, waiting for it to be extracted if necessary. */
    InputStream openPage(int index) throws IOException {
        Page page = pages.get(index);
        synchronized (lock) {
            if (closed) throw new IOException("Archive is closed.");
            if (zipFile != null) {
                try (InputStream in = zipFile.getInputStream(page.zipEntry)) {
                    if (in == null) throw new IOException("Cannot read " + page.name);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    copy(in, out);
                    return new ByteArrayInputStream(out.toByteArray());
                }
            }
            while (!extracted[index]) {
                if (closed) throw new IOException("Archive is closed.");
                if (extractionError != null) throw new IOException(extractionError.getMessage(), extractionError);
                if (extractionFinished) throw new IOException("Cannot extract " + page.name);
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
        }
        return new FileInputStream(pageFile(index));
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
            thread = extractor;
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException ignored) {
                }
            }
        }
        // Writes fail once the archive is closed, so the extractor stops at its next write and
        // then closes the archive. Not waiting for it keeps close() fast on the UI thread.
        if (thread != null) thread.interrupt();
        deleteRecursively(cacheDir);
    }

    private void openZip(FileChannel channel, Predicate<String> isPage) throws IOException {
        ZipFile zip = ZipFile.builder().setSeekableByteChannel(channel).get();
        zipFile = zip;
        int index = 0;
        for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
            String name = normalizeName(entry.getName());
            if (!entry.isDirectory() && isPage(name, isPage)) {
                pages.add(new Page(name, index, entry));
            }
            index++;
        }
        sortPages();
    }

    private void openRar(FileChannel channel, Predicate<String> isPage) throws IOException {
        Archive rar;
        try {
            rar = new Archive((archive, last) -> last == null ? new RarVolume(archive, channel) : null,
                    ArchiveOptions.builder().build());
        } catch (RarException e) {
            throw new IOException("Cannot read RAR archive: " + e.getMessage(), e);
        }
        try {
            if (rar.isEncrypted()) throw new IOException("Encrypted RAR archives are not supported.");
            List<FileHeader> headers = rar.getFileHeaders();
            for (int i = 0; i < headers.size(); i++) {
                FileHeader header = headers.get(i);
                String name = normalizeName(header.getFileName());
                if (!header.isDirectory() && isPage(name, isPage)) pages.add(new Page(name, i, null));
            }
        } catch (IOException | RarException | RuntimeException e) {
            rar.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        sortPages();
        startExtraction(() -> {
            try (Archive archive = rar) {
                List<FileHeader> headers = archive.getFileHeaders();
                for (int i = 0; i < headers.size(); i++) {
                    FileHeader header = headers.get(i);
                    if (header.isDirectory()) continue;
                    int page = pageForArchiveIndex(i);
                    // Every file is decoded in order: solid archives cannot skip entries.
                    try (OutputStream out = openPageOutput(page)) {
                        archive.extractFile(header, out);
                    } catch (RarException e) {
                        throw new IOException("Cannot extract " + header.getFileName() + ": " + e.getMessage(), e);
                    }
                    pageExtracted(page);
                }
            }
        });
    }

    private void openSevenZ(FileChannel channel, Predicate<String> isPage) throws IOException {
        SevenZFile sevenZ = SevenZFile.builder().setSeekableByteChannel(channel).get();
        try {
            int index = 0;
            for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
                String name = normalizeName(entry.getName());
                if (!entry.isDirectory() && entry.hasStream() && isPage(name, isPage)) {
                    pages.add(new Page(name, index, null));
                }
                index++;
            }
        } catch (RuntimeException e) {
            sevenZ.close();
            throw e;
        }
        sortPages();
        startExtraction(() -> {
            try (SevenZFile archive = sevenZ) {
                byte[] buffer = new byte[64 * 1024];
                int index = 0;
                SevenZArchiveEntry entry;
                while ((entry = archive.getNextEntry()) != null) {
                    int page = pageForArchiveIndex(index++);
                    if (page < 0) continue; // getNextEntry() skips unread data.
                    try (OutputStream out = openPageOutput(page)) {
                        int read;
                        while ((read = archive.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                    pageExtracted(page);
                }
            }
        });
    }

    /** Lets junrar read a single-volume archive from an already open channel. */
    private static final class RarVolume implements Volume, SeekableReadOnlyByteChannel {
        private final Archive archive;
        private final FileChannel channel;

        RarVolume(Archive archive, FileChannel channel) {
            this.archive = archive;
            this.channel = channel;
        }

        @Override
        public SeekableReadOnlyByteChannel getChannel() {
            return this;
        }

        @Override
        public long getLength() {
            try {
                return channel.size();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Archive getArchive() {
            return archive;
        }

        @Override
        public long getPosition() throws IOException {
            return channel.position();
        }

        @Override
        public void setPosition(long position) throws IOException {
            channel.position(position);
        }

        @Override
        public int read() throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            return channel.read(buffer) == 1 ? buffer.get(0) & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return length == 0 ? 0 : channel.read(ByteBuffer.wrap(buffer, offset, length));
        }

        @Override
        public int readFully(byte[] buffer, int count) throws IOException {
            ByteBuffer target = ByteBuffer.wrap(buffer, 0, count);
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) throw new EOFException();
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private interface Extraction {
        void run() throws IOException;
    }

    private void startExtraction(Extraction extraction) throws IOException {
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create " + cacheDir);
        }
        extracted = new boolean[pages.size()];
        extractor = new Thread(() -> {
            try {
                extraction.run();
            } catch (IOException | RuntimeException e) {
                synchronized (lock) {
                    extractionError = e instanceof IOException ? (IOException) e : new IOException(e.toString(), e);
                }
            } finally {
                synchronized (lock) {
                    extractionFinished = true;
                    lock.notifyAll();
                }
            }
        }, "ComicArchive extractor");
        extractor.start();
    }

    private int pageForArchiveIndex(int archiveIndex) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).archiveIndex == archiveIndex) return i;
        }
        return -1;
    }

    /** Opens the file for an extracted page, or a sink for entries that are not pages. */
    private OutputStream openPageOutput(int page) throws IOException {
        OutputStream target = page < 0 ? null : new FileOutputStream(partialFile(page));
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                synchronized (lock) {
                    if (closed) throw new InterruptedIOException("Archive is closed.");
                }
                if (target != null) target.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                if (target != null) target.close();
            }
        };
    }

    private void pageExtracted(int page) throws IOException {
        if (page < 0) return;
        if (!partialFile(page).renameTo(pageFile(page))) {
            throw new IOException("Cannot store " + pages.get(page).name);
        }
        synchronized (lock) {
            extracted[page] = true;
            lock.notifyAll();
        }
    }

    private File partialFile(int page) {
        return new File(cacheDir, page + ".part");
    }

    private File pageFile(int page) {
        return new File(cacheDir, page + ".page");
    }

    private void sortPages() {
        Collections.sort(pages, (a, b) -> NATURAL_ORDER.compare(a.name, b.name));
    }

    private static boolean isPage(String name, Predicate<String> isPage) {
        // Skip macOS resource forks and hidden files such as thumbnails.
        if (name.startsWith("__MACOSX/") || name.startsWith(".") || name.contains("/.")) return false;
        return isPage.test(name);
    }

    private static String normalizeName(String name) {
        return name.replace('\\', '/');
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
