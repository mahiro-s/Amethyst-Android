package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Собирает команду запуска игры из описания версии: classpath, аргументы JVM и игры. Саму JVM запускает среда выполнения. */
public final class LaunchCommand {
    private LaunchCommand() {}

    public static final class Options {
        public String javaPath = "java";
        public int maxMemoryMb = 2048;
        public int width, height;
        /** host или host:port; пусто: без быстрого входа на сервер. */
        public String server;
        public String launcherName = "mcrust", launcherVersion = "1";
        /** Свои аргументы JVM (например, параметры сборщика мусора), идут после стандартных. */
        public final List<String> extraJvm = new ArrayList<>();
    }

    /** Нативные jar-файлы версий до 1.19: их нужно распаковать в natives-каталог до запуска. */
    public static List<File> nativeJars(GameFiles.Layout l, GameVersion v, GameVersion.Platform p) {
        List<File> out = new ArrayList<>();
        for (GameVersion.Library lib : v.activeLibraries(p)) {
            String cls = lib.natives.get(p.os);
            if (cls == null) continue;
            GameVersion.Download nd = lib.classifiers.get(cls.replace("${arch}", p.arch.equals("x86") ? "32" : "64"));
            if (nd == null) continue;
            String rel = nd.path != null ? nd.path : GameVersion.mavenPath(lib.name + ":" + cls);
            out.add(new File(l.libraries(), rel));
        }
        return out;
    }

    /** Распаковывает .so/.dll из нативных jar в каталог, без META-INF и вложенных папок. */
    public static void extractNatives(List<File> jars, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("не создать " + dir);
        for (File jar : jars) {
            if (!jar.isFile()) throw new IOException("нет нативной библиотеки " + jar);
            try (ZipFile z = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                    String name = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                    if (name.isEmpty()) continue;
                    File out = new File(dir, name);
                    try (InputStream in = z.getInputStream(e); OutputStream o = Files.newOutputStream(out.toPath())) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            }
        }
    }

    public static File nativesDir(GameFiles.Layout l, String versionId) {
        return new File(new File(new File(l.root, "versions"), versionId), "natives");
    }

    /** classpath: библиотеки платформы без повторов, затем jar игры. */
    public static List<File> classpath(GameFiles.Layout l, GameFiles.Resolved r, GameVersion.Platform p) {
        Set<File> out = new LinkedHashSet<>();
        for (GameVersion.Library lib : r.version.activeLibraries(p)) {
            // у библиотек старых версий с natives нет основного jar: в classpath им нечего делать
            if (lib.artifact == null && lib.mavenUrl == null && !lib.natives.isEmpty()) continue;
            out.add(new File(l.libraries(), lib.relPath()));
        }
        out.add(l.versionJar(r.baseId));
        return new ArrayList<>(out);
    }

    /** Что нужно среде выполнения: команда, папка игры и нужная версия Java. */
    public static final class Prepared {
        public final List<String> command;
        public final File gameDir;
        public final int javaMajor;
        public final String versionId;

        Prepared(List<String> command, File gameDir, int javaMajor, String versionId) {
            this.command = command;
            this.gameDir = gameDir;
            this.javaMajor = javaMajor;
            this.versionId = versionId;
        }
    }

    /** Идентификатор версии, с которой запускается профиль (имена задают сервисы загрузчиков). */
    public static String versionIdFor(Profile p) throws IOException {
        switch (p.loader) {
            case VANILLA:
                return p.mcVersion;
            case FABRIC:
                return "fabric-loader-" + p.loaderVersion + "-" + p.mcVersion;
            case QUILT:
                return "quilt-loader-" + p.loaderVersion + "-" + p.mcVersion;
            case NEOFORGE:
                return "neoforge-" + p.loaderVersion;
            case FORGE:
                return p.mcVersion + "-forge-" + p.loaderVersion;
            default:
                throw new IOException("неизвестный загрузчик");
        }
    }

    /**
     * Проверяет, что файлы на месте (без сети), распаковывает нативные библиотеки и собирает команду.
     * Если что-то не скачано, ошибка говорит об этом прямо, а не падает уже внутри игры.
     */
    public static Prepared prepare(Fetcher f, GameFiles.Layout l, Profile p, GameVersion.Platform pl,
                                   Account acc, File gameDir, Options o) throws IOException {
        String id = versionIdFor(p);
        if (!l.versionJson(id).isFile()) {
            throw new IOException("Файлы версии " + id + " не скачаны: нажмите «Скачать файлы игры»"
                    + (p.loader == Loader.NEOFORGE || p.loader == Loader.FORGE ? " и запустите установщик загрузчика" : ""));
        }
        GameFiles.Resolved r = GameFiles.resolve(f, l, id);
        for (GameFiles.Task t : GameFiles.planFiles(l, r, pl)) {
            if (t.url != null && !t.dest.isFile()) throw new IOException("Не хватает файла " + t.dest.getName() + ": скачайте файлы игры заново");
        }
        if (!gameDir.isDirectory() && !gameDir.mkdirs() && !gameDir.isDirectory()) throw new IOException("не создать папку " + gameDir);
        List<File> natives = nativeJars(l, r.version, pl);
        if (!natives.isEmpty()) extractNatives(natives, nativesDir(l, r.baseId));
        List<String> cmd = build(l, r, pl, acc, gameDir, o);
        return new Prepared(cmd, gameDir, r.version.javaMajor != 0 ? r.version.javaMajor : 17, id);
    }

    /** Полная команда: [java, JVM-аргументы..., главный класс, аргументы игры...]. */
    public static List<String> build(GameFiles.Layout l, GameFiles.Resolved r, GameVersion.Platform p,
                                     Account acc, File gameDir, Options o) throws IOException {
        GameVersion v = r.version;
        if (v.mainClass == null) throw new IOException("в версии " + v.id + " не указан главный класс");

        Set<String> features = new HashSet<>();
        boolean sized = o.width > 0 && o.height > 0;
        if (sized) features.add("has_custom_resolution");
        boolean server = o.server != null && !o.server.isEmpty();
        if (server) {
            features.add("is_quick_play_multiplayer");
            features.add("has_quick_plays_support");
        }

        StringBuilder cp = new StringBuilder();
        for (File f : classpath(l, r, p)) {
            if (cp.length() > 0) cp.append(File.pathSeparatorChar);
            cp.append(f.getPath());
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", acc.name);
        vars.put("auth_uuid", acc.uuid);
        vars.put("auth_access_token", acc.accessToken);
        vars.put("auth_xuid", acc.xuid);
        vars.put("auth_session", "token:" + acc.accessToken + ":" + acc.uuid);
        vars.put("clientid", "");
        vars.put("user_type", acc.userType());
        vars.put("user_properties", "{}");
        vars.put("version_name", v.id);
        vars.put("version_type", v.type != null ? v.type : "release");
        vars.put("game_directory", gameDir.getPath());
        vars.put("assets_root", l.assets().getPath());
        vars.put("game_assets", l.assets().getPath());
        vars.put("assets_index_name", v.assets != null ? v.assets : "legacy");
        vars.put("natives_directory", nativesDir(l, r.baseId).getPath());
        vars.put("library_directory", l.libraries().getPath());
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("classpath", cp.toString());
        vars.put("launcher_name", o.launcherName);
        vars.put("launcher_version", o.launcherVersion);
        if (sized) {
            vars.put("resolution_width", String.valueOf(o.width));
            vars.put("resolution_height", String.valueOf(o.height));
        }
        if (server) vars.put("quickPlayMultiplayer", o.server);
        if (v.loggingArgument != null && v.logging != null) {
            vars.put("path", GameFiles.loggingFile(l, v.logging).getPath());
        }

        List<String> jvm = new ArrayList<>();
        List<String> modernJvm = GameVersion.selectArgs(v.jvmArgs, p, features);
        if (modernJvm.isEmpty()) {
            // старый формат: classpath и каталог нативных библиотек в описании не указаны
            jvm.add("-Djava.library.path=${natives_directory}");
            jvm.add("-cp");
            jvm.add("${classpath}");
        } else {
            jvm.addAll(modernJvm);
        }
        if (v.loggingArgument != null && v.logging != null) jvm.add(v.loggingArgument);

        List<String> game = new ArrayList<>();
        if (!v.gameArgs.isEmpty()) {
            game.addAll(GameVersion.selectArgs(v.gameArgs, p, features));
        } else if (v.minecraftArguments != null) {
            for (String s : v.minecraftArguments.trim().split("\\s+")) if (!s.isEmpty()) game.add(s);
        }
        if (server && !hasPlaceholder(game, "quickPlayMultiplayer")) {
            // версия до 1.20: быстрого входа нет, вход на сервер задаётся --server/--port
            String host = o.server;
            String port = null;
            int c = host.lastIndexOf(':');
            if (c > 0 && host.indexOf(':') == c) {
                port = host.substring(c + 1);
                host = host.substring(0, c);
            }
            game.add("--server");
            game.add(host);
            if (port != null) {
                game.add("--port");
                game.add(port);
            }
        }
        if (sized && !hasPlaceholder(game, "resolution_width")) {
            game.add("--width");
            game.add(String.valueOf(o.width));
            game.add("--height");
            game.add(String.valueOf(o.height));
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(o.javaPath);
        cmd.add("-Xmx" + o.maxMemoryMb + "m");
        for (String s : jvm) cmd.add(fill(s, vars));
        cmd.addAll(o.extraJvm);
        cmd.add(v.mainClass);
        for (String s : game) cmd.add(fill(s, vars));
        return cmd;
    }

    private static boolean hasPlaceholder(List<String> args, String name) {
        for (String s : args) if (s.contains("${" + name + "}")) return true;
        return false;
    }

    /** Подставляет ${имя}; неизвестные имена остаются как есть, чтобы ошибка была видна, а не молча пустая. */
    static String fill(String s, Map<String, String> vars) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf("${", i);
            if (a < 0) {
                b.append(s, i, s.length());
                break;
            }
            int e = s.indexOf('}', a);
            if (e < 0) {
                b.append(s, i, s.length());
                break;
            }
            b.append(s, i, a);
            String key = s.substring(a + 2, e);
            String val = vars.get(key);
            b.append(val != null ? val : s.substring(a, e + 1));
            i = e + 1;
        }
        return b.toString();
    }
}
