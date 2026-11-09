package app.mcrust.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Описание версии игры (JSON Mojang, Fabric, Quilt, Forge): то, что нужно для скачивания файлов. */
public final class GameVersion {

    /** Платформа для правил библиотек: os = windows|linux|osx, arch = x86|x86_64|arm64. */
    public static final class Platform {
        public final String os, arch;

        public Platform(String os, String arch) {
            this.os = os;
            this.arch = arch;
        }

        /** Телефон: игру запускает среда на базе Linux с ARM-процессором. */
        public static final Platform ANDROID = new Platform("linux", "arm64");
    }

    public static final class Download {
        public String url, sha1, path;
        public long size;
    }

    public static final class Rule {
        boolean allow = true;
        String osName, osArch;
        Map<String, Boolean> features = new LinkedHashMap<>();

        boolean applies(Platform p) {
            return applies(p, Collections.<String>emptySet());
        }

        /** enabled: включённые возможности запуска (свой размер окна, быстрый вход на сервер); демо не включаем никогда. */
        boolean applies(Platform p, Set<String> enabled) {
            if (osName != null && !osName.equals(p.os)) return false;
            if (osArch != null && !osArch.equals(p.arch)) return false;
            for (Map.Entry<String, Boolean> e : features.entrySet()) {
                if (enabled.contains(e.getKey()) != e.getValue()) return false;
            }
            return true;
        }
    }

    /** Один элемент списка аргументов: значения и условия, при которых они попадают в команду. */
    public static final class Arg {
        public final List<String> values = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
    }

    public static final class Library {
        public String name, mavenUrl;
        public Download artifact;
        public final List<Rule> rules = new ArrayList<>();
        /** os -> классификатор нативной библиотеки (старые версии, до 1.19). */
        public final Map<String, String> natives = new LinkedHashMap<>();
        public final Map<String, Download> classifiers = new LinkedHashMap<>();

        public String relPath() {
            if (artifact != null && artifact.path != null) return artifact.path;
            return mavenPath(name);
        }

        /** Откуда качать; null: библиотеку создаёт установщик Forge/NeoForge, скачивать её нечем. */
        public Download download() {
            if (artifact != null) {
                if (artifact.url == null || artifact.url.isEmpty()) return null;
                Download d = new Download();
                d.url = artifact.url;
                d.sha1 = artifact.sha1;
                d.size = artifact.size;
                d.path = relPath();
                return d;
            }
            if (mavenUrl == null) return null;
            Download d = new Download();
            String rel = mavenPath(name);
            d.url = mavenUrl + (mavenUrl.endsWith("/") ? "" : "/") + rel;
            d.path = rel;
            return d;
        }

        String key() {
            String[] p = name.split(":", -1);
            StringBuilder b = new StringBuilder(p.length > 0 ? p[0] : "").append(':').append(p.length > 1 ? p[1] : "");
            for (int i = 3; i < p.length; i++) b.append(':').append(p[i]); // версию пропускаем, классификатор остаётся
            return b.toString();
        }
    }

    public String id, inheritsFrom, mainClass, assets, type, minecraftArguments, loggingArgument;
    /** Современный формат: списки arguments.jvm и arguments.game. */
    public final List<Arg> jvmArgs = new ArrayList<>(), gameArgs = new ArrayList<>();
    public Download assetIndex, client, logging;
    public int javaMajor;
    public final List<Library> libraries = new ArrayList<>();

    /** group:artifact:version[:classifier][@ext] -> путь в Maven-репозитории. */
    public static String mavenPath(String name) {
        String coords = name, ext = "jar";
        int at = name.indexOf('@');
        if (at >= 0) {
            coords = name.substring(0, at);
            ext = name.substring(at + 1);
        }
        String[] p = coords.split(":", -1);
        String g = p.length > 0 ? p[0] : "", a = p.length > 1 ? p[1] : "", v = p.length > 2 ? p[2] : "";
        String cls = p.length > 3 ? "-" + p[3] : "";
        return g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + cls + "." + ext;
    }

    private static Download download(Object o) {
        String url = Json.text(o, "url");
        if (url == null) return null;
        Download d = new Download();
        d.url = url;
        d.sha1 = Json.text(o, "sha1");
        d.size = Json.num(o, 0, "size");
        d.path = Json.text(o, "path");
        return d;
    }

    private static List<Rule> parseRules(Object rules) {
        List<Rule> out = new ArrayList<>();
        for (Object ro : Json.arr(rules)) {
            Rule r = new Rule();
            r.allow = !"disallow".equals(Json.text(ro, "action"));
            r.osName = Json.text(ro, "os", "name");
            r.osArch = Json.text(ro, "os", "arch");
            for (Map.Entry<String, Object> e : Json.obj(Json.at(ro, "features")).entrySet()) {
                r.features.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
            }
            out.add(r);
        }
        return out;
    }

    private static void parseArgs(Object list, List<Arg> into) {
        for (Object o : Json.arr(list)) {
            Arg a = new Arg();
            if (o instanceof String) {
                a.values.add((String) o);
            } else {
                Object val = Json.at(o, "value");
                if (val instanceof String) a.values.add((String) val);
                for (Object x : Json.arr(val)) if (x instanceof String) a.values.add((String) x);
                a.rules.addAll(parseRules(Json.at(o, "rules")));
            }
            if (!a.values.isEmpty()) into.add(a);
        }
    }

    public static GameVersion parse(String text) throws IOException {
        Object j = Json.parse(text);
        GameVersion v = new GameVersion();
        v.id = Json.text(j, "id");
        if (v.id == null) throw new IOException("в описании версии нет id");
        v.inheritsFrom = Json.text(j, "inheritsFrom");
        v.mainClass = Json.text(j, "mainClass");
        v.type = Json.text(j, "type");
        v.minecraftArguments = Json.text(j, "minecraftArguments");
        v.loggingArgument = Json.text(j, "logging", "client", "argument");
        parseArgs(Json.at(j, "arguments", "jvm"), v.jvmArgs);
        parseArgs(Json.at(j, "arguments", "game"), v.gameArgs);
        v.assets = Json.text(j, "assets");
        if (v.assets == null) v.assets = Json.text(j, "assetIndex", "id");
        Object ai = Json.at(j, "assetIndex");
        v.assetIndex = download(ai);
        v.client = download(Json.at(j, "downloads", "client"));
        v.logging = download(Json.at(j, "logging", "client", "file"));
        v.javaMajor = (int) Json.num(j, 0, "javaVersion", "majorVersion");
        for (Object lo : Json.arr(Json.at(j, "libraries"))) {
            Library l = new Library();
            l.name = Json.text(lo, "name");
            if (l.name == null) continue;
            l.artifact = download(Json.at(lo, "downloads", "artifact"));
            l.mavenUrl = Json.text(lo, "url");
            l.rules.addAll(parseRules(Json.at(lo, "rules")));
            for (Map.Entry<String, Object> e : Json.obj(Json.at(lo, "natives")).entrySet()) {
                if (e.getValue() instanceof String) l.natives.put(e.getKey(), (String) e.getValue());
            }
            for (Map.Entry<String, Object> e : Json.obj(Json.at(lo, "downloads", "classifiers")).entrySet()) {
                Download d = download(e.getValue());
                if (d != null) l.classifiers.put(e.getKey(), d);
            }
            v.libraries.add(l);
        }
        return v;
    }

    /** Без правил библиотека нужна; иначе решает последнее подошедшее правило. */
    public static boolean allowed(List<Rule> rules, Platform p) {
        if (rules.isEmpty()) return true;
        boolean ok = false;
        for (Rule r : rules) if (r.applies(p)) ok = r.allow;
        return ok;
    }

    /** Склеивает профиль загрузчика с родителем: свои библиотеки первыми и вытесняют одноимённые родительские. */
    public GameVersion mergedWith(GameVersion parent) {
        GameVersion m = new GameVersion();
        m.id = id;
        m.mainClass = mainClass != null ? mainClass : parent.mainClass;
        m.assets = assets != null ? assets : parent.assets;
        m.assetIndex = assetIndex != null ? assetIndex : parent.assetIndex;
        m.client = client != null ? client : parent.client;
        m.logging = logging != null ? logging : parent.logging;
        m.javaMajor = javaMajor != 0 ? javaMajor : parent.javaMajor;
        m.type = type != null ? type : parent.type;
        m.minecraftArguments = minecraftArguments != null ? minecraftArguments : parent.minecraftArguments;
        m.loggingArgument = loggingArgument != null ? loggingArgument : parent.loggingArgument;
        m.jvmArgs.addAll(parent.jvmArgs);
        m.jvmArgs.addAll(jvmArgs);
        m.gameArgs.addAll(parent.gameArgs);
        m.gameArgs.addAll(gameArgs);
        Set<String> seen = new HashSet<>();
        for (Library l : libraries) if (seen.add(l.key())) m.libraries.add(l);
        for (Library l : parent.libraries) if (seen.add(l.key())) m.libraries.add(l);
        return m;
    }

    /** Значения тех аргументов, чьи условия подходят платформе и включённым возможностям. */
    public static List<String> selectArgs(List<Arg> args, Platform p, Set<String> enabled) {
        List<String> out = new ArrayList<>();
        for (Arg a : args) {
            boolean ok = a.rules.isEmpty();
            for (Rule r : a.rules) if (r.applies(p, enabled)) ok = r.allow;
            if (ok) out.addAll(a.values);
        }
        return out;
    }

    public List<Library> activeLibraries(Platform p) {
        List<Library> out = new ArrayList<>();
        for (Library l : libraries) if (allowed(l.rules, p)) out.add(l);
        return out;
    }
}
