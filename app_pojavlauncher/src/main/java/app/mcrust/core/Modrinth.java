package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Клиент Modrinth API v2: поиск модов, версии проекта, сведения о проекте. */
public final class Modrinth implements ModSource {
    public static final String API = "https://api.modrinth.com/v2";

    public static final class Hit {
        public String projectId, slug, title, description, author, iconUrl;
        public long downloads;
    }

    public static final class FileRef {
        /** null: автор запретил скачивание вне своего сайта (бывает на CurseForge) */
        public String url, filename, sha1, sha512;
        public long size;
        public boolean primary;
    }

    public static final class Dep {
        public String projectId, versionId, type;
    }

    public static final class Ver {
        public String id, projectId, number, name, type, published;
        public FileRef file;
        public List<Dep> deps = new ArrayList<>();
    }

    private final Fetcher fetcher;
    private final File scratch;
    private final String api;

    public Modrinth(Fetcher fetcher, File scratchDir) {
        this(fetcher, scratchDir, API);
    }

    public Modrinth(Fetcher fetcher, File scratchDir, String api) {
        this.fetcher = fetcher;
        this.scratch = scratchDir;
        this.api = api;
    }

    @Override
    public String id() {
        return "modrinth";
    }

    @Override
    public List<Ver> versions(String projectId, String mc, Loader loader) throws IOException {
        return versions(projectId, mc, loader.modrinthLoaders());
    }

    private Object getJson(String url) throws IOException {
        return Json.parse(Files2.fetchText(fetcher, url, new File(scratch, "modrinth-" + Files2.uid() + ".json")));
    }

    // ---- адреса (отдельно, чтобы тестировать без сети) ----

    public String searchUrl(String query, String mc, Loader loader, int offset, int limit, String index) {
        StringBuilder facets = new StringBuilder("[[\"project_type:mod\"]");
        if (mc != null && !mc.isEmpty()) facets.append(",[\"versions:").append(mc).append("\"]");
        List<String> ls = loader == null ? new ArrayList<String>() : loader.modrinthLoaders();
        if (!ls.isEmpty()) {
            facets.append(",[");
            for (int i = 0; i < ls.size(); i++) {
                if (i > 0) facets.append(',');
                facets.append("\"categories:").append(ls.get(i)).append('"');
            }
            facets.append(']');
        }
        facets.append(']');
        return api + "/search?query=" + Net.enc(query == null ? "" : query)
                + "&facets=" + Net.enc(facets.toString())
                + "&index=" + Net.enc(index == null ? "relevance" : index)
                + "&offset=" + offset + "&limit=" + limit;
    }

    public String versionsUrl(String projectId, String mc, List<String> loaders) {
        StringBuilder u = new StringBuilder(api + "/project/" + Net.enc(projectId) + "/version?include_changelog=false");
        if (mc != null && !mc.isEmpty()) u.append("&game_versions=").append(Net.enc("[\"" + mc + "\"]"));
        if (loaders != null && !loaders.isEmpty()) {
            StringBuilder a = new StringBuilder("[");
            for (int i = 0; i < loaders.size(); i++) {
                if (i > 0) a.append(',');
                a.append('"').append(loaders.get(i)).append('"');
            }
            u.append("&loaders=").append(Net.enc(a.append(']').toString()));
        }
        return u.toString();
    }

    // ---- разбор ответов ----

    public static List<Hit> parseSearch(String json) throws IOException {
        List<Hit> out = new ArrayList<>();
        for (Object o : Json.arr(Json.at(Json.parse(json), "hits"))) {
            Hit h = new Hit();
            h.projectId = Json.text(o, "project_id");
            if (h.projectId == null) continue;
            h.slug = Json.text(o, "slug");
            h.title = nz(Json.text(o, "title"), h.slug);
            h.description = nz(Json.text(o, "description"), "");
            h.author = nz(Json.text(o, "author"), "");
            h.iconUrl = Json.text(o, "icon_url");
            h.downloads = Json.num(o, 0, "downloads");
            out.add(h);
        }
        return out;
    }

    public static Ver parseVersion(Object o) {
        Ver v = new Ver();
        v.id = Json.text(o, "id");
        if (v.id == null) return null;
        v.projectId = Json.text(o, "project_id");
        v.number = nz(Json.text(o, "version_number"), v.id);
        v.name = nz(Json.text(o, "name"), v.number);
        v.type = nz(Json.text(o, "version_type"), "release");
        v.published = nz(Json.text(o, "date_published"), "");
        FileRef first = null;
        for (Object fo : Json.arr(Json.at(o, "files"))) {
            FileRef f = new FileRef();
            f.url = Json.text(fo, "url");
            f.filename = Json.text(fo, "filename");
            if (f.url == null || f.filename == null) continue;
            f.sha1 = Json.text(fo, "hashes", "sha1");
            f.sha512 = Json.text(fo, "hashes", "sha512");
            f.size = Json.num(fo, 0, "size");
            f.primary = Json.bool(fo, false, "primary");
            if (first == null) first = f;
            if (f.primary && v.file == null) v.file = f;
        }
        if (v.file == null) v.file = first;
        for (Object d : Json.arr(Json.at(o, "dependencies"))) {
            Dep dep = new Dep();
            dep.projectId = Json.text(d, "project_id");
            dep.versionId = Json.text(d, "version_id");
            dep.type = nz(Json.text(d, "dependency_type"), "optional");
            v.deps.add(dep);
        }
        return v;
    }

    public static List<Ver> parseVersions(String json) throws IOException {
        List<Ver> out = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(json))) {
            Ver v = parseVersion(o);
            if (v != null && v.file != null) out.add(v);
        }
        return out;
    }

    private static String nz(String s, String d) {
        return s == null ? d : s;
    }

    // ---- запросы ----

    @Override
    public List<Hit> search(String query, String mc, Loader loader, int offset, int limit, String index) throws IOException {
        return parseSearch(Files2.fetchText(fetcher, searchUrl(query, mc, loader, offset, limit, index), new File(scratch, "search-" + Files2.uid() + ".json")));
    }

    /** Версии проекта для данной игры и загрузчиков; новые сверху (так отдаёт API). */
    public List<Ver> versions(String projectId, String mc, List<String> loaders) throws IOException {
        return parseVersions(Files2.fetchText(fetcher, versionsUrl(projectId, mc, loaders), new File(scratch, "versions-" + Files2.uid() + ".json")));
    }

    @Override
    public Ver version(String versionId) throws IOException {
        Ver v = parseVersion(getJson(api + "/version/" + Net.enc(versionId)));
        if (v == null) throw new IOException("Modrinth: версия " + versionId + " не найдена");
        return v;
    }

    @Override
    public String projectTitle(String projectId) throws IOException {
        Object o = getJson(api + "/project/" + Net.enc(projectId));
        return nz(Json.text(o, "title"), projectId);
    }
}
