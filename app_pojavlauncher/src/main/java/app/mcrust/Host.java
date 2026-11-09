package app.mcrust;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import java.io.File;

import app.mcrust.core.CurseForge;
import app.mcrust.core.Fetcher;
import app.mcrust.core.ModSource;
import app.mcrust.core.ModStore;
import app.mcrust.core.Modrinth;
import app.mcrust.core.Profile;
import app.mcrust.core.Updater;
import app.mcrust.core.Versions;

import java.util.List;

/**
 * Всё, что экранам нужно от окружения: сеть, папки, каталоги модов, навигация.
 * Экраны не знают, в каком приложении живут (наше или встроены в Amethyst), поэтому зависят от Host, а не от Activity.
 */
public class Host extends ContextWrapper {
    /** Куда экраны просят перейти (в нашем приложении стек экранов, в Amethyst фрагмент). */
    public interface Nav {
        void push(Screen s);

        void pop();

        void popToRoot();
    }

    private final Activity activity;
    public Fetcher net;
    GameRuntime runtime = new GameRuntime.Missing();
    File root;
    File scratch;
    Profile.Store profiles;
    Modrinth modrinth;
    /** Манифест версий кэшируется на время работы приложения. */
    List<Versions.Entry> manifest;
    Nav nav;
    /** Ключ CurseForge по умолчанию, если пользователь не вводил свой (в Amethyst он зашит в приложение). */
    String defaultCurseKey = "";

    public Host(Activity activity) {
        super(activity);
        this.activity = activity;
    }

    boolean isDestroyed() {
        return activity.isDestroyed();
    }

    boolean isFinishing() {
        return activity.isFinishing();
    }

    void push(Screen s) {
        nav.push(s);
    }

    void pop() {
        nav.pop();
    }

    void popToRoot() {
        nav.popToRoot();
    }

    String curseKey() {
        String k = getSharedPreferences("mcrust", 0).getString("cf_key", "");
        return k.isEmpty() ? defaultCurseKey : k;
    }

    void setCurseKey(String key) {
        getSharedPreferences("mcrust", 0).edit().putString("cf_key", key.trim()).apply();
    }

    /** CurseForge без ключа недоступен: тогда null. */
    ModSource curseForge() {
        String k = curseKey();
        return k.isEmpty() ? null : new CurseForge(net, scratch, k);
    }

    /** Каталог, которому принадлежит проект (у CurseForge идентификатор начинается с «cf:»). */
    ModSource sourceFor(String projectId) {
        return projectId.startsWith(CurseForge.PREFIX) ? curseForge() : modrinth;
    }

    Updater.Sources updaterSources() {
        return new Updater.Sources() {
            @Override
            public ModSource forMod(ModStore.Mod mod) {
                return sourceFor(mod.projectId);
            }
        };
    }

    /** Общая папка игры для всех профилей: версии, библиотеки, ассеты. */
    File gameDir() {
        return new File(root, "game");
    }

    void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }
}
