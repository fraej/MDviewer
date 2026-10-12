package com.mdviewer;

import static org.junit.Assert.*;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ComicArchiveTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void detectsFormatFromSignatureNotExtension() {
        assertEquals(ComicArchive.Format.ZIP, ComicArchive.detectFormat(new byte[]{'P', 'K', 3, 4, 0}));
        assertEquals(ComicArchive.Format.RAR,
                ComicArchive.detectFormat(new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00}));
        assertEquals(ComicArchive.Format.SEVEN_Z,
                ComicArchive.detectFormat(new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}));
        assertNull(ComicArchive.detectFormat("# Markdown".getBytes(StandardCharsets.UTF_8)));
        assertNull(ComicArchive.detectFormat(new byte[]{'P'}));
    }

    @Test
    public void naturalOrderSortsNumbersByValue() {
        List<String> names = new ArrayList<>(Arrays.asList(
                "Page10.jpg", "page2.jpg", "Page1.jpg", "page02b.jpg", "cover.jpg", "ch2/1.jpg", "ch10/1.jpg"));
        names.sort(ComicArchive.NATURAL_ORDER);
        assertEquals(Arrays.asList(
                "ch2/1.jpg", "ch10/1.jpg", "cover.jpg", "Page1.jpg", "page2.jpg", "page02b.jpg", "Page10.jpg"), names);
    }

    @Test
    public void zipListsOnlyImagePagesInNaturalOrder() throws Exception {
        // Many .cbr files are really ZIP archives, so the extension must not matter.
        File file = folder.newFile("comic.cbr");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (String name : new String[]{"10.jpg", "2.PNG", "__MACOSX/._2.PNG", "notes.txt", "dir/", "1.avif",
                    "dir\\.thumb.jpg"}) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write(name.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        try (ComicArchive archive = ComicArchive.open(file, folder.newFolder("zip"))) {
            assertPages(archive, "1.avif", "2.PNG", "10.jpg");
            assertEquals("10.jpg", read(archive, 2));
            assertEquals("1.avif", read(archive, 0));
        }
    }

    @Test
    public void sevenZipExtractsPagesInTheBackground() throws Exception {
        File file = folder.newFile("comic.cb7");
        try (SevenZOutputFile sevenZ = new SevenZOutputFile(file)) {
            for (String name : new String[]{"b/02.webp", "info.xml", "b/01.jpeg", "a.gif"}) {
                SevenZArchiveEntry entry = sevenZ.createArchiveEntry(new File(name), name);
                sevenZ.putArchiveEntry(entry);
                sevenZ.write(name.getBytes(StandardCharsets.UTF_8));
                sevenZ.closeArchiveEntry();
            }
        }
        File cache = new File(folder.getRoot(), "7z-cache");
        ComicArchive archive = ComicArchive.open(file, cache);
        assertPages(archive, "a.gif", "b/01.jpeg", "b/02.webp");
        assertEquals("a.gif", read(archive, 0));
        assertEquals("b/02.webp", read(archive, 2));
        assertEquals("b/01.jpeg", read(archive, 1));
        archive.close();
        assertFalse(cache.exists());
        try {
            archive.openPage(0);
            fail("Closed archives must not serve pages");
        } catch (IOException expected) {
        }
    }

    @Test
    public void rar4AndRar5ArchivesAreExtracted() throws Exception {
        for (String name : new String[]{"rar4.rar", "rar5.rar"}) {
            File file = folder.newFile(name);
            try (InputStream in = getClass().getResourceAsStream(name)) {
                assertNotNull(name, in);
                Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // The fixtures contain text files, so accept every entry.
            try (ComicArchive archive = ComicArchive.open(file, folder.newFolder(name + "-cache"), entry -> true)) {
                assertPages(archive, "FILE1.TXT", "FILE2.TXT");
                assertEquals("file2", read(archive, 1).trim());
                assertEquals("file1", read(archive, 0).trim());
            }
            try (ComicArchive archive = ComicArchive.open(file, folder.newFolder(name + "-images"))) {
                assertEquals(0, archive.getPageCount());
            }
        }
    }

    @Test(expected = IOException.class)
    public void rejectsFilesThatAreNotArchives() throws Exception {
        File file = folder.newFile("fake.cbz");
        Files.write(file.toPath(), "not an archive".getBytes(StandardCharsets.UTF_8));
        ComicArchive.open(file, folder.newFolder("fake"));
    }

    private static void assertPages(ComicArchive archive, String... names) {
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < archive.getPageCount(); i++) actual.add(archive.getPageName(i));
        assertEquals(Arrays.asList(names), actual);
    }

    private static String read(ComicArchive archive, int page) throws IOException {
        try (InputStream in = archive.openPage(page)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }
}
