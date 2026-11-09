package app.mcrust.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** HTTPS через стандартный HttpURLConnection: работает и на Android, и в обычной JVM. */
public final class UrlFetcher implements Fetcher {
    /** Modrinth просит указывать, кто обращается к его серверу. */
    public static final String USER_AGENT = "mc-rust/mcrust-android (hobby launcher)";

    @Override
    public void get(String url, File dest, Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                fetchOnce(url, dest, headers);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException(url + ": " + (last == null ? "ошибка" : last.getMessage()), last);
    }

    private void fetchOnce(String url, File dest, Map<String, String> headers) throws IOException {
        String cur = url;
        String host = new URL(url).getHost();
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            // ключ API уходит только тому серверу, которому предназначен, а не тому, куда нас перенаправили
            if (new URL(cur).getHost().equalsIgnoreCase(host)) {
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("сервер ответил " + code);
            }
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            return;
        }
        throw new IOException("слишком много перенаправлений");
    }
}
