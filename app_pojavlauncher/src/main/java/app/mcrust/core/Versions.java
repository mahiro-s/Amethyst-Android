package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Список версий игры (манифест Mojang) и версий загрузчиков. */
public final class Versions {
    private Versions() {}

    public static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    public static final String QUILT_META = "https://meta.quiltmc.org/v3";
    public static final String NEOFORGE_METADATA = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    public static final String FORGE_METADATA = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";

    public static final class Entry {
        public final String id;
        /** release, snapshot, old_beta, old_alpha */
        public final String type;
        public final String url;
        public final String releaseTime;
        public final String sha1;

        Entry(String id, String type, String url, String releaseTime, String sha1) {
            this.id = id;
            this.type = type;
            this.url = url;
            this.releaseTime = releaseTime;
            this.sha1 = sha1;
        }
    }

    public static List<Entry> parseManifest(String json) throws IOException {
        Object root = Json.parse(json);
        List<Entry> out = new ArrayList<>();
        for (Object o : Json.arr(Json.at(root, "versions"))) {
            String id = Json.text(o, "id");
            if (id == null) continue;
            String type = Json.text(o, "type");
            out.add(new Entry(id, type == null ? "release" : type, Json.text(o, "url"), Json.text(o, "releaseTime"), Json.text(o, "sha1")));
        }
        return out;
    }

    public static String latestRelease(String json) throws IOException {
        return Json.text(Json.parse(json), "latest", "release");
    }

    /** Оставляет релизы, а по желанию снапшоты и старые бета/альфа-версии. Порядок манифеста (новые сверху) сохраняется. */
    public static List<Entry> filter(List<Entry> all, boolean snapshots, boolean old) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            boolean ok = "release".equals(e.type)
                    || (snapshots && "snapshot".equals(e.type))
                    || (old && (e.type.startsWith("old_")));
            if (ok) out.add(e);
        }
        return out;
    }

    public static List<Entry> list(Fetcher f, File scratch, boolean snapshots, boolean old) throws IOException {
        return filter(parseManifest(Files2.fetchText(f, MANIFEST, new File(scratch, "manifest.json"))), snapshots, old);
    }

    // ---- загрузчики ----

    /** Ответ /versions/loader/{mc} у Fabric и Quilt: [{"loader":{"version":"0.16.5","stable":true}}, ...] (новые сверху). */
    public static List<String> parseFabricLike(String json) throws IOException {
        List<String> stable = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(json))) {
            String v = Json.text(o, "loader", "version");
            if (v == null) continue;
            // у Quilt поля stable нет: бета-версии узнаём по суффиксу
            boolean st = Json.bool(o, !(v.contains("-beta") || v.contains("-alpha") || v.contains("-rc")), "loader", "stable");
            (st ? stable : other).add(v);
        }
        stable.addAll(other);
        return stable;
    }

    /** Префикс версий NeoForge для версии игры: 1.21.1 -> "21.1.", 1.21 -> "21.0.". Для снапшотов null. */
    public static String neoForgePrefix(String mc) {
        String[] p = mc.split("\\.");
        if (p.length < 2 || !p[0].equals("1")) return null;
        try {
            int minor = Integer.parseInt(p[1]);
            int patch = p.length >= 3 ? Integer.parseInt(p[2]) : 0;
            return minor + "." + patch + ".";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Все {@code <version>} из maven-metadata.xml в порядке файла. */
    public static List<String> mavenVersions(String xml) {
        List<String> all = new ArrayList<>();
        int i = 0;
        while ((i = xml.indexOf("<version>", i)) >= 0) {
            int j = xml.indexOf("</version>", i);
            if (j < 0) break;
            all.add(xml.substring(i + 9, j).trim());
            i = j + 10;
        }
        return all;
    }

    /** maven-metadata.xml NeoForge. Возвращает новые сверху, стабильные впереди бета-версий. */
    public static List<String> parseNeoForge(String xml, String mc) {
        String prefix = neoForgePrefix(mc);
        List<String> stable = new ArrayList<>();
        List<String> beta = new ArrayList<>();
        if (prefix == null) return stable;
        for (String v : mavenVersions(xml)) {
            if (!v.startsWith(prefix)) continue;
            (v.contains("-") ? beta : stable).add(v);
        }
        Collections.reverse(stable);
        Collections.reverse(beta);
        stable.addAll(beta);
        return stable;
    }

    /**
     * maven-metadata.xml Forge: версии вида «1.21.1-52.0.16» (у старых ещё суффикс «-1.12.2»).
     * Возвращает номера Forge без префикса игры, новые сверху.
     */
    public static List<String> parseForge(String xml, String mc) {
        List<String> out = new ArrayList<>();
        String prefix = mc + "-";
        for (String v : mavenVersions(xml)) {
            if (!v.startsWith(prefix)) continue;
            String rest = v.substring(prefix.length());
            if (rest.endsWith("-" + mc)) rest = rest.substring(0, rest.length() - mc.length() - 1);
            if (!rest.isEmpty()) out.add(rest);
        }
        Collections.reverse(out);
        return out;
    }

    public static List<String> loaderVersions(Fetcher f, File scratch, Loader loader, String mc) throws IOException {
        File tmp = new File(scratch, "loaders.json");
        switch (loader) {
            case FABRIC:
                return parseFabricLike(Files2.fetchText(f, FABRIC_META + "/versions/loader/" + Net.enc(mc), tmp));
            case QUILT:
                return parseFabricLike(Files2.fetchText(f, QUILT_META + "/versions/loader/" + Net.enc(mc), tmp));
            case NEOFORGE:
                return parseNeoForge(Files2.fetchText(f, NEOFORGE_METADATA, tmp), mc);
            case FORGE:
                return parseForge(Files2.fetchText(f, FORGE_METADATA, tmp), mc);
            default:
                return new ArrayList<>();
        }
    }
}
