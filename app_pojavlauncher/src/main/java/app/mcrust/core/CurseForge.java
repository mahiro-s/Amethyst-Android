package app.mcrust.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиент CurseForge API v1. Нужен бесплатный ключ с console.curseforge.com (заголовок x-api-key).
 * Идентификаторы проектов здесь с префиксом «cf:», чтобы не путаться с Modrinth.
 */
public final class CurseForge implements ModSource {
    public static final String API = "https://api.curseforge.com/v1";
    public static final int GAME_MINECRAFT = 432;
    public static final int CLASS_MODS = 6;
    public static final String PREFIX = "cf:";

    private final Fetcher fetcher;
    private final File scratch;
    private final String api;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public CurseForge(Fetcher fetcher, File scratchDir, String apiKey) {
        this(fetcher, scratchDir, apiKey, API);
    }

    public CurseForge(Fetcher fetcher, File scratchDir, String apiKey, String api) {
        this.fetcher = fetcher;
        this.scratch = scratchDir;
        this.api = api;
        headers.put("x-api-key", apiKey == null ? "" : apiKey.trim());
        headers.put("Accept", "application/json");
    }

    @Override
    public String id() {
        return "curseforge";
    }

    static String raw(String projectId) {
        return projectId.startsWith(PREFIX) ? projectId.substring(PREFIX.length()) : projectId;
    }

    private String getText(String url, String name) throws IOException {
        if (headers.get("x-api-key").isEmpty()) throw new IOException("Не задан ключ CurseForge (бесплатный, на console.curseforge.com)");
        try {
            return Files2.fetchText(fetcher, url, new File(scratch, name + "-" + Files2.uid() + ".json"), headers);
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("403") || m.contains("401")) throw new IOException("CurseForge отклонил ключ (ответ 401/403): проверь ключ", e);
            throw e;
        }
    }

    // ---- адреса ----

    /** sort: relevance, downloads, updated, newest. */
    static int sortField(String sort) {
        if ("downloads".equals(sort)) return 6;
        if ("updated".equals(sort)) return 3;
        if ("newest".equals(sort)) return 11;
        return 1; // подходящие: «рекомендуемые» самого CurseForge
    }

    public String searchUrl(String query, String mc, Loader loader, int offset, int limit, String sort) {
        StringBuilder u = new StringBuilder(api + "/mods/search?gameId=" + GAME_MINECRAFT + "&classId=" + CLASS_MODS);
        if (mc != null && !mc.isEmpty()) u.append("&gameVersion=").append(Net.enc(mc));
        if (loader != null && loader.curseForgeType() > 0) u.append("&modLoaderType=").append(loader.curseForgeType());
        if (query != null && !query.isEmpty()) u.append("&searchFilter=").append(Net.enc(query));
        u.append("&sortField=").append(sortField(sort)).append("&sortOrder=desc");
        u.append("&index=").append(offset).append("&pageSize=").append(Math.min(50, limit));
        return u.toString();
    }

    public String filesUrl(String projectId, String mc, Loader loader) {
        StringBuilder u = new StringBuilder(api + "/mods/" + Net.enc(raw(projectId)) + "/files?pageSize=50");
        if (mc != null && !mc.isEmpty()) u.append("&gameVersion=").append(Net.enc(mc));
        if (loader != null && loader.curseForgeType() > 0) u.append("&modLoaderType=").append(loader.curseForgeType());
        return u.toString();
    }

    // ---- разбор ----

    static String idOf(Object o, String... path) {
        Object v = Json.at(o, path);
        return v instanceof Double ? String.valueOf(((Double) v).longValue()) : null;
    }

    public static List<Modrinth.Hit> parseSearch(String json) throws IOException {
        List<Modrinth.Hit> out = new ArrayList<>();
        for (Object o : Json.arr(Json.at(Json.parse(json), "data"))) {
            String id = idOf(o, "id");
            if (id == null) continue;
            Modrinth.Hit h = new Modrinth.Hit();
            h.projectId = PREFIX + id;
            h.slug = Json.text(o, "slug");
            h.title = Json.text(o, "name") == null ? h.slug : Json.text(o, "name");
            h.description = Json.text(o, "summary") == null ? "" : Json.text(o, "summary");
            List<Object> authors = Json.arr(Json.at(o, "authors"));
            h.author = authors.isEmpty() || Json.text(authors.get(0), "name") == null ? "" : Json.text(authors.get(0), "name");
            h.iconUrl = Json.text(o, "logo", "thumbnailUrl");
            h.downloads = Json.num(o, 0, "downloadCount");
            out.add(h);
        }
        return out;
    }

    static String releaseType(long t) {
        return t == 1 ? "release" : t == 2 ? "beta" : "alpha";
    }

    /** Новые файлы сверху (по fileDate), как у Modrinth. Файлы без ссылки остаются в списке: их запретил автор. */
    public static List<Modrinth.Ver> parseFiles(String json) throws IOException {
        List<Modrinth.Ver> out = new ArrayList<>();
        for (Object o : Json.arr(Json.at(Json.parse(json), "data"))) {
            String fid = idOf(o, "id");
            String fileName = Json.text(o, "fileName");
            if (fid == null || fileName == null) continue;
            Modrinth.Ver v = new Modrinth.Ver();
            v.id = fid;
            String mod = idOf(o, "modId");
            v.projectId = mod == null ? null : PREFIX + mod;
            v.number = Json.text(o, "displayName") == null ? fileName : Json.text(o, "displayName");
            v.name = v.number;
            v.type = releaseType(Json.num(o, 1, "releaseType"));
            v.published = Json.text(o, "fileDate") == null ? "" : Json.text(o, "fileDate");
            Modrinth.FileRef f = new Modrinth.FileRef();
            f.url = Json.text(o, "downloadUrl"); // null: автор запретил сторонним программам
            f.filename = fileName;
            f.size = Json.num(o, 0, "fileLength");
            f.primary = true;
            for (Object h : Json.arr(Json.at(o, "hashes"))) {
                if (Json.num(h, 0, "algo") == 1) f.sha1 = Json.text(h, "value");
            }
            v.file = f;
            for (Object d : Json.arr(Json.at(o, "dependencies"))) {
                String dm = idOf(d, "modId");
                if (dm == null) continue;
                long rel = Json.num(d, 0, "relationType");
                Modrinth.Dep dep = new Modrinth.Dep();
                dep.projectId = PREFIX + dm;
                dep.type = rel == 3 ? "required" : rel == 5 ? "incompatible" : "optional";
                v.deps.add(dep);
            }
            out.add(v);
        }
        Collections.sort(out, new Comparator<Modrinth.Ver>() {
            @Override
            public int compare(Modrinth.Ver a, Modrinth.Ver b) {
                return b.published.compareTo(a.published);
            }
        });
        return out;
    }

    // ---- запросы ----

    @Override
    public List<Modrinth.Hit> search(String query, String mc, Loader loader, int offset, int limit, String sort) throws IOException {
        return parseSearch(getText(searchUrl(query, mc, loader, offset, limit, sort), "cf-search"));
    }

    @Override
    public List<Modrinth.Ver> versions(String projectId, String mc, Loader loader) throws IOException {
        return parseFiles(getText(filesUrl(projectId, mc, loader), "cf-files"));
    }

    @Override
    public Modrinth.Ver version(String versionId) throws IOException {
        throw new IOException("CurseForge: зависимости с точной версией не поддерживаются");
    }

    @Override
    public String projectTitle(String projectId) throws IOException {
        String n = Json.text(Json.parse(getText(api + "/mods/" + Net.enc(raw(projectId)), "cf-mod")), "data", "name");
        return n == null ? projectId : n;
    }
}
