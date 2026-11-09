package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Скачивание файлов игры в устройстве обычного .minecraft: versions/, libraries/, assets/.
 * Каталог общий для всех профилей; у каждого профиля свои только mods/, миры и настройки.
 */
public final class GameFiles {
    private GameFiles() {}

    public static final String ASSET_URL = "https://resources.download.minecraft.net";
    public static final String FABRIC_API = "https://meta.fabricmc.net/v2";
    public static final String QUILT_API = "https://meta.quiltmc.org/v3";

    public static final class Layout {
        public final File root;

        public Layout(File root) {
            this.root = root;
        }

        public File versionJson(String id) {
            return new File(new File(new File(root, "versions"), id), id + ".json");
        }

        public File versionJar(String id) {
            return new File(new File(new File(root, "versions"), id), id + ".jar");
        }

        public File libraries() {
            return new File(root, "libraries");
        }

        public File assets() {
            return new File(root, "assets");
        }

        public File installers() {
            return new File(root, "installers");
        }

        public File scratch(String name) {
            return new File(new File(root, "tmp"), name + "-" + Files2.uid());
        }
    }

    public static final class Task {
        public final String url;
        public final File dest;
        public final String sha1;
        public final long size;

        public Task(String url, File dest, String sha1, long size) {
            this.url = url;
            this.dest = dest;
            this.sha1 = sha1;
            this.size = size;
        }
    }

    public interface Progress {
        /** done из total; text для людей. */
        void step(String text, int done, int total);
    }

    /** Файл на месте и целый? */
    static boolean isFresh(Task t) {
        if (!t.dest.isFile()) return false;
        if (t.size > 0 && t.dest.length() != t.size) return false;
        if (t.size <= 0 && t.dest.length() == 0) return false;
        if (t.sha1 == null || t.sha1.isEmpty()) return true;
        try {
            return t.sha1.equalsIgnoreCase(Files2.sha1(t.dest));
        } catch (IOException e) {
            return false;
        }
    }

    /** Скачивает во временный файл, проверяет SHA-1 и только потом кладёт на место: оборванная загрузка кэш не портит. */
    public static void download(Fetcher f, Task t) throws IOException {
        if (isFresh(t)) return;
        Files2.mkdirs(t.dest.getParentFile());
        File part = new File(t.dest.getPath() + ".part");
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            part.delete();
            try {
                f.get(t.url, part);
                if (t.sha1 != null && !t.sha1.isEmpty()) {
                    String got = Files2.sha1(part);
                    if (!got.equalsIgnoreCase(t.sha1)) {
                        last = new IOException(t.url + ": контрольная сумма " + got + " вместо " + t.sha1);
                        continue;
                    }
                }
                t.dest.delete();
                if (!part.renameTo(t.dest)) throw new IOException("не удалось сохранить " + t.dest);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        part.delete();
        throw last == null ? new IOException("не скачалось " + t.url) : last;
    }

    /** Качает всё параллельно. Возвращает список ошибок (пустой: всё хорошо). */
    public static List<String> downloadAll(final Fetcher f, List<Task> tasks, int threads, final Progress progress) {
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        final int total = tasks.size();
        if (total == 0) return errors;
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final List<Task> list = tasks;
        int n = Math.max(1, Math.min(Math.min(threads, 32), total));
        ExecutorService pool = Executors.newFixedThreadPool(n);
        final CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            int k = next.getAndIncrement();
                            if (k >= total) break;
                            Task t = list.get(k);
                            try {
                                download(f, t);
                            } catch (IOException e) {
                                errors.add(e.getMessage());
                            }
                            int d = done.incrementAndGet();
                            if (progress != null) progress.step(t.dest.getName(), d, total);
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdown();
        return errors;
    }

    // ---- версии ----

    public static final class Resolved {
        public final GameVersion version;
        /** Версия, чей jar лежит в classpath (для профиля загрузчика это игра-родитель). */
        public final String baseId;

        Resolved(GameVersion version, String baseId) {
            this.version = version;
            this.baseId = baseId;
        }
    }

    /** Описание версии с диска или по манифесту Mojang; профили загрузчиков склеиваются с родителем. */
    public static Resolved resolve(Fetcher f, Layout l, String id) throws IOException {
        return resolve(f, l, id, 0);
    }

    private static Resolved resolve(Fetcher f, Layout l, String id, int depth) throws IOException {
        if (depth > 4) throw new IOException("версия " + id + ": слишком длинная цепочка inheritsFrom");
        File path = l.versionJson(id);
        if (!path.isFile()) {
            String manifest = Files2.fetchText(f, Versions.MANIFEST, l.scratch("manifest"));
            Versions.Entry found = null;
            for (Versions.Entry e : Versions.parseManifest(manifest)) if (e.id.equals(id)) found = e;
            if (found == null || found.url == null) {
                throw new IOException("версии «" + id + "» нет в манифесте Mojang, и профиля загрузчика с таким именем нет на диске");
            }
            download(f, new Task(found.url, path, found.sha1, 0));
        }
        GameVersion v = GameVersion.parse(Files2.read(path));
        if (v.inheritsFrom == null) return new Resolved(v, v.id);
        Resolved parent = resolve(f, l, v.inheritsFrom, depth + 1);
        return new Resolved(v.mergedWith(parent.version), parent.baseId);
    }

    /** Клиент, библиотеки (с нативными классификаторами старых версий), настройки журнала. */
    public static List<Task> planFiles(Layout l, Resolved r, GameVersion.Platform p) {
        List<Task> out = new ArrayList<>();
        GameVersion v = r.version;
        if (v.client != null) out.add(new Task(v.client.url, l.versionJar(r.baseId), v.client.sha1, v.client.size));
        for (GameVersion.Library lib : v.activeLibraries(p)) {
            GameVersion.Download d = lib.download();
            if (d != null) out.add(new Task(d.url, new File(l.libraries(), d.path), d.sha1, d.size));
            String cls = lib.natives.get(p.os);
            if (cls != null) {
                GameVersion.Download nd = lib.classifiers.get(cls.replace("${arch}", p.arch.equals("x86") ? "32" : "64"));
                if (nd != null) {
                    String rel = nd.path != null ? nd.path : GameVersion.mavenPath(lib.name + ":" + cls);
                    out.add(new Task(nd.url, new File(l.libraries(), rel), nd.sha1, nd.size));
                }
            }
        }
        if (v.logging != null) out.add(new Task(v.logging.url, loggingFile(l, v.logging), v.logging.sha1, v.logging.size));
        return out;
    }

    public static File loggingFile(Layout l, GameVersion.Download d) {
        String name = d.path != null ? d.path : d.url.substring(d.url.lastIndexOf('/') + 1);
        return new File(new File(l.assets(), "log_configs"), name);
    }

    /** Индекс ассетов (скачивает при необходимости) и задача на каждый объект; одинаковые объекты один раз. */
    public static List<Task> planAssets(Fetcher f, Layout l, GameVersion v) throws IOException {
        List<Task> out = new ArrayList<>();
        if (v.assetIndex == null || v.assets == null) return out;
        File idx = new File(new File(l.assets(), "indexes"), v.assets + ".json");
        download(f, new Task(v.assetIndex.url, idx, v.assetIndex.sha1, v.assetIndex.size));
        Object j = Json.parse(Files2.read(idx));
        Set<String> seen = new HashSet<>();
        for (Object o : Json.obj(Json.at(j, "objects")).values()) {
            String h = Json.text(o, "hash");
            if (h == null || h.length() < 2 || !seen.add(h)) continue;
            String pre = h.substring(0, 2);
            out.add(new Task(ASSET_URL + "/" + pre + "/" + h, new File(new File(new File(l.assets(), "objects"), pre), h), h, Json.num(o, 0, "size")));
        }
        Collections.sort(out, new Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                return a.dest.getPath().compareTo(b.dest.getPath());
            }
        });
        return out;
    }

    // ---- загрузчики ----

    /** Что получилось после установки загрузчика. */
    public static final class Loaded {
        /** Версия для запуска; null, если её создаст установщик (NeoForge/Forge), когда появится Java. */
        public final String versionId;
        /** Установщик NeoForge/Forge, который надо будет выполнить (иначе null). */
        public final File installer;
        /** Версия, которая появится после установщика. */
        public final String expectedId;

        Loaded(String versionId, File installer, String expectedId) {
            this.versionId = versionId;
            this.installer = installer;
            this.expectedId = expectedId;
        }
    }

    /** Fabric и Quilt: профиль версии берётся с их сервера метаданных и кладётся как обычная версия. */
    static String installFabricLike(Fetcher f, Layout l, String api, String mc, String loaderVersion) throws IOException {
        String text = Files2.fetchText(f, api + "/versions/loader/" + Net.enc(mc) + "/" + Net.enc(loaderVersion) + "/profile/json", l.scratch("profile"));
        GameVersion v = GameVersion.parse(text);
        Files2.write(l.versionJson(v.id), text);
        return v.id;
    }

    /** Ставит загрузчик профиля. Для Vanilla это сама игра. */
    public static Loaded installLoader(Fetcher f, Layout l, Profile p) throws IOException {
        switch (p.loader) {
            case VANILLA:
                return new Loaded(p.mcVersion, null, p.mcVersion);
            case FABRIC:
                return new Loaded(installFabricLike(f, l, FABRIC_API, p.mcVersion, p.loaderVersion), null, null);
            case QUILT:
                return new Loaded(installFabricLike(f, l, QUILT_API, p.mcVersion, p.loaderVersion), null, null);
            case NEOFORGE: {
                String v = p.loaderVersion;
                String id = "neoforge-" + v;
                if (l.versionJson(id).isFile()) return new Loaded(id, null, id);
                File jar = new File(l.installers(), "neoforge-" + v + "-installer.jar");
                download(f, new Task("https://maven.neoforged.net/releases/net/neoforged/neoforge/" + v + "/neoforge-" + v + "-installer.jar", jar, null, 0));
                return new Loaded(null, jar, id);
            }
            case FORGE: {
                String v = p.loaderVersion;
                String id = p.mcVersion + "-forge-" + v;
                if (l.versionJson(id).isFile()) return new Loaded(id, null, id);
                File jar = new File(l.installers(), "forge-" + p.mcVersion + "-" + v + "-installer.jar");
                download(f, new Task("https://maven.minecraftforge.net/net/minecraftforge/forge/" + p.mcVersion + "-" + v + "/forge-" + p.mcVersion + "-" + v + "-installer.jar", jar, null, 0));
                return new Loaded(null, jar, id);
            }
            default:
                throw new IOException("неизвестный загрузчик");
        }
    }

    /** Итог установки профиля. */
    public static final class Installed {
        /** Версия для запуска (null: нужен установщик). */
        public String versionId;
        public File installer;
        public String expectedId;
        public int downloaded;
    }

    /**
     * Скачивает всё, что нужно профилю: игру, загрузчик, библиотеки, ассеты.
     * Уже скачанное и целое пропускается, так что повторный вызов быстрый.
     */
    public static Installed install(Fetcher f, Layout l, Profile p, GameVersion.Platform platform, int threads, Progress progress) throws IOException {
        Files2.mkdirs(l.root);
        if (progress != null) progress.step("Описание версии " + p.mcVersion, 0, 0);
        resolve(f, l, p.mcVersion); // vanilla json скачивается всегда: нужен и загрузчикам
        if (progress != null && p.loader.supportsMods()) progress.step("Загрузчик " + p.loader.title, 0, 0);
        Loaded loaded = installLoader(f, l, p);
        Installed out = new Installed();
        out.versionId = loaded.versionId;
        out.installer = loaded.installer;
        out.expectedId = loaded.expectedId;
        Resolved r = resolve(f, l, loaded.versionId != null ? loaded.versionId : p.mcVersion);
        List<Task> tasks = planFiles(l, r, platform);
        if (progress != null) progress.step("Индекс ассетов", 0, 0);
        tasks.addAll(planAssets(f, l, r.version));
        List<Task> todo = new ArrayList<>();
        for (Task t : tasks) if (!isFresh(t)) todo.add(t);
        out.downloaded = todo.size();
        List<String> errors = downloadAll(f, todo, threads, progress);
        if (!errors.isEmpty()) {
            throw new IOException("не скачалось файлов: " + errors.size() + ", например: " + errors.get(0));
        }
        return out;
    }
}
