package app.mcrust.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Строит план установки мода: сам мод и все его обязательные зависимости. */
public final class Resolver {
    private Resolver() {}

    public static final class Item {
        public Modrinth.Ver ver;
        public String projectId;
        public String title;
        public boolean explicit;
        /** projectId обязательных зависимостей этой версии */
        public List<String> requires = new ArrayList<>();
    }

    public static final class Plan {
        public final List<Item> items = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    /** Лучшая версия из списка (новые сверху): сначала release, потом beta, потом alpha. */
    public static Modrinth.Ver best(List<Modrinth.Ver> all) {
        // версии без ссылки (автор запретил скачивание сторонними программами) выбирать бесполезно
        List<Modrinth.Ver> vs = new ArrayList<>();
        for (Modrinth.Ver v : all) if (v.file != null && v.file.url != null) vs.add(v);
        String[] order = {"release", "beta", "alpha"};
        for (String t : order) for (Modrinth.Ver v : vs) if (t.equals(v.type)) return v;
        return vs.isEmpty() ? null : vs.get(0);
    }

    /**
     * @param installed projectId уже установленных модов (их не качаем заново)
     * @param titles     название корневого мода (для сообщений); зависимости спросим у API
     */
    public static Plan plan(ModSource m, String projectId, String rootTitle, String mc, Loader loader, Set<String> installed) throws IOException {
        Plan plan = new Plan();
        Set<String> seen = new HashSet<>(installed);
        ArrayDeque<String[]> queue = new ArrayDeque<>(); // {projectId, versionId|null, requiredBy|null}
        queue.add(new String[]{projectId, null, null});
        boolean root = true;
        while (!queue.isEmpty()) {
            String[] cur = queue.poll();
            String pid = cur[0];
            String vid = cur[1];
            if (pid == null) {
                if (vid == null) continue;
                Modrinth.Ver byId = m.version(vid);
                pid = byId.projectId;
                if (pid == null) continue;
            }
            if (!seen.add(pid)) continue;
            Modrinth.Ver v = null;
            if (vid != null) v = m.version(vid);
            boolean blocked = false;
            if (v == null) {
                List<Modrinth.Ver> found = m.versions(pid, mc, loader);
                v = best(found);
                blocked = v == null && !found.isEmpty();
            }
            String title = root ? rootTitle : titleOf(m, pid);
            if (v == null && blocked) {
                String why = "автор «" + title + "» запретил скачивание сторонними программами: скачай мод вручную на сайте каталога";
                if (root) throw new IOException(why);
                plan.warnings.add("Зависимость: " + why);
                continue;
            }
            if (v == null) {
                if (root) throw new IOException("Для «" + title + "» нет версии под " + mc + " и " + loader.title);
                plan.warnings.add("Для обязательной зависимости «" + title + "» нет версии под " + mc + " и " + loader.title + ": мод может не запуститься");
                continue;
            }
            Item it = new Item();
            it.ver = v;
            it.projectId = pid;
            it.title = title;
            it.explicit = root;
            for (Modrinth.Dep d : v.deps) {
                if ("required".equals(d.type) && (d.projectId != null || d.versionId != null)) {
                    if (d.projectId != null) it.requires.add(d.projectId);
                    queue.add(new String[]{d.projectId, d.versionId, pid});
                } else if ("incompatible".equals(d.type) && d.projectId != null && installed.contains(d.projectId)) {
                    plan.warnings.add("«" + title + "» несовместим с уже установленным модом (" + d.projectId + ")");
                }
            }
            plan.items.add(it);
            root = false;
        }
        return plan;
    }

    private static String titleOf(ModSource m, String pid) {
        try {
            return m.projectTitle(pid);
        } catch (IOException e) {
            return pid;
        }
    }
}
