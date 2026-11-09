package app.mcrust.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

final class Net {
    private Net() {}

    /** Кодирование для части URL (пробел как %20, а не +). */
    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
