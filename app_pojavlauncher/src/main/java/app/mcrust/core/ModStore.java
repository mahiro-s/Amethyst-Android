package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Установленные моды одного профиля: файлы в mods/ и индекс mcrust-mods.json рядом с папкой.
 * Выключенный мод — файл с суффиксом .disabled (Fabric/NeoForge такие не загружают).
 */
public final class ModStore {
    public static final class Mod {
        public String projectId, versionId, title, version, filename, sha1, published;
        public boolean explicit = true, enabled = true;
        public List<String> requires = new ArrayList<>();

        /** Каталог, откуда мод: у CurseForge идентификатор начинается с «cf:». */
        public String source() {
            return projectId != null && projectId.startsWith("cf:") ? "curseforge" : "modrinth";
        }
    }

    private final File modsDir;
    private final File index;
    private final Map<String, Mod> mods = new LinkedHashMap<>();

    public ModStore(File instanceDir) throws IOException {
        this.modsDir = new File(instanceDir, "mods");
        this.index = new File(instanceDir, "mcrust-mods.json");
        Files2.mkdirs(modsDir);
        load();
    }

    public File modsDir() {
        return modsDir;
    }

    public List<Mod> list() {
        return new ArrayList<>(mods.values());
    }

    public Mod get(String projectId) {
        return mods.get(projectId);
    }

    public boolean has(String projectId) {
        return mods.containsKey(projectId);
    }

    public java.util.Set<String> projectIds() {
        return new java.util.HashSet<>(mods.keySet());
    }

    private void load() throws IOException {
        mods.clear();
        if (!index.isFile()) return;
        Object root = Json.parse(Files2.read(index));
        for (Object o : Json.arr(Json.at(root, "mods"))) {
            Mod m = new Mod();
            m.projectId = Json.text(o, "project_id");
            if (m.projectId == null) continue;
            m.versionId = Json.text(o, "version_id");
            m.title = Json.text(o, "title");
            m.version = Json.text(o, "version");
            m.filename = Json.text(o, "filename");
            m.sha1 = Json.text(o, "sha1");
            m.published = Json.text(o, "published");
            m.explicit = Json.bool(o, true, "explicit");
            m.enabled = Json.bool(o, true, "enabled");
            for (Object r : Json.arr(Json.at(o, "requires"))) if (r instanceof String) m.requires.add((String) r);
            if (m.filename != null) mods.put(m.projectId, m);
        }
    }

    private void save() throws IOException {
        List<Object> arr = new ArrayList<>();
        for (Mod m : mods.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("project_id", m.projectId);
            o.put("version_id", m.versionId);
            o.put("title", m.title);
            o.put("version", m.version);
            o.put("filename", m.filename);
            o.put("sha1", m.sha1);
            o.put("published", m.published);
            o.put("explicit", m.explicit);
            o.put("enabled", m.enabled);
            o.put("requires", new ArrayList<Object>(m.requires));
            arr.add(o);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mods", arr);
        File tmp = new File(index.getPath() + ".part");
        Files2.write(tmp, Json.write(root));
        if (!tmp.renameTo(index)) {
            index.delete();
            if (!tmp.renameTo(index)) throw new IOException("не удалось записать " + index);
        }
    }

    private File fileOf(Mod m) {
        return new File(modsDir, m.filename + (m.enabled ? "" : ".disabled"));
    }

    /** Имя файла из чужого JSON не должно выводить за пределы mods/. */
    static String safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("недопустимое имя файла: " + name);
        }
        return name;
    }

    public interface Progress {
        void step(String text);
    }

    /** Скачивает и ставит всё из плана. При ошибке проверки хэша файл не остаётся. */
    public void install(Fetcher fetcher, Resolver.Plan plan, Progress progress) throws IOException {
        for (Resolver.Item it : plan.items) {
            Modrinth.FileRef f = it.ver.file;
            if (f.url == null) throw new IOException("«" + it.title + "»: автор запретил скачивание сторонними программами");
            String name = safeName(f.filename);
            if (progress != null) progress.step("Скачиваю " + it.title + " " + it.ver.number);
            File dest = new File(modsDir, name);
            File part = new File(modsDir, name + ".part");
            fetcher.get(f.url, part);
            try {
                if (f.sha1 != null && !f.sha1.equalsIgnoreCase(Files2.sha1(part))) {
                    throw new IOException("«" + name + "»: не сходится SHA-1, файл повреждён");
                }
                if (f.size > 0 && part.length() != f.size) {
                    throw new IOException("«" + name + "»: размер " + part.length() + " вместо " + f.size);
                }
            } catch (IOException e) {
                part.delete();
                throw e;
            }
            Mod old = mods.get(it.projectId);
            if (old != null && !old.filename.equals(name)) fileOf(old).delete();
            dest.delete();
            new File(modsDir, name + ".disabled").delete();
            if (!part.renameTo(dest)) {
                part.delete();
                throw new IOException("не удалось сохранить " + name);
            }
            Mod m = old != null ? old : new Mod();
            m.projectId = it.projectId;
            m.versionId = it.ver.id;
            m.title = it.title;
            m.version = it.ver.number;
            m.filename = name;
            m.sha1 = f.sha1;
            m.published = it.ver.published;
            m.enabled = true;
            m.explicit = (old != null && old.explicit) || it.explicit;
            m.requires = new ArrayList<>(it.requires);
            mods.put(m.projectId, m);
            save();
        }
    }

    /** Удаляет мод и те автоматически поставленные зависимости, которые больше никому не нужны. Возвращает названия удалённого. */
    public List<String> remove(String projectId) throws IOException {
        List<String> removed = new ArrayList<>();
        Mod m = mods.remove(projectId);
        if (m == null) return removed;
        fileOf(m).delete();
        removed.add(m.title);
        boolean again = true;
        while (again) {
            again = false;
            for (Mod c : new ArrayList<>(mods.values())) {
                if (c.explicit) continue;
                boolean needed = false;
                for (Mod other : mods.values()) if (other != c && other.requires.contains(c.projectId)) needed = true;
                if (!needed) {
                    mods.remove(c.projectId);
                    fileOf(c).delete();
                    removed.add(c.title);
                    again = true;
                }
            }
        }
        save();
        return removed;
    }

    public void setEnabled(String projectId, boolean enabled) throws IOException {
        Mod m = mods.get(projectId);
        if (m == null || m.enabled == enabled) return;
        File from = fileOf(m);
        m.enabled = enabled;
        File to = fileOf(m);
        if (from.exists() && !from.renameTo(to)) {
            m.enabled = !enabled;
            throw new IOException("не удалось переименовать " + from.getName());
        }
        save();
    }

    /** Моды, зависимости которых отсутствуют (например, удалили вручную). */
    public List<String> brokenRequirements() {
        List<String> out = new ArrayList<>();
        for (Mod m : mods.values()) {
            if (!m.enabled) continue;
            for (String r : m.requires) {
                Mod d = mods.get(r);
                if (d == null || !d.enabled) out.add(m.title + " требует мод " + (d == null ? r : d.title));
            }
        }
        return out;
    }
}
