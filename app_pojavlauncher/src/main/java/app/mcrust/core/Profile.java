package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Профиль: версия игры + загрузчик + свои моды (папка instances/&lt;id&gt;). */
public final class Profile {
    public String id, name, mcVersion, loaderVersion;
    public Loader loader = Loader.VANILLA;
    public int memoryMb = 2048;

    public String describe() {
        return mcVersion + (loader.supportsMods() ? " · " + loader.title + (loaderVersion == null ? "" : " " + loaderVersion) : "");
    }

    /**
     * Профиль по уже установленной версии (так версии называет лаунчер, в который мы встроены):
     * загрузчик определяется по имени, версия игры берётся из inheritsFrom описания версии.
     * versionsDir: папка versions/, внутри неё <id>/<id>.json.
     */
    public static Profile fromInstalledVersion(File versionsDir, String versionId) {
        Profile p = new Profile();
        p.id = versionId;
        p.name = versionId;
        String low = versionId.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("neoforge")) p.loader = Loader.NEOFORGE;
        else if (low.contains("forge")) p.loader = Loader.FORGE;
        else if (low.contains("fabric")) p.loader = Loader.FABRIC;
        else if (low.contains("quilt")) p.loader = Loader.QUILT;
        p.mcVersion = versionId;
        File json = new File(new File(versionsDir, versionId), versionId + ".json");
        if (json.isFile()) {
            try {
                String parent = Json.text(Json.parse(Files2.read(json)), "inheritsFrom");
                if (parent != null && !parent.isEmpty()) p.mcVersion = parent;
            } catch (IOException e) {
                // описание не читается: остаётся имя версии
            }
        }
        if (p.loader == Loader.FABRIC || p.loader == Loader.QUILT) {
            // fabric-loader-0.16.5-1.21.1: между «loader-» и версией игры стоит версия загрузчика
            String marker = "-loader-";
            int i = versionId.indexOf(marker);
            if (i >= 0) {
                String rest = versionId.substring(i + marker.length());
                int last = rest.lastIndexOf('-');
                if (last > 0) {
                    p.loaderVersion = rest.substring(0, last);
                    if (p.mcVersion.equals(versionId)) p.mcVersion = rest.substring(last + 1);
                }
            }
        }
        return p;
    }

    /** Хранилище профилей: root/profiles.json. */
    public static final class Store {
        private final File root;
        private final List<Profile> all = new ArrayList<>();

        public Store(File root) throws IOException {
            this.root = root;
            File f = new File(root, "profiles.json");
            if (f.isFile()) {
                for (Object o : Json.arr(Json.at(Json.parse(Files2.read(f)), "profiles"))) {
                    Profile p = new Profile();
                    p.id = Json.text(o, "id");
                    p.mcVersion = Json.text(o, "mc_version");
                    if (p.id == null || p.mcVersion == null) continue;
                    p.name = Json.text(o, "name");
                    if (p.name == null) p.name = p.id;
                    p.loader = Loader.byId(String.valueOf(Json.text(o, "loader")));
                    p.loaderVersion = Json.text(o, "loader_version");
                    p.memoryMb = (int) Json.num(o, 2048, "memory_mb");
                    all.add(p);
                }
            }
        }

        public List<Profile> list() {
            return new ArrayList<>(all);
        }

        public Profile get(String id) {
            for (Profile p : all) if (p.id.equals(id)) return p;
            return null;
        }

        public File instanceDir(Profile p) {
            return new File(new File(root, "instances"), p.id);
        }

        /** Создаёт профиль; id получается из имени и не повторяется. */
        public Profile create(String name, String mc, Loader loader, String loaderVersion) throws IOException {
            if (mc == null || mc.isEmpty()) throw new IOException("не выбрана версия игры");
            if (loader.supportsMods() && (loaderVersion == null || loaderVersion.isEmpty())) {
                throw new IOException("не выбрана версия загрузчика " + loader.title);
            }
            Profile p = new Profile();
            p.name = (name == null || name.trim().isEmpty()) ? mc + (loader.supportsMods() ? " " + loader.title : "") : name.trim();
            String base = slug(p.name);
            String id = base;
            for (int n = 2; get(id) != null; n++) id = base + "-" + n;
            p.id = id;
            p.mcVersion = mc;
            p.loader = loader;
            p.loaderVersion = loader.supportsMods() ? loaderVersion : null;
            all.add(p);
            Files2.mkdirs(instanceDir(p));
            save();
            return p;
        }

        public void delete(String id, boolean withFiles) throws IOException {
            Profile p = get(id);
            if (p == null) return;
            all.remove(p);
            if (withFiles) deleteTree(instanceDir(p));
            save();
        }

        public void save() throws IOException {
            List<Object> arr = new ArrayList<>();
            for (Profile p : all) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("mc_version", p.mcVersion);
                o.put("loader", p.loader.id);
                o.put("loader_version", p.loaderVersion);
                o.put("memory_mb", (double) p.memoryMb);
                arr.add(o);
            }
            Map<String, Object> rootObj = new LinkedHashMap<>();
            rootObj.put("profiles", arr);
            Files2.write(new File(root, "profiles.json"), Json.write(rootObj));
        }

        static String slug(String s) {
            StringBuilder b = new StringBuilder();
            for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
                else if (b.length() > 0 && b.charAt(b.length() - 1) != '-') b.append('-');
            }
            String r = b.toString();
            while (r.endsWith("-")) r = r.substring(0, r.length() - 1);
            return r.isEmpty() ? "profile" : r;
        }

        static void deleteTree(File d) {
            File[] kids = d.listFiles();
            if (kids != null) for (File k : kids) {
                if (k.isDirectory() && !java.nio.file.Files.isSymbolicLink(k.toPath())) deleteTree(k);
                else k.delete();
            }
            d.delete();
        }
    }
}
