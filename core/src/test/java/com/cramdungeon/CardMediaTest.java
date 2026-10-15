package com.cramdungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CardMediaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final CardMedia.Zstd ZSTD = ZstdInputStream::new;
    private static final CardMedia.Progress NONE = (done, total) -> { };

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Anki 2.1.49 and older, or "support older Anki versions": JSON list, plain files. */
    private static byte[] legacyPackage(String... namesAndBodies) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("collection.anki21", new byte[]{1});
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < namesAndBodies.length; i += 2) {
            int index = i / 2;
            entries.put(Integer.toString(index), utf8(namesAndBodies[i + 1]));
            json.append(index == 0 ? "" : ",").append('"').append(index).append("\":\"")
                    .append(namesAndBodies[i]).append('"');
        }
        entries.put("media", utf8(json.append('}').toString()));
        return zip(entries);
    }

    private static void varint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static void field(ByteArrayOutputStream out, int number, byte[] value) {
        varint(out, (number << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    /** Current exports: protobuf meta and media list, zstd-compressed list and files. */
    private static byte[] latestPackage(String... namesAndBodies) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        varint(meta, 1 << 3);
        varint(meta, 3);
        entries.put("meta", meta.toByteArray());
        entries.put("collection.anki21b", Zstd.compress(new byte[]{1}));
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        for (int i = 0; i < namesAndBodies.length; i += 2) {
            byte[] body = utf8(namesAndBodies[i + 1]);
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            field(entry, 1, utf8(namesAndBodies[i]));
            varint(entry, 2 << 3);
            varint(entry, body.length);
            field(entry, 3, new byte[20]);
            field(list, 1, entry.toByteArray());
            entries.put(Integer.toString(i / 2), Zstd.compress(body));
        }
        entries.put("media", Zstd.compress(list.toByteArray()));
        return zip(entries);
    }

    private CardMedia.Result importInto(CardMedia media, byte[] pkg) throws IOException {
        return media.importPackage(() -> new ByteArrayInputStream(pkg), ZSTD, NONE);
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void importsLegacyPackages() throws IOException {
        CardMedia media = new CardMedia(tmp.newFolder());
        CardMedia.Result r = importInto(media, legacyPackage("cat.jpg", "meow", "ねこ.mp3", "nya"));
        assertEquals(2, r.added);
        assertEquals("meow", read(media.file("cat.jpg")));
        assertEquals("nya", read(media.file("ねこ.mp3")));
        assertEquals(2, media.count());
        assertEquals(7, media.bytes());
    }

    @Test
    public void importsZstdPackages() throws IOException {
        CardMedia media = new CardMedia(tmp.newFolder());
        CardMedia.Result r = importInto(media, latestPackage("a.png", "aaa", "b.ogg", "bb"));
        assertEquals(2, r.added);
        assertEquals("aaa", read(media.file("a.png")));
        assertEquals("bb", read(media.file("b.ogg")));
    }

    @Test
    public void reimportingMerges() throws IOException {
        CardMedia media = new CardMedia(tmp.newFolder());
        importInto(media, legacyPackage("old.jpg", "1", "same.jpg", "2"));
        CardMedia.Result r = importInto(media,
                latestPackage("same.jpg", "2", "old.jpg", "changed", "new.jpg", "3"));
        assertEquals(1, r.added);
        assertEquals(1, r.updated);
        assertEquals(1, r.unchanged);
        assertEquals("changed", read(media.file("old.jpg")));
        assertEquals(3, media.count());
    }

    @Test
    public void skipsUnsafeNames() throws IOException {
        File dir = tmp.newFolder();
        CardMedia media = new CardMedia(dir);
        CardMedia.Result r = importInto(media, legacyPackage("../evil.txt", "x", "ok.jpg", "y"));
        assertEquals(1, r.added);
        assertEquals(1, r.skipped);
        assertTrue(!new File(dir.getParentFile(), "evil.txt").exists());
        assertNull(media.file("../evil.txt"));
        assertNull(CardMedia.safeName(".hidden"));
    }

    @Test
    public void rejectsOtherFiles() throws IOException {
        CardMedia media = new CardMedia(tmp.newFolder());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("readme.txt", utf8("hi"));
        try {
            importInto(media, zip(entries));
            fail();
        } catch (IOException expected) {
            // not an Anki package
        }
    }

    @Test
    public void clearRemovesEverything() throws IOException {
        CardMedia media = new CardMedia(tmp.newFolder());
        importInto(media, legacyPackage("a.jpg", "1"));
        media.clear();
        assertEquals(0, media.count());
    }
}
