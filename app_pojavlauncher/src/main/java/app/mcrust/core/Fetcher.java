package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/** Откуда берутся файлы. В приложении это HTTPS, в тестах словарь. */
public interface Fetcher {
    /** Записывает содержимое {@code url} в {@code dest} (каталог уже создан). */
    void get(String url, File dest, Map<String, String> headers) throws IOException;

    default void get(String url, File dest) throws IOException {
        get(url, dest, Collections.<String, String>emptyMap());
    }
}
