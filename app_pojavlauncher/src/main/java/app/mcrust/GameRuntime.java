package app.mcrust;

import java.io.IOException;

import app.mcrust.core.LaunchCommand;

/**
 * Запуск самой игры на телефоне. Сюда подключается среда выполнения Java и слой OpenGL для Android;
 * пока её нет, приложение честно говорит об этом вместо того, чтобы делать вид.
 */
interface GameRuntime {
    /** Готова ли среда запуска. */
    boolean available();

    /** Что сказать пользователю, если не готова. */
    String status();

    /** Запускает игру по готовой команде (аккаунт, classpath и аргументы уже подставлены). */
    void launch(Host a, LaunchCommand.Prepared launch) throws IOException;

    /** Среда, которой ещё нет. */
    final class Missing implements GameRuntime {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String status() {
            return "Запуск игры на телефоне ещё не подключён: нужна среда Java и слой OpenGL для Android. "
                    + "Профиль, версия и моды уже готовы и лежат в папке профиля.";
        }

        @Override
        public void launch(Host a, LaunchCommand.Prepared launch) throws IOException {
            throw new IOException(status());
        }
    }
}
