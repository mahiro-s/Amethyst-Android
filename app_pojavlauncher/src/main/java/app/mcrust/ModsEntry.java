package app.mcrust;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;

import app.mcrust.core.Modrinth;
import app.mcrust.core.Profile;
import app.mcrust.core.UrlFetcher;

/** Вход для чужих лаунчеров: готовый экран «Моды» для одного профиля (используется в форке Amethyst). */
public final class ModsEntry {
    private ModsEntry() {}

    /**
     * @param cacheDir  куда складывать временные файлы и значки
     * @param curseKey  ключ CurseForge по умолчанию (пусто: пользователь введёт свой)
     * @param instance  папка профиля, внутри которой лежит mods/
     */
    public static View create(Activity activity, File cacheDir, String curseKey, Profile profile, File instance, final Runnable close) {
        Host h = new Host(activity);
        h.net = new UrlFetcher();
        h.scratch = new File(cacheDir, "mcrust");
        h.scratch.mkdirs();
        h.root = h.scratch;
        h.modrinth = new Modrinth(h.net, h.scratch);
        h.defaultCurseKey = curseKey == null ? "" : curseKey.trim();
        h.nav = new Host.Nav() {
            @Override
            public void push(Screen s) {
                // внутри встроенного экрана переходов нет
            }

            @Override
            public void pop() {
                if (close != null) close.run();
            }

            @Override
            public void popToRoot() {
                if (close != null) close.run();
            }
        };
        Screen s = new ModsScreen(h, profile, instance);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Ui.BG);
        root.addView(s.view());
        s.onShow();
        return root;
    }
}
