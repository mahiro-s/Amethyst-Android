package app.mcrust.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Мелкие помощники для файлов и хэшей. */
public final class Files2 {
    private Files2() {}

    private static final java.util.concurrent.atomic.AtomicLong UID = new java.util.concurrent.atomic.AtomicLong();

    /** Уникальное число для имён временных файлов. */
    public static long uid() {
        return UID.incrementAndGet();
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    public static String sha1(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha1(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** UUID игрока на сервере с online-mode=false: nameUUIDFromBytes("OfflinePlayer:" + имя). */
    public static String offlineUuid(String name) {
        return java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void write(File f, String text) throws IOException {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    public static void mkdirs(File d) throws IOException {
        // параллельные загрузки создают одну папку одновременно: mkdirs у проигравшего вернёт false, хотя папка уже есть
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) throw new IOException("не удалось создать " + d);
    }

    public static String fetchText(Fetcher f, String url, File scratch) throws IOException {
        return fetchText(f, url, scratch, java.util.Collections.<String, String>emptyMap());
    }

    public static String fetchText(Fetcher f, String url, File scratch, java.util.Map<String, String> headers) throws IOException {
        mkdirs(scratch.getParentFile());
        f.get(url, scratch, headers);
        try {
            return read(scratch);
        } finally {
            scratch.delete();
        }
    }
}
