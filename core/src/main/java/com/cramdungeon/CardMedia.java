package com.cramdungeon;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Images and audio for the cards. AnkiDroid doesn't let other apps read its media
 * folder, so players share a deck export (.apkg / .colpkg, with media) to the game
 * and the media files are copied into a flat folder keyed by Anki's file names.
 * Re-importing merges: new files are added, changed ones replaced, the rest kept.
 */
public final class CardMedia {

    public interface Source {
        InputStream open() throws IOException;
    }

    /** Wraps a zstd-compressed stream (newer exports compress the media list and files). */
    public interface Zstd {
        InputStream decompress(InputStream in) throws IOException;
    }

    public interface Progress {
        void update(int done, int total);
    }

    public static final class Result {
        public int added;
        public int updated;
        public int unchanged;
        public int skipped;
        public long bytes;

        public int total() {
            return added + updated + unchanged + skipped;
        }
    }

    // PackageMetadata.Version in Anki's import_export.proto
    private static final int VERSION_LATEST = 3;

    private static final String TEMP = ".importing";

    private final File dir;

    public CardMedia(File dir) {
        this.dir = dir;
    }

    /** Anki's name normalised (NFC), or null if it can't be a plain file name. */
    public static String safeName(String name) {
        if (name == null) {
            return null;
        }
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.startsWith(".") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            return null;
        }
        return name;
    }

    /** The imported file for an Anki media name, or null if it isn't here. */
    public File file(String name) {
        String safe = safeName(name);
        if (safe == null) {
            return null;
        }
        File f = new File(dir, safe);
        return f.isFile() ? f : null;
    }

    private File[] files() {
        File[] list = dir.listFiles((d, n) -> safeName(n) != null);
        return list == null ? new File[0] : list;
    }

    public int count() {
        return files().length;
    }

    public long bytes() {
        long total = 0;
        for (File f : files()) {
            total += f.length();
        }
        return total;
    }

    public void clear() {
        for (File f : files()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Copies the media of an Anki package into the store. Reads the package twice
     * (media list first, files second) so it can stream from a content URI.
     */
    public Result importPackage(Source source, Zstd zstd, Progress progress) throws IOException {
        byte[] meta = null;
        byte[] mediaList = null;
        boolean anyCollection = false;
        try (ZipInputStream zip = new ZipInputStream(source.open())) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("meta")) {
                    meta = readAll(zip);
                } else if (e.getName().equals("media")) {
                    mediaList = readAll(zip);
                } else if (e.getName().startsWith("collection.anki2")) {
                    anyCollection = true;
                }
            }
        }
        if (!anyCollection && mediaList == null) {
            throw new IOException("not an Anki package");
        }

        boolean latest = false;
        if (meta != null) {
            int version = (int) metaVersion(meta);
            if (version < 1 || version > VERSION_LATEST) {
                throw new IOException("package version " + version + " is not supported");
            }
            latest = version == VERSION_LATEST;
        }

        Map<String, String> byZipName = new HashMap<>();
        if (mediaList != null) {
            if (latest) {
                try (InputStream in = zstd.decompress(new java.io.ByteArrayInputStream(mediaList))) {
                    byZipName = parseEntries(readAll(in));
                }
            } else {
                byZipName = parseLegacy(new String(mediaList, StandardCharsets.UTF_8));
            }
        }

        Result result = new Result();
        int total = byZipName.size();
        if (total == 0) {
            return result;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("can't create " + dir);
        }
        progress.update(0, total);

        int done = 0;
        try (ZipInputStream zip = new ZipInputStream(source.open())) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String listed = byZipName.get(e.getName());
                if (listed == null) {
                    continue;
                }
                String name = safeName(listed);
                if (name == null) {
                    result.skipped++;
                } else {
                    InputStream body = new FilterInputStream(zip) {
                        @Override
                        public void close() {
                            // the zip stream stays open for the next entry
                        }
                    };
                    if (latest) {
                        body = zstd.decompress(body);
                    }
                    store(name, body, result);
                }
                progress.update(++done, total);
            }
        }
        // entries listed but absent from the zip
        result.skipped += total - done;
        return result;
    }

    private void store(String name, InputStream body, Result result) throws IOException {
        File temp = new File(dir, TEMP);
        MessageDigest sha1 = sha1();
        long size = 0;
        try (InputStream in = new DigestInputStream(body, sha1);
             OutputStream out = new FileOutputStream(temp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                size += n;
            }
        }
        byte[] digest = sha1.digest();

        File target = new File(dir, name);
        if (target.isFile()) {
            if (target.length() == size && Arrays.equals(digest, sha1Of(target))) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                result.unchanged++;
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            result.updated++;
        } else {
            result.added++;
        }
        if (!temp.renameTo(target)) {
            throw new IOException("can't store " + name);
        }
        result.bytes += size;
    }

    // --- package format

    private static long metaVersion(byte[] meta) throws IOException {
        Proto p = new Proto(meta);
        long version = 0;
        while (p.more()) {
            int key = (int) p.varint();
            if (key >>> 3 == 1 && (key & 7) == 0) {
                version = p.varint();
            } else {
                p.skip(key & 7);
            }
        }
        return version;
    }

    /** MediaEntries: files are stored in the zip under their position in this list. */
    static Map<String, String> parseEntries(byte[] data) throws IOException {
        Map<String, String> map = new HashMap<>();
        Proto p = new Proto(data);
        int index = 0;
        while (p.more()) {
            int key = (int) p.varint();
            if (key >>> 3 != 1 || (key & 7) != 2) {
                p.skip(key & 7);
                continue;
            }
            Proto e = new Proto(p.bytes());
            String name = null;
            long zipName = index;
            while (e.more()) {
                int k = (int) e.varint();
                int field = k >>> 3;
                if (field == 1 && (k & 7) == 2) {
                    name = new String(e.bytes(), StandardCharsets.UTF_8);
                } else if (field == 255 && (k & 7) == 0) {
                    zipName = e.varint();
                } else {
                    e.skip(k & 7);
                }
            }
            if (name != null) {
                map.put(Long.toString(zipName), name);
            }
            index++;
        }
        return map;
    }

    /** Older exports: a JSON object of zip name to media name. */
    static Map<String, String> parseLegacy(String json) throws IOException {
        Map<String, String> map = new HashMap<>();
        JsonValue root;
        try {
            root = new JsonReader().parse(json);
        } catch (RuntimeException e) {
            throw new IOException("bad media list", e);
        }
        if (root == null) {
            return map;
        }
        for (JsonValue v = root.child; v != null; v = v.next) {
            if (v.name != null && v.isString()) {
                map.put(v.name, v.asString());
            }
        }
        return map;
    }

    /** Just enough protobuf to read Anki's package metadata and media list. */
    private static final class Proto {
        private final byte[] buf;
        private int pos;

        Proto(byte[] buf) {
            this.buf = buf;
        }

        boolean more() {
            return pos < buf.length;
        }

        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= buf.length) {
                    throw new IOException("truncated varint");
                }
                byte b = buf[pos++];
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("bad varint");
        }

        byte[] bytes() throws IOException {
            long len = varint();
            if (len < 0 || len > buf.length - pos) {
                throw new IOException("truncated field");
            }
            byte[] out = Arrays.copyOfRange(buf, pos, pos + (int) len);
            pos += (int) len;
            return out;
        }

        void skip(int wireType) throws IOException {
            switch (wireType) {
                case 0:
                    varint();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 2:
                    bytes();
                    break;
                case 5:
                    pos += 4;
                    break;
                default:
                    throw new IOException("unsupported wire type " + wireType);
            }
            if (pos > buf.length) {
                throw new IOException("truncated field");
            }
        }
    }

    // --- helpers

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha1Of(File f) throws IOException {
        MessageDigest sha1 = sha1();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha1.update(buf, 0, n);
            }
        }
        return sha1.digest();
    }
}
