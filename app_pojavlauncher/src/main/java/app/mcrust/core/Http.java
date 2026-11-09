package app.mcrust.core;

import java.io.IOException;
import java.util.Map;

/** Запросы с телом (POST) и ответом в виде текста: нужны для входа в аккаунт. В тестах подменяется. */
public interface Http {
    /** Ответ с кодом не 2xx: тело нужно, чтобы понять причину (например, authorization_pending). */
    final class Error extends IOException {
        public final int status;
        public final String body;

        public Error(String url, int status, String body) {
            super(url + ": сервер ответил " + status);
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    String send(String method, String url, Map<String, String> headers, String body) throws IOException;
}
