package app.mcrust.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** {@link Http} поверх HttpURLConnection. */
public final class UrlHttp implements Http {
    @Override
    public String send(String method, String url, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UrlFetcher.USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "" : read(in);
            if (code < 200 || code >= 300) throw new Error(url, code, text);
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
