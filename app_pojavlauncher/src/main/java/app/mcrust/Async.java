package app.mcrust;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Фоновая работа с возвратом результата в главный поток. Результат не доставляется, если экран уже закрыт. */
final class Async {
    private Async() {}

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    interface Work<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void ok(T value);
    }

    interface Fail {
        void err(Exception e);
    }

    static <T> void run(final Host a, final Work<T> work, final Done<T> done, final Fail fail) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                T value = null;
                Exception error = null;
                try {
                    value = work.run();
                } catch (Exception e) {
                    error = e;
                }
                final T v = value;
                final Exception err = error;
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isDestroyed() || a.isFinishing()) return;
                        if (err != null) fail.err(err);
                        else done.ok(v);
                    }
                });
            }
        });
    }

    static void ui(Host a, final Runnable r) {
        UI.post(r);
    }
}
